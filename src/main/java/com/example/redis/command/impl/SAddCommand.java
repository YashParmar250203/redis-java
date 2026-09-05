package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.SetOperations;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** SADD key value [value ...] - O(1) average per value. Returns count newly added. */
@Component
public class SAddCommand implements Command {

    private final SetOperations setOperations;

    public SAddCommand(SetOperations setOperations) {
        this.setOperations = setOperations;
    }

    @Override
    public String name() {
        return "SADD";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length < 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        String key = args[0];
        String[] values = Arrays.copyOfRange(args, 1, args.length);
        return setOperations.sadd(key, values);
    }
}
