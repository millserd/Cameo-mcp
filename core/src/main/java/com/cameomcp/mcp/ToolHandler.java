package com.cameomcp.mcp;

/**
 * Implementation of one tool. Return a JSON-shaped value (usually a {@code Map}),
 * a {@link ToolResult} for custom content such as images, or throw {@link ToolException}.
 */
@FunctionalInterface
public interface ToolHandler {
    /**
     * Runs the tool.
     *
     * @param args the {@code arguments} object of the tools/call request, with typed accessors
     * @param ctx  which tool and session the call belongs to
     * @return a JSON-shaped value that becomes the structured result, or a {@link ToolResult}
     * @throws ToolException for failures the agent should read and act on; any other exception is
     *                       reported as an internal error with its message
     *
     * @throws Exception any other failure; the server reports it as a TOOL_FAILED error result, after
     * unwrapping ExecutionException and InvocationTargetException to the real cause
     */
    Object call(Args args, ToolContext ctx) throws Exception;
}
