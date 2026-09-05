package com.example.redis.command;

/**
 * A single Redis command (SET, GET, DEL, EXISTS, ...).
 * <p>
 * Deliberately transport-agnostic: a Command only knows about its own name
 * and already-tokenized arguments. It has no idea whether it was invoked via
 * REST (Phase 1) or a raw TCP/RESP connection (Phase 4) - that decoupling is
 * the whole point of this abstraction.
 * <p>
 * Return type is {@code Object} because Redis replies are heterogeneous:
 * simple strings ("OK"), integers (EXISTS/DEL counts), bulk strings (GET),
 * or nil (missing key on GET). Keeping this loose now avoids a redesign when
 * RESP-typed replies are introduced later.
 */
public interface Command {

    /**
     * @return the command name, e.g. "SET". Matched case-insensitively by the executor.
     */
    String name();

    /**
     * @param args command arguments, NOT including the command name itself.
     * @return the result of executing the command.
     * @throws com.example.redis.exception.RedisException if arguments are invalid.
     */
    Object execute(String[] args);
}
