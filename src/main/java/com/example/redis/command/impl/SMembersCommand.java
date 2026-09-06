package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.SetOperations;
import org.springframework.stereotype.Component;

/** SMEMBERS key - O(n). Returns empty set if missing. */
@Component
public class SMembersCommand implements Command {

    private final SetOperations setOperations;

    public SMembersCommand(SetOperations setOperations) {
        this.setOperations = setOperations;
    }

    @Override
    public String name() {
        return "SMEMBERS";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 1) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return setOperations.smembers(args[0]);
    }

    @Override
    public boolean isWrite() {
        return false;
    }
}
