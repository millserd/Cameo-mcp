package com.cameomcp.mcp;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** One MCP client connection (identified by the Mcp-Session-Id header). */
public final class McpSession {

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

    public McpSession() {
        this(UUID.randomUUID().toString());
    }

    public McpSession(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public long createdMillis() {
        return createdMillis;
    }

    public long lastSeenMillis() {
        return lastSeenMillis;
    }

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

    void client(String name, String version) {
        this.clientName = name == null ? "unknown" : name;
        this.clientVersion = version == null ? "" : version;
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    void protocolVersion(String v) {
        this.protocolVersion = v;
    }

    public boolean initialized() {
        return initialized;
    }

    void markInitialized() {
        initialized = true;
    }

    public long requests() {
        return requests.get();
    }

    public long toolCalls() {
        return toolCalls.get();
    }

    void countToolCall() {
        toolCalls.incrementAndGet();
    }

    public long errors() {
        return errors.get();
    }

    void countError() {
        errors.incrementAndGet();
    }
}
