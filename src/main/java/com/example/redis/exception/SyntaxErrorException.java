package com.example.redis.exception;

public class SyntaxErrorException extends RedisException {

    public SyntaxErrorException() {
        super("ERR syntax error");
    }
}