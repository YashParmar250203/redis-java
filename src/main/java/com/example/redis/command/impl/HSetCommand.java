package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.HashOperations;
import org.springframework.stereotype.Component;

/** HSET key field value - O(1) average. Returns 1 if the field is new, 0 if overwritten. */
@Component
public class HSetCommand implements Command {

    private final HashOperations hashOperations;

    public HSetCommand(HashOperations hashOperations) {
        this.hashOperations = hashOperations;
    }

    @Override
    public String name() {
        return "HSET";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 3) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return hashOperations.hset(args[0], args[1], args[2]) ? 1 : 0;
    }

    @Override
    public boolean isWrite() {
        return true;
    }
}
