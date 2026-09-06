package com.example.redis.storage;

import java.io.Serializable;

/**
 * Internal representation of a stored entry.
 *
 * @param value          the payload: a String for STRING, a
 *                        java.util.concurrent.ConcurrentLinkedDeque<String> for LIST,
 *                        a Set<String> for SET, a Map<String,String> for HASH,
 *                        or a SortedSetValue for ZSET.
 * @param expireAtMillis absolute epoch-millis timestamp at which this entry expires,
 *                        or {@code null} if the key has no TTL.
 * @param type           which Redis data type this entry currently holds.
 */
record StoredValue(Object value, Long expireAtMillis, RedisType type) implements Serializable {
}
