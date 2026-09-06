package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.HashOperations;
import org.springframework.stereotype.Component;

/** HDEL key field - O(1) average. Returns 1 if removed, 0 otherwise. */
@Component
public class HDelCommand implements Command {

    private final HashOperations hashOperations;

    public HDelCommand(HashOperations hashOperations) {
        this.hashOperations = hashOperations;
    }

    @Override
    public String name() {
        return "HDEL";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return hashOperations.hdel(args[0], args[1]) ? 1 : 0;
    }

    @Override
    public boolean isWrite() {
        return true;
    }
}
