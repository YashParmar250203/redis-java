package com.example.redis.server;

import com.example.redis.command.CommandExecutor;
import com.example.redis.exception.RedisException;
import com.example.redis.protocol.RequestParser;
import com.example.redis.protocol.RespReplyEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Per-connection state for the TCP server: accumulates bytes read from the
 * socket into a text buffer and repeatedly tries to parse and execute one
 * complete command at a time from it.
 * <p>
 * Treats all connection data as UTF-8 text rather than raw binary. This is
 * a deliberate simplification: none of our commands accept binary payloads,
 * so unlike real Redis (whose bulk strings are fully binary-safe - able to
 * embed arbitrary bytes, including nulls), this implementation assumes
 * single-byte-per-character content when interpreting RESP bulk-string
 * lengths against the decoded text buffer.
 * <p>
 * Because {@link #handleReadable} keeps parsing while the buffer still
 * contains a complete command, multiple pipelined commands sent in one TCP
 * packet are all executed and replied to within the same read event - basic
 * pipelining support falls out of the incremental-parsing design without
 * any extra code.
 * <p>
 * Writes are done with a simple blocking-style retry loop rather than
 * registering for {@code OP_WRITE} and resuming when the socket is ready.
 * For the small, single-reply-per-command payloads this project produces,
 * that's a reasonable simplification; a production NIO server handling
 * large replies or slow clients would need proper write-readiness handling
 * to avoid busy-spinning - a natural next improvement.
 */
class ClientConnection {

    private static final int READ_BUFFER_SIZE = 8192;

    private final SocketChannel channel;
    private final CommandExecutor commandExecutor;
    private final StringBuilder pending = new StringBuilder();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

    ClientConnection(SocketChannel channel, CommandExecutor commandExecutor) {
        this.channel = channel;
        this.commandExecutor = commandExecutor;
    }

    void handleReadable(SelectionKey key) throws IOException {
        readBuffer.clear();
        int bytesRead = channel.read(readBuffer);
        if (bytesRead == -1) {
            closeConnection(key);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        readBuffer.flip();
        pending.append(StandardCharsets.UTF_8.decode(readBuffer));

        Optional<String[]> parsed;
        while ((parsed = safeParseOne()).isPresent()) {
            String[] tokens = parsed.get();
            if (tokens.length == 0) {
                continue; // blank inline line - real Redis silently ignores these too
            }
            writeAll(executeAndEncode(tokens));
        }
    }

    /**
     * Wraps RequestParser.parseOne so malformed RESP framing reports a
     * protocol error to the client instead of leaving the connection stuck
     * re-parsing the same bad bytes forever.
     */
    private Optional<String[]> safeParseOne() {
        try {
            return RequestParser.parseOne(pending);
        } catch (RedisException e) {
            writeAll(RespReplyEncoder.encodeError(e.getMessage()));
            pending.setLength(0); // framing is unrecoverable mid-stream; drop the buffer
            return Optional.empty();
        }
    }

    private String executeAndEncode(String[] tokens) {
        try {
            Object result = commandExecutor.execute(tokens);
            return RespReplyEncoder.encode(result);
        } catch (RedisException e) {
            return RespReplyEncoder.encodeError(e.getMessage());
        }
    }

    private void writeAll(String reply) {
        ByteBuffer buffer = ByteBuffer.wrap(reply.getBytes(StandardCharsets.UTF_8));
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            // The client most likely disconnected mid-write; the next read
            // event (or absence of one) on this channel surfaces the failure.
        }
    }

    private void closeConnection(SelectionKey key) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Connection is already going away.
        }
        key.cancel();
    }
}
