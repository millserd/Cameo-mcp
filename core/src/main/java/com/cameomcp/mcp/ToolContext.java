package com.cameomcp.mcp;

/** Per-call information handed to a {@link ToolHandler}. */
public final class ToolContext {

    private final ToolSpec spec;
    private final String sessionId;
    private final String clientName;
    private volatile boolean cancelled;

    public ToolContext(ToolSpec spec, String sessionId, String clientName) {
        this.spec = spec;
        this.sessionId = sessionId;
        this.clientName = clientName;
    }

    public ToolSpec spec() {
        return spec;
    }

    public String sessionId() {
        return sessionId;
    }

    public String clientName() {
        return clientName;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
    }
}
