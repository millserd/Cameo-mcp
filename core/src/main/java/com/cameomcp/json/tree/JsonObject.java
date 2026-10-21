package com.cameomcp.json.tree;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A JSON object. Members keep insertion order; a null value is stored as {@link JsonNull}. */
public final class JsonObject extends JsonElement {

    private final LinkedHashMap<String, JsonElement> members = new LinkedHashMap<>();

    @Override
    public JsonObject deepCopy() {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> e : members.entrySet()) {
            copy.add(e.getKey(), e.getValue().deepCopy());
        }
        return copy;
    }

    public void add(String property, JsonElement value) {
        members.put(property, value == null ? JsonNull.INSTANCE : value);
    }

    public JsonElement remove(String property) {
        return members.remove(property);
    }

    public void addProperty(String property, String value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public void addProperty(String property, Number value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public void addProperty(String property, Boolean value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public void addProperty(String property, Character value) {
        add(property, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    public Set<Map.Entry<String, JsonElement>> entrySet() {
        return members.entrySet();
    }

    public Set<String> keySet() {
        return members.keySet();
    }

    /** The live member map. */
    public Map<String, JsonElement> asMap() {
        return members;
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public boolean has(String property) {
        return members.containsKey(property);
    }

    /** The member, or null when absent. */
    public JsonElement get(String property) {
        return members.get(property);
    }

    public JsonPrimitive getAsJsonPrimitive(String property) {
        return (JsonPrimitive) members.get(property);
    }

    public JsonArray getAsJsonArray(String property) {
        return (JsonArray) members.get(property);
    }

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
