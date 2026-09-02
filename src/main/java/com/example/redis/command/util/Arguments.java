package com.example.redis.command.util;

import com.example.redis.exception.InvalidExpireTimeException;
import com.example.redis.exception.NotAnIntegerException;

public final class Arguments {

    private Arguments() {
    }

    /**
     * Parses a raw argument as a positive number of seconds, throwing the same
     * Redis-style errors real Redis would for a bad TTL value.
     */
    public static long parsePositiveSeconds(String raw, String commandName) {
        long seconds;
        try {
            seconds = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new NotAnIntegerException();
        }
        if (seconds <= 0) {
            throw new InvalidExpireTimeException(commandName);
        }
        return seconds;
    }
}