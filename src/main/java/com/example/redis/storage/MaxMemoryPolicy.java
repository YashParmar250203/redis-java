package com.example.redis.storage;

/**
 * Mirrors real Redis's own maxmemory-policy naming (minus the volatile-*
 * variants, which only evict among keys that have a TTL set - a natural
 * extension left out to keep scope contained: it would sample from
 * {@code keysWithExpiry} instead of the whole keyspace, reusing the exact
 * same sampling mechanism).
 */
enum MaxMemoryPolicy {
    NOEVICTION, ALLKEYS_LRU, ALLKEYS_LFU;

    static MaxMemoryPolicy fromConfig(String raw) {
        return valueOf(raw.trim().toUpperCase().replace('-', '_'));
    }
}
