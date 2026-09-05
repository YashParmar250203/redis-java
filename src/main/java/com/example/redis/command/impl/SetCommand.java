package com.example.redis.command.impl;

import com.example.redis.command.Command;
import com.example.redis.command.util.Arguments;
import com.example.redis.exception.SyntaxErrorException;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.model.SimpleStringReply;
import com.example.redis.storage.Store;
import org.springframework.stereotype.Component;

/**
 * SET key value
 * SET key value EX seconds
 * <p>
 * A plain SET clears any existing TTL on the key (matches real Redis).
 * Time complexity: O(1) average (single ConcurrentHashMap.put).
 */
@Component
public class SetCommand implements Command {

    private static final String EX_OPTION = "EX";

    private final Store store;

    public SetCommand(Store store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "SET";
    }

    @Override
    public Object execute(String[] args) {
        if (args.length == 2) {
            store.set(args[0], args[1]);
            return new SimpleStringReply("OK");
        }

        if (args.length == 4 && EX_OPTION.equalsIgnoreCase(args[2])) {
            long ttlSeconds = Arguments.parsePositiveSeconds(args[3], name());
            store.set(args[0], args[1], ttlSeconds);
            return new SimpleStringReply("OK");
        }

        if (args.length < 2) {
            throw new WrongNumberOfArgumentsException(name());
        }
        throw new SyntaxErrorException();
    }
}
