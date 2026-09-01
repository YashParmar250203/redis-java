package com.example.redis.exception;

public class InvalidCommandException extends RedisException {

    public InvalidCommandException(String reason) {
        super("ERR " + reason);
    }
}