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

    /** Static methods only. */
    private Internal() {
    }

    /**
     * Invoke a tool and return its structured result; tool errors are rethrown as ToolException.
     *
     * @param tool the tool name
     * @param args the arguments, may be null
     * @return the structured result (wrapped as {"result": ...} when it is not an object)
     * @throws ToolException NOT_READY before the runtime exists, NOT_IMPLEMENTED for an unknown or unbound
     *     tool,
     * or the tool's own error
     *
     * @throws Exception whatever the tool's handler throws
     */
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
        // Goes straight to the handler: no CallGuard, no session counters, and the caller's write session (if any) is shared.
        Object value = handler.call(new Args(args == null ? Json.obj() : args), new ToolContext(spec, null, "internal"));
        ToolResult r = ToolResult.of(value);
        Object structured = r.structured();
        if (structured instanceof Map) {
            return (Map<String, Object>) structured;
        }
        return Json.obj("result", structured);
    }

    /**
     * Same as {@link #call(String, Map)} with arguments given as key/value pairs.
     *
     * @param tool the tool name
     * @param keyValues alternating argument names and values
     * @return the structured result
     * @throws Exception as for {@link #call(String, Map)}
     */
    public static Map<String, Object> call(String tool, Object... keyValues) throws Exception {
        return call(tool, Json.obj(keyValues));
    }
}
