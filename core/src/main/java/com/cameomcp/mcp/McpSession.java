package com.cameomcp.mcp;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** One MCP client connection (identified by the Mcp-Session-Id header). */
public final class McpSession {

    // Fields are written by the request thread and read by the status window, hence volatile/atomic.
    private final String id;
    private final long createdMillis = System.currentTimeMillis();
    private volatile long lastSeenMillis = createdMillis;
    private volatile String clientName = "unknown";
    private volatile String clientVersion = "";
    private volatile String protocolVersion;
    private volatile boolean initialized;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    /** A session with a fresh random id, as created by initialize. */
    public McpSession() {
        this(UUID.randomUUID().toString());
    }

    /** A session with a fixed id; the server uses "implicit" for clients that skip initialize. */
    public McpSession(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public long createdMillis() {
        return createdMillis;
    }

    /** Time of the last request on this session, shown as "last seen" in the status window. */
    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    // Called once per JSON-RPC message, including notifications.
    void touch() {
        lastSeenMillis = System.currentTimeMillis();
        requests.incrementAndGet();
    }

    public String clientName() {
        return clientName;
    }

    public String clientVersion() {
        return clientVersion;
    }

    // Recorded from initialize's clientInfo; missing values fall back to placeholders.
    void client(String name, String version) {
        this.clientName = name == null ? "unknown" : name;
        this.clientVersion = version == null ? "" : version;
    }

    /** The protocol revision agreed in initialize (the client's, if supported, else the latest). */
    public String protocolVersion() {
        return protocolVersion;
    }

    void protocolVersion(String v) {
        this.protocolVersion = v;
    }

    /** True after the client sent notifications/initialized. Informational only; nothing waits on it. */
    public boolean initialized() {
        return initialized;
    }

    void markInitialized() {
        initialized = true;
    }

    /** Total JSON-RPC messages handled on this session. */
    public long requests() {
        return requests.get();
    }

    public long toolCalls() {
        return toolCalls.get();
    }

    void countToolCall() {
        toolCalls.incrementAndGet();
    }

    /** Requests on this session that ended in a protocol error or a tool error. */
    public long errors() {
        return errors.get();
    }

    void countError() {
        errors.incrementAndGet();
    }
}
