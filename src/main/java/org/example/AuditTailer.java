package org.example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AuditTailer {
    private static final int MAX_LINE = 1024 * 1024;
    private final Config config;
    private final Journal journal;

    AuditTailer(Config config, Journal journal) { this.config = config; this.journal = journal; }

    void tick() throws IOException {
        Journal.Cursor cursor = journal.cursor();
        Path source = sourceFor(cursor);
        BasicFileAttributes attrs = attributes(source);
        String key = key(attrs);
        try (RandomAccessFile input = new RandomAccessFile(source.toFile(), "r")) {
            if (cursor == null) {
                long start = config.replay ? 0 : initialOffset(input);
                journal.accept(List.of(), new Journal.Cursor(key, start, anchor(input, start)));
                cursor = journal.cursor();
            }
            long offset = cursor.offset();
            if (input.length() < offset || !anchor(input, offset).equals(cursor.anchor()))
                throw new IOException("Audit log was truncated/rewritten before its saved cursor; manual reconciliation required");

            int allowance = Math.min(config.readLimit, config.capacity - journal.size());
            if (allowance <= 0) return; // Backpressure: never advance the cursor past work we cannot persist.
            input.seek(offset);
            byte[] buffer = new byte[65536];
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            List<Operation> operations = new ArrayList<>();
            long consumed = offset, complete = offset;
            int lines = 0, read;
            outer: while ((read = input.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    consumed++;
                    if (buffer[i] == '\n') {
                        String text = line.toString(StandardCharsets.UTF_8);
                        if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
                        Operation operation = Operation.parse(text, config);
                        if (operation != null) operations.add(operation);
                        line.reset(); complete = consumed; lines++;
                        if (lines >= allowance) break outer;
                    } else {
                        line.write(buffer[i]);
                        if (line.size() > MAX_LINE) throw new IOException("Audit line exceeds 1 MiB; refusing to skip it");
                    }
                }
            }
            // If rotation raced this read, repeat it from the old, persisted cursor on the next tick.
            if (!Objects.equals(attrs.fileKey(), attributes(source).fileKey())) throw new IOException("Audit log rotated during read; retrying");
            if (complete != offset) {
                journal.accept(operations, new Journal.Cursor(key, complete, anchor(input, complete)));
            }
            if (!source.equals(config.auditLog) && complete == input.length()) {
                // Multiple rotations may have occurred during downtime. Never jump over .1, .2, ... .
                BasicFileAttributes current = attributes(nextAfter(source));
                journal.accept(List.of(), new Journal.Cursor(key(current), 0, emptyAnchor()));
            }
        }
    }

    private Path nextAfter(Path rotated) throws IOException {
        Matcher matcher = Pattern.compile(Pattern.quote(config.auditLog.getFileName().toString()) + "\\.([1-9][0-9]*)")
                .matcher(rotated.getFileName().toString());
        if (!matcher.matches()) throw new IOException("Unsupported rotation naming; expected audit filename followed by .1, .2, ...");
        int number;
        try { number = Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException e) { throw new IOException("Invalid rotation number", e); }
        return number == 1 ? config.auditLog : config.auditLog.resolveSibling(config.auditLog.getFileName() + "." + (number - 1));
    }

    private Path sourceFor(Journal.Cursor cursor) throws IOException {
        if (cursor == null) { attributes(config.auditLog); return config.auditLog; }
        if (Files.isRegularFile(config.auditLog, LinkOption.NOFOLLOW_LINKS)
                && key(attributes(config.auditLog)).equals(cursor.fileKey())) return config.auditLog;
        // A directory mount preserves access to renamed logs, including across a process restart.
        try (var siblings = Files.list(config.auditLog.getParent())) {
            for (Path candidate : siblings.limit(10000).toList()) {
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && key(attributes(candidate)).equals(cursor.fileKey())) return candidate;
            }
        }
        throw new IOException("Saved audit log inode is no longer available; retain rotated logs and reconcile before resetting state");
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.fileKey() == null) throw new IOException("Audit log must be a regular file on a filesystem exposing file identity");
        return attrs;
    }
    private static String key(BasicFileAttributes attrs) { return attrs.fileKey().toString(); }
    private static long initialOffset(RandomAccessFile input) throws IOException {
        long length = input.length();
        if (length == 0) return 0;
        long start = Math.max(0, length - MAX_LINE);
        byte[] tail = new byte[(int) (length - start)]; input.seek(start); input.readFully(tail);
        for (int i = tail.length - 1; i >= 0; i--) if (tail[i] == '\n') return start + i + 1;
        if (start != 0) throw new IOException("Initial audit log ends in a line exceeding 1 MiB");
        return 0;
    }
    private static String anchor(RandomAccessFile input, long offset) throws IOException {
        if (input.length() < offset) return "truncated";
        long start = Math.max(0, offset - 128);
        byte[] bytes = new byte[(int) (offset - start)]; input.seek(start); input.readFully(bytes);
        return digest(bytes);
    }
    private static String emptyAnchor() { return digest(new byte[0]); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
