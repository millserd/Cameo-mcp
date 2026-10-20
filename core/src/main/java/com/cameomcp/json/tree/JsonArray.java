package com.cameomcp.json.tree;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A JSON array; a null value is stored as {@link JsonNull}. The scalar {@code getAs...}
 * accessors work only on a one-element array and answer for that element.
 */
public final class JsonArray extends JsonElement implements Iterable<JsonElement> {

    private final ArrayList<JsonElement> elements;

    public JsonArray() {
        elements = new ArrayList<>();
    }

    public JsonArray(int capacity) {
        elements = new ArrayList<>(capacity);
    }

    @Override
    public JsonArray deepCopy() {
        JsonArray copy = new JsonArray(elements.size());
        for (JsonElement e : elements) {
            copy.add(e.deepCopy());
        }
        return copy;
    }

    public void add(Boolean value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public void add(Character value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public void add(Number value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public void add(String value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public void add(JsonElement value) {
        elements.add(value == null ? JsonNull.INSTANCE : value);
    }

    public void addAll(JsonArray array) {
        elements.addAll(array.elements);
    }

    /** Replaces the element at index and returns the old one. */
    public JsonElement set(int index, JsonElement value) {
        return elements.set(index, value == null ? JsonNull.INSTANCE : value);
    }

    public boolean remove(JsonElement value) {
        return elements.remove(value);
    }

    public JsonElement remove(int index) {
        return elements.remove(index);
    }

    public boolean contains(JsonElement value) {
        return elements.contains(value);
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public Iterator<JsonElement> iterator() {
        return elements.iterator();
    }

    public JsonElement get(int index) {
        return elements.get(index);
    }

    /** The live element list. */
    public List<JsonElement> asList() {
        return elements;
    }

    private JsonElement single() {
        if (elements.size() == 1) {
            return elements.get(0);
        }
        throw new IllegalStateException("Array must have size 1, but has size " + elements.size());
    }

    @Override
    public Number getAsNumber() {
        return single().getAsNumber();
    }

    @Override
    public String getAsString() {
        return single().getAsString();
    }

    @Override
    public double getAsDouble() {
        return single().getAsDouble();
    }

    @Override
    public BigDecimal getAsBigDecimal() {
        return single().getAsBigDecimal();
    }

    @Override
    public BigInteger getAsBigInteger() {
        return single().getAsBigInteger();
    }

    @Override
    public float getAsFloat() {
        return single().getAsFloat();
    }

    @Override
    public long getAsLong() {
        return single().getAsLong();
    }

    @Override
    public int getAsInt() {
        return single().getAsInt();
    }

    @Override
    public byte getAsByte() {
        return single().getAsByte();
    }

    @Override
    public short getAsShort() {
        return single().getAsShort();
    }

    @Override
    public boolean getAsBoolean() {
        return single().getAsBoolean();
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof JsonArray && ((JsonArray) o).elements.equals(elements));
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }
}
