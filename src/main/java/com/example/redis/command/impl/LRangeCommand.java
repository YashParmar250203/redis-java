package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.command.util.Arguments;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.ListOperations;
import org.springframework.stereotype.Component;

/** LRANGE key start stop - O(n) to snapshot the list plus O(k) to slice. */
@Component
public class LRangeCommand implements Command {

    private final ListOperations listOperations;

    public LRangeCommand(ListOperations listOperations) {
        this.listOperations = listOperations;
    }

    @Override
    public String name() {
        return "LRANGE";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 3) {
            throw new WrongNumberOfArgumentsException(name());
        }
        int start = Arguments.parseInteger(args[1]);
        int stop = Arguments.parseInteger(args[2]);
        return listOperations.lrange(args[0], start, stop);
    }
}
