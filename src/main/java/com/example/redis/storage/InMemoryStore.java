package com.example.redis.storage;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory key-value store.
 * <p>
 * Backed by {@link ConcurrentHashMap} instead of a synchronized HashMap:
 * ConcurrentHashMap uses lock striping / CAS operations internally so reads
 * are effectively lock-free and writes only contend when they land on the
 * same bucket, rather than serializing every single access behind one lock.
 * <p>
 * All operations here are O(1) average case (standard hash table complexity).
 * This class intentionally has no knowledge of commands, TTL, or the
 * protocol layer - it is a pure storage primitive.
 */
@Component
public class InMemoryStore implements Store {

    private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();

    @Override
    public void set(String key, String value) {
        data.put(key, value);
    }

    @Override
    public String get(String key) {
        return data.get(key);
    }

    @Override
    public boolean delete(String key) {
        return data.remove(key) != null;
    }

    @Override
    public boolean exists(String key) {
        return data.containsKey(key);
    }

    @Override
    public int size() {
        return data.size();
    }
}