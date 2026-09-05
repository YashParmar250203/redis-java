package com.example.redis.command;

import com.example.redis.exception.InvalidCommandException;
import com.example.redis.exception.UnknownCommandException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses a command and dispatches it to the matching {@link Command}.
 * <p>
 * This is the single chokepoint between "input in" and "command executed" -
 * both the REST controller (Phase 1) and the TCP/RESP server (Phase 4) call
 * into this class, so dispatch logic and command registration only exist
 * once. The REST path uses {@link #execute(String)} (whitespace-tokenized,
 * for convenience/backwards compatibility); the RESP path uses
 * {@link #execute(String[])} directly with tokens whose boundaries were
 * already determined by the wire protocol - which is what lets RESP-encoded
 * values contain spaces, unlike the whitespace-split path.
 */
@Component
public class CommandExecutor {

    private final Map<String, Command> commandsByName;

    public CommandExecutor(List<Command> commands) {
        this.commandsByName = commands.stream()
                .collect(Collectors.toMap(c -> c.name().toUpperCase(), c -> c));
    }

    /**
     * Parses a raw whitespace-separated command line and dispatches it.
     * Values containing spaces are not supported here - see {@link #execute(String[])}.
     */
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

    private String[] tokenize(String rawLine) {
        return rawLine.trim().split("\\s+");
    }
}
