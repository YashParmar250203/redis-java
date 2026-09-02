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
     * Clears any existing TTL on the key, matching real Redis SET semantics.
     */
    void set(String key, String value);

    /**
     * Associates the given value with the given key with an expiry.
     *
     * @param ttlSeconds seconds from now until the key expires. Must be positive.
     */
    void set(String key, String value, long ttlSeconds);

    /**
     * @return the value associated with the key, or {@code null} if the key does not
     * exist or has logically expired (lazy expiration happens here).
     */
    String get(String key);

    /**
     * Removes the key if present and not logically expired.
     *
     * @return true if the key existed (and was not already expired) and was removed.
     */
    boolean delete(String key);

    /**
     * @return true if the key exists and has not expired.
     */
    boolean exists(String key);

    /**
     * Sets a TTL on an already-existing key.
     *
     * @return true if the key existed (and was not already expired) and the TTL was applied,
     * false otherwise.
     */
    boolean expire(String key, long ttlSeconds);

    /**
     * @return remaining seconds until expiry, {@code -1} if the key exists but has no TTL,
     * or {@code -2} if the key does not exist (or has already logically expired).
     */
    long ttl(String key);

    /**
     * @return the number of keys currently stored. Useful for tests and metrics later.
     */
    int size();

    /**
     * Runs one active-expiration sweep: samples a bounded number of keys that carry a TTL
     * and reaps any that have expired, independent of whether anyone reads them.
     *
     * @return the number of keys actually removed by this sweep.
     */
    int runActiveExpirationCycle();
}   