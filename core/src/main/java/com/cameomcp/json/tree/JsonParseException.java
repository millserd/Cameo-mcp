package com.cameomcp.json.tree;

/** Thrown when text is not valid JSON. */
public class JsonParseException extends RuntimeException {
    public JsonParseException(String message) {
        super(message);
    }
}
