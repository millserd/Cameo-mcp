package com.cameomcp.mcp;

// Every method has an empty default so a listener only overrides what it shows.
/** Observes protocol activity, for the diagnostics window and the Cameo log. */
public interface McpListener {

    /** A client sent {@code initialize} and was given a new session id. */
    default void sessionStarted(McpSession session) {
    }

    /** A session was closed (HTTP DELETE or server stop); {@code reason} is free text for the log. */
    default void sessionEnded(McpSession session, String reason) {
    }

    /**
     * Called after every JSON-RPC request (tool is null unless method is tools/call).
     * {@code millis} is the wall time spent handling it; {@code errorOrNull} is the error message
     * sent back, or null on success.
     */
    /** Called after every JSON-RPC request (tool is null unless method is tools/call). */
    default void requestHandled(McpSession session, String method, String tool, long millis, String errorOrNull) {
    }

    /** Free-form server log line; {@code level} is "info", "warn" or "error". */
    default void log(String level, String message) {
    }
}
