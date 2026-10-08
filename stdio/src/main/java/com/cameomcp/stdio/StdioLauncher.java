package com.cameomcp.stdio;

import com.cameomcp.Version;
import com.cameomcp.catalog.Catalog;
import com.cameomcp.catalog.CatalogDocs;
import com.cameomcp.catalog.CatalogPrompts;
import com.cameomcp.json.Json;
import com.cameomcp.json.JsonPath;
import com.cameomcp.mcp.McpResource;
import com.cameomcp.mcp.McpServer;
import com.cameomcp.mcp.ServerInfo;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolRegistry;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * stdio transport for MCP clients that cannot speak HTTP.
 * <p>
 * Reads newline-delimited JSON-RPC from stdin and forwards each message to the Cameo
 * plugin's Streamable HTTP endpoint, writing replies to stdout. While Cameo is not running
 * it still answers {@code initialize} and {@code tools/list} from the built-in catalog and
 * returns a clear "start Cameo" error for tool calls, then connects automatically once
 * Cameo is up. It also re-initializes transparently if Cameo restarts.
 *
 * <pre>
 * java -jar cameo-mcp-stdio.jar [--port 18740] [--host 127.0.0.1] [--url http://...] [--token T]
 * java -jar cameo-mcp-stdio.jar --diagnose
 * </pre>
 */
public final class StdioLauncher {

    private final String endpoint;
    private final String token;
    private final PrintStream out;
    private final McpServer offline;
    private final Object writeLock = new Object();
    private final Object sessionLock = new Object();
    private volatile Map<String, Object> clientInitParams;
    private volatile String remoteSession;
    private volatile boolean lastOnline;
    private final int readTimeoutMillis;

    StdioLauncher(String endpoint, String token, PrintStream out, int readTimeoutMillis) {
        this.endpoint = endpoint;
        this.token = token;
        this.out = out;
        this.readTimeoutMillis = readTimeoutMillis;
        String message = "Cameo is not reachable at " + endpoint + ". Ask the user to start Cameo Systems Modeler, "
                + "open a project, and check MCP > MCP Server Status in Cameo (the server must be running on this port).";
        ToolRegistry registry = new ToolRegistry().addSpecs(Catalog.all());
        registry.bindRemaining((args, ctx) -> {
            throw new ToolException("CAMEO_NOT_RUNNING", message);
        });
        offline = new McpServer(new ServerInfo("cameo-mcp", Version.get(),
                Catalog.INSTRUCTIONS + "\n(Connected through the stdio launcher; Cameo was not reachable at startup.)"),
                registry);
        offline.addResource(new McpResource("cameo://guide", "Cameo MCP agent guide",
                "How to use this server: workflows, conventions, pitfalls.", "text/markdown", CatalogDocs::guide));
        offline.addResource(new McpResource("cameo://tools", "Tool reference",
                "Every tool with its parameters.", "text/markdown", () -> CatalogDocs.toolReference(registry.specs())));
        CatalogPrompts.register(offline);
    }

    public static void main(String[] argv) throws Exception {
        String host = env("CAMEO_MCP_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("CAMEO_MCP_PORT", "18740"));
        String url = System.getenv("CAMEO_MCP_URL");
        String token = System.getenv("CAMEO_MCP_TOKEN");
        int timeout = 600;
        boolean diagnose = false;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--port": port = Integer.parseInt(argv[++i]); break;
                case "--host": host = argv[++i]; break;
                case "--url": url = argv[++i]; break;
                case "--token": token = argv[++i]; break;
                case "--timeout": timeout = Integer.parseInt(argv[++i]); break;
                case "--diagnose": diagnose = true; break;
                case "--version":
                    System.out.println("cameo-mcp-stdio " + Version.get());
                    return;
                case "-h":
                case "--help":
                    System.out.println(usage());
                    return;
                default:
                    System.err.println("Unknown option " + a + "\n" + usage());
                    System.exit(2);
            }
        }
        if (url == null) {
            url = "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/mcp";
        }
        if (diagnose) {
            System.exit(diagnose(url, token));
        }
        // stdout belongs to the protocol; anything else printed goes to stderr.
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        System.setOut(System.err);
        new StdioLauncher(url, token, protocolOut, timeout * 1000).serve(System.in);
    }

    static String usage() {
        return String.join("\n",
                "cameo-mcp-stdio " + Version.get() + " - stdio bridge to the Cameo MCP plugin",
                "Usage: java -jar cameo-mcp-stdio.jar [options]",
                "  --port N        plugin port (default 18740, env CAMEO_MCP_PORT)",
                "  --host H        plugin host (default 127.0.0.1, env CAMEO_MCP_HOST)",
                "  --url U         full endpoint URL, overrides host/port (env CAMEO_MCP_URL)",
                "  --token T       bearer token if the plugin requires one (env CAMEO_MCP_TOKEN)",
                "  --timeout S     max seconds to wait for one tool call (default 600)",
                "  --diagnose      check the connection to Cameo and exit",
                "  --version, --help");
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? dflt : v.trim();
    }

    // ------------------------------------------------------------------ serving

    void serve(InputStream in) throws IOException, InterruptedException {
        log("cameo-mcp-stdio " + Version.get() + " forwarding to " + endpoint);
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cameo-mcp-stdio");
            t.setDaemon(true);
            return t;
        });
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String msg = line.trim();
            if (msg.isEmpty()) {
                continue;
            }
            if (msg.contains("\"initialize\"") && isMethod(msg, "initialize")) {
                // Handle initialize synchronously so later messages see the session.
                handle(msg);
            } else {
                pool.execute(() -> handle(msg));
            }
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        closeRemoteSession();
    }

    private static boolean isMethod(String msg, String method) {
        try {
            Object o = Json.parse(msg);
            return o instanceof Map && method.equals(((Map<?, ?>) o).get("method"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    void handle(String msg) {
        String reply;
        try {
            Object parsed = Json.parse(msg);
            if (parsed instanceof Map && "initialize".equals(((Map<?, ?>) parsed).get("method"))) {
                Object params = ((Map<?, ?>) parsed).get("params");
                clientInitParams = params instanceof Map ? (Map<String, Object>) params : Json.obj();
                synchronized (sessionLock) {
                    remoteSession = null;
                }
            }
            reply = forward(msg, parsed);
        } catch (RuntimeException e) {
            reply = offline.handle(msg, null).body;
        }
        if (reply != null) {
            synchronized (writeLock) {
                out.println(reply);
                out.flush();
            }
        }
    }

    /** Send to Cameo; fall back to the offline catalog when Cameo is unreachable. */
    private String forward(String msg, Object parsed) {
        boolean initialize = parsed instanceof Map && "initialize".equals(((Map<?, ?>) parsed).get("method"));
        try {
            if (!initialize) {
                ensureRemoteSession();
            }
            HttpReply r = post(msg, initialize ? null : remoteSession);
            if (r.code == 404 && !initialize) {
                // Cameo restarted and forgot our session: re-initialize and retry once.
                log("Cameo session expired; re-initializing");
                synchronized (sessionLock) {
                    remoteSession = null;
                }
                ensureRemoteSession();
                r = post(msg, remoteSession);
            }
            if (initialize && r.sessionId != null) {
                synchronized (sessionLock) {
                    remoteSession = r.sessionId;
                }
                // The client's own notifications/initialized will follow and be forwarded.
            }
            online(true);
            if (r.code == 202 || r.body == null || r.body.isEmpty()) {
                return null;
            }
            if (r.code == 401) {
                return errorReply(parsed, "Cameo rejected the request (401): the plugin requires a bearer token. "
                        + "Pass --token or set CAMEO_MCP_TOKEN to the token shown in Cameo's MCP Server Status window.");
            }
            if (r.code >= 400 && !r.body.trim().startsWith("{\"jsonrpc\"")) {
                return errorReply(parsed, "Cameo returned HTTP " + r.code + ": " + r.body);
            }
            return r.body;
        } catch (ConnectException e) {
            online(false);
            return offline.handle(msg, null).body;
        } catch (SocketTimeoutException e) {
            return errorReply(parsed, "Timed out waiting for Cameo (" + readTimeoutMillis / 1000 + " s). Cameo may be "
                    + "busy or showing a modal dialog.");
        } catch (IOException e) {
            online(false);
            return offline.handle(msg, null).body;
        }
    }

    private void ensureRemoteSession() throws IOException {
        synchronized (sessionLock) {
            if (remoteSession != null) {
                return;
            }
            Map<String, Object> params = clientInitParams != null ? clientInitParams : Json.obj(
                    "protocolVersion", McpServer.LATEST_PROTOCOL, "capabilities", Json.obj(),
                    "clientInfo", Json.obj("name", "cameo-mcp-stdio", "version", Version.get()));
            HttpReply r = post(Json.write(Json.obj("jsonrpc", "2.0", "id", "cameo-mcp-stdio-init",
                    "method", "initialize", "params", params)), null);
            if (r.code != 200 || r.sessionId == null) {
                throw new IOException("Cameo did not accept initialize (HTTP " + r.code + ")");
            }
            remoteSession = r.sessionId;
            post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", remoteSession);
        }
    }

    private void online(boolean now) {
        if (now != lastOnline) {
            log(now ? "Connected to Cameo at " + endpoint : "Cameo not reachable at " + endpoint + " - serving offline catalog");
            lastOnline = now;
        }
    }

    private void closeRemoteSession() {
        String s = remoteSession;
        if (s == null) {
            return;
        }
        try {
            HttpURLConnection c = open("DELETE");
            c.setRequestProperty("Mcp-Session-Id", s);
            c.getResponseCode();
        } catch (IOException ignored) {
            // Cameo is gone; nothing to close
        }
    }

    private static String errorReply(Object parsed, String message) {
        Object id = parsed instanceof Map ? ((Map<?, ?>) parsed).get("id") : null;
        if (id == null) {
            return null;
        }
        return Json.write(Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", -32000, "message", message)));
    }

    // ------------------------------------------------------------------ HTTP

    static final class HttpReply {
        int code;
        String body;
        String sessionId;
    }

    private HttpURLConnection open(String method) throws IOException {
        URL url = new URL(endpoint);
        boolean loopback;
        try {
            loopback = InetAddress.getByName(url.getHost()).isLoopbackAddress();
        } catch (IOException e) {
            loopback = false;
        }
        HttpURLConnection c = (HttpURLConnection) (loopback ? url.openConnection(Proxy.NO_PROXY) : url.openConnection());
        c.setRequestMethod(method);
        c.setConnectTimeout(2000);
        c.setReadTimeout(readTimeoutMillis);
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        if (token != null && !token.trim().isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        return c;
    }

    HttpReply post(String body, String sessionId) throws IOException {
        HttpURLConnection c = open("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (sessionId != null) {
            c.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        HttpReply r = new HttpReply();
        r.code = c.getResponseCode();
        r.sessionId = c.getHeaderField("Mcp-Session-Id");
        r.body = read(r.code < 400 ? c.getInputStream() : c.getErrorStream());
        return r;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try (InputStream is = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void log(String message) {
        System.err.println("[cameo-mcp-stdio] " + message);
    }

    // ------------------------------------------------------------------ diagnose

    static int diagnose(String endpoint, String token) {
        System.out.println("cameo-mcp-stdio " + Version.get());
        System.out.println("Endpoint: " + endpoint);
        StdioLauncher l = new StdioLauncher(endpoint, token, System.out, 15000);
        try {
            HttpReply r = l.post(Json.write(Json.obj("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
                    Json.obj("protocolVersion", McpServer.LATEST_PROTOCOL, "capabilities", Json.obj(),
                            "clientInfo", Json.obj("name", "cameo-mcp-stdio-diagnose", "version", Version.get())))), null);
            if (r.code == 401) {
                System.out.println("FAIL: the plugin requires a bearer token (use --token or CAMEO_MCP_TOKEN).");
                return 1;
            }
            if (r.code != 200) {
                System.out.println("FAIL: HTTP " + r.code + " " + r.body);
                return 1;
            }
            System.out.println("OK: initialize -> " + JsonPath.str(Json.parse(r.body), "result", "serverInfo", "version")
                    + " (MCP " + JsonPath.str(Json.parse(r.body), "result", "protocolVersion") + ")");
            HttpReply s = l.post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"cameo_status\","
                    + "\"arguments\":{}}}", r.sessionId);
            Object status = JsonPath.get(Json.parse(s.body), "result", "structuredContent");
            System.out.println("Status: " + Json.pretty(status));
            HttpURLConnection c = l.open("DELETE");
            if (r.sessionId != null) {
                c.setRequestProperty("Mcp-Session-Id", r.sessionId);
            }
            c.getResponseCode();
            return 0;
        } catch (ConnectException e) {
            System.out.println("FAIL: nothing is listening at " + endpoint + ". Start Cameo and check "
                    + "MCP > MCP Server Status (server running? same port?).");
            return 1;
        } catch (Exception e) {
            System.out.println("FAIL: " + e);
            return 1;
        }
    }
}
