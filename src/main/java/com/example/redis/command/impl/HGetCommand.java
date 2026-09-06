package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.HashOperations;
import org.springframework.stereotype.Component;

/** HGET key field - O(1) average. Returns null if the field/key is missing. */
@Component
public class HGetCommand implements Command {

    private final HashOperations hashOperations;

    public HGetCommand(HashOperations hashOperations) {
        this.hashOperations = hashOperations;
    }

    @Override
    public String name() {
        return "HGET";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return hashOperations.hget(args[0], args[1]);
    }

    @Override
    public boolean isWrite() {
        return false;
    }
}
