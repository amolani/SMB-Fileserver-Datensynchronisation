package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class Main {
    private static final Pattern AUDIT_PREFIX = Pattern.compile("^\\w+\\s+\\d+\\s+\\d+:\\d+:\\d+\\s+\\S+\\s+smbd_audit:\\s+");
    private static final Set<String> WRITE_OPS = Set.of("mkdirat", "pwrite_send", "pwrite_recv");
    private static final Set<String> DELETE_OPS = Set.of("unlinkat");
    private static final Set<String> RENAME_OPS = Set.of("renameat");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        log("Starting FileserverSync with servers " + config.syncServers);

        SyncEngine engine = new SyncEngine(config);
        AuditTailer tailer = new AuditTailer(config, engine);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Shutting down application...");
            tailer.shutdown();
            engine.shutdown();
            log("Shutdown complete.");
        }, "shutdown-hook"));

        tailer.start();
        Thread.currentThread().join();
    }

    private static void log(String message) {
        System.out.println(Instant.now() + " " + message);
    }

    private static final class Config {
        private final Path auditLogPath;
        private final Path rootPath;
        private final Path healthFile;
        private final List<String> syncServers;
        private final int bwlimit;
        private final int debounceSeconds;
        private final int rsyncTimeoutSeconds;
        private final int sshTimeoutSeconds;
        private final int maxConcurrentSyncs;
        private final int maxQueuedLogLines;
        private final int maxReadLinesPerTick;
        private final boolean useRsyncDaemon;
        private final String rsyncModule;
        private final boolean replayExistingLogOnStartup;
        private final String relayHost;

        private Config(Properties properties) {
            auditLogPath = Paths.get(property(properties, "audit.log.path", "/var/log/fullauditlog/samba_audit.log"));
            rootPath = Paths.get(property(properties, "root.path", "/srv/samba/schools/default-school")).toAbsolutePath().normalize();
            healthFile = Paths.get(property(properties, "health.file", "/tmp/fileserversync.heartbeat"));
            syncServers = parseServers(property(properties, "sync.servers", ""));
            bwlimit = parseInt(properties, "bwlimit", 3000);
            debounceSeconds = parseInt(properties, "buffer.time.seconds", 600);
            rsyncTimeoutSeconds = parseInt(properties, "rsync.timeout.seconds", 1800);
            sshTimeoutSeconds = parseInt(properties, "ssh.timeout.seconds", 120);
            maxConcurrentSyncs = parseInt(properties, "max.concurrent.syncs", 4);
            maxQueuedLogLines = parseInt(properties, "max.queued.log.lines", 20000);
            maxReadLinesPerTick = parseInt(properties, "max.read.lines.per.tick", 5000);
            useRsyncDaemon = Boolean.parseBoolean(property(properties, "rsync.daemon.enabled", "true"));
            rsyncModule = property(properties, "rsync.daemon.module", "default-school");
            replayExistingLogOnStartup = Boolean.parseBoolean(property(properties, "replay.existing.log.on.startup", "false"));
            relayHost = property(properties, "relay.host", "");
            if (syncServers.isEmpty()) {
                throw new IllegalArgumentException("sync.servers is missing or empty");
            }
        }

        private static Config load() throws IOException {
            Properties properties = new Properties();
            Path external = Paths.get("/app/config.properties");
            if (Files.isRegularFile(external)) {
                try (InputStream input = Files.newInputStream(external)) {
                    properties.load(input);
                }
            } else {
                try (InputStream input = Main.class.getClassLoader().getResourceAsStream("config.properties")) {
                    if (input != null) {
                        properties.load(input);
                    }
                }
            }
            return new Config(properties);
        }

        private static String property(Properties properties, String key, String fallback) {
            return properties.getProperty(key, fallback).trim();
        }

        private static int parseInt(Properties properties, String key, int fallback) {
            String value = property(properties, key, Integer.toString(fallback));
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be an integer: " + value, e);
            }
        }

        private static List<String> parseServers(String raw) {
            Set<String> servers = new LinkedHashSet<>();
            for (String part : raw.split(",")) {
                String server = part.trim();
                if (!server.isEmpty()) {
                    servers.add(server);
                }
            }
            return List.copyOf(servers);
        }
    }

    private enum OperationType {
        UPDATE,
        DELETE,
        RENAME
    }

    private static final class Operation {
        private final Path path;
        private final Path newPath;
        private final OperationType type;

        private Operation(Path path, Path newPath, OperationType type) {
            this.path = path;
            this.newPath = newPath;
            this.type = type;
        }
    }

    private static final class AuditTailer {
        private final Config config;
        private final SyncEngine engine;
        private final ScheduledExecutorService scheduler;
        private final ThreadPoolExecutor processor;
        private long offset;
        private Object fileKey;
        private boolean initialized;
        private final AtomicBoolean reading = new AtomicBoolean(false);

        private AuditTailer(Config config, SyncEngine engine) {
            this.config = config;
            this.engine = engine;
            this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(named("audit-tailer"));
            this.processor = new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(config.maxQueuedLogLines),
                    named("audit-processor"),
                    new ThreadPoolExecutor.CallerRunsPolicy());
        }

        private void start() {
            scheduler.scheduleWithFixedDelay(this::readNewLinesSafely, 0, 1, TimeUnit.SECONDS);
        }

        private void readNewLinesSafely() {
            if (!reading.compareAndSet(false, true)) {
                log("Audit read skipped because previous read is still running");
                return;
            }
            try {
                readNewLines();
                writeHeartbeat(config.healthFile);
            } catch (Exception e) {
                log("Audit read failed: " + e.getMessage());
                e.printStackTrace(System.err);
            } finally {
                reading.set(false);
            }
        }

        private void readNewLines() throws IOException {
            if (!Files.exists(config.auditLogPath)) {
                log("Audit log does not exist: " + config.auditLogPath);
                return;
            }
            BasicFileAttributes attrs = Files.readAttributes(config.auditLogPath, BasicFileAttributes.class);
            long size = attrs.size();
            Object currentKey = attrs.fileKey();
            if (!initialized) {
                fileKey = currentKey;
                if (!config.replayExistingLogOnStartup) {
                    offset = size;
                    initialized = true;
                    return;
                }
                initialized = true;
            }
            if (!Objects.equals(fileKey, currentKey) || size < offset) {
                log("Audit log rotation/truncation detected. Resetting offset from " + offset + " to 0.");
                offset = 0;
                fileKey = currentKey;
            }

            int lines = 0;
            try (RandomAccessFile file = new RandomAccessFile(config.auditLogPath.toFile(), "r")) {
                file.seek(offset);
                String line;
                while ((line = file.readLine()) != null) {
                    String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).trim();
                    if (!decoded.isEmpty()) {
                        processor.execute(() -> process(decoded));
                    }
                    lines++;
                    if (lines >= config.maxReadLinesPerTick) {
                        break;
                    }
                }
                offset = file.getFilePointer();
            }
        }

        private void process(String line) {
            String payload = AUDIT_PREFIX.matcher(line).replaceFirst("");
            String[] fields = payload.split("\\|", -1);
            if (fields.length < 6 || payload.equals(line)) {
                log("Ignoring unparsable audit line: " + line);
                return;
            }
            String operation = fields[3].toLowerCase(Locale.ROOT);
            Path path = normalizeAuditPath(fields[5]);
            Path newPath = fields.length > 6 ? normalizeAuditPath(fields[6]) : null;

            if (path == null || shouldIgnore(path)) {
                return;
            }

            if (WRITE_OPS.contains(operation)) {
                engine.submit(new Operation(path, null, OperationType.UPDATE));
            } else if (DELETE_OPS.contains(operation)) {
                engine.submit(new Operation(path, null, OperationType.DELETE));
            } else if (RENAME_OPS.contains(operation) && newPath != null && !shouldIgnore(newPath)) {
                engine.submit(new Operation(path, newPath, OperationType.RENAME));
            }
        }

        private Path normalizeAuditPath(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            Path path = Paths.get(raw.trim()).toAbsolutePath().normalize();
            if (!path.startsWith(config.rootPath)) {
                return null;
            }
            return path;
        }

        private boolean shouldIgnore(Path path) {
            String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
            return fileName.endsWith(".tmp") || fileName.contains("~$");
        }

        private void shutdown() {
            scheduler.shutdownNow();
            processor.shutdownNow();
        }
    }

    private static final class SyncEngine {
        private final Config config;
        private final ScheduledExecutorService scheduler;
        private final ExecutorService workers;
        private final ExecutorService outputReaders;
        private final ConcurrentMap<Path, List<Operation>> pending = new ConcurrentHashMap<>();
        private final ConcurrentMap<Path, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Boolean> daemonCache = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Boolean> sshCache = new ConcurrentHashMap<>();

        private SyncEngine(Config config) {
            this.config = config;
            this.scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1, named("sync-scheduler"));
            this.workers = new ThreadPoolExecutor(
                    config.maxConcurrentSyncs,
                    config.maxConcurrentSyncs,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(config.maxQueuedLogLines),
                    named("sync-worker"),
                    new ThreadPoolExecutor.CallerRunsPolicy());
            this.outputReaders = java.util.concurrent.Executors.newCachedThreadPool(named("process-output"));
        }

        private void submit(Operation operation) {
            Path key = operation.type == OperationType.RENAME && operation.newPath != null ? operation.newPath : operation.path;
            pending.compute(key, (ignored, operations) -> {
                List<Operation> list = operations == null ? Collections.synchronizedList(new ArrayList<>()) : operations;
                list.add(operation);
                return list;
            });
            scheduled.compute(key, (ignored, oldFuture) -> {
                if (oldFuture != null) {
                    oldFuture.cancel(false);
                }
                return scheduler.schedule(() -> dispatch(key), config.debounceSeconds, TimeUnit.SECONDS);
            });
        }

        private void dispatch(Path key) {
            List<Operation> operations = pending.remove(key);
            scheduled.remove(key);
            if (operations == null || operations.isEmpty()) {
                return;
            }
            Operation merged = merge(operations);
            for (String server : config.syncServers) {
                workers.execute(() -> process(server, merged));
            }
        }

        private Operation merge(List<Operation> operations) {
            Operation lastRename = null;
            Operation lastDelete = null;
            Operation lastUpdate = null;
            synchronized (operations) {
                for (Operation operation : operations) {
                    if (operation.type == OperationType.DELETE) {
                        lastDelete = operation;
                    } else if (operation.type == OperationType.RENAME) {
                        lastRename = operation;
                    } else {
                        lastUpdate = operation;
                    }
                }
            }
            if (lastDelete != null) {
                return lastDelete;
            }
            if (lastRename != null) {
                return lastRename;
            }
            return lastUpdate;
        }

        private void process(String server, Operation operation) {
            try {
                if (operation.type == OperationType.DELETE) {
                    deleteRemote(server, operation.path);
                } else if (operation.type == OperationType.RENAME) {
                    syncToServer(server, operation.newPath);
                    deleteRemote(server, operation.path);
                } else {
                    syncToServer(server, operation.path);
                }
                writeHeartbeat(config.healthFile);
            } catch (Exception e) {
                log("Sync failed for " + server + " " + operation.type + " " + operation.path + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        private void syncToServer(String server, Path source) throws Exception {
            if (shouldRelay(server)) {
                syncViaRelay(server, source);
                return;
            }
            String destination = destination(server, source);
            List<String> command = new ArrayList<>();
            command.add("rsync");
            command.add("-aAX");
            command.add("--numeric-ids");
            command.add("--delete-delay");
            command.add("--partial");
            command.add("--timeout=" + config.rsyncTimeoutSeconds);
            command.add("--contimeout=30");
            command.add("--bwlimit=" + config.bwlimit);
            if (!destination.startsWith("rsync://")) {
                command.add("-e");
                command.add("ssh -o BatchMode=yes -o ConnectTimeout=30 -o ServerAliveInterval=30 -o ServerAliveCountMax=3");
            }

            String sourceArg = Files.isDirectory(source) ? ensureTrailingSlash(source.toString()) : source.toString();
            command.add(sourceArg);
            command.add(destination);
            run(command, Duration.ofSeconds(config.rsyncTimeoutSeconds + 60));
        }

        private String destination(String server, Path source) {
            if (config.useRsyncDaemon && daemonAvailable(server)) {
                return daemonDestination(server, source);
            }
            return "root@" + server + ":" + source.toAbsolutePath().normalize();
        }

        private String daemonDestination(String server, Path source) {
            Path relative = config.rootPath.relativize(source.toAbsolutePath().normalize());
            return "rsync://" + server + "/" + config.rsyncModule + "/" + relative.toString().replace('\\', '/');
        }

        private boolean daemonAvailable(String server) {
            return daemonCache.computeIfAbsent(server, this::checkDaemon);
        }

        private boolean checkDaemon(String server) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(server, 873), 2000);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private boolean sshAvailable(String server) {
            return sshCache.computeIfAbsent(server, this::checkSsh);
        }

        private boolean checkSsh(String server) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(server, 22), 2000);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private boolean shouldRelay(String server) {
            return !config.relayHost.isBlank()
                    && !server.equals(config.relayHost)
                    && !daemonAvailable(server)
                    && !sshAvailable(server);
        }

        private void syncViaRelay(String server, Path source) throws Exception {
            log("Relaying sync to " + server + " via " + config.relayHost + " for " + source);
            syncToServer(config.relayHost, source);
            String sourceArg = Files.isDirectory(source) ? ensureTrailingSlash(source.toString()) : source.toString();
            String destination = daemonDestination(server, source);
            String remoteCommand = String.join(" ",
                    "rsync",
                    "-aAX",
                    "--numeric-ids",
                    "--delete-delay",
                    "--partial",
                    "--timeout=" + config.rsyncTimeoutSeconds,
                    "--contimeout=30",
                    "--bwlimit=" + config.bwlimit,
                    shellQuote(sourceArg),
                    shellQuote(destination));
            runSshCommand(config.relayHost, remoteCommand, Duration.ofSeconds(config.rsyncTimeoutSeconds + 120));
        }

        private void deleteRemote(String server, Path path) throws Exception {
            if (shouldRelay(server)) {
                log("Relaying delete to " + server + " via " + config.relayHost + " for " + path);
                String remoteCommand = String.join(" ",
                        "ssh",
                        "-o BatchMode=yes",
                        "-o ConnectTimeout=30",
                        "-o ServerAliveInterval=30",
                        "-o ServerAliveCountMax=3",
                        "root@" + shellQuote(server),
                        "rm -rf --",
                        shellQuote(path.toAbsolutePath().normalize().toString()));
                runSshCommand(config.relayHost, remoteCommand, Duration.ofSeconds(config.sshTimeoutSeconds + 60));
                return;
            }
            String quoted = "'" + path.toAbsolutePath().normalize().toString().replace("'", "'\\''") + "'";
            List<String> command = Arrays.asList(
                    "ssh",
                    "-o", "BatchMode=yes",
                    "-o", "ConnectTimeout=30",
                    "-o", "ServerAliveInterval=30",
                    "-o", "ServerAliveCountMax=3",
                    "root@" + server,
                    "rm -rf -- " + quoted);
            run(command, Duration.ofSeconds(config.sshTimeoutSeconds));
        }

        private void runSshCommand(String server, String remoteCommand, Duration timeout) throws Exception {
            List<String> command = Arrays.asList(
                    "ssh",
                    "-o", "BatchMode=yes",
                    "-o", "ConnectTimeout=30",
                    "-o", "ServerAliveInterval=30",
                    "-o", "ServerAliveCountMax=3",
                    "root@" + server,
                    remoteCommand);
            run(command, timeout);
        }

        private void run(List<String> command, Duration timeout) throws Exception {
            log("Executing: " + String.join(" ", command));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Future<?> output = outputReaders.submit(() -> drainOutput(process.getInputStream()));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new TimeoutException("Command timed out after " + timeout);
            }
            output.get(30, TimeUnit.SECONDS);
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IOException("Command exited with code " + exit + ": " + String.join(" ", command));
            }
        }

        private void drainOutput(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log(line);
                }
            } catch (IOException e) {
                log("Could not read process output: " + e.getMessage());
            }
        }

        private void shutdown() {
            scheduler.shutdownNow();
            workers.shutdownNow();
            outputReaders.shutdownNow();
        }
    }

    private static String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void writeHeartbeat(Path path) {
        try {
            Files.writeString(path, Instant.now().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log("Could not write heartbeat " + path + ": " + e.getMessage());
        }
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + count.incrementAndGet());
            thread.setUncaughtExceptionHandler((t, e) -> {
                log("Uncaught exception in " + t.getName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
            });
            return thread;
        };
    }
}
