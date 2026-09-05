package com.example.redis.storage;

import java.util.Map;

public interface HashOperations {

    /** @return true if this field is new, false if an existing field's value was overwritten. O(1) average. */
    boolean hset(String key, String field, String value);

    /** O(1) average. */
    String hget(String key, String field);

    /** @return true if the field existed and was removed. O(1) average. */
    boolean hdel(String key, String field);

    /** @return a snapshot copy of all field-value pairs. O(n) in field count. */
    Map<String, String> hgetall(String key);
}
