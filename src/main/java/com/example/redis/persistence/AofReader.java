package com.example.redis.persistence;

import com.example.redis.protocol.RequestParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads an AOF file's contents into a list of tokenized commands, ready for
 * replay.
 * <p>
 * Because AOF entries are RESP-encoded (see {@link AofWriter}), this reuses
 * {@link RequestParser} directly - including its core correctness property
 * of never consuming a record until it's confirmed complete. That property
 * was built in Phase 4 to handle a command arriving split across TCP
 * packets, but it solves a second problem for free here: a process that
 * crashed mid-write leaves a truncated, incomplete final record in the
 * file, and the parser simply reports "not enough data" for it instead of
 * misinterpreting garbage bytes as a real command.
 * <p>
 * If a record in the *middle* of the file is genuinely malformed (not just
 * a truncated tail), reading stops at that point rather than attempting
 * byte-level resynchronization - matching how real Redis behaves by default
 * on a corrupted AOF (it refuses to guess past unparseable bytes; actually
 * repairing one requires the explicit `redis-check-aof --fix` tool, which
 * this project does not attempt to reimplement).
 */
public final class AofReader {

    private AofReader() {
    }

    public record ReadResult(List<String[]> commands, boolean stoppedEarlyDueToCorruption) {
    }

    public static ReadResult readAll(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            return new ReadResult(List.of(), false);
        }

        byte[] fileBytes = Files.readAllBytes(filePath);
        StringBuilder buffer = new StringBuilder(new String(fileBytes, StandardCharsets.UTF_8));

        List<String[]> commands = new ArrayList<>();
        boolean stoppedEarly = false;

        while (true) {
            Optional<String[]> parsed;
            try {
                parsed = RequestParser.parseOne(buffer);
            } catch (RuntimeException e) {
                // Malformed record mid-file: stop here rather than guessing past it.
                stoppedEarly = true;
                break;
            }
            if (parsed.isEmpty()) {
                // Buffer is either fully consumed, or what remains is an
                // incomplete (truncated) trailing record - either way,
                // nothing more can be safely replayed.
                stoppedEarly = !buffer.isEmpty();
                break;
            }
            if (parsed.get().length > 0) {
                commands.add(parsed.get());
            }
        }

        return new ReadResult(commands, stoppedEarly);
    }
}
