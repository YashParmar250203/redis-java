package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.Store;
import org.springframework.stereotype.Component;

/**
 * EXISTS key [key ...]
 * <p>
 * Returns the count of keys (from those given) that currently exist.
 * Time complexity: O(n) in the number of keys given, O(1) per key.
 */
@Component
public class ExistsCommand implements Command {

    private final Store store;

    public ExistsCommand(Store store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "EXISTS";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length < 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        int existingCount = 0;
        for (String key : args) {
            if (store.exists(key)) {
                existingCount++;
            }
        }
        return existingCount;
    }
}