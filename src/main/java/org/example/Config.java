package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

final class Config {
    final Path auditLog, root, state, health, identity, knownHosts;
    final List<String> servers;
    final int bandwidth, debounce, commandTimeout, sshTimeout, concurrency, capacity, readLimit;
    final int retryInitial, retryMaximum, cacheSeconds, watchdogSeconds, backlogSeconds, shutdownSeconds;
    final boolean daemon, replay, deletes;
    final String module, relay, sshUser;

    Config(Properties p) {
        auditLog = path(p, "audit.log.path", "/var/log/fullauditlog/samba_audit.log");
        root = path(p, "root.path", "/srv/samba/schools/default-school");
        state = path(p, "state.directory", "/var/lib/fileserversync");
        health = path(p, "health.file", "/tmp/fileserversync.heartbeat");
        identity = path(p, "ssh.identity.file", "/root/.ssh/id_ed25519");
        knownHosts = path(p, "ssh.known.hosts.file", "/root/.ssh/known_hosts");
        servers = Arrays.stream(value(p, "sync.servers", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (servers.isEmpty()) throw new IllegalArgumentException("sync.servers must contain at least one host");
        servers.forEach(Config::validateHost);
        if (servers.size() > 32) throw new IllegalArgumentException("At most 32 sync.servers are supported");
        if (root.getParent() == null || state.startsWith(root))
            throw new IllegalArgumentException("root.path must not be / and state.directory must be outside root.path");
        bandwidth = integer(p, "bwlimit", 3000, 0, Integer.MAX_VALUE);
        debounce = integer(p, "buffer.time.seconds", 30, 0, 3600);
        commandTimeout = integer(p, "rsync.timeout.seconds", 1800, 1, 86400);
        sshTimeout = integer(p, "ssh.timeout.seconds", 120, 1, 3600);
        concurrency = integer(p, "max.concurrent.syncs", 4, 1, 32);
        capacity = integer(p, "max.queued.log.lines", 20000, 1, 50000);
        readLimit = integer(p, "max.read.lines.per.tick", 500, 1, 5000);
        retryInitial = integer(p, "retry.initial.seconds", 5, 1, 3600);
        retryMaximum = integer(p, "retry.max.seconds", 300, retryInitial, 86400);
        cacheSeconds = integer(p, "connection.cache.seconds", 30, 1, 300);
        watchdogSeconds = integer(p, "watchdog.seconds", 180, 10, 86400);
        backlogSeconds = integer(p, "health.max.backlog.seconds", 3600, 1, 604800);
        shutdownSeconds = integer(p, "shutdown.timeout.seconds", 30, 1, 60);
        daemon = bool(p, "rsync.daemon.enabled", false);
        replay = bool(p, "replay.existing.log.on.startup", false);
        deletes = bool(p, "propagate.deletes", false);
        module = value(p, "rsync.daemon.module", "default-school");
        if (!module.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Invalid rsync.daemon.module");
        relay = value(p, "relay.host", "");
        if (!relay.isEmpty()) validateHost(relay);
        sshUser = value(p, "ssh.user", "root");
        if (!sshUser.matches("[a-z_][a-z0-9_-]*[$]?")) throw new IllegalArgumentException("Invalid ssh.user");
    }

    static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return new Config(p);
    }

    void validateFilesystem() throws IOException {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root) || !root.toRealPath().equals(root))
            throw new IOException("root.path must be an existing directory without symlink components: " + root);
        if (!Files.isRegularFile(auditLog) || !Files.isReadable(auditLog))
            throw new IOException("Audit log is missing or unreadable: " + auditLog);
    }

    String context() { return root + "\n" + String.join(",", servers); }

    static void validateHost(String host) {
        if (host.length() > 253 || !host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"))
            throw new IllegalArgumentException("Invalid host (use a DNS name or IPv4 address): " + host);
    }

    private static Path path(Properties p, String key, String fallback) {
        Path result = Path.of(value(p, key, fallback));
        if (!result.isAbsolute()) throw new IllegalArgumentException(key + " must be absolute");
        return result.normalize();
    }

    private static String value(Properties p, String key, String fallback) { return p.getProperty(key, fallback).trim(); }
    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = value(p, key, Boolean.toString(fallback));
        if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(v);
    }
    private static int integer(Properties p, String key, int fallback, int min, int max) {
        int n;
        try { n = Integer.parseInt(value(p, key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer", e); }
        if (n < min || n > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return n;
    }
}
