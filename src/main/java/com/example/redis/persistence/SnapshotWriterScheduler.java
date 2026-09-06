package com.example.redis.persistence;

import com.example.redis.storage.Snapshottable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Periodically writes the entire keyspace to a single snapshot file
 * (RDB-style), as an alternative persistence strategy to AOF's per-command
 * logging. Only active when {@code redis.persistence.mode=snapshot}.
 * <p>
 * Written atomically: the snapshot goes to a temporary file first, then
 * gets moved into place with {@link StandardCopyOption#ATOMIC_MOVE} - the
 * same trick real Redis's RDB save uses, so a crash mid-write never leaves
 * a half-written, corrupt file at the real path. Readers only ever see
 * either the previous complete snapshot or the new complete one, never
 * something in between.
 * <p>
 * <b>Trade-off vs AOF</b> - the classic interview question this phase sets
 * up: a snapshot's data-loss window on crash is "however long since the
 * last snapshot ran" (minutes, typically), versus AOF's "at most the fsync
 * interval" (up to ~1 second on EVERYSEC). In exchange, snapshotting has
 * effectively zero per-command overhead - nothing happens on the write path
 * at all between snapshots - while AOF logs every single write.
 * <p>
 * Real Redis's combined AOF+RDB mode gets both benefits at once (RDB
 * baseline, AOF rewrite/compaction layered on top), but that requires
 * coordinating AOF rewrite timing with snapshot timing - genuinely
 * nontrivial, and deliberately out of scope here. See
 * {@link PersistenceRecoveryRunner}: this project treats AOF and snapshot
 * as mutually exclusive, switchable strategies rather than attempting that
 * coordination.
 */
@Component
@ConditionalOnProperty(name = "redis.persistence.mode", havingValue = "snapshot")
public class SnapshotWriterScheduler {

    private final Snapshottable snapshottable;
    private final Path snapshotPath;

    public SnapshotWriterScheduler(Snapshottable snapshottable,
                                    @Value("${redis.snapshot.path:data/dump.snapshot}") String snapshotPath) {
        this.snapshottable = snapshottable;
        this.snapshotPath = Paths.get(snapshotPath);
    }

    @Scheduled(fixedRateString = "${redis.snapshot.interval-ms:300000}")
    public void saveSnapshot() throws IOException {
        Path parent = snapshotPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = Files.createTempFile(parent, "snapshot-", ".tmp");
        try {
            Files.write(tempFile, snapshottable.createSnapshot());
            Files.move(tempFile, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }
}
