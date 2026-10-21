package com.cameomcp.json.tree;

/** Thrown when text is not valid JSON. */
public class JsonParseException extends RuntimeException {
    /**
     * Creates the exception.
     *
     * @param message what is wrong and, from the parser, the character offset where it was found
     */
    public JsonParseException(String message) {
        super(message);
    }
}
