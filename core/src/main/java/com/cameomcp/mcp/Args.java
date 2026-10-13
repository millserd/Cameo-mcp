package com.cameomcp.mcp;

import com.cameomcp.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed, forgiving access to tool-call arguments.
 * <p>
 * Parameter names are snake_case. As a convenience for agents, a camelCase
 * spelling of the same name ({@code element_id} / {@code elementId}) is accepted too.
 * Getters coerce obvious representations (numeric strings, "true"/"false") and
 * throw {@link ToolException} with code INVALID_PARAMS for anything else.
 */
public final class Args {

    // The arguments object as received; never null.
    private final Map<String, Object> raw;

    /** Wraps a tool call's {@code arguments} object; null means "no arguments". */
    public Args(Map<String, Object> raw) {
        this.raw = raw == null ? Collections.emptyMap() : raw;
    }

    /** Builds arguments from key/value pairs, mainly for tests. */
    public static Args of(Object... keyValues) {
        return new Args(Json.obj(keyValues));
    }

    /** The underlying map, for handlers that pass arguments through unchanged. */
    public Map<String, Object> raw() {
        return raw;
    }

    // element_id -> elementId, so either spelling of a parameter is found.
    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    /** Raw value or null when absent (JSON null counts as absent). */
    public Object get(String name) {
        Object v = raw.get(name);
        if (v == null) {
            String alt = camel(name);
            if (!alt.equals(name)) {
                v = raw.get(alt);
            }
        }
        return v;
    }

    /** True when the parameter is present and not JSON null. */
    public boolean has(String name) {
        return get(name) != null;
    }

    // ---------------------------------------------------------------- strings

    /** String value or null. Numbers and booleans are converted; objects and arrays are rejected. */
    public String str(String name) {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Map || v instanceof List) {
            throw ToolException.invalid("Parameter '" + name + "' must be a string.");
        }
        String s = String.valueOf(v);
        return s;
    }

    /** String value, or {@code dflt} when absent. */
    public String str(String name, String dflt) {
        String s = str(name);
        return s == null ? dflt : s;
    }

    /** String that must be present and non-blank. */
    public String reqStr(String name) {
        String s = str(name);
        if (s == null || s.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter '" + name + "'.");
        }
        return s;
    }

    /** Trimmed string, or null when absent/blank. */
    public String optTrim(String name) {
        String s = str(name);
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    // ---------------------------------------------------------------- numbers

    /**
     * Integer value or null. Numeric strings are accepted; a fractional value is truncated, so
     * {@code "3.0"} works for agents that send every number as a float.
     */
    public Long lng(String name) {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return null;
            }
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw ToolException.invalid("Parameter '" + name + "' must be an integer.");
        }
    }

    /** Integer value or {@code dflt}; values outside the int range are clamped, not wrapped. */
    public int integer(String name, int dflt) {
        Long v = lng(name);
        return v == null ? dflt : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    /** Integer value or null. */
    public Integer integerOrNull(String name) {
        Long v = lng(name);
        return v == null ? null : (int) (long) v;
    }

    /** Integer value that must be present. */
    public int reqInt(String name) {
        Long v = lng(name);
        if (v == null) {
            throw ToolException.invalid("Missing required integer parameter '" + name + "'.");
        }
        return (int) (long) v;
    }

    /** Integer clamped into [min, max]. */
    public int intIn(String name, int dflt, int min, int max) {
        int v = integer(name, dflt);
        return Math.max(min, Math.min(max, v));
    }

    /** Decimal value or null. Numeric strings are accepted. */
    public Double dbl(String name) {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw ToolException.invalid("Parameter '" + name + "' must be a number.");
        }
    }

    /** Decimal value or {@code dflt}. */
    public double dbl(String name, double dflt) {
        Double v = dbl(name);
        return v == null ? dflt : v;
    }

    // ---------------------------------------------------------------- booleans

    /** Boolean value or null. Accepts true/false, yes/no, 1/0 and on/off in any case. */
    public Boolean boolOrNull(String name) {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        switch (s) {
            case "true": case "yes": case "1": case "on":
                return true;
            case "false": case "no": case "0": case "off":
                return false;
            default:
                throw ToolException.invalid("Parameter '" + name + "' must be true or false.");
        }
    }

    /** Boolean value or {@code dflt}. */
    public boolean bool(String name, boolean dflt) {
        Boolean b = boolOrNull(name);
        return b == null ? dflt : b;
    }

    // ---------------------------------------------------------------- structures

    /** Map value or null. A JSON string containing an object is accepted and parsed. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String name) {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        if (v instanceof String && ((String) v).trim().startsWith("{")) {
            return Json.parseObject((String) v);
        }
        throw ToolException.invalid("Parameter '" + name + "' must be an object.");
    }

    /** Object value, or a new empty map when absent. */
    public Map<String, Object> mapOrEmpty(String name) {
        Map<String, Object> m = map(name);
        return m == null ? new LinkedHashMap<>() : m;
    }

    /** A nested object parameter wrapped as {@code Args}, for options such as {@code layout: {...}}. */
    public Args sub(String name) {
        return new Args(map(name));
    }

    /** List value or null. A single scalar becomes a one-element list; a JSON array string is parsed. */
    @SuppressWarnings("unchecked")
    public List<Object> list(String name) {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof List) {
            return (List<Object>) v;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.startsWith("[")) {
                Object parsed = Json.parse(s);
                if (parsed instanceof List) {
                    return (List<Object>) parsed;
                }
            }
        }
        List<Object> one = new ArrayList<>();
        one.add(v);
        return one;
    }

    /** List value, or a new empty list when absent. */
    public List<Object> listOrEmpty(String name) {
        List<Object> l = list(name);
        return l == null ? new ArrayList<>() : l;
    }

    /** List of strings (comma-separated string accepted), never null. */
    public List<String> strList(String name) {
        Object v = get(name);
        List<String> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof String && !((String) v).trim().startsWith("[")) {
            for (String part : ((String) v).split(",")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        for (Object o : list(name)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** List of strings that must have at least one entry. */
    public List<String> reqStrList(String name) {
        List<String> l = strList(name);
        if (l.isEmpty()) {
            throw ToolException.invalid("Parameter '" + name + "' must be a non-empty list.");
        }
        return l;
    }

    /** List of objects, never null. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> maps(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : listOrEmpty(name)) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            } else if (o != null) {
                throw ToolException.invalid("Every entry of '" + name + "' must be an object.");
            }
        }
        return out;
    }

    /** Enum-like string: must be one of the allowed values (case-insensitive); returns canonical value. */
    public String choice(String name, String dflt, String... allowed) {
        String v = str(name);
        if (v == null || v.trim().isEmpty()) {
            return dflt;
        }
        for (String a : allowed) {
            if (a.equalsIgnoreCase(v.trim())) {
                return a;
            }
        }
        throw ToolException.invalid("Parameter '" + name + "' must be one of " + String.join(", ", allowed)
                + " (got '" + v + "').");
    }

    @Override
    public String toString() {
        return Json.write(raw);
    }
}
