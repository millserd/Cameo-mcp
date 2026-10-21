package com.cameomcp.plugin.tools;

import com.cameomcp.json.tree.JsonObject;

/**
 * A tool implemented against JSON trees, the style used by the Cameo-facing code.
 * {@link Bind#tree} adapts it to a core {@link com.cameomcp.mcp.ToolHandler}.
 */
@FunctionalInterface
public interface TreeTool {
    /**
     * Runs the tool; {@code params} holds the client's arguments, the result becomes structuredContent.
     *
     * @param params the arguments, with snake_case and camelCase aliases added
     * @return the structured result; null becomes an empty object
     * @throws Exception any failure; a ToolException reaches the agent as is
     */
    JsonObject call(JsonObject params) throws Exception;
}
