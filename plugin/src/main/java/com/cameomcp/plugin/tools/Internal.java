package com.cameomcp.plugin.tools;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolContext;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolHandler;
import com.cameomcp.mcp.ToolResult;
import com.cameomcp.mcp.ToolSpec;
import com.cameomcp.plugin.McpRuntime;

import java.util.Map;

/**
 * Call another tool in-process by name, exactly as an MCP client would.
 * Composite tools (verification, methodology workflows, ...) build on the public tool
 * contract this way instead of on another family's internals.
 */
public final class Internal {

    private Internal() {
    }

    /** Invoke a tool and return its structured result; tool errors are rethrown as ToolException. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> call(String tool, Map<String, Object> args) throws Exception {
        McpRuntime rt = McpRuntime.get();
        if (rt == null) {
            throw new ToolException("NOT_READY", "Cameo MCP runtime is not initialized.");
        }
        ToolSpec spec = rt.registry().spec(tool);
        ToolHandler handler = rt.registry().handler(tool);
        if (spec == null || handler == null) {
            throw new ToolException("NOT_IMPLEMENTED", "Internal tool " + tool + " is not available.");
        }
        Object value = handler.call(new Args(args == null ? Json.obj() : args), new ToolContext(spec, null, "internal"));
        ToolResult r = ToolResult.of(value);
        Object structured = r.structured();
        if (structured instanceof Map) {
            return (Map<String, Object>) structured;
        }
        return Json.obj("result", structured);
    }

    public static Map<String, Object> call(String tool, Object... keyValues) throws Exception {
        return call(tool, Json.obj(keyValues));
    }
}
