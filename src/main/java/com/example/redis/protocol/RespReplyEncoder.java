package com.example.redis.protocol;

import com.example.redis.model.SimpleStringReply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Encodes a {@link com.example.redis.command.Command} result (whatever
 * object it returned) into a RESP-typed reply. Replies are always encoded
 * in RESP, regardless of whether the request that produced them arrived as
 * RESP or as an inline command - that matches real Redis, where only
 * *requests* get the inline shorthand.
 */
public final class RespReplyEncoder {

    private RespReplyEncoder() {
    }

    public static String encode(Object result) {
        if (result == null) {
            return "$-1\r\n"; // nil bulk string
        }
        if (result instanceof SimpleStringReply reply) {
            return "+" + reply.value() + "\r\n";
        }
        if (result instanceof String value) {
            return encodeBulkString(value);
        }
        if (result instanceof Integer || result instanceof Long) {
            return ":" + result + "\r\n";
        }
        if (result instanceof Map<?, ?> map) {
            // RESP2 has no native map type - real Redis represents HGETALL
            // as a flat array of alternating field/value bulk strings, so we do too.
            return encodeArray(flattenMap(map));
        }
        if (result instanceof Collection<?> collection) {
            return encodeArray(new ArrayList<>(collection));
        }
        return encodeBulkString(result.toString());
    }

    public static String encodeError(String message) {
        return "-" + message + "\r\n";
    }

    private static String encodeBulkString(String value) {
        return "$" + value.length() + "\r\n" + value + "\r\n";
    }

    private static String encodeArray(List<?> items) {
        StringBuilder builder = new StringBuilder();
        builder.append('*').append(items.size()).append("\r\n");
        for (Object item : items) {
            builder.append(item == null ? "$-1\r\n" : encodeBulkString(item.toString()));
        }
        return builder.toString();
    }

    private static List<String> flattenMap(Map<?, ?> map) {
        List<String> flattened = new ArrayList<>(map.size() * 2);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            flattened.add(String.valueOf(entry.getKey()));
            flattened.add(String.valueOf(entry.getValue()));
        }
        return flattened;
    }
}
