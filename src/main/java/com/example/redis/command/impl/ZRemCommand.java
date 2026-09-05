package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.SortedSetOperations;
import org.springframework.stereotype.Component;

/** ZREM key value - O(log n). Returns 1 if removed, 0 otherwise. */
@Component
public class ZRemCommand implements Command {

    private final SortedSetOperations sortedSetOperations;

    public ZRemCommand(SortedSetOperations sortedSetOperations) {
        this.sortedSetOperations = sortedSetOperations;
    }

    @Override
    public String name() {
        return "ZREM";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        return sortedSetOperations.zrem(args[0], args[1]) ? 1 : 0;
    }
}
