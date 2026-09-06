package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.HashOperations;
import org.springframework.stereotype.Component;

/** HGETALL key - O(n) in field count. Returns an empty map if missing. */
@Component
public class HGetAllCommand implements Command {

    private final HashOperations hashOperations;

    public HGetAllCommand(HashOperations hashOperations) {
        this.hashOperations = hashOperations;
    }

    @Override
    public String name() {
        return "HGETALL";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return hashOperations.hgetall(args[0]);
    }

    @Override
    public boolean isWrite() {
        return false;
    }
}
