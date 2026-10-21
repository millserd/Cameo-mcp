package com.cameomcp.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Null-safe accessors for JSON-shaped Java values (maps, lists, leaves). */
public final class JsonPath {

    /** Static methods only. */
    private JsonPath() {
    }

    /**
     * Navigate {@code root} by keys (for maps) or integer indexes (for lists); null when absent.
     *
     * @param root a JSON-shaped value (map, list or leaf), may be null
     * @param path the steps: an Integer indexes a list, anything else is a map key (converted with
     *     String.valueOf)
     * @return the value found, or null when any step is missing, out of range, or not navigable
     */
    public static Object get(Object root, Object... path) {
        Object cur = root;
        for (Object step : path) {
            if (cur == null) {
                return null;
            }
            if (step instanceof Integer && cur instanceof List) {
                List<?> l = (List<?>) cur;
                int idx = (Integer) step;
                cur = idx >= 0 && idx < l.size() ? l.get(idx) : null;
            } else if (cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(String.valueOf(step));
            } else {
                return null;
            }
        }
        return cur;
    }

    /**
     * The value at path as a string (numbers and booleans converted), or null.
     *
     * @param root a JSON-shaped value, may be null
     * @param path the steps, as for {@link #get}
     * @return the value's string form, or null when absent
     */
    public static String str(Object root, Object... path) {
        Object v = get(root, path);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * The value at path as a long; numeric strings are parsed; anything else gives {@code dflt}.
     *
     * @param root a JSON-shaped value, may be null
     * @param dflt the result when the value is missing or not numeric
     * @param path the steps, as for {@link #get}
     * @return the value as a long (decimals are truncated), or {@code dflt}
     */
    public static long num(Object root, long dflt, Object... path) {
        Object v = get(root, path);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }

    /**
     * The value at path as a boolean; "true"/"false" strings are accepted; otherwise {@code dflt}.
     *
     * @param root a JSON-shaped value, may be null
     * @param dflt the result when the value is missing or neither a boolean nor a string
     * @param path the steps, as for {@link #get}
     * @return the boolean value; any string other than "true" (ignoring case) is false
     */
    public static boolean bool(Object root, boolean dflt, Object... path) {
        Object v = get(root, path);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return dflt;
    }

    /**
     * The object at path, or null if absent or not an object.
     *
     * @param root a JSON-shaped value, may be null
     * @param path the steps, as for {@link #get}
     * @return the live map, or null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object root, Object... path) {
        Object v = get(root, path);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /**
     * List at path, or an empty list.
     *
     * @param root a JSON-shaped value, may be null
     * @param path the steps, as for {@link #get}
     * @return the live list, or an immutable empty list when absent or not a list
     */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object root, Object... path) {
        Object v = get(root, path);
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    /**
     * List of maps at path (non-map entries skipped), or an empty list.
     *
     * @param root a JSON-shaped value, may be null
     * @param path the steps, as for {@link #get}
     * @return a new list holding the map entries, possibly empty
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object root, Object... path) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(root, path)) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }
}
