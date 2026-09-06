package com.example.redis.command;

import com.example.redis.exception.InvalidCommandException;
import com.example.redis.exception.UnknownCommandException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The raw command dispatcher: parses a command and runs it, with no
 * awareness of persistence, TTL scheduling, or anything else - just
 * "input in, Command found and executed, result out."
 * <p>
 * This is the single chokepoint between "input in" and "command executed" -
 * REST, TCP, and AOF replay all funnel through here (REST/TCP via the
 * {@link CommandDispatcher} interface, usually through the AOF-logging
 * decorator; replay via this concrete class directly, bypassing that
 * decorator so replayed commands aren't re-logged).
 */
@Component
public class CommandExecutor implements CommandDispatcher {

    private final Map<String, Command> commandsByName;

    public CommandExecutor(List<Command> commands) {
        this.commandsByName = commands.stream()
                .collect(Collectors.toMap(c -> c.name().toUpperCase(), c -> c));
    }

    /**
     * Parses a raw whitespace-separated command line and dispatches it.
     * Values containing spaces are not supported here - see {@link #execute(String[])}.
     */
    @Override
    public Object execute(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            throw new InvalidCommandException("empty command");
        }
        return execute(tokenize(rawLine));
    }

    /**
     * Dispatches an already-tokenized command. Used by the RESP path, where
     * the protocol itself delimits argument boundaries explicitly.
     */
    @Override
    public Object execute(String[] tokens) {
        if (tokens.length == 0) {
            throw new InvalidCommandException("empty command");
        }

        String commandName = tokens[0].toUpperCase();
        Command command = commandsByName.get(commandName);
        if (command == null) {
            throw new UnknownCommandException(tokens[0]);
        }

        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        return command.execute(args);
    }

    /**
     * @return true if {@code commandName} is a registered write command.
     * Used by the AOF-logging decorator, after a successful execute(), to
     * decide whether the command needs to be persisted.
     */
    public boolean isWriteCommand(String commandName) {
        Command command = commandsByName.get(commandName.toUpperCase());
        return command != null && command.isWrite();
    }

    private String[] tokenize(String rawLine) {
        return rawLine.trim().split("\\s+");
    }
}
