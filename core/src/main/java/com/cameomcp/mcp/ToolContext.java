package com.cameomcp.mcp;

/** Per-call information handed to a {@link ToolHandler}. */
public final class ToolContext {

    /** The spec of the tool being called. */
    private final ToolSpec spec;
    /** The caller's session id, or null when the call has no session. */
    private final String sessionId;
    /** The caller's self-reported client name, or null. */
    private final String clientName;
    /** Set by {@link #cancel()} from another thread and read by the handler, hence volatile. */
    private volatile boolean cancelled;

    /**
     * Creates the context for one call.
     *
     * @param spec the tool being called
     * @param sessionId the caller's session id, or null
     * @param clientName the caller's client name, or null
     */
    public ToolContext(ToolSpec spec, String sessionId, String clientName) {
        this.spec = spec;
        this.sessionId = sessionId;
        this.clientName = clientName;
    }

    /**
     * Returns the spec of the tool being called.
     *
     * @return the spec of the tool being called
     */
    public ToolSpec spec() {
        return spec;
    }

    /**
     * Returns the caller's session id, or null.
     *
     * @return the caller's session id, or null
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns the caller's client name, or null.
     *
     * @return the caller's client name, or null
     */
    public String clientName() {
        return clientName;
    }

    /**
     * True once the call has been cancelled. Long-running handlers may poll this and stop early.
     * Nothing calls {@link #cancel()} yet; the hook is here so handlers can be written against it.
     *
     * @return whether cancellation was requested
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Requests cancellation; the handler decides whether and when to stop. */
    void cancel() {
        cancelled = true;
    }
}
