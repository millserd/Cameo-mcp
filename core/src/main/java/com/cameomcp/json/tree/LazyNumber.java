package com.cameomcp.json.tree;

import java.math.BigDecimal;

/**
 * A number kept as the text it was parsed from, so integers stay integers and decimals keep
 * their written form ("1.50" stays "1.50") until someone asks for a particular type.
 */
final class LazyNumber extends Number {

    /** Number is Serializable; this class is never serialized but keeps the compiler quiet. */
    private static final long serialVersionUID = 1L;

    /** The number exactly as it appeared in the JSON text. */
    private final String text;

    /**
     * Wraps a number literal without converting it.
     *
     * @param text a JSON number literal
     */
    LazyNumber(String text) {
        this.text = text;
    }

    @Override
    public int intValue() {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // Too large for an int: truncate like a long-to-int cast, then fall back to BigDecimal for
            // decimals.
            try {
                return (int) Long.parseLong(text);
            } catch (NumberFormatException e2) {
                return new BigDecimal(text).intValue();
            }
        }
    }

    @Override
    public long longValue() {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return new BigDecimal(text).longValue();
        }
    }

    @Override
    public float floatValue() {
        return Float.parseFloat(text);
    }

    @Override
    public double doubleValue() {
        return Double.parseDouble(text);
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LazyNumber && ((LazyNumber) o).text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
