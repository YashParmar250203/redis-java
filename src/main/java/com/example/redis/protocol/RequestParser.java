package com.example.redis.protocol;

import com.example.redis.exception.ProtocolException;

import java.util.Optional;

/**
 * Parses one command at a time out of a growing per-connection text buffer.
 * <p>
 * Supports the same two request formats real Redis does:
 * <ul>
 *     <li><b>RESP multibulk arrays</b> - what redis-cli and real client
 *     libraries send: {@code *3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\nYash\r\n}.
 *     Argument boundaries are explicit (length-prefixed), so values may
 *     contain spaces.</li>
 *     <li><b>Inline commands</b> - a plain newline-terminated line, e.g.
 *     {@code SET name Yash\r\n}, tokenized on whitespace. This is a real,
 *     documented Redis feature (useful for talking to a server with
 *     telnet/netcat), not a simplification invented for this project.</li>
 * </ul>
 * Which format applies is decided by the first byte: {@code *} means RESP,
 * anything else means inline.
 * <p>
 * <b>Correctness rule this class exists to enforce:</b> because a command
 * can arrive split across multiple TCP reads, {@link #parseOne} must never
 * consume bytes from the buffer unless a *complete* command is confirmed
 * present. If not enough data has arrived yet, it returns
 * {@link Optional#empty()} and leaves the buffer untouched, so the caller
 * can safely retry after the next read event appends more bytes.
 * <p>
 * Treats the buffer as text (not raw bytes), which assumes single-byte
 * (ASCII-range) content - see {@code server.ClientConnection} javadoc for
 * why that's an acceptable simplification here.
 */
public final class RequestParser {

    private RequestParser() {
    }

    /**
     * @return the parsed command's tokens if a complete command is present
     * (an empty array specifically means "a blank inline line was consumed,
     * there is no command to run"), or {@link Optional#empty()} if more
     * data is needed. Throws {@link ProtocolException} on malformed RESP
     * framing.
     */
    public static Optional<String[]> parseOne(StringBuilder buffer) {
        if (buffer.isEmpty()) {
            return Optional.empty();
        }
        return buffer.charAt(0) == '*' ? parseResp(buffer) : parseInline(buffer);
    }

    private static Optional<String[]> parseInline(StringBuilder buffer) {
        int newlineIndex = buffer.indexOf("\n");
        if (newlineIndex == -1) {
            return Optional.empty();
        }
        String line = buffer.substring(0, newlineIndex);
        buffer.delete(0, newlineIndex + 1);

        String trimmed = stripTrailingCarriageReturn(line).trim();
        return Optional.of(trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+"));
    }

    private static String stripTrailingCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static Optional<String[]> parseResp(StringBuilder buffer) {
        int headerEnd = buffer.indexOf("\r\n");
        if (headerEnd == -1) {
            return Optional.empty();
        }

        int arrayLength = parseLength(buffer.substring(1, headerEnd), "invalid multibulk length");
        if (arrayLength <= 0) {
            throw new ProtocolException("invalid multibulk length");
        }

        String[] tokens = new String[arrayLength];
        int pos = headerEnd + 2;

        for (int i = 0; i < arrayLength; i++) {
            if (pos >= buffer.length()) {
                return Optional.empty();
            }
            if (buffer.charAt(pos) != '$') {
                throw new ProtocolException("expected '$', got '" + buffer.charAt(pos) + "'");
            }

            int bulkHeaderEnd = buffer.indexOf("\r\n", pos);
            if (bulkHeaderEnd == -1) {
                return Optional.empty();
            }

            int bulkLength = parseLength(buffer.substring(pos + 1, bulkHeaderEnd), "invalid bulk length");
            int valueStart = bulkHeaderEnd + 2;
            int valueEnd = valueStart + bulkLength;
            int afterValue = valueEnd + 2; // account for the trailing \r\n after the value

            if (afterValue > buffer.length()) {
                return Optional.empty();
            }

            tokens[i] = buffer.substring(valueStart, valueEnd);
            pos = afterValue;
        }

        buffer.delete(0, pos);
        return Optional.of(tokens);
    }

    private static int parseLength(String raw, String errorReason) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ProtocolException(errorReason);
        }
    }
}
