package org.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class Transport implements RemoteActions, AutoCloseable {
    private record Availability(boolean available, long expires) { }
    private final Config config;
    private final ProcessRunner runner;
    private final ConcurrentHashMap<String, Availability> availability = new ConcurrentHashMap<>();

    Transport(Config config) { this.config = config; runner = new ProcessRunner(config.concurrency); }

    @Override public void synchronize(String server, Path path) throws Exception {
        Operation.requireSafe(path, config.root);
        if (!Operation.exists(path)) throw new IOException("Source disappeared before transfer: " + path);
        if (relayNeeded(server, false)) {
            synchronize(config.relay, path);
            // The relay executes the same relative-source transfer. A daemon is required on the last hop.
            if (!config.daemon) throw new IOException("Relay transfer currently requires rsync.daemon.enabled=true");
            List<String> remote = rsyncCommand(server, path, true, true);
            runner.run(sshCommand(config.relay, quoteCommand(remote), false), Duration.ofSeconds(config.commandTimeout + 60L));
        } else {
            boolean daemon = config.daemon && available(server, 873);
            try { runner.run(rsyncCommand(server, path, daemon, false), Duration.ofSeconds(config.commandTimeout + 30L)); }
            catch (Exception e) { availability.remove(server + ":873"); availability.remove(server + ":22"); throw e; }
        }
    }

    List<String> rsyncCommand(String server, Path source, boolean daemon, boolean onRelay) {
        List<String> command = new ArrayList<>(List.of("rsync", "-aAX", "--numeric-ids", "--relative", "--no-implied-dirs",
                "--safe-links", "--no-devices", "--no-specials", "--secluded-args", "--partial-dir=.filesync-partial",
                "--exclude=.filesync-partial/", "--exclude=*.tmp", "--exclude=~$*", "--timeout=" + config.commandTimeout, "--bwlimit=" + config.bandwidth));
        if (daemon) command.add("--contimeout=30");
        else { command.add("-e"); command.add(quoteCommand(sshBase(!onRelay))); }
        String sourceArg = config.root + "/./" + config.root.relativize(source);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) sourceArg += "/";
        command.add("--"); command.add(sourceArg);
        command.add(daemon ? "rsync://" + server + "/" + config.module + "/" : config.sshUser + "@" + server + ":" + config.root + "/");
        return command;
    }

    @Override public void delete(String server, Path path) throws Exception {
        Operation.requireSafe(path, config.root);
        if (!config.deletes) { Main.log("Deletion propagation disabled; retained remote path " + path); return; }
        if (Operation.exists(path)) throw new IOException("Source was recreated; refusing stale deletion: " + path);
        String command = deleteScript(path, config.root);
        boolean relay = relayNeeded(server, true);
        if (relay) command = quoteCommand(sshCommand(server, command, true));
        String host = relay ? config.relay : server;
        runner.run(sshCommand(host, command, false), Duration.ofSeconds(config.sshTimeout));
    }

    static String deleteScript(Path target, Path root) {
        // The target's parents are resolved remotely as well: local lexical confinement alone is insufficient.
        return "set -eu; root=" + quote(root.toString()) + "; target=" + quote(target.toString())
                + "; [ \"$target\" != \"$root\" ] || exit 65; "
                + "parent=$(dirname -- \"$target\"); resolved=$(realpath -m -- \"$parent\"); "
                + "case \"$resolved/\" in \"$root/\"|\"$root/\"*) ;; *) exit 65 ;; esac; rm -rf -- \"$target\"";
    }

    private List<String> sshBase(boolean localIdentity) {
        List<String> result = new ArrayList<>(List.of("ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3"));
        if (localIdentity) result.addAll(List.of("-o", "IdentitiesOnly=yes", "-i", config.identity.toString(),
                "-o", "UserKnownHostsFile=" + config.knownHosts));
        return result;
    }
    List<String> sshCommand(String server, String script, boolean onRelay) {
        List<String> result = sshBase(!onRelay);
        result.add(config.sshUser + "@" + server); result.add(script); return result;
    }
    private boolean relayNeeded(String server, boolean needsSsh) {
        return !config.relay.isEmpty() && !server.equals(config.relay) && !available(server, 22)
                && (needsSsh || !config.daemon || !available(server, 873));
    }
    private boolean available(String server, int port) {
        String key = server + ":" + port; long now = System.nanoTime();
        Availability saved = availability.get(key);
        if (saved != null && saved.expires > now) return saved.available;
        boolean value;
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(server, port), 2000); value = true; }
        catch (IOException e) { value = false; }
        availability.put(key, new Availability(value, now + Duration.ofSeconds(config.cacheSeconds).toNanos()));
        return value;
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static String quoteCommand(List<String> command) { return String.join(" ", command.stream().map(Transport::quote).toList()); }
    @Override public void close() { runner.close(); }
}
