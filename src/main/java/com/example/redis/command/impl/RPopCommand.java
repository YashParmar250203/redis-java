package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.ListOperations;
import org.springframework.stereotype.Component;

/** RPOP key - O(1). Returns null if the list is empty or missing. */
@Component
public class RPopCommand implements Command {

    private final ListOperations listOperations;

    public RPopCommand(ListOperations listOperations) {
        this.listOperations = listOperations;
    }

    @Override
    public String name() {
        return "RPOP";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return listOperations.rpop(args[0]);
    }

    @Override
    public boolean isWrite() {
        return true;
    }
}
