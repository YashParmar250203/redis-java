package com.example.redis.exception;

public class NotAFloatException extends RedisException {

    public NotAFloatException() {
        super("ERR value is not a valid float");
    }
}
