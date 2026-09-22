package org.example;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public final class TestSuite {
    private static int assertions;
    private TestSuite() { }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("crash")) { crashWriter(Path.of(args[1])); return; }
        testConfiguration(); testParser(); testJournal(); testCrashRecovery(); testTailer(); testRotation();
        testEngine(); testRetryIsolation(); testRsync(); testShellConfinement(); testTimeout();
        System.out.println("PASS: " + assertions + " assertions; durable recovery, audit rotation, ordering, transport and timeouts");
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void fails(Checked test, String message) throws Exception {
        boolean failed = false; try { test.run(); } catch (IOException | IllegalArgumentException e) { failed = true; }
        check(failed, message);
    }
    private static final class Fixture {
        final Path base, root, log;
        final Properties properties = new Properties();
        Fixture() throws IOException { this(Files.createTempDirectory("filesync-test-")); }
        Fixture(Path base) throws IOException {
            this.base = base; root = base.resolve("source"); log = base.resolve("audit.log");
            Files.createDirectories(root); if (!Files.exists(log)) Files.createFile(log);
            properties.setProperty("root.path", root.toString()); properties.setProperty("audit.log.path", log.toString());
            properties.setProperty("state.directory", base.resolve("state").toString());
            properties.setProperty("health.file", base.resolve("health").toString());
            properties.setProperty("sync.servers", "replica-one.example,replica-two.example");
            properties.setProperty("buffer.time.seconds", "0"); properties.setProperty("replay.existing.log.on.startup", "true");
            properties.setProperty("propagate.deletes", "true"); properties.setProperty("retry.initial.seconds", "1");
            properties.setProperty("retry.max.seconds", "1"); properties.setProperty("shutdown.timeout.seconds", "1");
        }
        Config config() { return new Config(properties); }
        Path file(String name) throws IOException { Path p = root.resolve(name); Files.createDirectories(p.getParent()); return Files.writeString(p, "content\n"); }
        String line(String operation, String status, Path path, Path next) {
            return "Sep 22 09:00:00 fileserver smbd_audit: user|client|share|" + operation + "|" + status + "|" + path + (next == null ? "" : "|" + next) + "\n";
        }
        void append(String line) throws IOException { Files.writeString(log, line, StandardOpenOption.APPEND); }
    }
    private static Operation update(Path p) { return new Operation(Operation.Type.UPDATE, p, null); }
    private static Journal.Cursor cursor(long n) { return new Journal.Cursor("test-inode", n, "anchor"); }

    private static void testConfiguration() throws Exception {
        Fixture f = new Fixture(); Config c = f.config(); c.validateFilesystem();
        check(!c.daemon, "encrypted SSH is the default transport");
        f.properties.setProperty("max.concurrent.syncs", "0"); fails(f::config, "reject zero concurrency");
        f.properties.setProperty("max.concurrent.syncs", "4"); f.properties.setProperty("sync.servers", "host;touch /tmp/no");
        fails(f::config, "reject host shell syntax");
        f.properties.setProperty("sync.servers", "host"); f.properties.setProperty("root.path", "/"); fails(f::config, "reject filesystem root");
        f.properties.setProperty("root.path", f.root.toString()); f.properties.setProperty("state.directory", f.root.resolve("state").toString());
        fails(f::config, "state must be outside source");
    }
    private static void testParser() throws Exception {
        Fixture f = new Fixture(); Config c = f.config(); Path file = f.root.resolve("a ' unicode-ä.txt");
        check(Operation.parse(f.line("pwrite_recv", "ok", file, null), c).path().equals(file), "parse paths without losing quotes/unicode");
        check(Operation.parse(f.line("unlinkat", "fail (Permission denied)", file, null), c) == null, "failed deletes ignored");
        check(Operation.parse(f.line("unlinkat", "ok", f.root, null), c) == null, "root deletion rejected");
        check(Operation.parse(f.line("unlinkat", "ok", f.root.resolve("../outside"), null), c) == null, "traversal rejected");
        check(Operation.parse(f.line("pwrite_recv", "ok", f.root.resolve("a|b"), null), c) == null, "ambiguous delimiter rejected");
        Operation saved = Operation.parse(f.line("renameat", "ok", f.root.resolve("save.tmp"), file), c);
        check(saved.type() == Operation.Type.UPDATE && saved.path().equals(file), "Office temporary-to-real save retained");
        Path spaced = f.root.resolve("ends with a space ");
        check(Operation.parse(f.line("pwrite_recv", "ok", spaced, null), c).path().equals(spaced), "filename trailing spaces preserved");
        check(Operation.parse(f.line("pwrite_recv", "ok", file, null).replace("smbd_audit:", "smbd_audit[123]:"), c) != null, "PID prefix supported");
        Path outside = Files.createDirectory(f.base.resolve("outside")); Files.createSymbolicLink(f.root.resolve("link"), outside);
        fails(() -> Operation.requireSafe(f.root.resolve("link/file"), f.root), "symlink parent escape blocked");
    }
    private static void testJournal() throws Exception {
        Fixture f = new Fixture(); Config c = f.config(); Path file = f.file("doc");
        long id;
        try (Journal j = new Journal(c)) {
            j.accept(List.of(update(file), update(file)), cursor(10));
            check(j.size() == 1, "consecutive pending updates coalesce");
            fails(() -> { try (Journal second = new Journal(c)) { second.size(); } }, "single-writer lock");
            Journal.Job job = j.head(c.servers.get(0)); id = job.id; j.started(job);
            j.accept(List.of(update(file)), cursor(20)); check(j.size() == 2, "write during in-flight transfer needs a follow-up");
            j.acknowledge(id, c.servers.get(0)); j.compact();
        }
        try (Journal j = new Journal(c)) {
            check(j.size() == 2 && j.cursor().offset() == 20, "snapshot preserves backlog and cursor");
            check(j.head(c.servers.get(1)).id == id && j.head(c.servers.get(0)).id != id, "per-target acknowledgement persists");
            check(Files.getPosixFilePermissions(c.state.resolve("queue.wal")).equals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "journal contents are private to the service account");
        }
        Path wal = c.state.resolve("queue.wal"); Files.write(wal, new byte[]{0, 0}, StandardOpenOption.APPEND);
        try (Journal j = new Journal(c)) { check(j.size() == 2, "incomplete trailing frame header is recoverable"); }
        byte[] bytes = Files.readAllBytes(wal); bytes[bytes.length - 1] ^= 1; Files.write(wal, bytes);
        fails(() -> { try (Journal j = new Journal(c)) { j.size(); } }, "complete corrupted record fails closed");
        Fixture g = new Fixture(); Config d = g.config();
        try (Journal j = new Journal(d)) { j.accept(List.of(update(g.file("x"))), cursor(1)); }
        g.properties.setProperty("sync.servers", "replacement.example");
        fails(() -> { try (Journal j = new Journal(g.config())) { j.size(); } }, "changing targets cannot silently reuse old state");
        Fixture h = new Fixture(); Config k = h.config();
        try (Journal j = new Journal(k)) {
            j.accept(List.of(update(h.file("torn-ack"))), cursor(12));
            j.acknowledge(j.head(k.servers.get(0)).id, k.servers.get(0));
        }
        try (RandomAccessFile partial = new RandomAccessFile(k.state.resolve("queue.wal").toFile(), "rw")) {
            partial.setLength(partial.length() - 2);
        }
        try (Journal j = new Journal(k)) {
            check(j.head(k.servers.get(0)) != null && j.cursor().offset() == 12,
                    "torn acknowledgement replays delivery without rolling forward an inconsistent cursor");
        }
    }
    private static void crashWriter(Path base) throws Exception {
        Fixture f = new Fixture(base); Journal j = new Journal(f.config());
        j.accept(List.of(update(f.file("survives-crash"))), cursor(99));
        Runtime.getRuntime().halt(0);
    }
    private static void testCrashRecovery() throws Exception {
        Fixture f = new Fixture();
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("java.class.path"), TestSuite.class.getName(), "crash", f.base.toString()).inheritIO().start();
        check(child.waitFor() == 0, "crash writer terminated without close");
        try (Journal j = new Journal(f.config())) { check(j.size() == 1 && j.cursor().offset() == 99, "fsynced job and cursor survive abrupt JVM halt"); }
    }
    private static void testTailer() throws Exception {
        Fixture f = new Fixture(); Config c = f.config(); String line = f.line("pwrite_recv", "ok", f.file("doc"), null);
        try (Journal j = new Journal(c)) {
            AuditTailer t = new AuditTailer(c, j); f.append(line.substring(0, line.length() - 1)); t.tick();
            check(j.size() == 0 && j.cursor().offset() == 0, "partial audit line not consumed");
            f.append("\n"); t.tick(); check(j.size() == 1 && j.cursor().offset() == Files.size(f.log), "completed audit line persisted exactly once");
            t.tick(); check(j.size() == 1, "unchanged log is not replayed");
            Files.writeString(f.log, "X".repeat((int) Files.size(f.log) + 50));
            fails(t::tick, "copytruncate-and-regrow detected via cursor anchor");
        }
        Fixture g = new Fixture(); g.properties.setProperty("max.queued.log.lines", "1"); Config d = g.config();
        g.append(g.line("pwrite_recv", "ok", g.file("one"), null)); g.append(g.line("pwrite_recv", "ok", g.file("two"), null));
        try (Journal j = new Journal(d)) {
            AuditTailer t = new AuditTailer(d, j); t.tick(); long first = j.cursor().offset(); t.tick();
            check(j.size() == 1 && j.cursor().offset() == first && first < Files.size(g.log), "full queue applies backpressure without skipping input");
            long id = j.head(d.servers.get(0)).id; for (String server : d.servers) j.acknowledge(id, server); t.tick();
            check(j.size() == 1 && j.cursor().offset() == Files.size(g.log), "reader resumes after queue drains");
        }
        Fixture h = new Fixture(); h.properties.setProperty("replay.existing.log.on.startup", "false");
        h.append(h.line("pwrite_recv", "ok", h.file("old"), null)); Config e = h.config();
        try (Journal j = new Journal(e)) {
            AuditTailer t = new AuditTailer(e, j); t.tick(); check(j.size() == 0, "first start may deliberately skip existing history");
        }
        h.append(h.line("pwrite_recv", "ok", h.file("during-outage"), null));
        try (Journal j = new Journal(e)) { new AuditTailer(e, j).tick(); check(j.size() == 1, "subsequent startup always resumes stored cursor"); }
    }
    private static void testRotation() throws Exception {
        Fixture f = new Fixture(); Config c = f.config();
        try (Journal j = new Journal(c)) { new AuditTailer(c, j).tick(); }
        f.append(f.line("pwrite_recv", "ok", f.file("old-inode"), null));
        Files.move(f.log, f.log.resolveSibling("audit.log.1")); Files.createFile(f.log);
        f.append(f.line("pwrite_recv", "ok", f.file("new-inode"), null));
        try (Journal j = new Journal(c)) {
            AuditTailer t = new AuditTailer(c, j); t.tick(); t.tick();
            check(j.size() == 2, "restart drains rotated inode before current file");
        }
        Files.delete(f.log); Files.createFile(f.log);
        try (Journal j = new Journal(c)) { fails(() -> new AuditTailer(c, j).tick(), "missing saved inode fails instead of skipping records"); }
        Fixture g = new Fixture(); Config d = g.config();
        try (Journal j = new Journal(d)) { new AuditTailer(d, j).tick(); }
        g.append(g.line("pwrite_recv", "ok", g.file("oldest"), null));
        Files.move(g.log, g.log.resolveSibling("audit.log.2")); Files.createFile(g.log);
        g.append(g.line("pwrite_recv", "ok", g.file("middle"), null));
        Files.move(g.log, g.log.resolveSibling("audit.log.1")); Files.createFile(g.log);
        g.append(g.line("pwrite_recv", "ok", g.file("newest"), null));
        try (Journal j = new Journal(d)) {
            AuditTailer t = new AuditTailer(d, j); t.tick(); t.tick(); t.tick();
            check(j.size() == 3, "multiple rotations do not skip intermediate files");
        }
    }
    private static final class Fake implements RemoteActions {
        final List<String> actions = Collections.synchronizedList(new ArrayList<>());
        volatile boolean fail;
        volatile String failedServer;
        @Override public void synchronize(String server, Path path) throws IOException {
            if (fail || server.equals(failedServer)) throw new IOException("simulated outage");
            actions.add("copy:" + server + ":" + path);
        }
        @Override public void delete(String server, Path path) { actions.add("delete:" + server + ":" + path); }
    }
    private static void testEngine() throws Exception {
        Fixture f = new Fixture(); Config c = f.config(); Fake fake = new Fake(); String server = c.servers.get(0);
        Path old = f.root.resolve("old"), next = f.file("new");
        try (Journal j = new Journal(c); SyncEngine e = new SyncEngine(c, j, fake, new AtomicReference<>())) {
            j.accept(List.of(new Operation(Operation.Type.RENAME, old, next)), cursor(1));
            fake.fail = true; fails(() -> e.apply(server, j.head(server)), "rename copy failure is reported");
            check(fake.actions.isEmpty(), "failed rename cannot delete original");
            fake.fail = false; e.apply(server, j.head(server));
            check(fake.actions.get(0).startsWith("copy:") && fake.actions.get(1).startsWith("delete:"), "rename copies before deleting");
        }
        Fixture g = new Fixture(); Config d = g.config(); Fake again = new Fake(); Path recreated = g.file("recreated");
        try (Journal j = new Journal(d); SyncEngine e = new SyncEngine(d, j, again, new AtomicReference<>())) {
            j.accept(List.of(new Operation(Operation.Type.DELETE, recreated, null)), cursor(1)); e.apply(server, j.head(server));
            check(again.actions.size() == 1 && again.actions.get(0).startsWith("copy:"), "stale delete cannot remove a recreated source");
        }
        Fixture invalid = new Fixture(); Config invalidConfig = invalid.config(); Fake untouched = new Fake();
        try (Journal j = new Journal(invalidConfig); SyncEngine e = new SyncEngine(invalidConfig, j, untouched, new AtomicReference<>())) {
            Path unreadable = invalid.root.resolve("x".repeat(300)); // A real stat error, rather than ENOENT.
            j.accept(List.of(new Operation(Operation.Type.DELETE, unreadable, null)), cursor(1));
            fails(() -> e.apply(server, j.head(server)), "metadata errors are not interpreted as missing source files");
            check(untouched.actions.isEmpty(), "uncertain source state never triggers remote deletion");
        }
        Fixture h = new Fixture(); Config k = h.config(); Fake chained = new Fake();
        Path a = h.root.resolve("a"), b = h.root.resolve("b"), z = h.file("c");
        try (Journal j = new Journal(k); SyncEngine e = new SyncEngine(k, j, chained, new AtomicReference<>())) {
            j.accept(List.of(new Operation(Operation.Type.RENAME, a, b), new Operation(Operation.Type.RENAME, b, z)), cursor(2));
            e.apply(server, j.head(server)); check(chained.actions.get(0).endsWith(z.toString()), "rename chain copies final existing destination");
        }
    }
    private static void testRetryIsolation() throws Exception {
        Fixture f = new Fixture(); Config c = f.config(); Fake fake = new Fake(); fake.failedServer = c.servers.get(0);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        try (Journal j = new Journal(c); SyncEngine e = new SyncEngine(c, j, fake, fatal)) {
            j.accept(List.of(update(f.file("independent"))), cursor(1));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (j.head(c.servers.get(1)) != null && System.nanoTime() < deadline) { e.tick(); Thread.sleep(20); }
            check(j.head(c.servers.get(1)) == null && j.head(c.servers.get(0)) != null, "offline target does not lose work or block healthy target");
            fake.failedServer = null; deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (j.size() != 0 && System.nanoTime() < deadline) { e.tick(); Thread.sleep(20); }
            check(j.size() == 0 && fatal.get() == null, "automatic retry eventually acknowledges recovered target");
        }
    }
    private static void testRsync() throws Exception {
        Fixture f = new Fixture(); Config c = f.config(); Path source = f.file("folder with spaces/Ä's file.txt");
        Path target = Files.createDirectory(f.base.resolve("target"));
        try (Transport transport = new Transport(c)) {
            List<String> ssh = transport.rsyncCommand(c.servers.get(0), source, false, false);
            check(ssh.stream().noneMatch(s -> s.startsWith("--contimeout")), "daemon-only contimeout excluded from SSH transfers");
            check(ssh.stream().noneMatch(s -> s.startsWith("--delete")), "no implicit recursive target deletion");
            check(ssh.contains("--secluded-args"), "remote paths are protected from remote shell expansion");
            List<String> local = new ArrayList<>(ssh); local.set(local.size() - 1, target + "/");
            try (ProcessRunner runner = new ProcessRunner(1)) { runner.run(local, Duration.ofSeconds(10)); }
            check(Files.readString(target.resolve(f.root.relativize(source))).equals("content\n"), "real rsync preserves relative path, Unicode, quotes and content");
            Files.writeString(target.resolve("folder with spaces/keep.txt"), "keep");
            List<String> directory = new ArrayList<>(transport.rsyncCommand(c.servers.get(0), source.getParent(), false, false));
            directory.set(directory.size() - 1, target + "/");
            try (ProcessRunner runner = new ProcessRunner(1)) { runner.run(directory, Duration.ofSeconds(10)); }
            check(Files.exists(target.resolve("folder with spaces/keep.txt")), "directory update retains unrelated target data");
        }
    }
    private static void testShellConfinement() throws Exception {
        Fixture f = new Fixture(); Path special = f.file("a'$(echo injected); name");
        Process valid = new ProcessBuilder("sh", "-c", Transport.deleteScript(special, f.root)).start();
        check(valid.waitFor() == 0 && !Files.exists(special), "literal metacharacters survive remote delete quoting");
        Process root = new ProcessBuilder("sh", "-c", Transport.deleteScript(f.root, f.root)).start();
        check(root.waitFor() != 0 && Files.isDirectory(f.root), "remote root deletion rejected");
        Path outside = Files.createDirectory(f.base.resolve("outside")); Path protectedFile = Files.writeString(outside.resolve("keep"), "safe");
        Files.createSymbolicLink(f.root.resolve("link"), outside);
        Process escaped = new ProcessBuilder("sh", "-c", Transport.deleteScript(f.root.resolve("link/keep"), f.root)).start();
        check(escaped.waitFor() != 0 && Files.exists(protectedFile), "remote parent symlink escape rejected");
        String nested = Transport.quoteCommand(List.of("sh", "-c", Transport.deleteScript(f.file("two'quotes"), f.root)));
        Process relayed = new ProcessBuilder("sh", "-c", nested).start();
        check(relayed.waitFor() == 0 && !Files.exists(f.root.resolve("two'quotes")), "nested relay shell quoting preserves argument boundaries");
    }
    private static void testTimeout() throws Exception {
        Fixture f = new Fixture(); Path pid = f.base.resolve("child.pid");
        boolean timedOut = false;
        try (ProcessRunner runner = new ProcessRunner(1)) {
            try { runner.run(List.of("sh", "-c", "sleep 30 & echo $! > " + Transport.quote(pid.toString()) + "; wait"), Duration.ofMillis(200)); }
            catch (java.util.concurrent.TimeoutException e) { timedOut = true; }
        }
        check(timedOut, "hung command has an overall deadline");
        long child = Long.parseLong(Files.readString(pid).trim());
        Path stat = Path.of("/proc", Long.toString(child), "stat");
        boolean running = Files.exists(stat) && !Files.readString(stat).split(" ")[2].equals("Z");
        check(!running, "timed-out command descendants are terminated");
    }
}
