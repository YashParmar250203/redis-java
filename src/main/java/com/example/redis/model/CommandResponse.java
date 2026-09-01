package com.example.redis.model;

/**
 * Uniform JSON envelope returned by the REST command endpoint.
 * <p>
 * Kept as a plain response wrapper rather than a raw value so the API can
 * distinguish "command succeeded and returned null / nil" from "command
 * failed" - both are legitimate outcomes (e.g. GET on a missing key is a
 * success with a null result, not an error).
 */
public class CommandResponse {

    private final boolean success;
    private final Object result;
    private final String error;

    private CommandResponse(boolean success, Object result, String error) {
        this.success = success;
        this.result = result;
        this.error = error;
    }

    public static CommandResponse success(Object result) {
        return new CommandResponse(true, result, null);
    }

    public static CommandResponse error(String message) {
        return new CommandResponse(false, null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getResult() {
        return result;
    }

    public String getError() {
        return error;
    }
}