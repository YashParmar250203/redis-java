package com.example.redis.storage;

/**
 * The type tag carried by every StoredValue. A key holds exactly one of
 * these at a time - mixing types on the same key is a WRONGTYPE error,
 * matching real Redis.
 */
enum RedisType {
    STRING, LIST, SET, HASH, ZSET
}
