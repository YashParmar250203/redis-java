package com.example.redis.storage;

/**
 * Internal representation of a stored entry.
 *
 * @param value           the raw string value.
 * @param expireAtMillis  absolute epoch-millis timestamp at which this entry expires,
 *                        or {@code null} if the key has no TTL.
 */
record StoredValue(String value, Long expireAtMillis) {
}