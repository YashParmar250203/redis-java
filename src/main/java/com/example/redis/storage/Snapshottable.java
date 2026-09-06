package com.example.redis.storage;

import java.io.IOException;

/**
 * A storage engine that can dump its entire state to bytes and restore from
 * those bytes later. Kept independent of {@link Store} (interface
 * segregation again) since not every possible storage backend would
 * necessarily need to support this - and, deliberately, this interface only
 * deals in opaque byte arrays. It knows nothing about files, scheduling, or
 * where the bytes end up; that's {@code persistence.SnapshotWriterScheduler}'s
 * job. Keeping "what state looks like" (here) separate from "when and where
 * we persist it" (persistence package) is the same separation of concerns
 * driving the AOF design in this phase.
 */
public interface Snapshottable {

    byte[] createSnapshot() throws IOException;

    void restoreSnapshot(byte[] snapshotData) throws IOException;
}
