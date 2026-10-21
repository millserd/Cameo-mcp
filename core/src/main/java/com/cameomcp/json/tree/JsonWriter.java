package com.cameomcp.json.tree;

import java.util.Iterator;
import java.util.Map;

/** Writes a tree as JSON text, compact or indented. */
final class JsonWriter {

    /** Static methods only. */
    private JsonWriter() {
    }

    /**
     * Appends {@code e}; {@code indent} null means compact output.
     *
     * @param e the element; null is written as JSON null
     * @param out where the text goes
     * @param indent the string for one level of indentation, or null for compact output
     * @param depth current nesting level, used for indentation
     */
    static void write(JsonElement e, StringBuilder out, String indent, int depth) {
        if (e == null || e.isJsonNull()) {
            out.append("null");
        } else if (e.isJsonPrimitive()) {
            Object v = ((JsonPrimitive) e).value();
            if (v instanceof String) {
                string((String) v, out);
            } else {
                // Numbers print through toString(): a LazyNumber keeps its original text. A Double NaN or
                // infinity would print as-is, which is not valid JSON; tool code does not create such values.
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

    /**
     * Starts a new line and indents it, in pretty mode only.
     *
     * @param out where the text goes
     * @param indent one level of indentation, or null for compact output (then nothing is written)
     * @param depth number of levels to indent
     */
    private static void newline(StringBuilder out, String indent, int depth) {
        if (indent != null) {
            out.append('\n');
            for (int i = 0; i < depth; i++) {
                out.append(indent);
            }
        }
    }

    /**
     * Appends a quoted, escaped JSON string.
     *
     * @param s the raw string
     * @param out where the text goes
     */
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
