package com.example.redis.controller;

import com.example.redis.command.CommandDispatcher;
import com.example.redis.model.CommandResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for Phase 1 testing.
 * <p>
 * Accepts a raw Redis-style command line as plain text, e.g. "SET name Yash",
 * and returns the result as JSON. This intentionally mirrors how a client
 * would talk to redis-cli, rather than exposing separate REST endpoints per
 * command - so switching the transport to raw TCP in Phase 4 is a matter of
 * feeding CommandExecutor from a socket instead of an HTTP body, with zero
 * changes to command logic itself.
 * <p>
 * Depends on {@link CommandDispatcher} rather than the concrete
 * CommandExecutor, so it transparently gets the AOF-logging decorator when
 * persistence is enabled (Phase 5), with no code changes here.
 */
@RestController
@RequestMapping("/api")
public class CommandController {

    private final CommandDispatcher commandDispatcher;

    public CommandController(CommandDispatcher commandDispatcher) {
        this.commandDispatcher = commandDispatcher;
    }

    @PostMapping(value = "/command", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<CommandResponse> executeCommand(@RequestBody String rawCommand) {
        Object result = commandDispatcher.execute(rawCommand);
        return ResponseEntity.ok(CommandResponse.success(result));
    }
}
