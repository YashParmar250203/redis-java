package com.example.redis.exception;

public class NotAnIntegerException extends RedisException {

    public NotAnIntegerException() {
        super("ERR value is not an integer or out of range");
    }
}