package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.SetOperations;
import org.springframework.stereotype.Component;

/** SISMEMBER key value - O(1) average. Returns 1 or 0. */
@Component
public class SIsMemberCommand implements Command {

    private final SetOperations setOperations;

    public SIsMemberCommand(SetOperations setOperations) {
        this.setOperations = setOperations;
    }

    @Override
    public String name() {
        return "SISMEMBER";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return setOperations.sismember(args[0], args[1]) ? 1 : 0;
    }

    @Override
    public boolean isWrite() {
        return false;
    }
}
