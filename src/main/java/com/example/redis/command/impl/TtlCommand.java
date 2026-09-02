package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.Store;
import org.springframework.stereotype.Component;

/**
 * TTL key
 * <p>
 * Returns remaining seconds until expiry, -1 if the key has no TTL,
 * or -2 if the key does not exist. Time complexity: O(1) average.
 */
@Component
public class TtlCommand implements Command {

    private final Store store;

    public TtlCommand(Store store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "TTL";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return store.ttl(args[0]);
    }
}