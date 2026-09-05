package com.example.redis.protocol;

import com.example.redis.exception.RedisException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestParserTest {

    // ---- inline commands ----

    @Test
    void parsesInlineCommand() {
        StringBuilder buffer = new StringBuilder("SET name Yash\r\n");
        Optional<String[]> result = RequestParser.parseOne(buffer);
        assertArrayEquals(new String[]{"SET", "name", "Yash"}, result.orElseThrow());
        assertEquals(0, buffer.length()); // fully consumed
    }

    @Test
    void inlineCommandToleratesBareLineFeed() {
        StringBuilder buffer = new StringBuilder("GET name\n");
        assertArrayEquals(new String[]{"GET", "name"}, RequestParser.parseOne(buffer).orElseThrow());
    }

    @Test
    void incompleteInlineLineReturnsEmptyAndLeavesBufferIntact() {
        StringBuilder buffer = new StringBuilder("SET name Ya");
        assertTrue(RequestParser.parseOne(buffer).isEmpty());
        assertEquals("SET name Ya", buffer.toString());
    }

    @Test
    void blankInlineLineParsesToEmptyTokenArraySignalingNoOp() {
        StringBuilder buffer = new StringBuilder("\r\n");
        Optional<String[]> result = RequestParser.parseOne(buffer);
        assertEquals(0, result.orElseThrow().length);
    }

    @Test
    void handlesTwoPipelinedInlineCommandsInOneBuffer() {
        StringBuilder buffer = new StringBuilder("SET a 1\r\nSET b 2\r\n");
        assertArrayEquals(new String[]{"SET", "a", "1"}, RequestParser.parseOne(buffer).orElseThrow());
        assertArrayEquals(new String[]{"SET", "b", "2"}, RequestParser.parseOne(buffer).orElseThrow());
        assertEquals(0, buffer.length());
    }

    // ---- RESP multibulk arrays ----

    @Test
    void parsesCompleteRespArray() {
        StringBuilder buffer = new StringBuilder("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\nYash\r\n");
        assertArrayEquals(new String[]{"SET", "name", "Yash"}, RequestParser.parseOne(buffer).orElseThrow());
        assertEquals(0, buffer.length());
    }

    @Test
    void respValuesMayContainSpaces() {
        // The exact limitation the whitespace-based inline parser (and pre-Phase-4
        // CommandExecutor.execute(String)) has - RESP's explicit length prefixes fix it.
        String value = "Yash Kumar Sharma";
        String request = "*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$" + value.length() + "\r\n" + value + "\r\n";
        String[] tokens = RequestParser.parseOne(new StringBuilder(request)).orElseThrow();
        assertEquals(value, tokens[2]);
    }

    @Test
    void respArrayArrivingAcrossMultipleChunksIsAssembledCorrectly() {
        // Simulates a request split across several separate TCP reads.
        StringBuilder buffer = new StringBuilder();
        buffer.append("*2\r\n$3\r\nGE");
        assertTrue(RequestParser.parseOne(buffer).isEmpty());

        buffer.append("T\r\n$4\r\nna");
        assertTrue(RequestParser.parseOne(buffer).isEmpty());

        buffer.append("me\r\n");
        assertArrayEquals(new String[]{"GET", "name"}, RequestParser.parseOne(buffer).orElseThrow());
    }

    @Test
    void incompleteRespArrayDoesNotConsumeAnyBytes() {
        String partial = "*2\r\n$3\r\nGET\r\n$4\r\nna";
        StringBuilder buffer = new StringBuilder(partial);
        assertTrue(RequestParser.parseOne(buffer).isEmpty());
        assertEquals(partial, buffer.toString());
    }

    @Test
    void malformedMultibulkLengthThrowsProtocolException() {
        assertThrows(RedisException.class, () -> RequestParser.parseOne(new StringBuilder("*notanumber\r\n")));
    }

    @Test
    void zeroLengthMultibulkThrowsProtocolException() {
        assertThrows(RedisException.class, () -> RequestParser.parseOne(new StringBuilder("*0\r\n")));
    }

    @Test
    void missingBulkStringMarkerThrowsProtocolException() {
        assertThrows(RedisException.class,
                () -> RequestParser.parseOne(new StringBuilder("*1\r\n#3\r\nSET\r\n")));
    }
}
