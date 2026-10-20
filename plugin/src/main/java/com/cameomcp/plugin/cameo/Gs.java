package com.cameomcp.plugin.cameo;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonNull;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParser;
import com.cameomcp.json.tree.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON tree helpers for the Cameo-facing code, which builds results as JSON trees.
 * Conversions to/from the core's plain-Java JSON go through text, which is cheap at
 * tool-call granularity and keeps both worlds independent.
 */
public final class Gs {

    private Gs() {
    }

    /** Converts a core JSON object to a tree; null becomes an empty object. */
    public static JsonObject toTree(Map<String, Object> map) {
        return map == null ? new JsonObject() : JsonParser.parseString(Json.write(map)).getAsJsonObject();
    }

    /** The tool-call arguments as a JSON tree object, for services written against JSON trees. */
    public static JsonObject toTree(Args args) {
        return toTree(args.raw());
    }

    /** Converts a tree element back to core JSON values (Map, List, String, Long/Double, Boolean). */
    public static Object fromTree(JsonElement el) {
        return el == null || el.isJsonNull() ? null : Json.parse(el.toString());
    }

    /** Builds a JSON tree object from key/value pairs; values are converted with {@link #toElement}. */
    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.add(String.valueOf(kv[i]), toElement(kv[i + 1]));
        }
        return o;
    }

    /** Convert any JSON-shaped Java value (or tree element) to a tree element. */
    public static JsonElement toElement(Object v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        if (v instanceof JsonElement) {
            return (JsonElement) v;
        }
        if (v instanceof String) {
            return new JsonPrimitive((String) v);
        }
        if (v instanceof Number) {
            return new JsonPrimitive((Number) v);
        }
        if (v instanceof Boolean) {
            return new JsonPrimitive((Boolean) v);
        }
        if (v instanceof Character) {
            return new JsonPrimitive((Character) v);
        }
        if (v instanceof Map || v instanceof Collection) {
            return JsonParser.parseString(Json.write(v));
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    /** A tree array of the given strings; null gives an empty array. */
    public static JsonArray strings(Collection<String> values) {
        JsonArray a = new JsonArray();
        if (values != null) {
            for (String s : values) {
                a.add(s);
            }
        }
        return a;
    }

    // ------------------------------------------------------------- readers

    /** True when the key is present and not JSON null. */
    public static boolean has(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull();
    }

    /** String value or null; non-string values are returned as their JSON text. */
    public static String str(JsonObject o, String key) {
        return str(o, key, null);
    }

    /** String value or {@code dflt}. */
    public static String str(JsonObject o, String key, String dflt) {
        if (!has(o, key)) {
            return dflt;
        }
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /** String value that must be present and non-blank. */
    public static String reqStr(JsonObject o, String key) {
        String s = str(o, key);
        if (s == null || s.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter '" + key + "'.");
        }
        return s;
    }

    /** Integer value or {@code dflt}; decimals are truncated. */
    public static int integer(JsonObject o, String key, int dflt) {
        if (!has(o, key)) {
            return dflt;
        }
        try {
            return (int) o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw ToolException.invalid("Parameter '" + key + "' must be an integer.");
        }
    }

    /** Number value or {@code dflt}. */
    public static double number(JsonObject o, String key, double dflt) {
        if (!has(o, key)) {
            return dflt;
        }
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw ToolException.invalid("Parameter '" + key + "' must be a number.");
        }
    }

    /** Boolean value or {@code dflt}; the strings true/1/yes count as true, anything else as false. */
    public static boolean bool(JsonObject o, String key, boolean dflt) {
        if (!has(o, key)) {
            return dflt;
        }
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        String s = e.getAsString().trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    /** Nested object, or null if absent or not an object. */
    public static JsonObject object(JsonObject o, String key) {
        return has(o, key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    /** Array value; a single non-array value becomes a one-element array; absent gives an empty array. */
    public static JsonArray array(JsonObject o, String key) {
        if (!has(o, key)) {
            return new JsonArray();
        }
        JsonElement e = o.get(key);
        if (e.isJsonArray()) {
            return e.getAsJsonArray();
        }
        JsonArray a = new JsonArray();
        a.add(e);
        return a;
    }

    /** List of strings from an array (or a single value); nulls are skipped. */
    public static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(o, key)) {
            if (e != null && !e.isJsonNull()) {
                out.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
            }
        }
        return out;
    }
}
