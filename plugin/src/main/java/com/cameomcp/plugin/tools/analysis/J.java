package com.cameomcp.plugin.tools.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Helpers for working with JSON-shaped Java values (Map / List / String / Number / Boolean)
 * with the same semantics the analysis heuristics were written against: Python-style
 * truthiness, {@code a or b} chains, whitespace normalization and tolerant integer parsing.
 */
public final class J {

    /**
     * Whitespace run. Unicode-aware so non-breaking and other Unicode spaces collapse like Python's
     * str.split().
     */
    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Static helpers only. */
    private J() {
    }

    // ------------------------------------------------------------------ builders

    /**
     * Ordered map from key/value pairs.
     *
     * @param kv alternating keys (strings) and values
     * @return a new insertion-ordered map
     * @throws IllegalArgumentException for an odd number of arguments
     */
    public static Map<String, Object> obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /**
     * Mutable list of the given values (Python list literal).
     *
     * @param values the elements
     * @return a new list
     */
    public static List<Object> arr(Object... values) {
        List<Object> l = new ArrayList<>(values.length);
        Collections.addAll(l, values);
        return l;
    }

    /**
     * One verification check entry in the {@code {name, ok, details}} shape used by check reports.
     *
     * @param name check name
     * @param ok whether the check passed
     * @param details supporting data, or null
     * @return a new check map
     */
    public static Map<String, Object> check(String name, boolean ok, Object details) {
        return obj("name", name, "ok", ok, "details", details);
    }

    // ------------------------------------------------------------------ access

    /**
     * True for any map value (a JSON object).
     *
     * @param o any value
     * @return whether o is a Map
     */
    public static boolean isMap(Object o) {
        return o instanceof Map;
    }

    /**
     * The value cast to a JSON object map, or null when it is not a map.
     *
     * @param o any value
     * @return o as a map (not copied), or null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    /**
     * The value as a map, or a new empty map.
     *
     * @param o any value
     * @return o itself when it is a map, otherwise a new empty map
     */
    public static Map<String, Object> mapOrEmpty(Object o) {
        Map<String, Object> m = map(o);
        return m == null ? new LinkedHashMap<>() : m;
    }

    /**
     * Shallow copy of a map (Python {@code dict(x)}); null becomes an empty map.
     *
     * @param o any value
     * @return a new map with the same entries
     */
    public static Map<String, Object> copy(Object o) {
        Map<String, Object> m = map(o);
        return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
    }

    /**
     * Null-safe {@code m.get(key)}: null when {@code m} is not a map.
     *
     * @param m a map, or any other value
     * @param key the key
     * @return the value, or null
     */
    public static Object get(Object m, String key) {
        Map<String, Object> map = map(m);
        return map == null ? null : map.get(key);
    }

    /**
     * Elements of a list-like value ({@code value or ()}); anything else is empty.
     *
     * <p>A List is returned as is, not copied. Other collections and arrays are copied.
     *
     * @param o any value
     * @return the elements; empty for null and non-collection values
     */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        if (o instanceof List) {
            return (List<Object>) o;
        }
        // Other collections (e.g. sets) are copied so callers always get a List; the copy is detached.
        if (o instanceof Collection) {
            return new ArrayList<>((Collection<Object>) o);
        }
        if (o instanceof Object[]) {
            List<Object> out = new ArrayList<>();
            Collections.addAll(out, (Object[]) o);
            return out;
        }
        return new ArrayList<>();
    }

    /**
     * Map entries of a list-like value, skipping non-map entries.
     *
     * @param o any value
     * @return a new list of the map items
     */
    public static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list(o)) {
            Map<String, Object> m = map(item);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ truthiness

    /**
     * Python truthiness: null, false, zero, empty strings and empty collections/maps are false;
     * any other object is true.
     *
     * @param o any value
     * @return the truthiness of o
     */
    public static boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue() != 0.0;
        }
        if (o instanceof CharSequence) {
            return ((CharSequence) o).length() > 0;
        }
        if (o instanceof Collection) {
            return !((Collection<?>) o).isEmpty();
        }
        if (o instanceof Map) {
            return !((Map<?, ?>) o).isEmpty();
        }
        return true;
    }

    /**
     * Python {@code a or b or c}: the first truthy value, else the last value.
     *
     * @param values candidates, in order
     * @return the first truthy value, the last value when none is truthy, or null when there are none
     */
    public static Object or(Object... values) {
        Object last = null;
        for (Object v : values) {
            if (truthy(v)) {
                return v;
            }
            last = v;
        }
        return last;
    }

    /**
     * First truthy value of the given keys, else null.
     *
     * @param m a map, or null
     * @param keys keys to try, in order
     * @return the first truthy value, or null when m is null or none is truthy
     */
    public static Object firstOf(Map<String, Object> m, String... keys) {
        if (m == null) {
            return null;
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (truthy(v)) {
                return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ text

    /**
     * Python {@code str(value)}.
     *
     * <p>Null gives "None", booleans "True" or "False", maps and collections their {@link #repr}.
     *
     * @param o any value
     * @return the string form
     */
    public static String str(Object o) {
        if (o == null) {
            return "None";
        }
        if (o instanceof Boolean) {
            return ((Boolean) o) ? "True" : "False";
        }
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof Map || o instanceof Collection) {
            return repr(o);
        }
        return String.valueOf(o);
    }

    /**
     * Python {@code str(value or "")}.
     *
     * @param o any value
     * @return {@link #str} of a truthy value, else ""
     */
    public static String text(Object o) {
        return truthy(o) ? str(o) : "";
    }

    /**
     * Python {@code " ".join(str(value or "").split())}.
     *
     * @param o any value
     * @return the text with whitespace runs collapsed to single spaces and trimmed
     */
    public static String normalizedText(Object o) {
        String s = text(o).trim();
        if (s.isEmpty()) {
            return "";
        }
        return WS.matcher(s).replaceAll(" ").trim();
    }

    /**
     * Whitespace-normalized, lower-cased text, used as a case-insensitive comparison key.
     *
     * @param o any value
     * @return the comparison key
     */
    public static String normalizedKey(Object o) {
        return lower(normalizedText(o));
    }

    /**
     * Locale-independent lower-casing (avoids e.g. the Turkish dotless i); null-safe.
     *
     * @param s a string, or null
     * @return s in lower case, or null
     */
    public static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /**
     * True for null, empty or whitespace-only strings.
     *
     * @param s a string, or null
     * @return whether s is blank
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Python {@code str.strip(chars)}.
     *
     * @param s a non-null string
     * @param chars the characters to remove from both ends
     * @return the stripped string
     */
    public static String strip(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * Python repr for JSON-shaped values (used in human-readable messages).
     *
     * @param o any value
     * @return the Python-style representation
     */
    public static String repr(Object o) {
        if (o == null) {
            return "None";
        }
        if (o instanceof Boolean) {
            return ((Boolean) o) ? "True" : "False";
        }
        if (o instanceof String) {
            String s = (String) o;
            // Python switches to double quotes when the string contains a single quote but no double quote.
            if (s.contains("'") && !s.contains("\"")) {
                return "\"" + s + "\"";
            }
            return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(repr(String.valueOf(e.getKey()))).append(": ").append(repr(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof Collection) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Collection<?>) o) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(repr(item));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(o);
    }

    // ------------------------------------------------------------------ numbers

    /**
     * Python {@code int(value)} or null when it would raise.
     *
     * <p>Booleans give 0 or 1; finite numbers are truncated toward zero; strings must be whole integers
     * (digit-group underscores allowed) within int range.
     *
     * @param o any value
     * @return the integer, or null when the conversion would fail
     */
    public static Integer safeInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean) {
            return ((Boolean) o) ? 1 : 0;
        }
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            // Truncates toward zero like Python int(float); values beyond int range wrap via the long cast.
            return (int) ((Number) o).longValue();
        }
        if (o instanceof String) {
            // Python int() accepts digit-group underscores ("1_000"); decimals and other text are rejected.
            String s = ((String) o).trim().replace("_", "");
            if (!s.matches("[+-]?\\d+")) {
                return null;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Lenient numeric conversion: numbers, booleans and numeric strings; anything else yields {@code dflt}.
     *
     * @param o any value
     * @param dflt value returned when o cannot be converted
     * @return the number
     */
    public static double toDouble(Object o, double dflt) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof Boolean) {
            return ((Boolean) o) ? 1.0 : 0.0;
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
        return dflt;
    }

    /**
     * Python {@code round(value, digits)} (half-to-even on the exact binary value).
     *
     * @param value the number
     * @param digits decimal places to keep
     * @return the rounded value; NaN and infinities unchanged
     */
    public static double round(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        // new BigDecimal(double) keeps the exact binary value, matching Python's round() results.
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /**
     * Python {@code ==} for JSON values: numbers compare by value, containers deeply.
     *
     * @param a first value
     * @param b second value
     * @return whether the values are equal in the Python sense
     */
    public static boolean pyEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number && b instanceof Number) {
            // Python 1 == 1.0 is true, so mixed Integer/Double values compare numerically.
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !pyEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a;
            List<?> lb = (List<?>) b;
            if (la.size() != lb.size()) {
                return false;
            }
            Iterator<?> ia = la.iterator();
            Iterator<?> ib = lb.iterator();
            while (ia.hasNext()) {
                if (!pyEquals(ia.next(), ib.next())) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    // ------------------------------------------------------------------ collections

    /**
     * Natural-order sorted copy; duplicates and empty strings are kept.
     *
     * @param values strings, without nulls
     * @return a new sorted list
     */
    public static List<String> sorted(Collection<String> values) {
        List<String> out = new ArrayList<>(values);
        Collections.sort(out);
        return out;
    }

    /**
     * Sorted, de-duplicated, non-empty strings.
     *
     * @param values strings, possibly null
     * @return a new sorted list without nulls, empty strings or duplicates
     */
    public static List<String> sortedNonEmpty(Collection<String> values) {
        Set<String> set = new TreeSet<>();
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                set.add(v);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * Python {@code sorted(item for item in values if item)}: keeps duplicates.
     *
     * @param values strings, possibly null
     * @return a new sorted list without nulls or empty strings
     */
    public static List<String> sortedNonEmptyList(Collection<String> values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                out.add(v);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * String list from a list-like parameter value ({@code [str(item) for item in value or ()]}).
     *
     * @param o a list-like value or a single string
     * @return a new list of {@link #str} forms; a bare string becomes a one-item list
     */
    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        // Unlike Python, a bare string is treated as a single item rather than iterated character by character.
        if (o instanceof String) {
            out.add((String) o);
            return out;
        }
        for (Object item : list(o)) {
            out.add(str(item));
        }
        return out;
    }
}
