package com.example.redis.protocol;

import com.example.redis.model.SimpleStringReply;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RespReplyEncoderTest {

    @Test
    void encodesNullAsNilBulkString() {
        assertEquals("$-1\r\n", RespReplyEncoder.encode(null));
    }

    @Test
    void encodesSimpleStringReplyAsStatusLine() {
        assertEquals("+OK\r\n", RespReplyEncoder.encode(new SimpleStringReply("OK")));
    }

    @Test
    void encodesGenericStringAsBulkStringNotStatusLine() {
        // Important distinction: a GET whose value happens to be "OK" must NOT
        // come back as a status line - only SimpleStringReply gets that treatment.
        assertEquals("$2\r\nOK\r\n", RespReplyEncoder.encode("OK"));
        assertEquals("$4\r\nYash\r\n", RespReplyEncoder.encode("Yash"));
    }

    @Test
    void encodesIntegersAsRespInteger() {
        assertEquals(":1\r\n", RespReplyEncoder.encode(1));
        assertEquals(":42\r\n", RespReplyEncoder.encode(42L));
        assertEquals(":0\r\n", RespReplyEncoder.encode(0));
    }

    @Test
    void encodesListAsArrayOfBulkStrings() {
        assertEquals("*2\r\n$1\r\na\r\n$1\r\nb\r\n", RespReplyEncoder.encode(List.of("a", "b")));
    }

    @Test
    void encodesEmptyListAsEmptyArray() {
        assertEquals("*0\r\n", RespReplyEncoder.encode(List.of()));
    }

    @Test
    void encodesMapAsFlattenedFieldValueArray() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", "Yash");
        map.put("city", "Delhi");
        assertEquals("*4\r\n$4\r\nname\r\n$4\r\nYash\r\n$4\r\ncity\r\n$5\r\nDelhi\r\n",
                RespReplyEncoder.encode(map));
    }

    @Test
    void encodesErrorMessageWithLeadingDash() {
        assertEquals("-ERR unknown command 'FOO'\r\n", RespReplyEncoder.encodeError("ERR unknown command 'FOO'"));
    }
}
