package com.example.redis.persistence;

import com.example.redis.command.CommandDispatcher;
import com.example.redis.command.CommandExecutor;
import com.example.redis.protocol.RespCommandEncoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Decorates the raw {@link CommandExecutor} with AOF logging, keeping
 * persistence entirely out of the command layer (per the project's
 * "separate persistence logic from the database engine" design goal - no
 * {@code Command} implementation knows this class exists).
 * <p>
 * Only active when {@code redis.persistence.mode=aof}. Appends *after*
 * successful execution, and only for commands that declare themselves a
 * write via {@link com.example.redis.command.Command#isWrite()} - matching
 * real Redis's own AOF semantics of logging effects that actually
 * happened, never failed calls or read-only operations.
 * <p>
 * {@code @Primary} so REST and TCP consumers, which depend on
 * {@link CommandDispatcher} rather than the concrete CommandExecutor,
 * transparently resolve to this decorated bean instead of the raw one when
 * both exist (AOF mode). Startup replay explicitly injects the concrete
 * {@link CommandExecutor} to bypass this decorator - otherwise every
 * restart would re-log everything it just replayed, growing the file
 * forever.
 */
@Component
@Primary
@ConditionalOnProperty(name = "redis.persistence.mode", havingValue = "aof")
public class PersistingCommandDispatcher implements CommandDispatcher {

    private final CommandExecutor rawExecutor;
    private final AofWriter aofWriter;

    public PersistingCommandDispatcher(CommandExecutor rawExecutor, AofWriter aofWriter) {
        this.rawExecutor = rawExecutor;
        this.aofWriter = aofWriter;
    }

    @Override
    public Object execute(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            // Let the raw executor throw its own InvalidCommandException,
            // preserving identical error behavior to the non-persisting path.
            return rawExecutor.execute(rawLine);
        }
        return execute(rawLine.trim().split("\\s+"));
    }

    @Override
    public Object execute(String[] tokens) {
        Object result = rawExecutor.execute(tokens); // throws before we'd ever try to persist a failed command

        if (tokens.length > 0 && rawExecutor.isWriteCommand(tokens[0])) {
            try {
                aofWriter.append(RespCommandEncoder.encode(tokens));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to append command to AOF", e);
            }
        }

        return result;
    }
}
