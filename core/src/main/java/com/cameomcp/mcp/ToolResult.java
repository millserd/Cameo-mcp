package com.cameomcp.mcp;

import com.cameomcp.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An MCP {@code CallToolResult}: a list of content blocks plus optional structured content.
 * Handlers that return a plain {@code Map} get it wrapped automatically by {@link #of(Object)}.
 */
public final class ToolResult {

    private final List<Map<String, Object>> content = new ArrayList<>();
    private Object structured;
    private boolean error;

    public static ToolResult of(Object value) {
        if (value instanceof ToolResult) {
            return (ToolResult) value;
        }
        ToolResult r = new ToolResult();
        if (value instanceof String) {
            r.text((String) value);
            return r;
        }
        if (value instanceof Map) {
            r.structured = value;
        } else {
            r.structured = Json.obj("result", value);
        }
        r.text(Json.write(r.structured));
        return r;
    }

    public static ToolResult error(String code, String message, Map<String, Object> details) {
        ToolResult r = new ToolResult();
        Map<String, Object> payload = Json.obj("error", true, "code", code, "message", message);
        if (details != null && !details.isEmpty()) {
            payload.put("details", details);
        }
        r.structured = payload;
        r.error = true;
        r.text(Json.write(payload));
        return r;
    }

    public ToolResult text(String text) {
        content.add(Json.obj("type", "text", "text", text));
        return this;
    }

    /** Base64 image content block (PNG, JPEG, ...). */
    public ToolResult image(String base64, String mimeType) {
        content.add(Json.obj("type", "image", "data", base64, "mimeType", mimeType));
        return this;
    }

    public ToolResult structured(Map<String, Object> value) {
        this.structured = value;
        return this;
    }

    public Object structured() {
        return structured;
    }

    public boolean isError() {
        return error;
    }

    public List<Map<String, Object>> content() {
        return content;
    }

    public Map<String, Object> toMcp() {
        Map<String, Object> out = Json.obj("content", content);
        if (structured instanceof Map) {
            out.put("structuredContent", structured);
        }
        if (error) {
            out.put("isError", true);
        }
        return out;
    }
}
