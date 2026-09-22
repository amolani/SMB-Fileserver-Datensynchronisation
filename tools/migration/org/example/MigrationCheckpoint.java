package org.example;

import java.io.IOException;
import java.nio.file.Path;

/** One-shot checkpoint preparation using the deployed application's own journal and tailer. */
public final class MigrationCheckpoint {
    private MigrationCheckpoint() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MigrationCheckpoint CONFIG_FILE");
        initialize(Config.load(Path.of(args[0])));
    }

    static void initialize(Config config) throws IOException {
        config.validateFilesystem();
        if (config.replay) throw new IllegalArgumentException("Migration checkpoint requires replay.existing.log.on.startup=false");
        try (Journal journal = new Journal(config)) {
            if (journal.cursor() != null || journal.size() != 0)
                throw new IOException("State is already initialized; refusing to reset its cursor or pending work");
            // No transport or worker is constructed. A racing append is either left in the audit log
            // or persisted as queued work by tick(); neither is delivered by this one-shot tool.
            new AuditTailer(config, journal).tick();
            Main.log("Migration checkpoint saved; audit.offset=" + journal.cursor().offset()
                    + " queued=" + journal.size() + "; no transfer attempted");
        }
    }
}
