package com.example.redis.storage;

import java.util.Set;

public interface SetOperations {

    /** @return count of values newly added (not already members). O(1) average per value. */
    long sadd(String key, String... values);

    /** @return count of values actually removed. O(1) average per value. */
    long srem(String key, String... values);

    /** O(1) average. */
    boolean sismember(String key, String value);

    /** @return a snapshot copy of all members. O(n). */
    Set<String> smembers(String key);
}
