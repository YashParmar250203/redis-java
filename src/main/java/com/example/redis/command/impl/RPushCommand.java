package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.ListOperations;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** RPUSH key value [value ...] - O(1) per value. */
@Component
public class RPushCommand implements Command {

    private final ListOperations listOperations;

    public RPushCommand(ListOperations listOperations) {
        this.listOperations = listOperations;
    }

    @Override
    public String name() {
        return "RPUSH";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length < 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        String key = args[0];
        String[] values = Arrays.copyOfRange(args, 1, args.length);
        return listOperations.rpush(key, values);
    }

    @Override
    public boolean isWrite() {
        return true;
    }
}
