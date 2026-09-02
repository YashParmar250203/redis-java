package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.command.util.Arguments;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.Store;
import org.springframework.stereotype.Component;

/**
 * EXPIRE key seconds
 * <p>
 * Sets a TTL on an already-existing key. Returns 1 if the timeout was set,
 * 0 if the key does not exist (or had already logically expired).
 * Time complexity: O(1) average (single atomic computeIfPresent).
 */
@Component
public class ExpireCommand implements Command {

    private final Store store;

    public ExpireCommand(Store store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "EXPIRE";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        long ttlSeconds = Arguments.parsePositiveSeconds(args[1], name());
        boolean applied = store.expire(args[0], ttlSeconds);
        return applied ? 1 : 0;
    }
}