package com.example.redis.exception;

public class ProtocolException extends RedisException {

    public ProtocolException(String reason) {
        super("ERR Protocol error: " + reason);
    }
}
