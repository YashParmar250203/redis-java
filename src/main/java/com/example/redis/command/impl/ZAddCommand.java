package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.command.util.Arguments;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.SortedSetOperations;
import org.springframework.stereotype.Component;

/** ZADD key score value - O(log n). Returns 1 if newly added, 0 if the score was updated. */
@Component
public class ZAddCommand implements Command {

    private final SortedSetOperations sortedSetOperations;

    public ZAddCommand(SortedSetOperations sortedSetOperations) {
        this.sortedSetOperations = sortedSetOperations;
    }

    @Override
    public String name() {
        return "ZADD";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length != 3) {
            throw new WrongNumberOfArgumentsException(name());
        }
        double score = Arguments.parseDouble(args[1]);
        return sortedSetOperations.zadd(args[0], score, args[2]) ? 1 : 0;
    }
}
