package com.cameomcp.json;

/** Thrown when JSON text cannot be parsed. */
public class JsonException extends RuntimeException {
    /** @param message what was wrong and, where known, the character offset it was found at */
    public JsonException(String message) {
        super(message);
    }
}
