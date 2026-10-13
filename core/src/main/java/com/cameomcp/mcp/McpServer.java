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

    // Newest first: initialize answers with the client's version if listed here, otherwise the first.
    public static final List<String> SUPPORTED_PROTOCOLS =
            Collections.unmodifiableList(Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05"));
    public static final String LATEST_PROTOCOL = SUPPORTED_PROTOCOLS.get(0);

    // Standard JSON-RPC 2.0 error codes.
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    // Sessions silent this long are dropped: clients killed without a DELETE would otherwise stay listed forever.
    public static final long SESSION_IDLE_MILLIS = 60 * 60 * 1000L;

    /** Outcome of handling one HTTP body. {@code body == null} means "202 Accepted, no content". */
    public static final class Reply {
        // "body" is the JSON to send (null for 202); "sessionId" goes in the Mcp-Session-Id response header;
        // "unknownSession" asks the transport to answer 404 so the client re-initializes, as the spec says.
        public final String body;
        public final String sessionId;
        public final boolean unknownSession;

        Reply(String body, String sessionId, boolean unknownSession) {
            this.body = body;
            this.sessionId = sessionId;
            this.unknownSession = unknownSession;
        }
    }

    private final ServerInfo info;
    private final ToolRegistry tools;
    private final Map<String, McpResource> resources = new LinkedHashMap<>();
    private final Map<String, McpPrompt> prompts = new LinkedHashMap<>();
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final List<McpListener> listeners = new CopyOnWriteArrayList<>();
    // Default guard does nothing extra; the plugin installs one that checks the open project and the macro switch.
    private volatile CallGuard guard = new CallGuard() {
    };
    // Off by default: some clients never send the session header, so they share an "implicit" session.
    private volatile boolean requireSession;

    /** Creates a server over a registry whose specs (and ideally handlers) are already registered. */
    public McpServer(ServerInfo info, ToolRegistry tools) {
        this.info = info;
        this.tools = tools;
    }

    /** Installs the hook run around every tool call. */
    public McpServer guard(CallGuard g) {
        this.guard = g;
        return this;
    }

    /** When true, non-initialize requests must carry a known session id (strict spec mode). */
    public McpServer requireSession(boolean strict) {
        this.requireSession = strict;
        return this;
    }

    /** Registers a resource; a later one with the same URI replaces it. */
    public McpServer addResource(McpResource r) {
        resources.put(r.uri(), r);
        return this;
    }

    /** Registers a prompt; a later one with the same name replaces it. */
    public McpServer addPrompt(McpPrompt p) {
        prompts.put(p.name(), p);
        return this;
    }

    /** Adds an observer for sessions, requests and log lines. Safe to call while serving. */
    public void addListener(McpListener l) {
        listeners.add(l);
    }

    public void removeListener(McpListener l) {
        listeners.remove(l);
    }

    public ServerInfo info() {
        return info;
    }

    public ToolRegistry tools() {
        return tools;
    }

    /** Live sessions, for the status window. */
    public Collection<McpSession> sessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /** Forgets a session and notifies listeners. Returns false if the id was unknown. */
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

    /** Ends sessions idle for longer than {@link #SESSION_IDLE_MILLIS}; a returning client gets 404 and re-initializes. */
    void expireIdleSessions(long nowMillis) {
        for (McpSession s : new ArrayList<>(sessions.values())) {
            if (nowMillis - s.lastSeenMillis() > SESSION_IDLE_MILLIS) {
                endSession(s.id(), "idle");
            }
        }
    }

    /** Ends every session, used when the server stops. */
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
     */
    public Reply handle(String body, String sessionId) {
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

    // True if the message, or any message of a batch, is an initialize request.
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

    private static Object idOf(Object msg) {
        return msg instanceof Map ? ((Map<?, ?>) msg).get("id") : null;
    }

    // Response to one message plus the session an initialize created (null otherwise).
    private static final class HandleOutcome {
        final Map<String, Object> response;
        final McpSession session;

        HandleOutcome(Map<String, Object> response, McpSession session) {
            this.response = response;
            this.session = session;
        }
    }

    /*
     * Dispatches one JSON-RPC message. Requests get a response map; notifications get null.
     * Every non-notification is reported to listeners with its duration and any error text.
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

    /*
     * Negotiates the protocol version and records the client's name. Capabilities are fixed: the
     * tool, resource and prompt lists never change at run time, so listChanged is false.
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

    private Map<String, Object> listTools() {
        List<Object> list = new ArrayList<>();
        for (ToolSpec s : tools.specs()) {
            list.add(s.toMcp());
        }
        return Json.obj("tools", list);
    }

    /*
     * Runs one tool. Every failure becomes an isError result rather than a JSON-RPC error, so the
     * agent reads the message. Only a missing name or an unknown tool is a protocol error.
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

    private Map<String, Object> listResources() {
        List<Object> list = new ArrayList<>();
        for (McpResource r : resources.values()) {
            list.add(r.toMcp());
        }
        return Json.obj("resources", list);
    }

    private Map<String, Object> readResource(Map<String, Object> params) {
        String uri = String.valueOf(params.get("uri"));
        McpResource r = resources.get(uri);
        if (r == null) {
            // -32002 is the resource-not-found code defined by the MCP spec.
            throw new RpcError(-32002, "Resource not found: " + uri, Json.obj("uri", uri));
        }
        return Json.obj("contents", Json.arr(Json.obj("uri", uri, "mimeType", r.mimeType(), "text", r.read())));
    }

    private Map<String, Object> listPrompts() {
        List<Object> list = new ArrayList<>();
        for (McpPrompt p : prompts.values()) {
            list.add(p.toMcp());
        }
        return Json.obj("prompts", list);
    }

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

    private static Map<String, Object> errorResponse(Object id, int code, String message, Object data) {
        Map<String, Object> error = Json.obj("code", code, "message", message);
        if (data != null) {
            error.put("data", data);
        }
        return Json.obj("jsonrpc", "2.0", "id", id, "error", error);
    }

    // An RPC failure that becomes a JSON-RPC error response with this code.
    private static final class RpcError extends RuntimeException {
        final int code;
        final Object data;

        RpcError(int code, String message) {
            this(code, message, null);
        }

        RpcError(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }
    }
}
