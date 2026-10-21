package com.cameomcp.json.tree;

import java.io.IOException;
import java.io.Reader;

/**
 * Parses JSON text into a tree. Strict JSON, except that blank input gives {@link JsonNull}.
 * Numbers keep their written form (see {@link JsonPrimitive#getAsString()}).
 */
public final class JsonParser {

    private final String s;
    private int pos;

    private JsonParser(String s) {
        this.s = s;
    }

    /** Parses one JSON value; text after it (other than whitespace) is an error. */
    public static JsonElement parseString(String json) {
        if (json == null) {
            throw new JsonParseException("No JSON text");
        }
        JsonParser p = new JsonParser(json);
        if (p.pos < json.length() && json.charAt(p.pos) == '﻿') {
            p.pos++;
        }
        p.skipWs();
        if (p.pos >= json.length()) {
            return JsonNull.INSTANCE;
        }
        JsonElement v = p.value(0);
        p.skipWs();
        if (p.pos < json.length()) {
            throw p.error("Unexpected text after the JSON value");
        }
        return v;
    }

    /** Reads the whole reader and parses it like {@link #parseString}. */
    public static JsonElement parseReader(Reader reader) {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        try {
            for (int n; (n = reader.read(buf)) > 0; ) {
                sb.append(buf, 0, n);
            }
        } catch (IOException e) {
            throw new JsonParseException("Could not read JSON: " + e.getMessage());
        }
        return parseString(sb.toString());
    }

    private JsonParseException error(String msg) {
        return new JsonParseException(msg + " at character " + pos);
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private JsonElement value(int depth) {
        if (depth > 512) {
            throw error("JSON nested too deeply");
        }
        skipWs();
        if (pos >= s.length()) {
            throw error("Unexpected end of JSON");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return new JsonPrimitive(string());
            case 't': literal("true"); return new JsonPrimitive(Boolean.TRUE);
            case 'f': literal("false"); return new JsonPrimitive(Boolean.FALSE);
            case 'n': literal("null"); return JsonNull.INSTANCE;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("Unexpected character '" + c + "'");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) {
            throw error("Expected " + word);
        }
        pos += word.length();
    }

    private JsonObject object(int depth) {
        JsonObject o = new JsonObject();
        pos++;
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return o;
        }
        while (true) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw error("Expected a member name");
            }
            String key = string();
            skipWs();
            expect(':');
            o.add(key, value(depth + 1));
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ',') {
                pos++;
                continue;
            }
            expect('}');
            return o;
        }
    }

    private JsonArray array(int depth) {
        JsonArray a = new JsonArray();
        pos++;
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return a;
        }
        while (true) {
            a.add(value(depth + 1));
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ',') {
                pos++;
                continue;
            }
            expect(']');
            return a;
        }
    }

    private void expect(char c) {
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("Unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("Control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error("Unterminated escape");
            }
            char e = s.charAt(pos++);
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
                    if (pos + 4 > s.length()) {
                        throw error("Bad unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Bad unicode escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("Bad escape \\" + e);
            }
        }
    }

    private JsonPrimitive number() {
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        int intStart = pos;
        digits();
        if (pos == intStart) {
            throw error("Bad number");
        }
        if (s.charAt(intStart) == '0' && pos - intStart > 1) {
            throw error("Leading zeros are not allowed");
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int fracStart = pos;
            digits();
            if (pos == fracStart) {
                throw error("Bad number");
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            int expStart = pos;
            digits();
            if (pos == expStart) {
                throw error("Bad number");
            }
        }
        return new JsonPrimitive(new LazyNumber(s.substring(start, pos)));
    }

    private void digits() {
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
            pos++;
        }
    }
}
