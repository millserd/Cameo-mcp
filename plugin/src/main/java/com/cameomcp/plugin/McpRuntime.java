package com.cameomcp.plugin;

import com.cameomcp.Version;
import com.cameomcp.catalog.Catalog;
import com.cameomcp.json.Json;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.CallGuard;
import com.cameomcp.mcp.McpHttpTransport;
import com.cameomcp.mcp.McpListener;
import com.cameomcp.mcp.McpServer;
import com.cameomcp.mcp.McpSession;
import com.cameomcp.mcp.ServerInfo;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import com.cameomcp.plugin.cameo.CameoInfo;
import com.cameomcp.plugin.tools.ToolBindings;

import java.net.BindException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The running MCP server inside Cameo: settings, protocol engine, HTTP transport and the
 * activity history shown in the status window. One instance per Cameo process.
 */
public final class McpRuntime {

    /** One line of activity for the status window. */
    // level is info, warn, error or call (a successful tool call).
    public static final class Event {
        public final long time;
        public final String level;
        public final String text;

        Event(long time, String level, String text) {
            this.time = time;
            this.level = level;
            this.text = text;
        }

        @Override
        public String toString() {
            return new SimpleDateFormat("HH:mm:ss").format(new Date(time)) + "  " + level.toUpperCase() + "  " + text;
        }
    }

    /** Notified on the calling thread whenever state or history changes. */
    public interface ChangeListener {
        void changed();
    }

    // Events kept for the status window's activity list; older ones are dropped.
    private static final int HISTORY = 400;
    private static volatile McpRuntime instance;

    // Replaced as a whole by apply(), never mutated in place while serving.
    private volatile Settings settings;
    private final ToolRegistry registry;
    private final McpServer server;
    // Null while stopped; a new transport is created on each start so settings changes take effect.
    private volatile McpHttpTransport transport;
    // "running", "stopped" or "error"; shown in the status window and cameo_status.
    private volatile String state = "stopped";
    private volatile String lastError;
    private final Deque<Event> history = new ArrayDeque<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong toolErrors = new AtomicLong();
    private volatile long lastToolCallMillis;
    private volatile String lastToolName;

    // Builds the full catalog and binds every handler once; start/stop only open and close the port.
    private McpRuntime(Settings settings) {
        this.settings = settings;
        this.registry = new ToolRegistry().addSpecs(Catalog.all());
        ToolBindings.bindAll(registry);
        this.server = new McpServer(new ServerInfo("cameo-mcp", Version.get(), Catalog.INSTRUCTIONS), registry);
        server.guard(new Guard());
        server.addListener(new Activity());
        Resources.register(server, this);
    }

    /** Creates the process-wide runtime on first call; later calls return the same instance. */
    public static synchronized McpRuntime init(Settings settings) {
        if (instance == null) {
            instance = new McpRuntime(settings);
        }
        return instance;
    }

    /** The runtime, or null before the plugin initialized. */
    public static McpRuntime get() {
        return instance;
    }

    /** The settings currently in effect. */
    public Settings settings() {
        return settings;
    }

    /** Every tool spec and its bound handler. */
    public ToolRegistry registry() {
        return registry;
    }

    /** The protocol engine, shared by every transport start. */
    public McpServer server() {
        return server;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Opens the HTTP port with the current settings. Returns false (and records lastError) instead of
     * throwing, so menu actions and plugin start-up can report failure without crashing.
     */
    public synchronized boolean start() {
        if (transport != null && transport.isRunning()) {
            return true;
        }
        Settings s = settings;
        McpHttpTransport t = new McpHttpTransport(server, s.host, s.port, s.token, this::status);
        try {
            t.start();
            transport = t;
            state = "running";
            lastError = null;
            CameoLog.info("MCP server started. Endpoint " + t.endpointUrl() + " (port from " + s.portSource
                    + ", " + registry.size() + " tools" + (t.tokenRequired() ? ", bearer token required" : "") + ").");
            // Binding to a non-loopback address exposes Cameo to the network; make that loud in the log.
            if (!s.isLoopback()) {
                CameoLog.warn("MCP server is bound to " + s.host + ", which is reachable from other machines. "
                        + (t.tokenRequired() ? "A bearer token is required." : "No token is set: anyone on the network can drive Cameo."));
            }
            event("info", "Server started on " + t.endpointUrl());
            return true;
        } catch (BindException e) {
            state = "error";
            lastError = "Port " + s.port + " on " + s.host + " is already in use. Another Cameo instance or program "
                    + "is using it. Pick another port in MCP > MCP Server Status and update your MCP client.";
            CameoLog.error("MCP server could not start: " + lastError, null);
            event("error", lastError);
            return false;
        } catch (Exception e) {
            state = "error";
            lastError = "Could not start the MCP server: " + e;
            CameoLog.error(lastError, null);
            event("error", lastError);
            return false;
        } finally {
            fire();
        }
    }

    /** Closes the port and ends every client session. Safe to call when already stopped. */
    public synchronized void stop() {
        McpHttpTransport t = transport;
        transport = null;
        if (t != null) {
            t.stop();
            CameoLog.info("MCP server stopped.");
            event("info", "Server stopped");
        }
        state = "stopped";
        fire();
    }

    /** Stops and starts again, picking up the current settings. */
    public synchronized boolean restart() {
        stop();
        return start();
    }

    /** Replace settings (from the status window) and restart if running. */
    public synchronized boolean apply(Settings newSettings) {
        boolean wasRunning = isRunning();
        this.settings = newSettings;
        event("info", "Settings changed: port " + newSettings.port + ", host " + newSettings.host
                + ", macros " + (newSettings.allowMacros ? "allowed" : "blocked"));
        if (wasRunning) {
            return restart();
        }
        fire();
        return true;
    }

    /** True while the HTTP port is open. */
    public boolean isRunning() {
        McpHttpTransport t = transport;
        return t != null && t.isRunning();
    }

    /** "running", "stopped" or "error". */
    public String state() {
        return state;
    }

    /** Why the last start failed, or null. */
    public String lastError() {
        return lastError;
    }

    /** The endpoint clients should use; computed from settings while the server is stopped. */
    public String endpointUrl() {
        McpHttpTransport t = transport;
        if (t != null) {
            return t.endpointUrl();
        }
        String h = settings.host.equals("0.0.0.0") ? "127.0.0.1" : settings.host;
        return "http://" + h + ":" + settings.port + "/mcp";
    }

    /** The bound port while running (relevant when settings say 0), else the configured port. */
    public int port() {
        McpHttpTransport t = transport;
        return t != null ? t.boundPort() : settings.port;
    }

    // ------------------------------------------------------------- status

    /**
     * The status report behind cameo_status, the cameo://status resource and GET /status: server
     * state, counters, connected sessions, Cameo product and version, and the Java runtime.
     */
    public Map<String, Object> status() {
        McpHttpTransport t = transport;
        Map<String, Object> s = Json.obj(
                "server", "cameo-mcp",
                "version", Version.get(),
                "state", state,
                "running", t != null && t.isRunning(),
                "host", settings.host,
                "port", port(),
                "portSource", settings.portSource,
                "endpoint", endpointUrl(),
                "authRequired", settings.token != null && !settings.token.isEmpty(),
                "macrosAllowed", settings.allowMacros,
                "toolCount", registry.size(),
                "protocolVersions", McpServer.SUPPORTED_PROTOCOLS);
        if (t != null) {
            s.put("startedAt", iso(t.startedMillis()));
            s.put("uptimeSeconds", (System.currentTimeMillis() - t.startedMillis()) / 1000);
            s.put("httpRequests", t.httpRequests());
        }
        s.put("toolCalls", toolCalls.get());
        s.put("toolErrors", toolErrors.get());
        if (lastToolName != null) {
            s.put("lastToolCall", Json.obj("tool", lastToolName, "at", iso(lastToolCallMillis)));
        }
        if (lastError != null) {
            s.put("lastError", lastError);
        }
        List<Object> sessions = new ArrayList<>();
        for (McpSession m : server.sessions()) {
            sessions.add(Json.obj("client", m.clientName(), "clientVersion", m.clientVersion(),
                    "protocolVersion", m.protocolVersion(), "requests", m.requests(), "toolCalls", m.toolCalls(),
                    "errors", m.errors(), "connectedAt", iso(m.createdMillis()), "lastSeen", iso(m.lastSeenMillis())));
        }
        s.put("sessions", sessions);
        s.put("cameo", CameoInfo.describe());
        s.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        return s;
    }

    // ISO-8601 local time with offset; null for "never".
    static String iso(long millis) {
        return millis <= 0 ? null : new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(millis));
    }

    // ------------------------------------------------------------- history

    /** Registers a status-window listener; it is called on whatever thread changed the state. */
    public void addListener(ChangeListener l) {
        listeners.add(l);
    }

    public void removeListener(ChangeListener l) {
        listeners.remove(l);
    }

    /** A copy of the activity history, oldest first. */
    public List<Event> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** Records one activity line and notifies listeners. */
    public void event(String level, String text) {
        synchronized (history) {
            history.addLast(new Event(System.currentTimeMillis(), level, text));
            while (history.size() > HISTORY) {
                history.removeFirst();
            }
        }
        fire();
    }

    private void fire() {
        for (ChangeListener l : listeners) {
            try {
                l.changed();
            } catch (RuntimeException ignored) {
                // a broken UI listener must not break the server
            }
        }
    }

    // ------------------------------------------------------------- hooks

    // Runs before every tool call: refuses script tools when macros are off and project tools when none is open.
    private final class Guard implements CallGuard {
        @Override
        public void before(ToolSpec spec, Args args) {
            if (!settings.allowMacros && Catalog.isScriptTool(spec.name())) {
                throw new ToolException("MACROS_DISABLED", "Script execution is turned off in Cameo "
                        + "(MCP > MCP Server Status > Settings > Allow macro scripts). Ask the user to enable it, "
                        + "or use the structured tools.");
            }
            if (spec.requiresProject() && !CameoInfo.hasProject()) {
                throw ToolException.noProject();
            }
        }
    }

    // Turns protocol activity into history lines and log output, honouring the GUI log level setting.
    private final class Activity implements McpListener {
        @Override
        public void sessionStarted(McpSession session) {
            String text = "Client connected: " + session.clientName() + " " + session.clientVersion()
                    + " (MCP " + session.protocolVersion() + ")";
            CameoLog.info(text);
            event("info", text);
        }

        @Override
        public void sessionEnded(McpSession session, String reason) {
            String text = "Client disconnected: " + session.clientName() + " (" + reason + ")";
            CameoLog.fileOnly(text);
            event("info", text);
        }

        @Override
        public void requestHandled(McpSession session, String method, String tool, long millis, String error) {
            if (tool == null) {
                if (error != null) {
                    event("warn", method + " failed: " + error);
                }
                return;
            }
            toolCalls.incrementAndGet();
            lastToolName = tool;
            lastToolCallMillis = System.currentTimeMillis();
            String who = session == null ? "" : " [" + session.clientName() + "]";
            String text = tool + who + " " + millis + " ms" + (error == null ? "" : " - " + error);
            if (error != null) {
                toolErrors.incrementAndGet();
            }
            event(error == null ? "call" : "error", text);
            String level = settings.guiLogLevel;
            if ("all".equalsIgnoreCase(level) || (error != null && "errors".equalsIgnoreCase(level))) {
                CameoLog.info("Tool " + text);
            } else {
                CameoLog.fileOnly("Tool " + text);
            }
        }

        @Override
        public void log(String level, String message) {
            event(level, message);
            CameoLog.fileOnly(message);
        }
    }
}
