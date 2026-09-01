package com.example.redis.exception;

public class WrongNumberOfArgumentsException extends RedisException {

    public WrongNumberOfArgumentsException(String commandName) {
        super("ERR wrong number of arguments for '" + commandName.toLowerCase() + "' command");
    }
}