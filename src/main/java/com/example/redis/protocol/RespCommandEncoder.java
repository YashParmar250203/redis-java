package com.example.redis.protocol;

/**
 * Encodes a tokenized command as a RESP multibulk array - the exact same
 * wire format {@link RequestParser} reads. Used to write AOF entries in
 * {@code persistence.AofWriter}, so replay can reuse RequestParser directly
 * instead of a second, parallel text format.
 */
public final class RespCommandEncoder {

    private RespCommandEncoder() {
    }

    public static String encode(String[] tokens) {
        StringBuilder builder = new StringBuilder();
        builder.append('*').append(tokens.length).append("\r\n");
        for (String token : tokens) {
            builder.append('$').append(token.length()).append("\r\n").append(token).append("\r\n");
        }
        return builder.toString();
    }
}
