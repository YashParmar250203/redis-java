package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.Store;
import org.springframework.stereotype.Component;

/**
 * SET key value
 * <p>
 * Time complexity: O(1) average (single ConcurrentHashMap.put).
 */
@Component
public class SetCommand implements Command {

    private final Store store;

    public SetCommand(Store store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "SET";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        store.set(args[0], args[1]);
        return "OK";
    }
}