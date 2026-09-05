package com.example.redis.storage;

import java.util.List;

public interface SortedSetOperations {

    /** @return true if member is newly added, false if it already existed (score updated). O(log n). */
    boolean zadd(String key, double score, String member);

    /**
     * Inclusive range by rank in ascending score order, supports negative indices.
     * O(n) to walk to the requested slice - see SortedSetValue javadoc for why
     * this isn't O(log n) yet.
     */
    List<String> zrange(String key, int start, int stop);

    /** @return true if the member existed and was removed. O(log n). */
    boolean zrem(String key, String member);
}
