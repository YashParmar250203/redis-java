package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.SetOperations;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** SREM key value [value ...] - O(1) average per value. Returns count removed. */
@Component
public class SRemCommand implements Command {

    private final SetOperations setOperations;

    public SRemCommand(SetOperations setOperations) {
        this.setOperations = setOperations;
    }

    @Override
    public String name() {
        return "SREM";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length < 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        String key = args[0];
        String[] values = Arrays.copyOfRange(args, 1, args.length);
        return setOperations.srem(key, values);
    }

    @Override
    public boolean isWrite() {
        return true;
    }
}
