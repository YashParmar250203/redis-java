package com.example.redis.storage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory key-value store with TTL support.
 * <p>
 * Backed by {@link ConcurrentHashMap} instead of a synchronized HashMap:
 * ConcurrentHashMap uses lock striping / CAS operations internally so reads
 * are effectively lock-free and writes only contend when they land on the
 * same bucket, rather than serializing every single access behind one lock.
 * <p>
 * TTL strategy is a hybrid of lazy + active expiration, matching real Redis:
 * <ul>
 *     <li><b>Lazy:</b> every read path (get/exists/ttl/delete) checks the
 *     expiry timestamp and reaps the key on the way out if it's stale. This
 *     guarantees correctness immediately but, on its own, leaks memory for
 *     keys that expire and are never read again.</li>
 *     <li><b>Active:</b> {@link #runActiveExpirationCycle()} is invoked
 *     periodically (see {@code ttl.ExpirationScheduler}) and samples a
 *     bounded number of keys drawn only from {@link #keysWithExpiry} - not
 *     the whole keyspace - removing any that are expired. If a large
 *     fraction of the sample was expired, it samples again immediately,
 *     on the theory that expired keys tend to cluster in time.</li>
 * </ul>
 * All single-key operations remain O(1) average case.
 */
@Component
public class InMemoryStore implements Store {

    private static final int ACTIVE_EXPIRATION_SAMPLE_SIZE = 20;
    private static final int ACTIVE_EXPIRATION_MAX_ROUNDS = 5;
    private static final double ACTIVE_EXPIRATION_REPEAT_THRESHOLD = 0.25;

    private final ConcurrentHashMap<String, StoredValue> data = new ConcurrentHashMap<>();

    /**
     * Index of keys that currently carry a TTL. Exists purely so active
     * expiration can sample O(keys-with-ttl) instead of scanning every key
     * in the store on every sweep, most of which may have no TTL at all.
     */
    private final Set<String> keysWithExpiry = ConcurrentHashMap.newKeySet();

    @Override
    public void set(String key, String value) {
        data.put(key, new StoredValue(value, null));
        keysWithExpiry.remove(key);
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        data.put(key, new StoredValue(value, expireAt));
        keysWithExpiry.add(key);
    }

    @Override
    public String get(String key) {
        StoredValue current = data.get(key);
        if (current == null) {
            return null;
        }
        if (isExpired(current)) {
            reap(key, current);
            return null;
        }
        return current.value();
    }

    @Override
    public boolean delete(String key) {
        StoredValue removed = data.remove(key);
        keysWithExpiry.remove(key);
        // A logically-expired-but-not-yet-reaped key should not count as a
        // successful delete, matching real Redis: DEL on an expired key returns 0.
        return removed != null && !isExpired(removed);
    }

    @Override
    public boolean exists(String key) {
        StoredValue current = data.get(key);
        if (current == null) {
            return false;
        }
        if (isExpired(current)) {
            reap(key, current);
            return false;
        }
        return true;
    }

    @Override
    public boolean expire(String key, long ttlSeconds) {
        long newExpireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        // computeIfPresent is atomic: reads and replaces (or removes, if it
        // discovers the key is already logically expired) in one step, closing
        // the race window a plain get()-then-put() would have.
        StoredValue updated = data.computeIfPresent(key,
                (k, existing) -> isExpired(existing) ? null : new StoredValue(existing.value(), newExpireAt));

        if (updated == null) {
            keysWithExpiry.remove(key);
            return false;
        }
        keysWithExpiry.add(key);
        return true;
    }

    @Override
    public long ttl(String key) {
        StoredValue current = data.get(key);
        if (current == null) {
            return -2;
        }
        if (isExpired(current)) {
            reap(key, current);
            return -2;
        }
        if (current.expireAtMillis() == null) {
            return -1;
        }
        long remainingMillis = current.expireAtMillis() - System.currentTimeMillis();
        // Ceiling division so a key with 999ms left reports 1 second, not 0.
        return Math.max(0, (remainingMillis + 999) / 1000);
    }

    @Override
    public int size() {
        return data.size();
    }

    @Override
    public int runActiveExpirationCycle() {
        int totalRemoved = 0;

        for (int round = 0; round < ACTIVE_EXPIRATION_MAX_ROUNDS; round++) {
            List<String> snapshot = new ArrayList<>(keysWithExpiry);
            if (snapshot.isEmpty()) {
                break;
            }
            Collections.shuffle(snapshot);

            int sampleSize = Math.min(ACTIVE_EXPIRATION_SAMPLE_SIZE, snapshot.size());
            int expiredThisRound = 0;

            for (int i = 0; i < sampleSize; i++) {
                String key = snapshot.get(i);
                StoredValue current = data.get(key);
                if (current == null) {
                    // Index entry outlived the data entry (e.g. deleted directly) - clean it up.
                    keysWithExpiry.remove(key);
                    continue;
                }
                if (isExpired(current)) {
                    reap(key, current);
                    expiredThisRound++;
                    totalRemoved++;
                }
            }

            if (expiredThisRound < sampleSize * ACTIVE_EXPIRATION_REPEAT_THRESHOLD) {
                break;
            }
        }

        return totalRemoved;
    }

    private void reap(String key, StoredValue expectedValue) {
        data.remove(key, expectedValue);
        keysWithExpiry.remove(key);
    }

    private boolean isExpired(StoredValue value) {
        return value.expireAtMillis() != null && value.expireAtMillis() <= System.currentTimeMillis();
    }
}