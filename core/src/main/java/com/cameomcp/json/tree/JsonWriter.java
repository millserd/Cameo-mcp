package com.cameomcp.json.tree;

import java.util.Iterator;
import java.util.Map;

/** Writes a tree as JSON text, compact or indented. */
final class JsonWriter {

    private JsonWriter() {
    }

    /** Appends {@code e}; {@code indent} null means compact output. */
    static void write(JsonElement e, StringBuilder out, String indent, int depth) {
        if (e == null || e.isJsonNull()) {
            out.append("null");
        } else if (e.isJsonPrimitive()) {
            Object v = ((JsonPrimitive) e).value();
            if (v instanceof String) {
                string((String) v, out);
            } else {
                out.append(v);
            }
        } else if (e.isJsonArray()) {
            JsonArray a = (JsonArray) e;
            if (a.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            Iterator<JsonElement> it = a.iterator();
            while (it.hasNext()) {
                newline(out, indent, depth + 1);
                write(it.next(), out, indent, depth + 1);
                if (it.hasNext()) {
                    out.append(',');
                }
            }
            newline(out, indent, depth);
            out.append(']');
        } else {
            JsonObject o = (JsonObject) e;
            if (o.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            Iterator<Map.Entry<String, JsonElement>> it = o.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonElement> m = it.next();
                newline(out, indent, depth + 1);
                string(m.getKey(), out);
                out.append(indent == null ? ":" : ": ");
                write(m.getValue(), out, indent, depth + 1);
                if (it.hasNext()) {
                    out.append(',');
                }
            }
            newline(out, indent, depth);
            out.append('}');
        }
    }

    private static void newline(StringBuilder out, String indent, int depth) {
        if (indent != null) {
            out.append('\n');
            for (int i = 0; i < depth; i++) {
                out.append(indent);
            }
        }
    }

    static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    // Control characters and the JavaScript line separators are escaped.
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
