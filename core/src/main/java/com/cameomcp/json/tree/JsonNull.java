package com.cameomcp.json.tree;

/** JSON {@code null}. */
public final class JsonNull extends JsonElement {

    /** The single JSON null value; every null member or element refers to it. */
    public static final JsonNull INSTANCE = new JsonNull();

    /** Use {@link #INSTANCE}. */
    private JsonNull() {
    }

    @Override
    public JsonNull deepCopy() {
        return INSTANCE;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNull;
    }

    @Override
    public int hashCode() {
        return JsonNull.class.hashCode();
    }
}
