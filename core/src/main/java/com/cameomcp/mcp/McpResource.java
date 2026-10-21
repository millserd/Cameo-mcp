package com.cameomcp.mcp;

import com.cameomcp.json.Json;

import java.util.Map;
import java.util.function.Supplier;

/** A readable MCP resource (guide text, live status, ...). */
public final class McpResource {

    /** Resource URI, for example {@code cameo://guide}. */
    private final String uri;
    /** Short display name. */
    private final String name;
    /** What the resource contains. */
    private final String description;
    /** MIME type of the text returned by {@link #read()}. */
    private final String mimeType;
    /** Produces the content on demand. */
    private final Supplier<String> reader;

    /**
     * Creates a resource.
     *
     * @param uri         the address clients pass to resources/read, for example {@code cameo://guide}
     * @param name short display name
     * @param description what the resource contains
     * @param mimeType    usually {@code text/markdown} or {@code application/json}
     * @param reader      produces the content on every read, so live resources stay current
     */
    public McpResource(String uri, String name, String description, String mimeType, Supplier<String> reader) {
        this.uri = uri;
        this.name = name;
        this.description = description;
        this.mimeType = mimeType;
        this.reader = reader;
    }

    /**
     * Returns the resource URI.
     *
     * @return the resource URI
     */
    public String uri() {
        return uri;
    }

    /**
     * Produces the resource's current content.
     *
     * @return the content text
     */
    public String read() {
        return reader.get();
    }

    /**
     * Returns the content MIME type.
     *
     * @return the content MIME type
     */
    public String mimeType() {
        return mimeType;
    }

    /**
     * The entry this resource contributes to a resources/list response.
     *
     * @return the resources/list entry
     */
    public Map<String, Object> toMcp() {
        return Json.obj("uri", uri, "name", name, "description", description, "mimeType", mimeType);
    }
}
