package com.cameomcp.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Null-safe accessors for JSON-shaped Java values (maps, lists, leaves). */
public final class JsonPath {

    private JsonPath() {
    }

    /** Navigate {@code root} by keys (for maps) or integer indexes (for lists); null when absent. */
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

    /** The value at path as a string (numbers and booleans converted), or null. */
    public static String str(Object root, Object... path) {
        Object v = get(root, path);
        return v == null ? null : String.valueOf(v);
    }

    /** The value at path as a long; numeric strings are parsed; anything else gives {@code dflt}. */
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

    /** The value at path as a boolean; "true"/"false" strings are accepted; otherwise {@code dflt}. */
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

    /** The object at path, or null if absent or not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object root, Object... path) {
        Object v = get(root, path);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** List at path, or an empty list. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object root, Object... path) {
        Object v = get(root, path);
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    /** List of maps at path (non-map entries skipped), or an empty list. */
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
