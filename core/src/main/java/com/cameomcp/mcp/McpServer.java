package com.cameomcp.mcp;

import com.cameomcp.json.Json;
import com.cameomcp.json.JsonException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Transport-independent MCP protocol engine (JSON-RPC 2.0).
 * <p>
 * Implements {@code initialize}, {@code ping}, {@code tools/*}, {@code resources/*},
 * {@code prompts/*}, {@code logging/setLevel} and {@code completion/complete}.
 * The HTTP transport ({@link McpHttpTransport}) and the stdio launcher both feed raw
 * JSON-RPC text into {@link #handle(String, String)}.
 */
public final class McpServer {

    /**
     * MCP protocol revisions this server speaks, newest first. {@code initialize} answers with the
     * client's requested version if it is listed here, otherwise with the first (newest) one.
     */
    public static final List<String> SUPPORTED_PROTOCOLS =
            Collections.unmodifiableList(Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05"));
    /** The newest supported protocol revision, offered to clients that ask for an unknown one. */
    public static final String LATEST_PROTOCOL = SUPPORTED_PROTOCOLS.get(0);

    // Standard JSON-RPC 2.0 error codes.
    /** JSON-RPC: the request body is not valid JSON. */
    public static final int PARSE_ERROR = -32700;
    /** JSON-RPC: the message is not a valid request object, or (strict mode) has no session. */
    public static final int INVALID_REQUEST = -32600;
    /** JSON-RPC: the method is not one this server implements. */
    public static final int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC: bad parameters, such as a missing tool name or an unknown tool or prompt. */
    public static final int INVALID_PARAMS = -32602;
    /**
     * JSON-RPC: an unexpected exception while handling a protocol method (tool failures are reported as tool
     * results instead).
     */
    public static final int INTERNAL_ERROR = -32603;

    /**
     * Sessions silent this long (one hour) are dropped: clients killed without a DELETE would
     * otherwise stay listed forever. A client that comes back gets 404 and re-initializes.
     */
    public static final long SESSION_IDLE_MILLIS = 60 * 60 * 1000L;

    /** Outcome of handling one HTTP body. {@code body == null} means "202 Accepted, no content". */
    public static final class Reply {
        /** The JSON to send, or null for "202 Accepted, no content" (notifications only). */
        public final String body;
        /** Value for the {@code Mcp-Session-Id} response header, or null. */
        public final String sessionId;
        /** True asks the transport to answer 404 so the client re-initializes, as the spec says. */
        public final boolean unknownSession;

        /**
         * Creates a reply.
         *
         * @param body JSON text, or null for 202
         * @param sessionId session id for the response header, or null
         * @param unknownSession true to request a 404
         */
        Reply(String body, String sessionId, boolean unknownSession) {
            this.body = body;
            this.sessionId = sessionId;
            this.unknownSession = unknownSession;
        }
    }

    /** Name, version and agent instructions reported by {@code initialize}. */
    private final ServerInfo info;
    /** Tool specs and handlers; read-only after start-up. */
    private final ToolRegistry tools;
    /** Resources by URI, in registration order. Filled during start-up, read while serving. */
    private final Map<String, McpResource> resources = new LinkedHashMap<>();
    /** Prompts by name, in registration order. Filled during start-up, read while serving. */
    private final Map<String, McpPrompt> prompts = new LinkedHashMap<>();
    /** Live sessions by id. Concurrent: HTTP worker threads add and remove sessions. */
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    /** Observers; copy-on-write so the status window can subscribe while requests are being handled. */
    private final List<McpListener> listeners = new CopyOnWriteArrayList<>();
    /**
     * Hook run around every tool call. The default does nothing extra; the plugin installs one that
     * checks the open project and the macro switch.
     */
    private volatile CallGuard guard = new CallGuard() {
    };
    /**
     * When false (the default), requests without a session header share one "implicit" session,
     * because some clients never send the header.
     */
    private volatile boolean requireSession;

    /**
     * Creates a server over a registry whose specs (and ideally handlers) are already registered.
     *
     * @param info server identity and instructions
     * @param tools the registry to serve
     */
    public McpServer(ServerInfo info, ToolRegistry tools) {
        this.info = info;
        this.tools = tools;
    }

    /**
     * Installs the hook run around every tool call.
     *
     * @param g the guard; replaces the previous one
     * @return this server, for chaining
     */
    public McpServer guard(CallGuard g) {
        this.guard = g;
        return this;
    }

    /**
     * When true, non-initialize requests must carry a known session id (strict spec mode).
     *
     * @param strict true for strict spec mode; false (the default) lets header-less clients share an implicit
     *     session
     * @return this server, for chaining
     */
    public McpServer requireSession(boolean strict) {
        this.requireSession = strict;
        return this;
    }

    /**
     * Registers a resource; a later one with the same URI replaces it.
     *
     * @param r the resource
     * @return this server, for chaining
     */
    public McpServer addResource(McpResource r) {
        resources.put(r.uri(), r);
        return this;
    }

    /**
     * Registers a prompt; a later one with the same name replaces it.
     *
     * @param p the prompt
     * @return this server, for chaining
     */
    public McpServer addPrompt(McpPrompt p) {
        prompts.put(p.name(), p);
        return this;
    }

    /**
     * Adds an observer for sessions, requests and log lines. Safe to call while serving.
     *
     * @param l the listener; called on HTTP worker threads
     */
    public void addListener(McpListener l) {
        listeners.add(l);
    }

    /**
     * Removes an observer added with {@link #addListener}.
     *
     * @param l the listener; ignored if not registered
     */
    public void removeListener(McpListener l) {
        listeners.remove(l);
    }

    /**
     * Returns the server's identity, as reported by {@code initialize}.
     *
     * @return the server's identity, as reported by {@code initialize}
     */
    public ServerInfo info() {
        return info;
    }

    /**
     * Returns the tool registry this server serves.
     *
     * @return the tool registry this server serves
     */
    public ToolRegistry tools() {
        return tools;
    }

    /**
     * Live sessions, for the status window.
     *
     * @return a read-only live view of the current sessions
     */
    public Collection<McpSession> sessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Forgets a session and notifies listeners. Returns false if the id was unknown.
     *
     * @param id the session id, may be null
     * @param reason free text passed to {@link McpListener#sessionEnded}, for example "idle"
     * @return true if a session was removed
     */
    public boolean endSession(String id, String reason) {
        McpSession s = id == null ? null : sessions.remove(id);
        if (s != null) {
            for (McpListener l : listeners) {
                l.sessionEnded(s, reason);
            }
            return true;
        }
        return false;
    }

    /**
     * Ends sessions idle for longer than {@link #SESSION_IDLE_MILLIS}; a returning client gets 404 and
     * re-initializes.
     *
     * @param nowMillis the current time, as a parameter so tests can control it
     */
    void expireIdleSessions(long nowMillis) {
        for (McpSession s : new ArrayList<>(sessions.values())) {
            if (nowMillis - s.lastSeenMillis() > SESSION_IDLE_MILLIS) {
                endSession(s.id(), "idle");
            }
        }
    }

    /**
     * Ends every session, used when the server stops.
     *
     * @param reason free text passed to each listener
     */
    public void endAllSessions(String reason) {
        for (String id : new ArrayList<>(sessions.keySet())) {
            endSession(id, reason);
        }
    }

    // ----------------------------------------------------------------- entry

    /**
     * Handle one transport message (a single JSON-RPC message or a batch array).
     *
     * @param body      raw JSON text
     * @param sessionId the {@code Mcp-Session-Id} header value, or null
     * @return the reply to send; see {@link Reply}
     */
    public Reply handle(String body, String sessionId) {
        // Expiry runs on each request instead of on a timer, so an idle server needs no background thread.
        expireIdleSessions(System.currentTimeMillis());
        Object msg;
        try {
            msg = Json.parse(body);
        } catch (JsonException e) {
            return new Reply(Json.write(errorResponse(null, PARSE_ERROR, "Parse error: " + e.getMessage(), null)),
                    sessionId, false);
        }

        McpSession session = sessionId == null ? null : sessions.get(sessionId);
        boolean isInitialize = isInitialize(msg);
        // A session id we don't know (expired or from before a restart): signal 404 so the client starts over.
        if (sessionId != null && session == null && !isInitialize) {
            return new Reply(null, sessionId, true);
        }
        if (session == null && !isInitialize) {
            if (requireSession) {
                return new Reply(Json.write(errorResponse(idOf(msg), INVALID_REQUEST,
                        "Missing Mcp-Session-Id header; call initialize first.", null)), null, false);
            }
            // Lenient mode: requests without a session id share one implicit session so they are still counted.
            session = sessions.computeIfAbsent("implicit", McpSession::new);
        }

        // Batch: an initialize inside the batch creates the session the rest of the batch runs in.
        if (msg instanceof List) {
            List<Object> out = new ArrayList<>();
            String sid = session == null ? null : session.id();
            for (Object m : (List<?>) msg) {
                HandleOutcome o = handleOne(m, session);
                if (o.session != null) {
                    session = o.session;
                    sid = session.id();
                }
                if (o.response != null) {
                    out.add(o.response);
                }
            }
            return new Reply(out.isEmpty() ? null : Json.write(out), sid, false);
        }
        HandleOutcome o = handleOne(msg, session);
        McpSession s = o.session != null ? o.session : session;
        return new Reply(o.response == null ? null : Json.write(o.response), s == null ? null : s.id(), false);
    }

    /**
     * True if the message, or any message of a batch, is an initialize request.
     *
     * @param msg the parsed JSON (map, list or other)
     * @return whether an initialize request is present
     */
    private static boolean isInitialize(Object msg) {
        if (msg instanceof Map) {
            return "initialize".equals(((Map<?, ?>) msg).get("method"));
        }
        if (msg instanceof List) {
            for (Object m : (List<?>) msg) {
                if (isInitialize(m)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The id of a single message, used when an error must be sent before dispatch.
     *
     * @param msg the parsed message
     * @return the JSON-RPC id of a single message, or null for batches and malformed input
     */
    private static Object idOf(Object msg) {
        return msg instanceof Map ? ((Map<?, ?>) msg).get("id") : null;
    }

    /** The response to one message, plus the session an initialize created (null otherwise). */
    private static final class HandleOutcome {
        /** The JSON-RPC response, or null for notifications and client responses. */
        final Map<String, Object> response;
        /** The session created by an initialize request, or null. */
        final McpSession session;

        /**
         * Creates an outcome.
         *
         * @param response the response map, or null
         * @param session the new session, or null
         */
        HandleOutcome(Map<String, Object> response, McpSession session) {
            this.response = response;
            this.session = session;
        }
    }

    /**
     * Dispatches one JSON-RPC message. Requests get a response map; notifications get null.
     * Every non-notification is reported to listeners with its duration and any error text.
     *
     * @param raw one parsed message (should be a map)
     * @param session the session the message belongs to, or null
     * @return the response and any new session
     */
    @SuppressWarnings("unchecked")
    private HandleOutcome handleOne(Object raw, McpSession session) {
        if (!(raw instanceof Map)) {
            return new HandleOutcome(errorResponse(null, INVALID_REQUEST, "Request must be a JSON object", null), null);
        }
        Map<String, Object> msg = (Map<String, Object>) raw;
        Object id = msg.get("id");
        Object methodObj = msg.get("method");
        boolean isNotification = !msg.containsKey("id");

        if (methodObj == null) {
            // A response from the client (we never send requests) - ignore.
            return new HandleOutcome(null, null);
        }
        String method = String.valueOf(methodObj);
        Map<String, Object> params = msg.get("params") instanceof Map
                ? (Map<String, Object>) msg.get("params") : new LinkedHashMap<>();

        long start = System.nanoTime();
        String tool = null;
        String err = null;
        McpSession newSession = null;
        Map<String, Object> response;
        try {
            Object result;
            switch (method) {
                case "initialize":
                    newSession = new McpSession();
                    result = initialize(params, newSession);
                    sessions.put(newSession.id(), newSession);
                    for (McpListener l : listeners) {
                        l.sessionStarted(newSession);
                    }
                    break;
                case "notifications/initialized":
                    if (session != null) {
                        session.markInitialized();
                    }
                    result = null;
                    break;
                // Acknowledged and ignored: tool calls run to completion, and we keep no roots or progress state.
                case "notifications/cancelled":
                case "notifications/roots/list_changed":
                case "notifications/progress":
                    result = null;
                    break;
                case "ping":
                    result = new LinkedHashMap<>();
                    break;
                case "tools/list":
                    result = listTools();
                    break;
                case "tools/call":
                    tool = String.valueOf(params.get("name"));
                    result = callTool(params, session);
                    if (result instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) result).get("isError"))) {
                        Object sc = ((Map<?, ?>) result).get("structuredContent");
                        err = sc instanceof Map ? ((Map<?, ?>) sc).get("code") + ": " + ((Map<?, ?>) sc).get("message")
                                : "tool error";
                    }
                    break;
                case "resources/list":
                    result = listResources();
                    break;
                case "resources/templates/list":
                    result = Json.obj("resourceTemplates", new ArrayList<>());
                    break;
                case "resources/read":
                    result = readResource(params);
                    break;
                case "prompts/list":
                    result = listPrompts();
                    break;
                case "prompts/get":
                    result = getPrompt(params);
                    break;
                // Logging level and completions are accepted for client compatibility but have no effect.
                case "logging/setLevel":
                    result = new LinkedHashMap<>();
                    break;
                case "completion/complete":
                    result = Json.obj("completion", Json.obj("values", new ArrayList<>(), "hasMore", false));
                    break;
                default:
                    throw new RpcError(METHOD_NOT_FOUND, "Method not found: " + method);
            }
            response = isNotification ? null : Json.obj("jsonrpc", "2.0", "id", id, "result", result);
        } catch (RpcError e) {
            err = e.getMessage();
            response = isNotification ? null : errorResponse(id, e.code, e.getMessage(), e.data);
        } catch (RuntimeException e) {
            err = String.valueOf(e);
            response = isNotification ? null : errorResponse(id, INTERNAL_ERROR, "Internal error: " + e, null);
        }

        // The session counters feed the status window.
        McpSession s = newSession != null ? newSession : session;
        if (s != null) {
            s.touch();
            if (err != null) {
                s.countError();
            }
        }
        if (!method.startsWith("notifications/")) {
            long millis = (System.nanoTime() - start) / 1_000_000L;
            for (McpListener l : listeners) {
                l.requestHandled(s, method, tool, millis, err);
            }
        }
        return new HandleOutcome(response, newSession);
    }

    // ----------------------------------------------------------------- methods

    /**
     * Negotiates the protocol version and records the client's name. Capabilities are fixed: the
     * tool, resource and prompt lists never change at run time, so listChanged is false.
     *
     * @param params the initialize params
     * @param session the new session, updated with the agreed version and client info
     * @return the initialize result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> initialize(Map<String, Object> params, McpSession session) {
        String requested = params.get("protocolVersion") == null ? null : String.valueOf(params.get("protocolVersion"));
        String version = requested != null && SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL;
        session.protocolVersion(version);
        Object ci = params.get("clientInfo");
        if (ci instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) ci;
            session.client(m.get("name") == null ? null : String.valueOf(m.get("name")),
                    m.get("version") == null ? null : String.valueOf(m.get("version")));
        }
        Map<String, Object> caps = Json.obj(
                "tools", Json.obj("listChanged", false),
                "resources", Json.obj("subscribe", false, "listChanged", false),
                "prompts", Json.obj("listChanged", false),
                "logging", new LinkedHashMap<>());
        Map<String, Object> result = Json.obj(
                "protocolVersion", version,
                "capabilities", caps,
                "serverInfo", Json.obj("name", info.name(), "title", "Cameo MCP", "version", info.version()));
        if (info.instructions() != null) {
            result.put("instructions", info.instructions());
        }
        return result;
    }

    /**
     * Builds the tools/list result. All tools are returned in one page (no cursor).
     *
     * @return the result with every registered tool spec
     */
    private Map<String, Object> listTools() {
        List<Object> list = new ArrayList<>();
        for (ToolSpec s : tools.specs()) {
            list.add(s.toMcp());
        }
        return Json.obj("tools", list);
    }

    /**
     * Runs one tool. Every failure becomes an isError result rather than a JSON-RPC error, so the
     * agent reads the message. Only a missing name or an unknown tool is a protocol error.
     *
     * @param params the tools/call params ({@code name}, {@code arguments})
     * @param session the caller's session, or null
     * @return the MCP CallToolResult map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params, McpSession session) {
        Object nameObj = params.get("name");
        if (nameObj == null) {
            throw new RpcError(INVALID_PARAMS, "tools/call requires 'name'");
        }
        String name = String.valueOf(nameObj);
        ToolSpec spec = tools.spec(name);
        if (spec == null) {
            throw new RpcError(INVALID_PARAMS, "Unknown tool: " + name
                    + ". Call tools/list (or cameo_list_tools) to see available tools.");
        }
        ToolHandler handler = tools.handler(name);
        Object argsObj = params.get("arguments");
        Map<String, Object> argMap = argsObj instanceof Map ? (Map<String, Object>) argsObj : new LinkedHashMap<>();
        Args args = new Args(argMap);
        if (session != null) {
            session.countToolCall();
        }
        ToolContext ctx = new ToolContext(spec, session == null ? null : session.id(),
                session == null ? null : session.clientName());
        try {
            if (handler == null) {
                throw new ToolException("NOT_IMPLEMENTED", "Tool " + name + " is not available in this build.");
            }
            guard.before(spec, args);
            Object value = guard.invoke(spec, handler, args, ctx);
            return ToolResult.of(value).toMcp();
        } catch (ToolException e) {
            return ToolResult.error(e.code(), e.getMessage(), e.details()).toMcp();
        } catch (Exception e) {
            // Unwrap executor and reflection wrappers so the agent sees the real cause, not "ExecutionException".
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root
                    && (root instanceof java.util.concurrent.ExecutionException
                    || root instanceof java.lang.reflect.InvocationTargetException)) {
                root = root.getCause();
            }
            if (root instanceof ToolException) {
                ToolException te = (ToolException) root;
                return ToolResult.error(te.code(), te.getMessage(), te.details()).toMcp();
            }
            String msg = root.getMessage() == null ? root.getClass().getSimpleName()
                    : root.getClass().getSimpleName() + ": " + root.getMessage();
            for (McpListener l : listeners) {
                l.log("error", "Tool " + name + " failed: " + msg);
            }
            return ToolResult.error("TOOL_FAILED", msg, null).toMcp();
        } catch (Error e) {
            // LinkageError and friends mean an API mismatch with this Cameo version - report, don't crash.
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            for (McpListener l : listeners) {
                l.log("error", "Tool " + name + " hit " + msg);
            }
            return ToolResult.error("API_INCOMPATIBLE",
                    msg + ". This Cameo version may not support this operation.", null).toMcp();
        }
    }

    /**
     * Builds the resources/list result.
     *
     * @return the result listing every registered resource
     */
    private Map<String, Object> listResources() {
        List<Object> list = new ArrayList<>();
        for (McpResource r : resources.values()) {
            list.add(r.toMcp());
        }
        return Json.obj("resources", list);
    }

    /**
     * Reads one resource as text.
     *
     * @param params the resources/read params ({@code uri})
     * @return the result with the resource's current content
     */
    private Map<String, Object> readResource(Map<String, Object> params) {
        String uri = String.valueOf(params.get("uri"));
        McpResource r = resources.get(uri);
        if (r == null) {
            // -32002 is the resource-not-found code defined by the MCP spec.
            throw new RpcError(-32002, "Resource not found: " + uri, Json.obj("uri", uri));
        }
        return Json.obj("contents", Json.arr(Json.obj("uri", uri, "mimeType", r.mimeType(), "text", r.read())));
    }

    /**
     * Builds the prompts/list result.
     *
     * @return the result listing every registered prompt
     */
    private Map<String, Object> listPrompts() {
        List<Object> list = new ArrayList<>();
        for (McpPrompt p : prompts.values()) {
            list.add(p.toMcp());
        }
        return Json.obj("prompts", list);
    }

    /**
     * Renders a prompt as a single user message, after checking its required arguments.
     *
     * @param params the prompts/get params ({@code name}, {@code arguments})
     * @return the result with the prompt description and the rendered message
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getPrompt(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        McpPrompt p = prompts.get(name);
        if (p == null) {
            throw new RpcError(INVALID_PARAMS, "Unknown prompt: " + name);
        }
        Map<String, Object> args = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments") : new LinkedHashMap<>();
        for (Map<String, Object> a : p.arguments()) {
            if (Boolean.TRUE.equals(a.get("required")) && args.get(String.valueOf(a.get("name"))) == null) {
                throw new RpcError(INVALID_PARAMS, "Prompt " + name + " requires argument '" + a.get("name") + "'");
            }
        }
        return Json.obj(
                "description", p.toMcp().get("description"),
                "messages", Json.arr(Json.obj("role", "user",
                        "content", Json.obj("type", "text", "text", p.render(args)))));
    }

    // ----------------------------------------------------------------- errors

    /**
     * Builds a JSON-RPC error response.
     *
     * @param id the request id (may be null when the request could not be read)
     * @param code the JSON-RPC error code
     * @param message human-readable message
     * @param data optional extra data, omitted when null
     * @return the response map
     */
    private static Map<String, Object> errorResponse(Object id, int code, String message, Object data) {
        Map<String, Object> error = Json.obj("code", code, "message", message);
        if (data != null) {
            error.put("data", data);
        }
        return Json.obj("jsonrpc", "2.0", "id", id, "error", error);
    }

    /** An RPC failure that becomes a JSON-RPC error response with this code. */
    private static final class RpcError extends RuntimeException {
        /** JSON-RPC error code. */
        final int code;
        /** Optional error data, or null. */
        final Object data;

        /**
         * Creates an error without data.
         *
         * @param code JSON-RPC error code
         * @param message error message
         */
        RpcError(int code, String message) {
            this(code, message, null);
        }

        /**
         * Creates an error with data.
         *
         * @param code JSON-RPC error code
         * @param message error message
         * @param data optional error data, or null
         */
        RpcError(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }
    }
}
