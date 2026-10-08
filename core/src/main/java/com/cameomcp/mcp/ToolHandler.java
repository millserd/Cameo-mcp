package com.cameomcp.mcp;

/**
 * Implementation of one tool. Return a JSON-shaped value (usually a {@code Map}),
 * a {@link ToolResult} for custom content such as images, or throw {@link ToolException}.
 */
@FunctionalInterface
public interface ToolHandler {
    Object call(Args args, ToolContext ctx) throws Exception;
}
