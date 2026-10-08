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

    public static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    private final McpServer server;
    private final String host;
    private final int port;
    private final String token;
    private final Supplier<Map<String, Object>> status;
    private HttpServer http;
    private ExecutorService executor;
    private volatile long startedMillis;
    private final AtomicLong httpRequests = new AtomicLong();
    private volatile String lastError;

    /**
     * @param token  bearer token clients must send, or null/blank for none
     * @param status supplier for the {@code /status} payload (may be null)
     */
    public McpHttpTransport(McpServer server, String host, int port, String token, Supplier<Map<String, Object>> status) {
        this.server = server;
        this.host = host == null || host.trim().isEmpty() ? "127.0.0.1" : host.trim();
        this.port = port;
        this.token = token == null || token.trim().isEmpty() ? null : token.trim();
        this.status = status;
    }

    public synchronized void start() throws IOException {
        if (http != null) {
            return;
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 64);
        AtomicInteger n = new AtomicInteger();
        executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "cameo-mcp-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(executor);
        s.createContext("/mcp", this::handleMcp);
        s.createContext("/status", this::handleStatus);
        s.createContext("/health", this::handleStatus);
        s.createContext("/", this::handleRoot);
        s.start();
        http = s;
        startedMillis = System.currentTimeMillis();
    }

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

    public synchronized boolean isRunning() {
        return http != null;
    }

    /** The actual bound port (useful when constructed with port 0). */
    public synchronized int boundPort() {
        return http == null ? port : http.getAddress().getPort();
    }

    public String host() {
        return host;
    }

    public boolean tokenRequired() {
        return token != null;
    }

    public long startedMillis() {
        return startedMillis;
    }

    public long httpRequests() {
        return httpRequests.get();
    }

    public String lastError() {
        return lastError;
    }

    public String endpointUrl() {
        String h = host.equals("0.0.0.0") || host.equals("::") ? "127.0.0.1" : host;
        if (h.contains(":") && !h.startsWith("[")) {
            h = "[" + h + "]";
        }
        return "http://" + h + ":" + boundPort() + "/mcp";
    }

    // ------------------------------------------------------------------ handlers

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
            if (reply.unknownSession) {
                sendJson(ex, 404, Json.obj("jsonrpc", "2.0", "id", null, "error",
                        Json.obj("code", -32001, "message", "Session not found or expired; re-initialize.")));
                return;
            }
            if (reply.sessionId != null && !"implicit".equals(reply.sessionId)) {
                ex.getResponseHeaders().set("Mcp-Session-Id", reply.sessionId);
            }
            if (reply.body == null) {
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

    /** Requests without Origin (CLI clients) pass; browser origins must be local. */
    static boolean isLocalOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return true;
        }
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

    private static boolean originAllowed(HttpExchange ex) {
        return isLocalOrigin(ex.getRequestHeaders().getFirst("Origin"));
    }

    private static void addCors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && isLocalOrigin(origin)) {
            Headers h = ex.getResponseHeaders();
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Access-Control-Expose-Headers", "Mcp-Session-Id");
            h.set("Vary", "Origin");
        }
    }

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

    private static void sendJson(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(body));
    }

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
