package com.cameomcp.mcp;

// Every method has an empty default so a listener only overrides what it shows.
/** Observes protocol activity, for the diagnostics window and the Cameo log. */
public interface McpListener {

    /**
     * A client sent {@code initialize} and was given a new session id.
     *
     * @param session the new session
     */
    default void sessionStarted(McpSession session) {
    }

    /**
     * A session was closed (HTTP DELETE or server stop); {@code reason} is free text for the log.
     *
     * @param session the ended session
     * @param reason why it ended: "client closed session", "idle" or "server stopped"
     */
    default void sessionEnded(McpSession session, String reason) {
    }

    /**
     * Called after every JSON-RPC request. Called on the HTTP worker thread that handled it, so
     * implementations that touch Swing must hand off to the EDT themselves.
     *
     * @param session the client's session; null only for messages of a batch that come before its {@code initialize}
     * @param method the JSON-RPC method, for example {@code tools/call}
     * @param tool the tool name for tools/call, otherwise null
     * @param millis wall time spent handling the request
     * @param errorOrNull the error message sent back, or null on success
     */
    default void requestHandled(McpSession session, String method, String tool, long millis, String errorOrNull) {
    }

    /**
     * Free-form server log line.
     *
     * @param level the severity: "info", "warn" or "error"
     * @param message the line to log
     */
    default void log(String level, String message) {
    }
}
