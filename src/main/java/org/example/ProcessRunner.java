package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProcessRunner implements AutoCloseable {
    private final Set<Process> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService readers;
    private volatile boolean closed;

    ProcessRunner(int concurrency) { readers = Executors.newFixedThreadPool(concurrency, Main.named("process-output")); }

    void run(List<String> command, Duration timeout) throws Exception {
        Process process;
        synchronized (this) {
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("Command runner is stopping");
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            active.add(process);
        }
        Future<?> output = null;
        try {
            output = readers.submit(() -> drain(process.getInputStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new TimeoutException("Command exceeded " + timeout);
            output.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IOException(command.get(0) + " exited with code " + process.exitValue());
        } finally {
            terminate(process);
            active.remove(process);
            if (output != null) output.cancel(true);
            process.getInputStream().close(); process.getOutputStream().close(); process.getErrorStream().close();
        }
    }

    private static void drain(InputStream stream) {
        // Bound retained output even when a child writes a single enormous line.
        byte[] buffer = new byte[4096]; int remaining = 65536;
        try {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (remaining > 0) {
                    int shown = Math.min(remaining, count);
                    Main.log("command: " + new String(buffer, 0, shown, StandardCharsets.UTF_8).replace('\r', ' ').replace('\n', ' '));
                    remaining -= shown;
                    if (remaining == 0) Main.log("Further output from this command is suppressed");
                }
            }
        } catch (IOException e) { Main.log("Process output closed: " + e.getMessage()); }
    }

    private static void terminate(Process process) {
        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        if (process.isAlive()) process.destroy();
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }
    @Override public synchronized void close() {
        closed = true; active.forEach(ProcessRunner::terminate); readers.shutdownNow();
    }
}
