package com.example.redis.exception;

public class MaxMemoryReachedException extends RedisException {

    public MaxMemoryReachedException() {
        super("OOM command not allowed when used memory > 'maxmemory'");
    }
}
