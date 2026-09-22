package org.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SyncEngine implements AutoCloseable {
    private final Config config;
    private final Journal journal;
    private final RemoteActions transport;
    private final ExecutorService workers;
    private final Set<String> active = new HashSet<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> fatal;
    private boolean closed;
    private int nextServer;

    SyncEngine(Config config, Journal journal, RemoteActions transport, AtomicReference<Throwable> fatal) {
        this.config = config; this.journal = journal; this.transport = transport; this.fatal = fatal;
        workers = Executors.newFixedThreadPool(config.concurrency, Main.named("sync-worker"));
    }

    synchronized void tick() {
        if (closed) return;
        int start = nextServer;
        for (int i = 0; i < config.servers.size(); i++) {
            if (active.size() >= config.concurrency) return;
            int index = (start + i) % config.servers.size();
            String server = config.servers.get(index);
            if (active.contains(server) || retryAfter.getOrDefault(server, 0L) > System.currentTimeMillis()) continue;
            Journal.Job job = journal.head(server);
            if (job == null || System.currentTimeMillis() < job.created + config.debounce * 1000L) continue;
            // Serialize every target, including parent/child paths; other targets keep progressing independently.
            active.add(server); journal.started(job);
            nextServer = (index + 1) % config.servers.size();
            workers.execute(() -> deliver(server, job));
        }
    }

    private void deliver(String server, Journal.Job job) {
        try {
            apply(server, job);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Shutdown interrupted delivery");
            try { journal.acknowledge(job.id, server); }
            catch (IOException e) { fatal.compareAndSet(null, e); return; }
            attempts.remove(server); retryAfter.remove(server);
            Main.log("Delivered job=" + job.id + " target=" + server + " type=" + job.operation.type());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) {
            int attempt = attempts.merge(server, 1, (a, b) -> Math.min(30, a + b));
            long delay = Math.min(config.retryMaximum, config.retryInitial * (1L << Math.min(attempt - 1, 20)));
            retryAfter.put(server, System.currentTimeMillis() + delay * 1000L);
            Main.log("Delivery failed; job=" + job.id + " target=" + server + " retryIn=" + delay + "s reason=" + e.getMessage());
        } finally { synchronized (this) { active.remove(server); } }
    }

    void apply(String server, Journal.Job job) throws Exception {
        Operation operation = job.operation;
        Operation.requireSafe(operation.path(), config.root);
        if (operation.type() == Operation.Type.UPDATE) {
            if (exists(operation.path())) transport.synchronize(server, operation.path());
            // A later successful rename/delete audit event carries the removal. Absence alone is not a tombstone.
        } else if (operation.type() == Operation.Type.DELETE) {
            if (exists(operation.path())) transport.synchronize(server, operation.path());
            else transport.delete(server, operation.path());
        } else {
            Path destination = operation.newPath();
            Operation.requireSafe(destination, config.root);
            if (!exists(destination)) {
                // Resolve rename chains already accepted after an outage, before cleaning up an old name.
                for (Operation later : journal.following(job.id, server)) {
                    if (later.type() == Operation.Type.RENAME && destination.startsWith(later.path()))
                        destination = later.newPath().resolve(later.path().relativize(destination));
                }
            }
            if (!exists(destination)) {
                Path finalDestination = destination;
                boolean explicitlyDeleted = journal.following(job.id, server).stream().anyMatch(later ->
                        later.type() == Operation.Type.DELETE && finalDestination.startsWith(later.path()));
                if (!explicitlyDeleted) throw new IOException("Rename destination is unavailable; retaining old remote data: " + destination);
            } else transport.synchronize(server, destination);
            // A failed copy must never fall through into deleting the only surviving remote copy.
            if (!operation.path().equals(destination)) {
                if (exists(operation.path())) transport.synchronize(server, operation.path());
                else transport.delete(server, operation.path());
            }
        }
    }

    private static boolean exists(Path path) throws IOException { return Operation.exists(path); }
    boolean degraded() { return !attempts.isEmpty(); }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        workers.shutdown();
        try { if (!workers.awaitTermination(config.shutdownSeconds, TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch (InterruptedException e) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    boolean awaitStopped() throws InterruptedException { return workers.awaitTermination(5, TimeUnit.SECONDS); }
}
