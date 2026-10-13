package com.cameomcp.mcp;

import com.cameomcp.json.Json;

import java.util.Map;
import java.util.function.Supplier;

/** A readable MCP resource (guide text, live status, ...). */
public final class McpResource {

    private final String uri;
    private final String name;
    private final String description;
    private final String mimeType;
    private final Supplier<String> reader;

    public McpResource(String uri, String name, String description, String mimeType, Supplier<String> reader) {
        this.uri = uri;
        this.name = name;
        this.description = description;
        this.mimeType = mimeType;
        this.reader = reader;
    }

    public String uri() {
        return uri;
    }

    public String read() {
        return reader.get();
    }

    public String mimeType() {
        return mimeType;
    }

    public Map<String, Object> toMcp() {
        return Json.obj("uri", uri, "name", name, "description", description, "mimeType", mimeType);
    }
}
