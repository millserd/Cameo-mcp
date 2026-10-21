package com.cameomcp.json.tree;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Any JSON value: {@link JsonObject}, {@link JsonArray}, {@link JsonPrimitive} or {@link JsonNull}.
 * The {@code getAs...} accessors throw unless the value has that shape; a one-element array
 * answers them with its element.
 */
public abstract class JsonElement {

    JsonElement() {
    }

    /** A copy that shares no mutable state with this element. */
    public abstract JsonElement deepCopy();

    public boolean isJsonArray() {
        return this instanceof JsonArray;
    }

    public boolean isJsonObject() {
        return this instanceof JsonObject;
    }

    public boolean isJsonPrimitive() {
        return this instanceof JsonPrimitive;
    }

    public boolean isJsonNull() {
        return this instanceof JsonNull;
    }

    public JsonObject getAsJsonObject() {
        if (isJsonObject()) {
            return (JsonObject) this;
        }
        throw new IllegalStateException("Not a JSON Object: " + this);
    }

    public JsonArray getAsJsonArray() {
        if (isJsonArray()) {
            return (JsonArray) this;
        }
        throw new IllegalStateException("Not a JSON Array: " + this);
    }

    public JsonPrimitive getAsJsonPrimitive() {
        if (isJsonPrimitive()) {
            return (JsonPrimitive) this;
        }
        throw new IllegalStateException("Not a JSON Primitive: " + this);
    }

    public JsonNull getAsJsonNull() {
        if (isJsonNull()) {
            return (JsonNull) this;
        }
        throw new IllegalStateException("Not a JSON Null: " + this);
    }

    public boolean getAsBoolean() {
        throw unsupported();
    }

    public Number getAsNumber() {
        throw unsupported();
    }

    public String getAsString() {
        throw unsupported();
    }

    public double getAsDouble() {
        throw unsupported();
    }

    public float getAsFloat() {
        throw unsupported();
    }

    public long getAsLong() {
        throw unsupported();
    }

    public int getAsInt() {
        throw unsupported();
    }

    public short getAsShort() {
        throw unsupported();
    }

    public byte getAsByte() {
        throw unsupported();
    }

    public BigDecimal getAsBigDecimal() {
        throw unsupported();
    }

    public BigInteger getAsBigInteger() {
        throw unsupported();
    }

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

    /** JSON text indented by two spaces per level. */
    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.write(this, sb, "  ", 0);
        return sb.toString();
    }
}
