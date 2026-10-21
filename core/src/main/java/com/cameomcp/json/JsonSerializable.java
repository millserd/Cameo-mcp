package com.cameomcp.json;

/** A value that knows how to turn itself into a JSON-shaped Java value. */
public interface JsonSerializable {
    /**
     * Returns a value {@link Json} can write directly: a {@code Map}, {@code List}, {@code String},
     * {@code Number}, {@code Boolean}, {@code null}, or another {@code JsonSerializable}.
     *
     * @return the JSON-shaped form of this value
     */
    Object toJson();
}
