package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.command.util.Arguments;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.SortedSetOperations;
import org.springframework.stereotype.Component;

/**
 * ZRANGE key start stop - ascending score order, supports negative indices.
 * O(n) - see SortedSetValue javadoc for why this isn't O(log n) yet.
 */
@Component
public class ZRangeCommand implements Command {

    private final SortedSetOperations sortedSetOperations;

    public ZRangeCommand(SortedSetOperations sortedSetOperations) {
        this.sortedSetOperations = sortedSetOperations;
    }

    @Override
    public String name() {
        return "ZRANGE";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 3) {
            throw new WrongNumberOfArgumentsException(name());
        }
        int start = Arguments.parseInteger(args[1]);
        int stop = Arguments.parseInteger(args[2]);
        return sortedSetOperations.zrange(args[0], start, stop);
    }
}
