package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record Operation(Type type, Path path, Path newPath) {
    enum Type { UPDATE, DELETE, RENAME }
    private static final Pattern PREFIX = Pattern.compile("(?:^|\\s)smbd_audit(?:\\[\\d+\\])?:\\s*");

    static Operation parse(String line, Config config) {
        if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        Matcher m = PREFIX.matcher(line);
        if (!m.find()) return null;
        String[] f = line.substring(m.end()).split("\\|", -1);
        if (f.length < 6 || !f[4].equalsIgnoreCase("ok")) return null;
        String op = f[3].toLowerCase(Locale.ROOT);
        boolean rename = op.equals("renameat");
        if (f.length != (rename ? 7 : 6)) {
            Main.log("Rejected ambiguous audit record (unexpected field count)");
            return null;
        }
        Path source = parsePath(f[5], config.root);
        if (source == null) return null;
        if (rename) {
            Path target = parsePath(f[6], config.root);
            if (target == null || temporary(target)) return null;
            // Office saves commonly rename an ignored temporary file into a real document.
            if (temporary(source)) return new Operation(Type.UPDATE, target, null);
            return new Operation(Type.RENAME, source, target);
        }
        if (temporary(source)) return null;
        return switch (op) {
            case "mkdirat", "pwrite_send", "pwrite_recv" -> new Operation(Type.UPDATE, source, null);
            case "unlinkat" -> new Operation(Type.DELETE, source, null);
            default -> null;
        };
    }

    static Path parsePath(String raw, Path root) {
        try {
            if (raw.isEmpty() || raw.chars().anyMatch(c -> Character.isISOControl(c))) return null;
            Path path = Path.of(raw);
            if (!path.isAbsolute()) return null;
            path = path.normalize();
            return path.startsWith(root) && !path.equals(root) ? path : null;
        } catch (InvalidPathException e) { return null; }
    }

    static void requireSafe(Path path, Path root) throws IOException {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path)
                || !path.startsWith(root) || path.equals(root)) throw new IOException("Unsafe path: " + path);
        Path current = root;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) throw new IOException("Source root is unavailable");
        for (Path component : root.relativize(path)) {
            current = current.resolve(component);
            // A final symlink is copied as a symlink, never followed; parents must be real directories.
            if (!current.equals(path) && Files.isSymbolicLink(current))
                throw new IOException("Symlink in source parent: " + current);
        }
    }

    private static boolean temporary(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".tmp") || name.startsWith("~$") || name.equals(".filesync-partial");
    }
}
