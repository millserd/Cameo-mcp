package com.cameomcp.json.tree;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A JSON object. Members keep insertion order; a null value is stored as {@link JsonNull}. */
public final class JsonObject extends JsonElement {

    /** Members by name, in insertion order (so tool results serialize in the order they were built). */
    private final LinkedHashMap<String, JsonElement> members = new LinkedHashMap<>();

    @Override
    public JsonObject deepCopy() {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> e : members.entrySet()) {
            copy.add(e.getKey(), e.getValue().deepCopy());
        }
        return copy;
    }

    /**
     * Adds or replaces a member.
     *
     * @param property member name
     * @param value member value; null is stored as {@link JsonNull}
     */
    public void add(String property, JsonElement value) {
        members.put(property, value == null ? JsonNull.INSTANCE : value);
    }

    /**
     * Removes a member.
     *
     * @param property member name
     * @return the removed value, or null if there was none
     */
    public JsonElement remove(String property) {
        return members.remove(property);
    }

    /**
     * Adds or replaces a string member.
     *
     * @param property member name
     * @param value the string; null is stored as {@link JsonNull}
     */
    public void addProperty(String property, String value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Adds or replaces a number member.
     *
     * @param property member name
     * @param value the number; null is stored as {@link JsonNull}
     */
    public void addProperty(String property, Number value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Adds or replaces a boolean member.
     *
     * @param property member name
     * @param value the boolean; null is stored as {@link JsonNull}
     */
    public void addProperty(String property, Boolean value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Adds or replaces a member holding a one-character string.
     *
     * @param property member name
     * @param value the character; null is stored as {@link JsonNull}
     */
    public void addProperty(String property, Character value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /**
     * Returns the live members, in insertion order.
     *
     * @return the live members, in insertion order
     */
    public Set<Map.Entry<String, JsonElement>> entrySet() {
        return members.entrySet();
    }

    /**
     * Returns the live member names, in insertion order.
     *
     * @return the live member names, in insertion order
     */
    public Set<String> keySet() {
        return members.keySet();
    }

    /**
     * The live member map.
     *
     * @return the live map backing this object; changes write through
     */
    public Map<String, JsonElement> asMap() {
        return members;
    }

    /**
     * Returns the number of members.
     *
     * @return the number of members
     */
    public int size() {
        return members.size();
    }

    /**
     * Returns true if the object has no members.
     *
     * @return true if the object has no members
     */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * Returns true if the member is present (even when its value is {@link JsonNull}).
     *
     * @param property member name
     * @return true if the member is present (even when its value is {@link JsonNull})
     */
    public boolean has(String property) {
        return members.containsKey(property);
    }

    /**
     * The member, or null when absent.
     *
     * @param property member name
     * @return the member's value, or null when absent
     */
    public JsonElement get(String property) {
        return members.get(property);
    }

    /**
     * The member as a primitive.
     *
     * @param property member name
     * @return the primitive, or null when absent
     * @throws ClassCastException if the member is not a primitive
     */
    public JsonPrimitive getAsJsonPrimitive(String property) {
        return (JsonPrimitive) members.get(property);
    }

    /**
     * The member as an array.
     *
     * @param property member name
     * @return the array, or null when absent
     * @throws ClassCastException if the member is not an array
     */
    public JsonArray getAsJsonArray(String property) {
        return (JsonArray) members.get(property);
    }

    /**
     * The member as an object.
     *
     * @param property member name
     * @return the object, or null when absent
     * @throws ClassCastException if the member is not an object
     */
    public JsonObject getAsJsonObject(String property) {
        return (JsonObject) members.get(property);
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof JsonObject && ((JsonObject) o).members.equals(members));
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }
}
