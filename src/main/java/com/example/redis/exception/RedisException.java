package com.example.redis.exception;

/**
 * Base type for all errors that occur while parsing or executing a command.
 * <p>
 * Message format intentionally mirrors real Redis error replies (e.g.
 * "ERR wrong number of arguments for 'set' command") so that when RESP
 * error replies (-ERR ...\r\n) are added in a later phase, no message
 * strings need to change - only how they're framed on the wire.
 */
public class RedisException extends RuntimeException {

    public RedisException(String message) {
        super(message);
    }
}