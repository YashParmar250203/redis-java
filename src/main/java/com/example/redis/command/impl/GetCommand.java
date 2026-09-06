package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.Store;
import org.springframework.stereotype.Component;

/**
 * GET key
 * <p>
 * Returns null (rendered as JSON null / Redis "nil") if the key does not exist.
 * Time complexity: O(1) average.
 */
@Component
public class GetCommand implements Command {

    private final Store store;

    public GetCommand(Store store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "GET";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return store.get(args[0]);
    }

    @Override
    public boolean isWrite() {
        return false;
    }
}
