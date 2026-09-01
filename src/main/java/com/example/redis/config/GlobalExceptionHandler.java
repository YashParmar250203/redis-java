package com.example.redis.config;

import com.example.redis.exception.RedisException;
import com.example.redis.model.CommandResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central place where command/protocol errors become HTTP responses.
 * <p>
 * Keeping this separate from CommandController means the controller stays
 * about routing, not error formatting - and this same RedisException
 * hierarchy will be reused to build RESP error replies ("-ERR ...\r\n")
 * once the TCP server exists.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RedisException.class)
    public ResponseEntity<CommandResponse> handleRedisException(RedisException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CommandResponse.error(ex.getMessage()));
    }
}