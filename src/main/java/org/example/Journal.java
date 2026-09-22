package org.example;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/** Local, single-writer WAL. An accepted batch and its audit cursor share one fsynced frame. */
final class Journal implements AutoCloseable {
    private static final int MAX_FRAME = 64 * 1024 * 1024;
    private static final long COMPACT_BYTES = 32L * 1024 * 1024;
    record Cursor(String fileKey, long offset, String anchor) { }
    static final class Job {
        final long id, created;
        final Operation operation;
        final Set<String> remaining;
        boolean started;
        Job(long id, long created, Operation operation, Set<String> remaining) {
            this.id = id; this.created = created; this.operation = operation; this.remaining = remaining;
        }
    }
    private final Config config;
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private FileChannel channel;
    private final LinkedHashMap<Long, Job> jobs = new LinkedHashMap<>();
    private Cursor cursor;
    private long nextId = 1;
    private boolean poisoned, closed;

    Journal(Config config) throws IOException {
        this.config = config;
        Files.createDirectories(config.state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        file = config.state.resolve("queue.wal");
        lockChannel = privateFile(config.state.resolve("writer.lock"), Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE));
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException e) { lockChannel.close(); throw new IOException("State is already in use", e); }
        if (acquired == null) { lockChannel.close(); throw new IOException("State is already in use"); }
        lock = acquired;
        try {
            if (Files.exists(file)) recover();
            channel = privateFile(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
            if (channel.size() == 0) { append(snapshot()); forceDirectory(); }
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }

    synchronized Cursor cursor() { return cursor; }
    synchronized int size() { return jobs.size(); }
    synchronized long oldestAgeSeconds() {
        return jobs.isEmpty() ? 0 : Math.max(0, (System.currentTimeMillis() - jobs.values().iterator().next().created) / 1000);
    }
    synchronized Job head(String server) {
        for (Job job : jobs.values()) if (job.remaining.contains(server)) return job;
        return null;
    }
    synchronized void started(Job job) { job.started = true; }
    synchronized List<Operation> following(long id, String server) {
        List<Operation> result = new ArrayList<>();
        for (Job job : jobs.values()) if (job.id > id && job.remaining.contains(server)) result.add(job.operation);
        return result;
    }

    synchronized void accept(List<Operation> operations, Cursor newCursor) throws IOException {
        List<Job> additions = new ArrayList<>();
        Job last = null;
        for (Job job : jobs.values()) last = job;
        long id = nextId;
        for (Operation op : operations) {
            // Only consecutive, unstarted writes can be merged without crossing a rename/delete barrier.
            if (op.type() == Operation.Type.UPDATE && last != null && !last.started
                    && last.operation.equals(op) && last.remaining.size() == config.servers.size()) continue;
            last = new Job(id++, System.currentTimeMillis(), op, new LinkedHashSet<>(config.servers));
            additions.add(last);
        }
        if (jobs.size() + additions.size() > config.capacity) throw new IOException("Persistent queue is full");
        byte[] payload = encode(out -> {
            out.writeByte(1); writeCursor(out, newCursor); out.writeInt(additions.size());
            for (Job job : additions) writeJob(out, job);
        });
        append(payload);
        for (Job job : additions) jobs.put(job.id, job);
        nextId = id;
        cursor = newCursor;
        compactIfNeeded();
    }

    synchronized void acknowledge(long id, String server) throws IOException {
        Job job = jobs.get(id);
        if (job == null || !job.remaining.contains(server)) return;
        append(encode(out -> { out.writeByte(2); out.writeLong(id); out.writeUTF(server); }));
        job.remaining.remove(server);
        if (job.remaining.isEmpty()) jobs.remove(id);
        compactIfNeeded();
    }

    private void recover() throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "rw")) {
            long good = 0;
            while (input.getFilePointer() < input.length()) {
                if (input.length() - input.getFilePointer() < 4) { input.setLength(good); break; }
                int size = input.readInt();
                if (size <= 0 || size > MAX_FRAME) throw new IOException("Invalid journal frame length; restore state from backup");
                if (input.length() - input.getFilePointer() < size + 4L) {
                    Main.log("Discarding incomplete final journal frame after interrupted write");
                    input.setLength(good); break;
                }
                byte[] data = new byte[size]; input.readFully(data);
                int expected = input.readInt();
                CRC32 crc = new CRC32(); crc.update(data);
                if ((int) crc.getValue() != expected) throw new IOException("Journal checksum mismatch; refusing to skip confirmed work");
                if (good == 0 && data[0] != 3) throw new IOException("Journal is missing its initial snapshot");
                replay(data);
                good = input.getFilePointer();
            }
            input.getChannel().force(true);
        }
    }

    private void replay(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = in.readUnsignedByte();
            if (type == 3) {
                if (!in.readUTF().equals("FILESYNC-WAL-1") || !in.readUTF().equals(config.context()))
                    throw new IOException("State version/root/targets differ from this configuration; use a separately planned migration");
                jobs.clear(); nextId = in.readLong(); cursor = readCursor(in);
                int count = count(in);
                for (int i = 0; i < count; i++) { Job job = readJob(in); jobs.put(job.id, job); }
            } else if (type == 1) {
                cursor = readCursor(in);
                int count = count(in);
                for (int i = 0; i < count; i++) { Job job = readJob(in); jobs.put(job.id, job); nextId = Math.max(nextId, job.id + 1); }
            } else if (type == 2) {
                long id = in.readLong(); String server = in.readUTF(); Job job = jobs.get(id);
                if (job != null) { job.remaining.remove(server); if (job.remaining.isEmpty()) jobs.remove(id); }
            } else throw new IOException("Unknown journal record type");
            if (in.available() != 0) throw new IOException("Trailing data in journal record");
        } catch (IllegalArgumentException e) { throw new IOException("Invalid journal contents", e); }
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 0 || n > 50000) throw new IOException("Invalid journal entry count"); return n;
    }
    private Job readJob(DataInputStream in) throws IOException {
        long id = in.readLong(), created = in.readLong();
        Operation.Type type = Operation.Type.valueOf(in.readUTF());
        Path path = Path.of(in.readUTF()); String newValue = in.readUTF(); Path newPath = newValue.isEmpty() ? null : Path.of(newValue);
        if (Operation.parsePath(path.toString(), config.root) == null || (newPath != null && Operation.parsePath(newValue, config.root) == null))
            throw new IOException("Journal path is outside configured root");
        Set<String> servers = new LinkedHashSet<>(); int n = count(in);
        for (int i = 0; i < n; i++) { String server = in.readUTF(); if (!config.servers.contains(server)) throw new IOException("Unknown journal target"); servers.add(server); }
        return new Job(id, created, new Operation(type, path, newPath), servers);
    }
    private static void writeJob(DataOutputStream out, Job job) throws IOException {
        out.writeLong(job.id); out.writeLong(job.created); out.writeUTF(job.operation.type().name());
        out.writeUTF(job.operation.path().toString()); out.writeUTF(job.operation.newPath() == null ? "" : job.operation.newPath().toString());
        out.writeInt(job.remaining.size()); for (String server : job.remaining) out.writeUTF(server);
    }
    private static void writeCursor(DataOutputStream out, Cursor cursor) throws IOException {
        out.writeBoolean(cursor != null);
        if (cursor != null) { out.writeUTF(cursor.fileKey); out.writeLong(cursor.offset); out.writeUTF(cursor.anchor); }
    }
    private static Cursor readCursor(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        Cursor c = new Cursor(in.readUTF(), in.readLong(), in.readUTF());
        if (c.offset < 0) throw new IOException("Negative audit cursor");
        return c;
    }
    private byte[] snapshot() throws IOException {
        return encode(out -> {
            out.writeByte(3); out.writeUTF("FILESYNC-WAL-1"); out.writeUTF(config.context()); out.writeLong(nextId);
            writeCursor(out, cursor); out.writeInt(jobs.size()); for (Job job : jobs.values()) writeJob(out, job);
        });
    }
    private static ByteBuffer frame(byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME) throw new IOException("Journal frame exceeds size limit");
        CRC32 crc = new CRC32(); crc.update(payload);
        return ByteBuffer.allocate(payload.length + 8).putInt(payload.length).put(payload).putInt((int) crc.getValue()).flip();
    }
    private void append(byte[] payload) throws IOException {
        if (poisoned || closed) throw new IOException("Journal is not writable");
        try { ByteBuffer data = frame(payload); while (data.hasRemaining()) channel.write(data); channel.force(true); }
        catch (IOException e) { poisoned = true; throw e; }
    }
    private void forceDirectory() throws IOException {
        try (FileChannel dir = FileChannel.open(config.state, StandardOpenOption.READ)) { dir.force(true); }
    }
    synchronized void compactIfNeeded() throws IOException {
        if (channel.size() < COMPACT_BYTES) return;
        compact();
    }
    synchronized void compact() throws IOException {
        Path temporary = config.state.resolve("queue.wal.next");
        try {
            ByteBuffer data = frame(snapshot());
            try (FileChannel out = privateFile(temporary, Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                while (data.hasRemaining()) out.write(data); out.force(true);
            }
            // Fail closed on filesystems without atomic replacement, rather than silently weakening durability.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(); channel.close();
            channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) { poisoned = true; throw e; }
    }
    private static FileChannel privateFile(Path path, Set<StandardOpenOption> options) throws IOException {
        return FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { writer.write(out); }
        return bytes.toByteArray();
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { if (channel != null) channel.close(); }
        finally { try { lock.release(); } finally { lockChannel.close(); } }
    }
}
