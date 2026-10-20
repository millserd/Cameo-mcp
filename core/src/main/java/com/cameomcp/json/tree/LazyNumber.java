package com.cameomcp.json.tree;

import java.math.BigDecimal;

/**
 * A number kept as the text it was parsed from, so integers stay integers and decimals keep
 * their written form ("1.50" stays "1.50") until someone asks for a particular type.
 */
final class LazyNumber extends Number {

    private static final long serialVersionUID = 1L;

    private final String text;

    LazyNumber(String text) {
        this.text = text;
    }

    @Override
    public int intValue() {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
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
