package com.example.redis.exception;

public class InvalidExpireTimeException extends RedisException {

    public InvalidExpireTimeException(String commandName) {
        super("ERR invalid expire time in '" + commandName.toLowerCase() + "' command");
    }
}