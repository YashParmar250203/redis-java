package com.example.redis.command;

import com.example.redis.exception.InvalidCommandException;
import com.example.redis.exception.UnknownCommandException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses a raw command line and dispatches it to the matching {@link Command}.
 * <p>
 * This is the single chokepoint between "text in" and "command executed" -
 * both the REST controller (Phase 1) and the future TCP/RESP server
 * (Phase 4) will call {@link #execute(String)}, so dispatch logic and
 * command registration only need to exist once.
 * <p>
 * Tokenization is currently naive (split on whitespace). It does not yet
 * support quoted values containing spaces - that is a known limitation to
 * be resolved when RESP parsing (which encodes argument boundaries
 * explicitly rather than relying on whitespace) is introduced in Phase 4.
 */
@Component
public class CommandExecutor {

    private final Map<String, Command> commandsByName;

    public CommandExecutor(List<Command> commands) {
        this.commandsByName = commands.stream()
                .collect(Collectors.toMap(c -> c.name().toUpperCase(), c -> c));
    }

    public Object execute(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            throw new InvalidCommandException("empty command");
        }

        String[] tokens = tokenize(rawLine);
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