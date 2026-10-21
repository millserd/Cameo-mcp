package com.cameomcp.plugin;

import com.cameomcp.catalog.CatalogDocs;
import com.cameomcp.catalog.CatalogPrompts;
import com.cameomcp.json.Json;
import com.cameomcp.mcp.McpResource;
import com.cameomcp.mcp.McpServer;

/** MCP resources and prompts served by the plugin: agent guide, live status, tool reference, workflows. */
final class Resources {

    /** Static methods only. */
    private Resources() {
    }

    /**
     * Adds the resources (cameo://guide, cameo://tools, cameo://status) and the workflow prompts.
     * Each resource is read on demand, so the tool reference and status always reflect the live server.
     *
     * @param server the server to register with
     * @param runtime the plugin runtime, read on every request for the live tool list and status
     */
    static void register(McpServer server, McpRuntime runtime) {
        server.addResource(new McpResource("cameo://guide", "Cameo MCP agent guide",
                "How to use this server: workflows, conventions, pitfalls.", "text/markdown", CatalogDocs::guide));
        server.addResource(new McpResource("cameo://tools", "Tool reference",
                "Every tool with its parameters, generated from the live catalog.", "text/markdown",
                () -> CatalogDocs.toolReference(runtime.registry().specs())));
        server.addResource(new McpResource("cameo://status", "Live status",
                "Server, Cameo and project status as JSON (same as cameo_status).", "application/json",
                () -> Json.pretty(runtime.status())));
        CatalogPrompts.register(server);
    }
}
