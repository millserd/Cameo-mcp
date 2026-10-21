package com.cameomcp.mcp;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** One MCP client connection (identified by the Mcp-Session-Id header). */
public final class McpSession {

    // Fields are written by the request thread and read by the status window, hence volatile/atomic.
    /** The session id sent to the client in the {@code Mcp-Session-Id} header. */
    private final String id;
    /** Creation time, epoch milliseconds. */
    private final long createdMillis = System.currentTimeMillis();
    /** Time of the last message, epoch milliseconds; drives idle expiry. */
    private volatile long lastSeenMillis = createdMillis;
    /** Client name from initialize's clientInfo, or "unknown". */
    private volatile String clientName = "unknown";
    /** Client version from initialize's clientInfo, or empty. */
    private volatile String clientVersion = "";
    /** Protocol revision agreed in initialize; null for the implicit session. */
    private volatile String protocolVersion;
    /** Set when the client sends notifications/initialized. */
    private volatile boolean initialized;
    /** JSON-RPC messages handled, including notifications. */
    private final AtomicLong requests = new AtomicLong();
    /** tools/call requests handled. */
    private final AtomicLong toolCalls = new AtomicLong();
    /** Messages that ended in a protocol error or a tool error. */
    private final AtomicLong errors = new AtomicLong();

    /** A session with a fresh random id, as created by initialize. */
    public McpSession() {
        this(UUID.randomUUID().toString());
    }

    /**
     * A session with a fixed id; the server uses "implicit" for clients that skip initialize.
     *
     * @param id the session id
     */
    public McpSession(String id) {
        this.id = id;
    }

    /**
     * Returns the session id.
     *
     * @return the session id
     */
    public String id() {
        return id;
    }

    /**
     * Returns creation time, epoch milliseconds.
     *
     * @return creation time, epoch milliseconds
     */
    public long createdMillis() {
        return createdMillis;
    }

    /**
     * Time of the last request on this session, shown as "last seen" in the status window.
     *
     * @return epoch milliseconds
     */
    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    /**
     * Records activity: updates the last-seen time and counts the message. Called once per JSON-RPC
     * message, including notifications.
     */
    void touch() {
        lastSeenMillis = System.currentTimeMillis();
        requests.incrementAndGet();
    }

    /**
     * Returns the client's self-reported name, or "unknown".
     *
     * @return the client's self-reported name, or "unknown"
     */
    public String clientName() {
        return clientName;
    }

    /**
     * Returns the client's self-reported version, or an empty string.
     *
     * @return the client's self-reported version, or an empty string
     */
    public String clientVersion() {
        return clientVersion;
    }

    /**
     * Records the client identity from initialize's clientInfo; missing values fall back to placeholders.
     *
     * @param name client name, or null
     * @param version client version, or null
     */
    void client(String name, String version) {
        this.clientName = name == null ? "unknown" : name;
        this.clientVersion = version == null ? "" : version;
    }

    /**
     * The protocol revision agreed in initialize (the client's, if supported, else the latest).
     *
     * @return the protocol revision, or null if the session never initialized
     */
    public String protocolVersion() {
        return protocolVersion;
    }

    /**
     * Records the protocol revision agreed in initialize.
     *
     * @param v the revision string
     */
    void protocolVersion(String v) {
        this.protocolVersion = v;
    }

    /**
     * True after the client sent notifications/initialized. Informational only; nothing waits on it.
     *
     * @return whether the initialized notification arrived
     */
    public boolean initialized() {
        return initialized;
    }

    /** Records that the client sent notifications/initialized. */
    void markInitialized() {
        initialized = true;
    }

    /**
     * Total JSON-RPC messages handled on this session.
     *
     * @return the message count
     */
    public long requests() {
        return requests.get();
    }

    /**
     * Returns the number of tools/call requests on this session.
     *
     * @return the number of tools/call requests on this session
     */
    public long toolCalls() {
        return toolCalls.get();
    }

    /** Counts one tools/call request. */
    void countToolCall() {
        toolCalls.incrementAndGet();
    }

    /**
     * Requests on this session that ended in a protocol error or a tool error.
     *
     * @return the error count
     */
    public long errors() {
        return errors.get();
    }

    /** Counts one failed request. */
    void countError() {
        errors.incrementAndGet();
    }
}
