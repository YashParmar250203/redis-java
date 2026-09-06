package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.ListOperations;
import org.springframework.stereotype.Component;

/** LPOP key - O(1). Returns null if the list is empty or missing. */
@Component
public class LPopCommand implements Command {

    private final ListOperations listOperations;

    public LPopCommand(ListOperations listOperations) {
        this.listOperations = listOperations;
    }

    @Override
    public String name() {
        return "LPOP";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return listOperations.lpop(args[0]);
    }

    @Override
    public boolean isWrite() {
        return true;
    }
}
