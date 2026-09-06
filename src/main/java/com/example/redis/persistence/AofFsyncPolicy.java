package com.example.redis.persistence;

/**
 * Mirrors real Redis's own three AOF fsync policies and the exact
 * durability/performance trade-off each represents:
 * <ul>
 *     <li>{@link #ALWAYS} - force to physical disk after every append.
 *     Survives a full power loss with zero data loss; costs a disk sync on
 *     every single write.</li>
 *     <li>{@link #EVERYSEC} - a background thread forces once per second
 *     regardless of write volume. Bounds crash data loss to ~1 second, at
 *     negligible per-write cost - real Redis's own recommended default.</li>
 *     <li>{@link #NO} - never force explicitly; the OS decides when dirty
 *     pages reach disk. Survives our own process crashing (bytes are
 *     already handed to the kernel), but not an OS crash or power loss
 *     before the kernel flushes them.</li>
 * </ul>
 */
public enum AofFsyncPolicy {
    ALWAYS, EVERYSEC, NO;

    public static AofFsyncPolicy fromConfig(String raw) {
        return valueOf(raw.trim().toUpperCase());
    }
}
