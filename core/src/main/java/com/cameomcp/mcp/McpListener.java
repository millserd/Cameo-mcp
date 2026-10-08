package com.cameomcp.mcp;

/** Observes protocol activity, for the diagnostics window and the Cameo log. */
public interface McpListener {

    default void sessionStarted(McpSession session) {
    }

    default void sessionEnded(McpSession session, String reason) {
    }

    /** Called after every JSON-RPC request (tool is null unless method is tools/call). */
    default void requestHandled(McpSession session, String method, String tool, long millis, String errorOrNull) {
    }

    default void log(String level, String message) {
    }
}
