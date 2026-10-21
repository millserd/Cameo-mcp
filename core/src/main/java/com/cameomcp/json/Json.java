package com.cameomcp.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader and writer.
 * <p>
 * JSON values map to plain Java types: objects to {@code LinkedHashMap<String,Object>},
 * arrays to {@code ArrayList<Object>}, strings to {@code String}, numbers to {@code Long}
 * (integral values that fit) or {@code Double}/{@code BigDecimal}, booleans to {@code Boolean},
 * and null to {@code null}. The plugin deliberately avoids depending on the Gson copy
 * bundled with Cameo so the same code runs on every Cameo release.
 */
public final class Json {

    /** Static methods only. */
    private Json() {
    }

    // ------------------------------------------------------------------ parse

    /**
     * Parses one JSON value. Leading and trailing whitespace (and a byte-order mark) is allowed;
     * anything else after the value is an error.
     *
     * @param text the JSON text
     * @return a Map, List, String, Long, Double, BigDecimal, Boolean, or null for JSON null
     * @throws JsonException with the character offset of the problem
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("No JSON text");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw p.error("Unexpected trailing characters");
        }
        return value;
    }

    /**
     * Parses text that must be a JSON object, such as a request body or a settings file.
     *
     * @param text the JSON text
     * @return the parsed object as an insertion-ordered map
     * @throws JsonException if the text is not valid JSON or its top-level value is not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    /** Recursive-descent parser over the input string. Not thread-safe; one instance per parse. */
    private static final class Parser {
        /** The input text. */
        private final String s;
        /** Read position: index of the next character to examine. */
        private int i;

        /**
         * Creates a parser at offset 0.
         *
         * @param s the text to parse
         */
        Parser(String s) {
            this.s = s;
        }

        /**
         * Returns true once every character has been consumed.
         *
         * @return true once every character has been consumed
         */
        boolean atEnd() {
            return i >= s.length();
        }

        /**
         * Builds a parse error for the current position.
         *
         * @param msg what went wrong
         * @return an exception whose message also gives the current offset
         */
        JsonException error(String msg) {
            return new JsonException(msg + " at offset " + i);
        }

        /** Skips JSON whitespace and any byte-order mark. */
        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                // U+FEFF is the byte-order mark some editors put at the start of saved JSON files.
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                    i++;
                } else {
                    break;
                }
            }
        }

        /**
         * Reads the value starting at the current position. Unlike the tree package's {@code JsonParser},
         * there is no nesting-depth cap; inputs here are local requests and settings files.
         *
         * @return the parsed value (null for JSON null)
         * @throws JsonException on malformed input
         */
        Object readValue() {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("Unexpected character '" + c + "'");
            }
        }

        /**
         * Consumes a literal keyword (true, false, null) or fails.
         *
         * @param word the keyword expected at the current position
         */
        void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw error("Expected '" + word + "'");
            }
            i += word.length();
        }

        /**
         * Reads an object; the current character is the opening brace. A repeated key keeps the last value.
         *
         * @return the members in document order
         */
        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skipWs();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(i) != '"') {
                    throw error("Expected object key");
                }
                String key = readString();
                skipWs();
                if (atEnd() || s.charAt(i) != ':') {
                    throw error("Expected ':'");
                }
                i++;
                skipWs();
                map.put(key, readValue());
                skipWs();
                if (atEnd()) {
                    throw error("Unterminated object");
                }
                char c = s.charAt(i++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        /**
         * Reads an array; the current character is the opening bracket.
         *
         * @return the elements in document order
         */
        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            i++;
            skipWs();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue());
                skipWs();
                if (atEnd()) {
                    throw error("Unterminated array");
                }
                char c = s.charAt(i++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        /**
         * Reads a string literal; the current character is the opening quote. Copies unescaped runs in
         * one go and only builds a StringBuilder when an escape appears.
         *
         * @return the decoded string
         */
        String readString() {
            i++; // opening quote
            StringBuilder sb = null;
            int start = i;
            while (true) {
                if (atEnd()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(i);
                if (c == '"') {
                    String result = sb == null ? s.substring(start, i) : sb.append(s, start, i).toString();
                    i++;
                    return result;
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, start, i);
                    i++;
                    if (atEnd()) {
                        throw error("Unterminated escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw error("Bad unicode escape");
                            }
                            try {
                                // Surrogate pairs arrive as two consecutive escapes and recombine naturally in UTF-16.
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Bad unicode escape");
                            }
                            i += 4;
                            break;
                        default:
                            throw error("Bad escape '\\" + e + "'");
                    }
                    start = i;
                } else {
                    i++;
                }
            }
        }

        /**
         * Scans the number's characters, then converts. Integers that fit in a long stay Long (ids,
         * counts, coordinates compare cleanly); larger integers and out-of-range decimals keep full
         * precision as BigDecimal; other decimals become Double.
         *
         * <p>The scan is lenient (it does not reject leading zeros, and sign characters are accepted anywhere
         * in the run); the conversion step then rejects anything Java cannot parse as a number.
         *
         * @return a Long, BigDecimal or Double
         * @throws JsonException if the scanned characters are not a number
         */
        Object readNumber() {
            int start = i;
            if (s.charAt(i) == '-') {
                i++;
            }
            boolean fractional = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true;
                    i++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, i);
            try {
                if (!fractional) {
                    BigInteger bi = new BigInteger(num);
                    if (bi.bitLength() < 64) {
                        return bi.longValue();
                    }
                    return new BigDecimal(bi);
                }
                double d = Double.parseDouble(num);
                if (Double.isInfinite(d)) {
                    return new BigDecimal(num);
                }
                return d;
            } catch (NumberFormatException ex) {
                throw error("Bad number '" + num + "'");
            }
        }
    }

    // ------------------------------------------------------------------ write

    /**
     * Compact JSON, used on the wire.
     *
     * @param value a JSON-shaped value (see the class comment), or a {@link JsonSerializable}
     * @return compact JSON text with no whitespace
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    /**
     * Two-space indented JSON, used for files and for text shown to people.
     *
     * @param value a JSON-shaped value (see the class comment), or a {@link JsonSerializable}
     * @return JSON text indented by two spaces per level
     */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    /**
     * Starts a new line and indents it, in pretty mode only.
     *
     * @param sb output buffer
     * @param indent spaces per level, or a negative number for compact output (then nothing is written)
     * @param depth nesting level
     */
    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        for (int k = 0; k < indent * depth; k++) {
            sb.append(' ');
        }
    }

    /**
     * Writes any JSON-shaped Java value. Also accepts Character and Enum (written as strings), Float,
     * Object arrays, int and double arrays; any other type is written as its toString() in quotes.
     *
     * @param sb output buffer
     * @param v the value
     * @param indent spaces per level; a negative value means compact output
     * @param depth current nesting level
     */
    private static void write(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String || v instanceof Character || v instanceof Enum) {
            quote(sb, v.toString());
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            // JSON has no NaN or Infinity; null is the conventional stand-in.
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            // Whole doubles print without ".0" so 3.0 and 3 look the same to clients.
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof JsonSerializable) {
            write(sb, ((JsonSerializable) v).toJson(), indent, depth);
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent < 0 ? ":" : ": ");
                write(sb, e.getValue(), indent, depth + 1);
            }
            if (!m.isEmpty()) {
                newline(sb, indent, depth);
            }
            sb.append('}');
        } else if (v instanceof Collection || v instanceof Object[]) {
            Collection<?> c = v instanceof Collection ? (Collection<?>) v : java.util.Arrays.asList((Object[]) v);
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, depth + 1);
                write(sb, o, indent, depth + 1);
            }
            if (!c.isEmpty()) {
                newline(sb, indent, depth);
            }
            sb.append(']');
        } else if (v instanceof int[]) {
            List<Object> l = new ArrayList<>();
            for (int x : (int[]) v) {
                l.add(x);
            }
            write(sb, l, indent, depth);
        } else if (v instanceof double[]) {
            List<Object> l = new ArrayList<>();
            for (double x : (double[]) v) {
                l.add(x);
            }
            write(sb, l, indent, depth);
        // Unknown types (ids, enums of other APIs, ...) fall back to their string form.
        } else {
            quote(sb, v.toString());
        }
    }

    /**
     * Returns {@code s} as a quoted, escaped JSON string literal.
     *
     * @param s the raw string; must not be null
     * @return the string in double quotes with JSON escapes applied
     */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        quote(sb, s);
        return sb.toString();
    }

    /**
     * Appends {@code s} as a quoted, escaped JSON string literal.
     *
     * @param sb output buffer
     * @param s the raw string
     */
    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    // U+2028 and U+2029 are valid in JSON but break JavaScript string literals, so escape them too.
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ builders

    /**
     * Start an ordered JSON object: {@code Json.obj("a", 1, "b", "x")}.
     *
     * @param keyValues alternating keys and values; keys are converted with String.valueOf
     * @return a new insertion-ordered, mutable map
     * @throws IllegalArgumentException if an odd number of arguments is given
     */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < keyValues.length; k += 2) {
            m.put(String.valueOf(keyValues[k]), keyValues[k + 1]);
        }
        return m;
    }

    /**
     * Builds a mutable JSON array from the given values.
     *
     * @param values the elements, in order
     * @return a new mutable list
     */
    public static List<Object> arr(Object... values) {
        List<Object> l = new ArrayList<>(values.length);
        java.util.Collections.addAll(l, values);
        return l;
    }

    /**
     * Deep copy of a JSON-shaped value (maps and lists are copied, leaves shared).
     *
     * @param <T> the value's static type
     * @param value the value to copy; maps and collections are copied recursively (collections become lists)
     * @return the copy, or the same object for leaves
     */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(e.getKey()), copy(e.getValue()));
            }
            return (T) out;
        }
        if (value instanceof Collection) {
            List<Object> out = new ArrayList<>();
            for (Object o : (Collection<?>) value) {
                out.add(copy(o));
            }
            return (T) out;
        }
        return value;
    }
}
