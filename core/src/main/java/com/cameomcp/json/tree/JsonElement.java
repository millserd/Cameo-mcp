package com.cameomcp.json.tree;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Any JSON value: {@link JsonObject}, {@link JsonArray}, {@link JsonPrimitive} or {@link JsonNull}.
 * The {@code getAs...} accessors throw unless the value has that shape; a one-element array
 * answers them with its element.
 */
public abstract class JsonElement {

    /** Package-private: the only subclasses are the four in this package. */
    JsonElement() {
    }

    /**
     * A copy that shares no mutable state with this element.
     *
     * @return an independent deep copy; immutable values ({@link JsonPrimitive}, {@link JsonNull}) return themselves
     */
    public abstract JsonElement deepCopy();

    /**
     * Returns true if this is a {@link JsonArray}.
     *
     * @return true if this is a {@link JsonArray}
     */
    public boolean isJsonArray() {
        return this instanceof JsonArray;
    }

    /**
     * Returns true if this is a {@link JsonObject}.
     *
     * @return true if this is a {@link JsonObject}
     */
    public boolean isJsonObject() {
        return this instanceof JsonObject;
    }

    /**
     * Returns true if this is a {@link JsonPrimitive} (string, number or boolean).
     *
     * @return true if this is a {@link JsonPrimitive} (string, number or boolean)
     */
    public boolean isJsonPrimitive() {
        return this instanceof JsonPrimitive;
    }

    /**
     * Returns true if this is {@link JsonNull}.
     *
     * @return true if this is {@link JsonNull}
     */
    public boolean isJsonNull() {
        return this instanceof JsonNull;
    }

    /**
     * Casts to {@link JsonObject}.
     *
     * @return this element as an object
     * @throws IllegalStateException if this is not an object
     */
    public JsonObject getAsJsonObject() {
        if (isJsonObject()) {
            return (JsonObject) this;
        }
        throw new IllegalStateException("Not a JSON Object: " + this);
    }

    /**
     * Casts to {@link JsonArray}.
     *
     * @return this element as an array
     * @throws IllegalStateException if this is not an array
     */
    public JsonArray getAsJsonArray() {
        if (isJsonArray()) {
            return (JsonArray) this;
        }
        throw new IllegalStateException("Not a JSON Array: " + this);
    }

    /**
     * Casts to {@link JsonPrimitive}.
     *
     * @return this element as a primitive
     * @throws IllegalStateException if this is not a primitive
     */
    public JsonPrimitive getAsJsonPrimitive() {
        if (isJsonPrimitive()) {
            return (JsonPrimitive) this;
        }
        throw new IllegalStateException("Not a JSON Primitive: " + this);
    }

    /**
     * Casts to {@link JsonNull}.
     *
     * @return this element as null
     * @throws IllegalStateException if this is not JSON null
     */
    public JsonNull getAsJsonNull() {
        if (isJsonNull()) {
            return (JsonNull) this;
        }
        throw new IllegalStateException("Not a JSON Null: " + this);
    }

    /**
     * The value as a boolean. Overridden by {@link JsonPrimitive} and the one-element {@link JsonArray}.
     *
     * @return the boolean value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public boolean getAsBoolean() {
        throw unsupported();
    }

    /**
     * The value as a number.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public Number getAsNumber() {
        throw unsupported();
    }

    /**
     * The value as a string; numbers and booleans are rendered as text.
     *
     * @return the string value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public String getAsString() {
        throw unsupported();
    }

    /**
     * The value as a double.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public double getAsDouble() {
        throw unsupported();
    }

    /**
     * The value as a float.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public float getAsFloat() {
        throw unsupported();
    }

    /**
     * The value as a long.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public long getAsLong() {
        throw unsupported();
    }

    /**
     * The value as an int.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public int getAsInt() {
        throw unsupported();
    }

    /**
     * The value as a short.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public short getAsShort() {
        throw unsupported();
    }

    /**
     * The value as a byte.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public byte getAsByte() {
        throw unsupported();
    }

    /**
     * The value as an exact decimal.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public BigDecimal getAsBigDecimal() {
        throw unsupported();
    }

    /**
     * The value as an exact integer.
     *
     * @return the numeric value
     * @throws UnsupportedOperationException if this element has no scalar value
     */
    public BigInteger getAsBigInteger() {
        throw unsupported();
    }

    /** The exception every scalar accessor throws by default, naming the concrete element type. */
    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(getClass().getSimpleName());
    }

    /** Compact JSON text. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.write(this, sb, null, 0);
        return sb.toString();
    }

    /**
     * JSON text indented by two spaces per level.
     *
     * @return the formatted JSON text
     */
    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.write(this, sb, "  ", 0);
        return sb.toString();
    }
}
