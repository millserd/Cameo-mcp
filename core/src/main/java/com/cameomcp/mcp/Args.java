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

    /** The arguments object as received; never null. */
    private final Map<String, Object> raw;

    /**
     * Wraps a tool call's {@code arguments} object; null means "no arguments".
     *
     * @param raw the arguments map from the JSON-RPC request; kept by reference, not copied
     */
    public Args(Map<String, Object> raw) {
        this.raw = raw == null ? Collections.emptyMap() : raw;
    }

    /**
     * Builds arguments from key/value pairs, mainly for tests.
     *
     * @param keyValues alternating parameter names and values
     * @return the arguments
     */
    public static Args of(Object... keyValues) {
        return new Args(Json.obj(keyValues));
    }

    /**
     * The underlying map, for handlers that pass arguments through unchanged.
     *
     * @return the live arguments map (an immutable empty map when the call had no arguments)
     */
    public Map<String, Object> raw() {
        return raw;
    }

    /**
     * Converts a snake_case name to camelCase ({@code element_id} to {@code elementId}), so either
     * spelling of a parameter is found.
     *
     * @param snake the snake_case name
     * @return the camelCase spelling; the same string when it has no underscores
     */
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

    /**
     * Raw value or null when absent (JSON null counts as absent).
     *
     * @param name the snake_case parameter name; the camelCase spelling is tried when it is absent
     * @return the raw JSON-shaped value, or null
     */
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

    /**
     * True when the parameter is present and not JSON null.
     *
     * @param name the parameter name
     * @return whether a value was supplied
     */
    public boolean has(String name) {
        return get(name) != null;
    }

    // ---------------------------------------------------------------- strings

    /**
     * String value or null. Numbers and booleans are converted; objects and arrays are rejected.
     *
     * @param name the parameter name
     * @return the string, or null when absent
     * @throws ToolException INVALID_PARAMS if the value is an object or array
     */
    public String str(String name) {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Map || v instanceof List) {
            throw ToolException.invalid("Parameter '" + name + "' must be a string.");
        }
        // Note: a JSON number arrives as Long or Double, so 3.0 sent as a float becomes the text "3.0".
        String s = String.valueOf(v);
        return s;
    }

    /**
     * String value, or {@code dflt} when absent.
     *
     * @param name the parameter name
     * @param dflt value to use when absent
     * @return the string, or {@code dflt}
     * @throws ToolException INVALID_PARAMS if the value is an object or array
     */
    public String str(String name, String dflt) {
        String s = str(name);
        return s == null ? dflt : s;
    }

    /**
     * String that must be present and non-blank.
     *
     * @param name the parameter name
     * @return the string, untrimmed
     * @throws ToolException INVALID_PARAMS if absent, blank, or not a string-like value
     */
    public String reqStr(String name) {
        String s = str(name);
        if (s == null || s.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter '" + name + "'.");
        }
        return s;
    }

    /**
     * Trimmed string, or null when absent/blank.
     *
     * @param name the parameter name
     * @return the trimmed string, or null
     * @throws ToolException INVALID_PARAMS if the value is an object or array
     */
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
     *
     * @param name the parameter name
     * @return the value, or null when absent or a blank string
     * @throws ToolException INVALID_PARAMS if the value is not numeric
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

    /**
     * Integer value or {@code dflt}; values outside the int range are clamped, not wrapped.
     *
     * @param name the parameter name
     * @param dflt value to use when absent
     * @return the value as an int
     * @throws ToolException INVALID_PARAMS if the value is not numeric
     */
    public int integer(String name, int dflt) {
        Long v = lng(name);
        return v == null ? dflt : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    /**
     * Integer value or null.
     *
     * @param name the parameter name
     * @return the value as an Integer (out-of-range values wrap, unlike {@link #integer}), or null
     * @throws ToolException INVALID_PARAMS if the value is not numeric
     */
    public Integer integerOrNull(String name) {
        Long v = lng(name);
        return v == null ? null : (int) (long) v;
    }

    /**
     * Integer value that must be present.
     *
     * @param name the parameter name
     * @return the value as an int (out-of-range values wrap)
     * @throws ToolException INVALID_PARAMS if absent or not numeric
     */
    public int reqInt(String name) {
        Long v = lng(name);
        if (v == null) {
            throw ToolException.invalid("Missing required integer parameter '" + name + "'.");
        }
        return (int) (long) v;
    }

    /**
     * Integer clamped into [min, max].
     *
     * @param name the parameter name
     * @param dflt value to use when absent (also clamped)
     * @param min smallest allowed value
     * @param max largest allowed value
     * @return the value, clamped into the range
     * @throws ToolException INVALID_PARAMS if the value is not numeric
     */
    public int intIn(String name, int dflt, int min, int max) {
        int v = integer(name, dflt);
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Decimal value or null. Numeric strings are accepted.
     *
     * @param name the parameter name
     * @return the value, or null when absent or a blank string
     * @throws ToolException INVALID_PARAMS if the value is not numeric
     */
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

    /**
     * Decimal value or {@code dflt}.
     *
     * @param name the parameter name
     * @param dflt value to use when absent
     * @return the value
     * @throws ToolException INVALID_PARAMS if the value is not numeric
     */
    public double dbl(String name, double dflt) {
        Double v = dbl(name);
        return v == null ? dflt : v;
    }

    // ---------------------------------------------------------------- booleans

    /**
     * Boolean value or null. Accepts true/false, yes/no, 1/0 and on/off in any case.
     *
     * @param name the parameter name
     * @return the value, or null when absent
     * @throws ToolException INVALID_PARAMS for any other value
     */
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

    /**
     * Boolean value or {@code dflt}.
     *
     * @param name the parameter name
     * @param dflt value to use when absent
     * @return the value
     * @throws ToolException INVALID_PARAMS if the value is not a recognized boolean spelling
     */
    public boolean bool(String name, boolean dflt) {
        Boolean b = boolOrNull(name);
        return b == null ? dflt : b;
    }

    // ---------------------------------------------------------------- structures

    /**
     * Map value or null. A JSON string containing an object is accepted and parsed.
     *
     * @param name the parameter name
     * @return the live map, or null when absent
     * @throws ToolException INVALID_PARAMS if the value is neither an object nor a string holding one
     * @throws com.cameomcp.json.JsonException if a string value starts like an object but is malformed
     */
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

    /**
     * Object value, or a new empty map when absent.
     *
     * @param name the parameter name
     * @return the map; a fresh mutable map when absent
     * @throws ToolException INVALID_PARAMS if the value is not an object
     */
    public Map<String, Object> mapOrEmpty(String name) {
        Map<String, Object> m = map(name);
        return m == null ? new LinkedHashMap<>() : m;
    }

    /**
     * A nested object parameter wrapped as {@code Args}, for options such as {@code layout: {...}}.
     *
     * @param name the parameter name
     * @return the nested arguments; empty when absent
     * @throws ToolException INVALID_PARAMS if the value is not an object
     */
    public Args sub(String name) {
        return new Args(map(name));
    }

    /**
     * List value or null. A single scalar becomes a one-element list; a JSON array string is parsed.
     *
     * @param name the parameter name
     * @return the live list, or null when absent
     * @throws com.cameomcp.json.JsonException if a string value starts like an array but is malformed
     */
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

    /**
     * List value, or a new empty list when absent.
     *
     * @param name the parameter name
     * @return the list; a fresh mutable list when absent
     */
    public List<Object> listOrEmpty(String name) {
        List<Object> l = list(name);
        return l == null ? new ArrayList<>() : l;
    }

    /**
     * List of strings (comma-separated string accepted), never null.
     *
     * @param name the parameter name
     * @return the trimmed, non-empty entries of a comma-separated string, or each list entry's string form
     *     (nulls skipped)
     */
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

    /**
     * List of strings that must have at least one entry.
     *
     * @param name the parameter name
     * @return the entries, as for {@link #strList}
     * @throws ToolException INVALID_PARAMS if the list is absent or empty
     */
    public List<String> reqStrList(String name) {
        List<String> l = strList(name);
        if (l.isEmpty()) {
            throw ToolException.invalid("Parameter '" + name + "' must be a non-empty list.");
        }
        return l;
    }

    /**
     * List of objects, never null.
     *
     * @param name the parameter name
     * @return the objects, in order (null entries skipped); empty when absent
     * @throws ToolException INVALID_PARAMS if any entry is not an object
     */
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

    /**
     * Enum-like string: must be one of the allowed values (case-insensitive); returns canonical value.
     *
     * @param name the parameter name
     * @param dflt value to use when absent or blank (returned as given, not validated)
     * @param allowed the accepted values in their canonical spelling
     * @return the matching entry of {@code allowed}, or {@code dflt}
     * @throws ToolException INVALID_PARAMS if the value matches none of them
     */
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
