package com.example.redis.storage;

/**
 * Abstraction over the underlying key-value storage engine.
 * <p>
 * Kept as an interface (rather than exposing InMemoryStore directly) so that
 * later phases (persistence-backed store, sharded store, etc.) can be swapped
 * in without touching command implementations.
 */
public interface Store {

    /**
     * Associates the given value with the given key, overwriting any existing value.
     */
    void set(String key, String value);

    /**
     * @return the value associated with the key, or {@code null} if the key does not exist.
     */
    String get(String key);

    /**
     * Removes the key if present.
     *
     * @return true if the key existed and was removed, false otherwise.
     */
    boolean delete(String key);

    /**
     * @return true if the key exists in the store.
     */
    boolean exists(String key);

    /**
     * @return the number of keys currently stored. Useful for tests and metrics later.
     */
    int size();
}