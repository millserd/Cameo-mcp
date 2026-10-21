package com.cameomcp.mcp;

import com.cameomcp.json.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * MCP "Streamable HTTP" transport on the JDK's built-in HTTP server.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code POST /mcp} JSON-RPC requests; replies are {@code application/json}.</li>
 *   <li>{@code DELETE /mcp} ends the session named by {@code Mcp-Session-Id}.</li>
 *   <li>{@code GET /mcp} returns 405: this server never pushes over SSE.</li>
 *   <li>{@code GET /status} (alias {@code /health}) plain JSON health report for humans and scripts.</li>
 * </ul>
 * Binds to loopback by default, rejects browser requests from non-local origins
 * (DNS-rebinding protection required by the MCP spec), and optionally requires a bearer token.
 */
public final class McpHttpTransport {

    /**
     * Largest accepted request body (64 MiB). Generous because bulk-create and import tools take
     * large payloads; larger bodies get 413.
     */
    public static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    /** The protocol engine every POST is handed to. */
    private final McpServer server;
    /** Bind address; defaults to 127.0.0.1. */
    private final String host;
    /** Requested port; 0 picks a free one (see {@link #boundPort()}). */
    private final int port;
    /** Bearer token clients must send, or null when none is required. */
    private final String token;
    /** Builds the /status payload; the plugin supplies Cameo and project state. May be null. */
    private final Supplier<Map<String, Object>> status;
    /** The running server, or null when stopped. Guarded by this object's lock. */
    private HttpServer http;
    /** Request threads of the running server, or null when stopped. */
    private ExecutorService executor;
    /** When the server last started, epoch milliseconds. Feeds the status window's diagnostics. */
    private volatile long startedMillis;
    /** HTTP requests served since construction, across every endpoint. */
    private final AtomicLong httpRequests = new AtomicLong();
    /** The last transport-level failure, or null. */
    private volatile String lastError;

    /**
     * Creates a stopped transport; call {@link #start()} to serve.
     *
     * @param server the protocol engine
     * @param host bind address; null or blank means 127.0.0.1. Anything other than loopback exposes the model
     *     to the network.
     * @param port TCP port, or 0 for any free port
     * @param token bearer token clients must send, or null/blank for none
     * @param status supplier for the {@code /status} payload (may be null)
     */
    public McpHttpTransport(McpServer server, String host, int port, String token, Supplier<Map<String, Object>> status) {
        this.server = server;
        this.host = host == null || host.trim().isEmpty() ? "127.0.0.1" : host.trim();
        this.port = port;
        this.token = token == null || token.trim().isEmpty() ? null : token.trim();
        this.status = status;
    }

    /**
     * Binds the socket and starts serving. Does nothing if already running.
     *
     * @throws IOException if the port is taken or the host cannot be bound
     */
    public synchronized void start() throws IOException {
        if (http != null) {
            return;
        }
        // Backlog 64 queues bursts of parallel calls instead of refusing them.
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 64);
        AtomicInteger n = new AtomicInteger();
        // Eight request threads: plenty for a few local clients. Daemon threads never keep Cameo alive on exit.
        executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "cameo-mcp-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(executor);
        s.createContext("/mcp", this::handleMcp);
        s.createContext("/status", this::handleStatus);
        s.createContext("/health", this::handleStatus);
        // Catch-all context: "/" serves a plain-text banner, anything else 404.
        s.createContext("/", this::handleRoot);
        s.start();
        http = s;
        startedMillis = System.currentTimeMillis();
    }

    /** Stops serving, waits briefly for in-flight requests, and ends every session. */
    public synchronized void stop() {
        if (http == null) {
            return;
        }
        http.stop(0);
        http = null;
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
        server.endAllSessions("server stopped");
    }

    /**
     * Returns true while the server is bound and serving.
     *
     * @return true while the server is bound and serving
     */
    public synchronized boolean isRunning() {
        return http != null;
    }

    /**
     * The actual bound port (useful when constructed with port 0).
     *
     * @return the bound port while running, otherwise the configured port
     */
    public synchronized int boundPort() {
        return http == null ? port : http.getAddress().getPort();
    }

    /**
     * Returns the bind address.
     *
     * @return the bind address
     */
    public String host() {
        return host;
    }

    /**
     * True when clients must send the bearer token.
     *
     * @return whether a bearer token is configured
     */
    public boolean tokenRequired() {
        return token != null;
    }

    /**
     * When the server last started, in epoch milliseconds.
     *
     * @return epoch milliseconds, or 0 if never started
     */
    public long startedMillis() {
        return startedMillis;
    }

    /**
     * HTTP requests served since start, across every endpoint.
     *
     * @return the request count
     */
    public long httpRequests() {
        return httpRequests.get();
    }

    /**
     * The last transport-level failure, or null; shown in the status window.
     *
     * @return a description of the last I/O or runtime failure, or null
     */
    public String lastError() {
        return lastError;
    }

    /**
     * The URL clients should use. A wildcard bind address is reported as 127.0.0.1, which is
     * always reachable locally; IPv6 literals get the brackets URLs require.
     *
     * @return the MCP endpoint URL, for example {@code http://127.0.0.1:18740/mcp}
     */
    public String endpointUrl() {
        String h = host.equals("0.0.0.0") || host.equals("::") ? "127.0.0.1" : host;
        if (h.contains(":") && !h.startsWith("[")) {
            h = "[" + h + "]";
        }
        return "http://" + h + ":" + boundPort() + "/mcp";
    }

    // ------------------------------------------------------------------ handlers

    /**
     * The MCP endpoint. Checks run cheapest and most security-relevant first: path, origin,
     * CORS preflight, token. Then the HTTP verb picks the action. Runs on a transport worker thread.
     *
     * @param ex the exchange; always closed before returning
     * @throws IOException if the response cannot be written
     */
    private void handleMcp(HttpExchange ex) throws IOException {
        httpRequests.incrementAndGet();
        try {
            if (!"/mcp".equals(ex.getRequestURI().getPath()) && !"/mcp/".equals(ex.getRequestURI().getPath())) {
                sendJson(ex, 404, Json.obj("error", "Not found"));
                return;
            }
            if (!originAllowed(ex)) {
                sendJson(ex, 403, Json.obj("error", "Origin not allowed"));
                return;
            }
            addCors(ex);
            String method = ex.getRequestMethod();
            // CORS preflight from a local browser page; answered before the token check, as browsers send no credentials here.
            if ("OPTIONS".equals(method)) {
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, DELETE, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                        "Content-Type, Accept, Authorization, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!authorized(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendJson(ex, 401, Json.obj("error", "Missing or wrong bearer token. Set the Authorization header "
                        + "to 'Bearer <token>' using the token shown in Cameo's MCP Server window."));
                return;
            }
            String sessionId = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if ("GET".equals(method)) {
                ex.getResponseHeaders().set("Allow", "POST, DELETE");
                sendJson(ex, 405, Json.obj("error", "This server does not offer an SSE stream; use POST."));
                return;
            }
            if ("DELETE".equals(method)) {
                boolean ended = server.endSession(sessionId, "client closed session");
                ex.sendResponseHeaders(ended ? 204 : 404, -1);
                return;
            }
            if (!"POST".equals(method)) {
                ex.getResponseHeaders().set("Allow", "POST, DELETE");
                sendJson(ex, 405, Json.obj("error", "Method not allowed"));
                return;
            }
            String body = readBody(ex);
            if (body == null) {
                sendJson(ex, 413, Json.obj("error", "Request body too large"));
                return;
            }
            McpServer.Reply reply = server.handle(body, sessionId);
            // 404 tells the client to re-initialize; -32001 is a server-defined JSON-RPC code for "no such session".
            if (reply.unknownSession) {
                sendJson(ex, 404, Json.obj("jsonrpc", "2.0", "id", null, "error",
                        Json.obj("code", -32001, "message", "Session not found or expired; re-initialize.")));
                return;
            }
            // The implicit session is internal bookkeeping, not something a client can resume.
            if (reply.sessionId != null && !"implicit".equals(reply.sessionId)) {
                ex.getResponseHeaders().set("Mcp-Session-Id", reply.sessionId);
            }
            if (reply.body == null) {
                // A notification or a response from the client: accepted, nothing to send.
                ex.sendResponseHeaders(202, -1);
                return;
            }
            send(ex, 200, "application/json; charset=utf-8", reply.body);
        } catch (IOException e) {
            lastError = e.toString();
            throw e;
        } catch (RuntimeException e) {
            lastError = e.toString();
            sendJson(ex, 500, Json.obj("error", e.toString()));
        } finally {
            ex.close();
        }
    }

    /**
     * Health report for people and scripts. Deliberately unauthenticated: it exposes no model data.
     * Note: the payload map from the supplier must be mutable, because the endpoint and auth flags are added
     * to it.
     *
     * @param ex the exchange; always closed before returning
     * @throws IOException if the response cannot be written
     */
    private void handleStatus(HttpExchange ex) throws IOException {
        httpRequests.incrementAndGet();
        try {
            if (!originAllowed(ex)) {
                sendJson(ex, 403, Json.obj("error", "Origin not allowed"));
                return;
            }
            addCors(ex);
            Map<String, Object> payload = status == null ? Json.obj() : status.get();
            payload.put("endpoint", endpointUrl());
            payload.put("authRequired", token != null);
            sendJson(ex, 200, payload);
        } finally {
            ex.close();
        }
    }

    /**
     * Plain-text banner for someone who opens the server address in a browser; any path other than "/" gets
     * 404.
     *
     * @param ex the exchange; always closed before returning
     * @throws IOException if the response cannot be written
     */
    private void handleRoot(HttpExchange ex) throws IOException {
        httpRequests.incrementAndGet();
        try {
            String text = server.info().name() + " " + server.info().version() + " is running.\n"
                    + "MCP endpoint (Streamable HTTP): " + endpointUrl() + "\n"
                    + "Health: " + endpointUrl().replace("/mcp", "/status") + "\n";
            send(ex, "/".equals(ex.getRequestURI().getPath()) ? 200 : 404, "text/plain; charset=utf-8", text);
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Checks the bearer token. Uses a constant-time comparison so response timing does not leak how much of
     * the token matched.
     *
     * @param ex the exchange
     * @return true if no token is configured or the Authorization header carries the right one
     */
    private boolean authorized(HttpExchange ex) {
        if (token == null) {
            return true;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] given = auth.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Requests without Origin (CLI clients) pass; browser origins must be local.
     *
     * @param origin the Origin header value, or null
     * @return true for no origin, or an http(s) origin on localhost, 127.0.0.1 or ::1
     */
    static boolean isLocalOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        // Sandboxed iframes and file:// pages send the literal origin "null"; never trust it.
        if ("null".equals(origin)) {
            return false;
        }
        try {
            String h = URI.create(origin).getHost();
            if (h == null) {
                return false;
            }
            h = h.toLowerCase();
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Applies {@link #isLocalOrigin} to the request's Origin header (DNS-rebinding protection).
     *
     * @param ex the exchange
     * @return whether the request may proceed
     */
    private static boolean originAllowed(HttpExchange ex) {
        return isLocalOrigin(ex.getRequestHeaders().getFirst("Origin"));
    }

    /**
     * Echoes local origins back so browser-based MCP clients can read responses and the session header.
     *
     * @param ex the exchange whose response headers are set
     */
    private static void addCors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && isLocalOrigin(origin)) {
            Headers h = ex.getResponseHeaders();
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Access-Control-Expose-Headers", "Mcp-Session-Id");
            h.set("Vary", "Origin");
        }
    }

    /**
     * Reads the whole body, giving up once it passes {@link #MAX_BODY_BYTES}.
     *
     * @param ex the exchange
     * @return the body as UTF-8 text, or null when it is too large
     * @throws IOException if reading fails
     */
    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY_BYTES) {
                    return null;
                }
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Sends a JSON response.
     *
     * @param ex the exchange
     * @param code HTTP status
     * @param body the JSON object to send
     * @throws IOException if writing fails
     */
    private static void sendJson(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(body));
    }

    /**
     * Sends a text response with no caching.
     *
     * @param ex the exchange
     * @param code HTTP status
     * @param contentType the Content-Type header value
     * @param text the body
     * @throws IOException if writing fails
     */
    private static void send(HttpExchange ex, int code, String contentType, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
