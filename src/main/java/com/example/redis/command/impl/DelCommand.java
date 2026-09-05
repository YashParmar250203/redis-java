package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.Store;
import org.springframework.stereotype.Component;

/**
 * DEL key [key ...]
 * <p>
 * Matches real Redis behaviour of accepting multiple keys in one call.
 * Returns the number of keys that actually existed and were removed.
 * Time complexity: O(n) in the number of keys given, O(1) per key.
 */
@Component
public class DelCommand implements Command {

    private final Store store;

    public DelCommand(Store store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "DEL";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length < 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        int deletedCount = 0;
        for (String key : args) {
            if (store.delete(key)) {
                deletedCount++;
            }
        }
        return deletedCount;
    }
}
