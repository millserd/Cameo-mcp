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

    /** MCP content blocks (text, image) in the order the client should show them. */
    private final List<Map<String, Object>> content = new ArrayList<>();
    /** The structuredContent object; only a Map is sent, since MCP requires an object there. */
    private Object structured;
    /** True when the result reports a failure ({@code isError}). */
    private boolean error;

    /**
     * Wraps a handler's return value. A {@code ToolResult} passes through; a {@code String} becomes
     * one text block; a {@code Map} becomes structuredContent plus the same JSON as text (clients
     * that ignore structuredContent still see the data); anything else is wrapped as
     * {@code {"result": value}} first.
     *
     * @param value whatever the handler returned (may be null, which becomes {"result": null})
     * @return the result to send
     */
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

    /**
     * Builds the error result for a failed tool call. Tool failures are results with
     * {@code isError: true}, not JSON-RPC errors, so the agent sees the message and can recover.
     *
     * @param code stable error code
     * @param message actionable message
     * @param details extra fields, or null; omitted when empty
     * @return an error result whose structured content is {error, code, message, details}
     */
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

    /**
     * Appends a text content block.
     *
     * @param text the text
     * @return this result
     */
    public ToolResult text(String text) {
        content.add(Json.obj("type", "text", "text", text));
        return this;
    }

    /**
     * Base64 image content block (PNG, JPEG, ...).
     *
     * @param base64 base64-encoded image bytes
     * @param mimeType image type, for example image/png
     * @return this result
     */
    public ToolResult image(String base64, String mimeType) {
        content.add(Json.obj("type", "image", "data", base64, "mimeType", mimeType));
        return this;
    }

    /**
     * Flags a result whose payload already describes a failure (for example a failed script run).
     *
     * @return this result
     */
    public ToolResult markError() {
        this.error = true;
        return this;
    }

    /**
     * Replaces the structured content.
     *
     * @param value the new structured content
     * @return this result
     */
    public ToolResult structured(Map<String, Object> value) {
        this.structured = value;
        return this;
    }

    /**
     * Returns the structured content (a Map, or whatever {@link #of} wrapped), or null.
     *
     * @return the structured content (a Map, or whatever {@link #of} wrapped), or null
     */
    public Object structured() {
        return structured;
    }

    /**
     * Returns true if this result reports a failure.
     *
     * @return true if this result reports a failure
     */
    public boolean isError() {
        return error;
    }

    /**
     * Returns the live list of content blocks.
     *
     * @return the live list of content blocks
     */
    public List<Map<String, Object>> content() {
        return content;
    }

    /**
     * The CallToolResult object sent back to the client.
     *
     * @return the map with content, structuredContent (when a Map) and isError (when set)
     */
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
