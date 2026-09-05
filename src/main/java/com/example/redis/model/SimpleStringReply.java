package com.example.redis.model;

/**
 * Marker for a Redis "simple status reply" (e.g. +OK), as distinct from an
 * ordinary bulk-string value a command might return (e.g. GET's result).
 * <p>
 * This distinction matters on the wire: RESP encodes status replies as
 * "+OK\r\n" but generic string data as a length-prefixed bulk string
 * ("$2\r\nOK\r\n") - a GET whose value happens to literally be "OK" must
 * still be encoded as a bulk string, not a status line. Only the RESP
 * encoder treats this type specially; the REST/JSON layer just unwraps it
 * to its plain string value.
 */
public record SimpleStringReply(String value) {
}
