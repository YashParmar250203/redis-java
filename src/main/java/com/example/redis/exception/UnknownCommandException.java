package com.example.redis.exception;

public class UnknownCommandException extends RedisException {

    public UnknownCommandException(String commandName) {
        super("ERR unknown command '" + commandName + "'");
    }
}