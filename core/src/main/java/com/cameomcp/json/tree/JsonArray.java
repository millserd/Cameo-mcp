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

    /** The elements in order; never contains Java null (it is replaced by {@link JsonNull}). */
    private final ArrayList<JsonElement> elements;

    /** Creates an empty array. */
    public JsonArray() {
        elements = new ArrayList<>();
    }

    /**
     * Creates an empty array with room for a known number of elements.
     *
     * @param capacity initial capacity (a sizing hint only)
     */
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

    /**
     * Appends a boolean.
     *
     * @param value the boolean; null is stored as {@link JsonNull}
     */
    public void add(Boolean value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Appends a one-character string.
     *
     * @param value the character; null is stored as {@link JsonNull}
     */
    public void add(Character value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Appends a number.
     *
     * @param value the number; null is stored as {@link JsonNull}
     */
    public void add(Number value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Appends a string.
     *
     * @param value the string; null is stored as {@link JsonNull}
     */
    public void add(String value) {
        add(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Appends an element.
     *
     * @param value the element; null is stored as {@link JsonNull}
     */
    public void add(JsonElement value) {
        elements.add(value == null ? JsonNull.INSTANCE : value);
    }

    /**
     * Appends every element of another array. The elements are shared, not copied.
     *
     * @param array the source array
     */
    public void addAll(JsonArray array) {
        elements.addAll(array.elements);
    }

    /**
     * Replaces the element at index and returns the old one.
     *
     * @param index position to replace
     * @param value the new element; null is stored as {@link JsonNull}
     * @return the element previously at that position
     * @throws IndexOutOfBoundsException if index is outside the array
     */
    public JsonElement set(int index, JsonElement value) {
        return elements.set(index, value == null ? JsonNull.INSTANCE : value);
    }

    /**
     * Removes the first element equal to the given one.
     *
     * @param value the element to remove
     * @return true if an element was removed
     */
    public boolean remove(JsonElement value) {
        return elements.remove(value);
    }

    /**
     * Removes the element at a position.
     *
     * @param index position to remove
     * @return the removed element
     * @throws IndexOutOfBoundsException if index is outside the array
     */
    public JsonElement remove(int index) {
        return elements.remove(index);
    }

    /**
     * Tests membership by equals.
     *
     * @param value the element to look for
     * @return true if an equal element is present
     */
    public boolean contains(JsonElement value) {
        return elements.contains(value);
    }

    /**
     * Returns the number of elements.
     *
     * @return the number of elements
     */
    public int size() {
        return elements.size();
    }

    /**
     * Returns true if the array has no elements.
     *
     * @return true if the array has no elements
     */
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public Iterator<JsonElement> iterator() {
        return elements.iterator();
    }

    /**
     * Gets the element at a position.
     *
     * @param index position, from 0
     * @return the element; JSON null is returned as {@link JsonNull}, never Java null
     * @throws IndexOutOfBoundsException if index is outside the array
     */
    public JsonElement get(int index) {
        return elements.get(index);
    }

    /**
     * The live element list.
     *
     * @return the live list backing this array; changes write through
     */
    public List<JsonElement> asList() {
        return elements;
    }

    /**
     * The only element, for the scalar accessors.
     *
     * @return the single element
     * @throws IllegalStateException if the array does not have exactly one element
     */
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
