package com.cameomcp.plugin.tools;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolHandler;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonObject;

import java.util.Map;

/** Small helpers for binding tool implementations to catalog specs. */
public final class Bind {

    private Bind() {
    }

    /**
     * Bind a Gson-style implementation. The tool's arguments arrive as a Gson object holding the
     * keys the client sent plus their snake_case/camelCase twins; the returned object becomes the
     * tool's structured result.
     */
    public static void gson(ToolRegistry registry, String name, GsonTool tool) {
        registry.bind(name, gsonHandler(tool));
    }

    /**
     * Adds the other spelling of every top-level key (snake_case and camelCase) when absent,
     * so Gson-style code written against either convention sees what the client sent.
     */
    static JsonObject withAliases(JsonObject in) {
        JsonObject out = in.deepCopy();
        for (String key : in.keySet()) {
            String alt = key.indexOf('_') >= 0 ? camel(key) : snake(key);
            if (!alt.equals(key) && !out.has(alt)) {
                out.add(alt, in.get(key));
            }
        }
        return out;
    }

    // some_name -> someName; a leading underscore is dropped without capitalizing the next letter.
    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = sb.length() > 0;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    // someName -> some_name.
    static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Adapts a Gson-style tool to a core handler; a null result becomes an empty object. */
    public static ToolHandler gsonHandler(GsonTool tool) {
        return (Args args, com.cameomcp.mcp.ToolContext ctx) -> {
            JsonObject result = tool.call(withAliases(Gs.toGson(args)));
            return result == null ? Json.obj() : (Map<?, ?>) Gs.fromGson(result);
        };
    }
}
