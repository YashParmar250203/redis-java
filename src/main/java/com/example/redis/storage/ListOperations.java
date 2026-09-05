package com.example.redis.storage;

import java.util.List;

/**
 * List-specific operations, split out from Store the same way Spring Data
 * Redis separates ValueOperations/ListOperations/etc, so a command that only
 * needs list behaviour only depends on this interface.
 */
public interface ListOperations {

    /** @return the length of the list after the push. O(1) per value pushed. */
    long lpush(String key, String... values);

    /** @return the length of the list after the push. O(1) per value pushed. */
    long rpush(String key, String... values);

    /** @return the removed element, or null if the list is empty/missing. O(1). */
    String lpop(String key);

    /** @return the removed element, or null if the list is empty/missing. O(1). */
    String rpop(String key);

    /**
     * Inclusive range, supports negative indices (-1 = last element).
     * O(n) to snapshot the underlying deque plus O(k) to slice it.
     */
    List<String> lrange(String key, int start, int stop);
}
