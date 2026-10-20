package com.cameomcp.json.tree;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A JSON string, number or boolean. Numeric accessors also accept numeric strings, and
 * {@link #getAsString()} gives the text of a number or boolean.
 */
public final class JsonPrimitive extends JsonElement {

    private final Object value;

    public JsonPrimitive(Boolean value) {
        this.value = requireNonNull(value);
    }

    public JsonPrimitive(Number value) {
        this.value = requireNonNull(value);
    }

    public JsonPrimitive(String value) {
        this.value = requireNonNull(value);
    }

    /** A character is stored as a one-character string. */
    public JsonPrimitive(Character value) {
        this.value = requireNonNull(value).toString();
    }

    private static <T> T requireNonNull(T v) {
        if (v == null) {
            throw new NullPointerException("JsonPrimitive value");
        }
        return v;
    }

    @Override
    public JsonPrimitive deepCopy() {
        return this;
    }

    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public boolean isString() {
        return value instanceof String;
    }

    @Override
    public boolean getAsBoolean() {
        return isBoolean() ? (Boolean) value : Boolean.parseBoolean(getAsString());
    }

    @Override
    public Number getAsNumber() {
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof String) {
            return new LazyNumber((String) value);
        }
        throw new UnsupportedOperationException("Primitive is neither a number nor a string");
    }

    @Override
    public String getAsString() {
        return value instanceof String ? (String) value : value.toString();
    }

    @Override
    public double getAsDouble() {
        return isNumber() ? ((Number) value).doubleValue() : Double.parseDouble(getAsString());
    }

    @Override
    public float getAsFloat() {
        return isNumber() ? ((Number) value).floatValue() : Float.parseFloat(getAsString());
    }

    @Override
    public long getAsLong() {
        return isNumber() ? ((Number) value).longValue() : Long.parseLong(getAsString());
    }

    @Override
    public int getAsInt() {
        return isNumber() ? ((Number) value).intValue() : Integer.parseInt(getAsString());
    }

    @Override
    public short getAsShort() {
        return isNumber() ? ((Number) value).shortValue() : Short.parseShort(getAsString());
    }

    @Override
    public byte getAsByte() {
        return isNumber() ? ((Number) value).byteValue() : Byte.parseByte(getAsString());
    }

    @Override
    public BigDecimal getAsBigDecimal() {
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(getAsString());
    }

    @Override
    public BigInteger getAsBigInteger() {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (isIntegral(value)) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return new BigDecimal(getAsString()).toBigIntegerExact();
    }

    /** The raw value: a Boolean, Number or String. */
    Object value() {
        return value;
    }

    private static boolean isIntegral(Object v) {
        return v instanceof BigInteger || v instanceof Long || v instanceof Integer
                || v instanceof Short || v instanceof Byte;
    }

    @Override
    public int hashCode() {
        if (isIntegral(value)) {
            long v = ((Number) value).longValue();
            return (int) (v ^ (v >>> 32));
        }
        if (value instanceof Number) {
            long bits = Double.doubleToLongBits(getAsNumber().doubleValue());
            return (int) (bits ^ (bits >>> 32));
        }
        return value.hashCode();
    }

    // Numbers compare by value (1 equals 1.0); strings and booleans by equals.
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof JsonPrimitive)) {
            return false;
        }
        Object other = ((JsonPrimitive) obj).value;
        if (isIntegral(value) && isIntegral(other)) {
            if (value instanceof BigInteger || other instanceof BigInteger) {
                return getAsBigInteger().equals(((JsonPrimitive) obj).getAsBigInteger());
            }
            return ((Number) value).longValue() == ((Number) other).longValue();
        }
        if (value instanceof Number && other instanceof Number) {
            if (value instanceof BigDecimal && other instanceof BigDecimal) {
                return ((BigDecimal) value).compareTo((BigDecimal) other) == 0;
            }
            double a = ((Number) value).doubleValue();
            double b = ((Number) other).doubleValue();
            return a == b || (Double.isNaN(a) && Double.isNaN(b));
        }
        return value.equals(other);
    }
}
