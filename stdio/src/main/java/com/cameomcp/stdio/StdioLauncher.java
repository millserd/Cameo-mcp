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
 * java -jar cameo-mcp-stdio.jar --install | --uninstall [--cameo-home DIR]... [--all]
 * </pre>
 */
public final class StdioLauncher {

    /** Plugin endpoint every message is forwarded to, e.g. http://127.0.0.1:18740/mcp. */
    private final String endpoint;
    /** Bearer token sent to the plugin, or null. */
    private final String token;
    /** The real stdout, reserved for protocol replies. */
    private final PrintStream out;
    /** Local stand-in server used while Cameo is unreachable: full catalog, every tool call fails clearly. */
    private final McpServer offline;
    /** Replies come from worker threads; one lock keeps each reply on its own line. */
    private final Object writeLock = new Object();
    /** Guards remoteSession so only one thread re-initializes with Cameo at a time. */
    private final Object sessionLock = new Object();
    /** The client's own initialize params, replayed when we reconnect so Cameo sees the real client name. */
    private volatile Map<String, Object> clientInitParams;
    /** Mcp-Session-Id that Cameo gave us; null until the first successful initialize. */
    private volatile String remoteSession;
    /** Last known reachability, so "connected"/"not reachable" is logged only on change. */
    private volatile boolean lastOnline;
    /** Read timeout for one HTTP exchange with the plugin, in milliseconds. */
    private final int readTimeoutMillis;

    /**
     * Creates a launcher and its offline stand-in server (catalog, guide and tool reference resources,
     * prompts).
     *
     * @param endpoint          plugin URL to forward to
     * @param token             bearer token, or null
     * @param out               where protocol replies are written (stdout in production)
     * @param readTimeoutMillis longest wait for one reply; tool calls such as reports can take minutes
     */
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

    /**
     * Entry point. Options override the CAMEO_MCP_* environment variables, which override the
     * defaults. Exits with status 2 on an unknown option.
     *
     * @param argv command-line options; see {@link #usage()}
     * @throws Exception if a numeric option is not a number, or stdio fails
     */
    public static void main(String[] argv) throws Exception {
        String host = env("CAMEO_MCP_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("CAMEO_MCP_PORT", "18740"));
        String url = System.getenv("CAMEO_MCP_URL");
        String token = System.getenv("CAMEO_MCP_TOKEN");
        int timeout = 600;
        boolean diagnose = false;
        Boolean install = null;
        boolean all = false;
        java.util.List<String> homes = new java.util.ArrayList<>();
        java.nio.file.Path from = null;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--port": port = Integer.parseInt(argv[++i]); break;
                case "--host": host = argv[++i]; break;
                case "--url": url = argv[++i]; break;
                case "--token": token = argv[++i]; break;
                case "--timeout": timeout = Integer.parseInt(argv[++i]); break;
                case "--diagnose": diagnose = true; break;
                case "--install": install = Boolean.TRUE; break;
                case "--uninstall": install = Boolean.FALSE; break;
                case "--cameo-home": homes.add(argv[++i]); break;
                case "--all": all = true; break;
                case "--from": from = java.nio.file.Paths.get(argv[++i]); break;
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
        if (install != null) {
            // Installer.run's exit status ends the process; nothing below runs for --install/--uninstall.
            System.exit(Installer.run(!install, homes, all, from != null ? from : Installer.defaultSource(),
                    System.out));
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

    /**
     * Help text printed by --help and after an unknown option.
     *
     * @return the multi-line usage text
     */
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
                "  --install       copy the plugin into Cameo's plugins folder (close Cameo first)",
                "  --uninstall     remove the plugin from Cameo",
                "  --cameo-home D  Cameo install folder for --install/--uninstall (repeatable; default: search)",
                "  --all           with several Cameo installs found, use all of them",
                "  --from D        plugin folder to install (default: found next to this jar)",
                "  --version, --help");
    }

    /**
     * Reads an environment variable's trimmed value.
     *
     * @param name variable name
     * @param dflt value when unset or blank
     * @return the value, or {@code dflt}
     */
    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? dflt : v.trim();
    }

    // ------------------------------------------------------------------ serving

    /**
     * Reads messages from {@code in} until end of input, handling each on a worker thread so a slow
     * tool call doesn't block pings or other requests. Closes the Cameo session on exit.
     *
     * @param in the client's stdin
     * @throws IOException if reading stdin fails
     * @throws InterruptedException if interrupted while waiting for in-flight calls (up to 30 s) at end of
     *     input
     */
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
            // The cheap contains() check avoids parsing every message just to find initialize.
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

    /**
     * Tells whether a message is a JSON-RPC message with the given method.
     *
     * @param msg the raw message text
     * @param method the method name to test for
     * @return true if it parses as an object with that method; false on any parse error
     */
    private static boolean isMethod(String msg, String method) {
        try {
            Object o = Json.parse(msg);
            return o instanceof Map && method.equals(((Map<?, ?>) o).get("method"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Handles one client message and writes the reply, if any. Remembers initialize params for later
     * reconnects. Unparseable input is answered by the offline server, which produces the proper
     * JSON-RPC parse error.
     *
     * @param msg one raw JSON-RPC message (or batch) from the client
     */
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

    /**
     * Send to Cameo; fall back to the offline catalog when Cameo is unreachable.
     *
     * @param msg the raw message, forwarded unchanged
     * @param parsed the parsed message, used to recognize initialize and to build error replies
     * @return the reply text, or null when no reply is due (notifications, 202 answers)
     */
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

    /**
     * Opens a Cameo session on demand: after start-up, after Cameo restarted, or when the client sent
     * its initialize while Cameo was down. Uses the client's initialize params when we have them.
     *
     * @throws IOException if Cameo is unreachable or refuses the initialize
     */
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

    /**
     * Logs only transitions between reachable and unreachable.
     *
     * @param now whether the last exchange reached Cameo
     */
    private void online(boolean now) {
        if (now != lastOnline) {
            log(now ? "Connected to Cameo at " + endpoint : "Cameo not reachable at " + endpoint + " - serving offline catalog");
            lastOnline = now;
        }
    }

    /** Best-effort DELETE so Cameo's status window doesn't keep listing a client that has gone. */
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

    /**
     * Builds a JSON-RPC error reply (code -32000) for a request.
     *
     * @param parsed the parsed request
     * @param message the error message
     * @return the reply text; null for a notification, which must get no reply
     */
    private static String errorReply(Object parsed, String message) {
        Object id = parsed instanceof Map ? ((Map<?, ?>) parsed).get("id") : null;
        if (id == null) {
            return null;
        }
        return Json.write(Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", -32000, "message", message)));
    }

    // ------------------------------------------------------------------ HTTP

    /** Status, body and Mcp-Session-Id header of one HTTP exchange. */
    static final class HttpReply {
        /** HTTP status code. */
        int code;
        /** Response body (the error stream for 4xx/5xx), or null. */
        String body;
        /** The Mcp-Session-Id response header, or null. */
        String sessionId;
    }

    /**
     * Opens a connection to the endpoint. Loopback addresses bypass any system proxy: corporate proxy
     * settings would otherwise route 127.0.0.1 through the proxy and fail. Connect timeout is 2 s.
     *
     * @param method the HTTP method
     * @return an unconnected request with Accept and Authorization headers set
     * @throws IOException if the endpoint URL is malformed or the connection cannot be created
     */
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
        // The Streamable HTTP spec requires clients to accept both; this server only ever answers JSON.
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        if (token != null && !token.trim().isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        return c;
    }

    /**
     * Sends one JSON-RPC body and returns the reply. Error statuses still carry a JSON body worth reading.
     *
     * @param body the JSON-RPC text
     * @param sessionId the Mcp-Session-Id to send, or null
     * @return the status, body and session header
     * @throws IOException on connection failures and timeouts (ConnectException, SocketTimeoutException)
     */
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

    /**
     * Reads a stream to the end as UTF-8 and closes it.
     *
     * @param in the stream, may be null
     * @return the text, or null when {@code in} is null
     * @throws IOException if reading fails
     */
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

    /**
     * Logs to stderr, which MCP clients show in their server log; stdout carries only protocol.
     *
     * @param message the line to log
     */
    private static void log(String message) {
        System.err.println("[cameo-mcp-stdio] " + message);
    }

    // ------------------------------------------------------------------ diagnose

    /**
     * The --diagnose check: initialize, call cameo_status, print the result, close the session.
     *
     * @param endpoint the plugin URL
     * @param token bearer token, or null
     * @return process exit code, 0 when Cameo answered
     */
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
