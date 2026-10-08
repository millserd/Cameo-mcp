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

    private Json() {
    }

    // ------------------------------------------------------------------ parse

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

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        JsonException error(String msg) {
            return new JsonException(msg + " at offset " + i);
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                    i++;
                } else {
                    break;
                }
            }
        }

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

        void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw error("Expected '" + word + "'");
            }
            i += word.length();
        }

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

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        for (int k = 0; k < indent * depth; k++) {
            sb.append(' ');
        }
    }

    private static void write(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String || v instanceof Character || v instanceof Enum) {
            quote(sb, v.toString());
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
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
        } else {
            quote(sb, v.toString());
        }
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        quote(sb, s);
        return sb.toString();
    }

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

    /** Start an ordered JSON object: {@code Json.obj("a", 1, "b", "x")}. */
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

    public static List<Object> arr(Object... values) {
        List<Object> l = new ArrayList<>(values.length);
        java.util.Collections.addAll(l, values);
        return l;
    }

    /** Deep copy of a JSON-shaped value (maps and lists are copied, leaves shared). */
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
