package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(System.getenv().getOrDefault("FILESYNC_CONFIG", "/app/config.properties"));
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) configPath = Path.of(args[++i]);
            else if (!args[i].equals("--check-config")) throw new IllegalArgumentException("Usage: [--config FILE] [--check-config]");
        }
        Config config = Config.load(configPath);
        config.validateFilesystem();
        if (Arrays.asList(args).contains("--check-config")) { log("Configuration and source paths are valid; no transfer attempted"); return; }
        Files.deleteIfExists(config.health);
        Journal journal = new Journal(config);
        Transport transport = new Transport(config);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SyncEngine engine = new SyncEngine(config, journal, transport, fatal);
        AuditTailer tailer = new AuditTailer(config, journal);
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(named("audit-tailer"));
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(named("watchdog"));
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicLong lastHealthy = new AtomicLong(System.nanoTime());
        Runnable shutdown = () -> {
            if (!stopped.compareAndSet(false, true)) return;
            poller.shutdownNow(); watchdog.shutdownNow();
            engine.close(); transport.close();
            try {
                if (!engine.awaitStopped()) log("Workers still stopping; unfinished work remains in the journal");
                if (!poller.awaitTermination(5, TimeUnit.SECONDS)) log("Audit poller still stopping");
                journal.close(); Files.deleteIfExists(config.health);
            } catch (Exception e) { log("Shutdown: " + e.getMessage()); }
            log("Stopped; unacknowledged deliveries remain on disk");
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "shutdown-hook"));
        log("Starting durable FileServerSync; targets=" + config.servers + " queued=" + journal.size()
                + " deletionPropagation=" + config.deletes);
        poller.scheduleWithFixedDelay(() -> {
            try {
                config.validateFilesystem();
                tailer.tick();
                heartbeat(config, journal, engine);
                lastHealthy.set(System.nanoTime());
            } catch (Exception e) { log("Audit/checkpoint/health failure: " + e.getMessage()); }
        }, 0, 1, TimeUnit.SECONDS);
        watchdog.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - lastHealthy.get() > TimeUnit.SECONDS.toNanos(config.watchdogSeconds)) {
                log("No successful audit cycle within watchdog timeout; exiting for supervised restart");
                System.exit(2);
            }
        }, 1, 1, TimeUnit.SECONDS);
        try {
            while (!stopped.get()) {
                if (fatal.get() != null) throw new IOException("Cannot safely acknowledge durable work", fatal.get());
                engine.tick(); Thread.sleep(200);
            }
        } finally { shutdown.run(); }
    }

    static void heartbeat(Config config, Journal journal, SyncEngine engine) throws IOException {
        boolean degraded = engine.degraded() || journal.size() >= config.capacity || journal.oldestAgeSeconds() > config.backlogSeconds;
        String text = "status=" + (degraded ? "degraded" : "healthy") + "\npid=" + ProcessHandle.current().pid()
                + "\nqueued=" + journal.size() + "\noldest.seconds=" + journal.oldestAgeSeconds()
                + "\nupdated=" + Instant.now() + "\n";
        Path temporary = config.health.resolveSibling(config.health.getFileName() + ".next");
        Files.writeString(temporary, text);
        Files.move(temporary, config.health, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void log(String message) { System.out.println(Instant.now() + " " + message); }
    static ThreadFactory named(String prefix) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + "-" + count.incrementAndGet());
    }
}
