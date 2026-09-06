package com.example.redis.command;

/**
 * Abstraction over "given a command, run it and give me the result."
 * <p>
 * {@link CommandExecutor} is the raw implementation with no awareness of
 * persistence. Phase 5 adds a decorator around this interface
 * (persistence.PersistingCommandDispatcher) that appends successful writes
 * to the AOF before returning - REST and TCP consumers depend on this
 * interface rather than the concrete CommandExecutor, so they transparently
 * get the decorated version when AOF persistence is enabled, with zero
 * changes to the consumers themselves. Startup replay explicitly depends on
 * the concrete {@link CommandExecutor} instead, to avoid re-logging
 * commands it's replaying.
 */
public interface CommandDispatcher {

    Object execute(String rawLine);

    Object execute(String[] tokens);
}
