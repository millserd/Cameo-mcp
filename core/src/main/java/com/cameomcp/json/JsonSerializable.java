package com.cameomcp.json;

/** A value that knows how to turn itself into a JSON-shaped Java value. */
public interface JsonSerializable {
    Object toJson();
}
