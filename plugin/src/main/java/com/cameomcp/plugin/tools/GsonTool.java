package com.cameomcp.plugin.tools;

import com.google.gson.JsonObject;

/**
 * A tool implemented against Gson trees, the style used by the Cameo-facing code.
 * {@link Bind#gson} adapts it to a core {@link com.cameomcp.mcp.ToolHandler}.
 */
@FunctionalInterface
public interface GsonTool {
    /** Runs the tool; {@code params} holds the client's arguments, the result becomes structuredContent. */
    JsonObject call(JsonObject params) throws Exception;
}
