package com.cameomcp.plugin.tools.server;

import com.cameomcp.catalog.Catalog;
import com.cameomcp.json.Json;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.McpServer;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import com.cameomcp.plugin.CameoLog;
import com.cameomcp.plugin.McpRuntime;
import com.cameomcp.plugin.cameo.CameoInfo;
import com.cameomcp.plugin.cameo.Edt;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Status, capabilities, diagnostics and recovery tools. */
public final class ServerBindings {

    /** Optional Cameo plugins worth reporting, by plugin id fragment -> capability name. */
    private static final String[][] KNOWN_PLUGINS = {
            {"dependencymatrix", "dependencyMatrix"},
            {"diagramtable", "genericTable"},
            {"relationshipmap", "relationMap"},
            {"simulation", "simulation"},
            {"teamwork", "teamwork"},
            {"reportwizard", "reportWizard"},
            {"datahub", "dataHub"},
            {"sysml", "sysml"},
            {"variability", "variants"},
            {"macros", "macros"},
    };

    /** Static methods only. */
    private ServerBindings() {
    }

    /**
     * Binds the server family's tools; each lambda unpacks arguments and delegates to a method below.
     *
     * @param r the registry holding the server family's specs
     */
    public static void bind(ToolRegistry r) {
        r.bind("cameo_status", (a, c) -> status());
        r.bind("cameo_get_capabilities", (a, c) -> capabilities(a));
        r.bind("cameo_probe_bridge", (a, c) -> probe());
        r.bind("cameo_list_tools", (a, c) -> listTools(a));
        r.bind("cameo_reset_session", (a, c) -> resetSession());
        r.bind("cameo_undo", (a, c) -> undoRedo(true, a.intIn("steps", 1, 1, 50)));
        r.bind("cameo_redo", (a, c) -> undoRedo(false, a.intIn("steps", 1, 1, 50)));
        r.bind("cameo_log", (a, c) -> log(a));
    }

    /**
     * The runtime, or NOT_READY if the plugin hasn't initialized (only possible in tests).
     *
     * @return the runtime
     */
    private static McpRuntime rt() {
        McpRuntime rt = McpRuntime.get();
        if (rt == null) {
            throw new ToolException("NOT_READY", "Cameo MCP runtime is not initialized.");
        }
        return rt;
    }

    // ------------------------------------------------------------------ status

    /**
     * cameo_status: the runtime status plus project state and any write in progress. Runs on the
     * HTTP thread and works without a project.
     *
     * @return the status map
     */
    static Map<String, Object> status() {
        Map<String, Object> s = rt().status();
        boolean project = CameoInfo.hasProject();
        s.put("ok", true);
        s.put("projectOpen", project);
        if (!project) {
            s.put("hint", "Cameo is running but no project is open. Ask the user to open or create a project; "
                    + "model tools fail with NO_PROJECT until then.");
        }
        String active = Edt.activeWrite();
        if (active != null) {
            s.put("activeWrite", active);
        }
        return s;
    }

    /**
     * cameo_get_capabilities: tool counts per family, which optional Cameo plugins are loaded
     * (matched by plugin id fragment), macro setting and Cameo version.
     *
     * @param a arguments: include_tools (also list tool names per family)
     * @return the capabilities map
     */
    static Map<String, Object> capabilities(Args a) {
        McpRuntime rt = rt();
        Map<String, Integer> families = new LinkedHashMap<>();
        Map<String, List<String>> toolsByFamily = new LinkedHashMap<>();
        for (ToolSpec s : rt.registry().specs()) {
            families.merge(s.family(), 1, Integer::sum);
            toolsByFamily.computeIfAbsent(s.family(), f -> new ArrayList<>()).add(s.name());
        }
        List<String> pluginIds = loadedPluginIds();
        Map<String, Object> optional = new LinkedHashMap<>();
        for (String[] k : KNOWN_PLUGINS) {
            boolean present = false;
            for (String id : pluginIds) {
                if (id.toLowerCase(Locale.ROOT).contains(k[0])) {
                    present = true;
                    break;
                }
            }
            optional.put(k[1], present);
        }
        Map<String, Object> out = Json.obj(
                "server", "cameo-mcp",
                "version", rt.server().info().version(),
                "protocolVersions", McpServer.SUPPORTED_PROTOCOLS,
                "transport", Json.obj("type", "streamable-http", "endpoint", rt.endpointUrl(),
                        "stdioLauncher", "cameo-mcp-stdio.jar"),
                "toolCount", rt.registry().size(),
                "unimplementedTools", rt.registry().unboundTools(),
                "families", families,
                "optionalPlugins", optional,
                "macrosAllowed", rt.settings().allowMacros,
                "projectOpen", CameoInfo.hasProject(),
                "cameo", CameoInfo.describe());
        if (a.bool("include_tools", false)) {
            out.put("tools", toolsByFamily);
        }
        out.put("loadedPluginCount", pluginIds.size());
        return out;
    }

    /**
     * Ids of every loaded Cameo plugin, read reflectively since PluginUtils isn't public API in every
     * release.
     *
     * @return the plugin ids; empty outside Cameo
     */
    private static List<String> loadedPluginIds() {
        List<String> ids = new ArrayList<>();
        try {
            Class<?> utils = Class.forName("com.nomagic.magicdraw.plugins.PluginUtils");
            Object plugins = utils.getMethod("getPlugins").invoke(null);
            if (plugins instanceof Iterable) {
                for (Object p : (Iterable<?>) plugins) {
                    try {
                        Object d = p.getClass().getMethod("getDescriptor").invoke(p);
                        Object id = d.getClass().getMethod("getID").invoke(d);
                        if (id != null) {
                            ids.add(String.valueOf(id));
                        }
                    } catch (Exception ignored) {
                        // skip this plugin
                    }
                }
            }
        } catch (Throwable ignored) {
            // not inside Cameo
        }
        return ids;
    }

    // ------------------------------------------------------------------ probe

    /**
     * cameo_probe_bridge: calls our own HTTP endpoint the way a client would, and checks that the
     * Swing event thread answers within 5 s. A stuck EDT (usually a modal dialog) is the most
     * common reason write tools time out. Must not run on the EDT (it would wait on itself).
     *
     * @return {healthy, checks}
     */
    static Map<String, Object> probe() {
        McpRuntime rt = rt();
        Map<String, Object> checks = new LinkedHashMap<>();
        String endpoint = rt.endpointUrl();
        checks.put("serverRunning", rt.isRunning());
        checks.put("endpoint", endpoint);
        if (rt.isRunning()) {
            checks.put("statusPath", httpCheck("GET", endpoint.replace("/mcp", "/status"), null, rt));
            checks.put("mcpPing", httpCheck("POST", endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":\"probe\",\"method\":\"ping\"}", rt));
        }
        long t0 = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(latch::countDown);
        boolean edtOk;
        try {
            edtOk = latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            edtOk = false;
        }
        checks.put("edt", Json.obj("responsive", edtOk, "latencyMs", (System.nanoTime() - t0) / 1_000_000L,
                "hint", edtOk ? null : "Cameo's UI thread is busy or blocked, often by a modal dialog. "
                        + "Ask the user to check the Cameo window."));
        try {
            Project p = Application.getInstance().getProject();
            checks.put("project", p == null ? null : p.getName());
            if (p != null) {
                checks.put("editingSessionOpen", SessionManager.getInstance().isSessionCreated(p));
            }
        } catch (Throwable t) {
            checks.put("project", "unavailable: " + t);
        }
        checks.put("activeWrite", Edt.activeWrite());
        checks.put("lastError", rt.lastError());
        boolean healthy = rt.isRunning() && edtOk;
        return Json.obj("healthy", healthy, "checks", checks);
    }

    /**
     * One timed HTTP request to our own server, with the configured token; the body is cut to 300 characters.
     *
     * @param method HTTP method
     * @param url target URL
     * @param body JSON body, or null
     * @param rt the runtime, for the token
     * @return {ok, httpStatus, latencyMs, body} or {ok: false, error}
     */
    private static Map<String, Object> httpCheck(String method, String url, String body, McpRuntime rt) {
        long t0 = System.nanoTime();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
            c.setRequestMethod(method);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            String token = rt.settings().token;
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "application/json, text/event-stream");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            return Json.obj("ok", code < 400, "httpStatus", code,
                    "latencyMs", (System.nanoTime() - t0) / 1_000_000L,
                    "body", text.length() > 300 ? text.substring(0, 300) + "..." : text);
        } catch (Exception e) {
            return Json.obj("ok", false, "error", e.toString());
        }
    }

    /**
     * Reads a stream to the end as UTF-8 and closes it.
     *
     * @param in the stream, may be null
     * @return the text; empty for null
     * @throws java.io.IOException if reading fails
     */
    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ catalog

    /**
     * cameo_list_tools: catalog search by family and/or keyword, with a one-sentence summary per tool.
     *
     * @param a arguments: family, query (case-insensitive match on name or description)
     * @return {count, total, families, tools}
     */
    static Map<String, Object> listTools(Args a) {
        String family = a.optTrim("family");
        String query = a.optTrim("query");
        String q = query == null ? null : query.toLowerCase(Locale.ROOT);
        List<Object> tools = new ArrayList<>();
        Map<String, Integer> families = new TreeMap<>();
        for (ToolSpec s : rt().registry().specs()) {
            families.merge(s.family(), 1, Integer::sum);
            if (family != null && !s.family().equalsIgnoreCase(family)) {
                continue;
            }
            if (q != null && !s.name().toLowerCase(Locale.ROOT).contains(q)
                    && !s.description().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            tools.add(Json.obj("name", s.name(), "family", s.family(),
                    "kind", s.kind().name().toLowerCase(Locale.ROOT),
                    "summary", firstSentence(s.description()),
                    "parameters", new ArrayList<>(s.parameters().keySet())));
        }
        return Json.obj("count", tools.size(), "total", rt().registry().size(), "families", families, "tools", tools);
    }

    /**
     * Text up to and including the first ". ", or all of it.
     *
     * @param text a description
     * @return its first sentence
     */
    static String firstSentence(String text) {
        int i = text.indexOf(". ");
        return i < 0 ? text : text.substring(0, i + 1);
    }

    // ------------------------------------------------------------------ recovery

    /**
     * cameo_reset_session: cancels (rolls back) a session left open by a crashed macro. If cancel
     * itself fails, closing the session keeps the partial change but unblocks new writes.
     *
     * @return {reset, method} or {reset: false, message}
     * @throws ToolException RESET_FAILED when neither cancel nor close works
     * @throws Exception from the EDT hand-off (TIMEOUT, NO_PROJECT)
     */
    static Map<String, Object> resetSession() throws Exception {
        return Edt.onEdt("Reset session", project -> {
            SessionManager sm = SessionManager.getInstance();
            if (!sm.isSessionCreated(project)) {
                return Json.obj("reset", false, "message", "No editing session is open; nothing to reset.");
            }
            try {
                sm.cancelSession(project);
                CameoLog.warn("Editing session cancelled by cameo_reset_session.");
                return Json.obj("reset", true, "method", "cancel");
            } catch (Exception cancel) {
                try {
                    sm.closeSession(project);
                    CameoLog.warn("Editing session closed by cameo_reset_session (cancel failed: " + cancel + ").");
                    return Json.obj("reset", true, "method", "close", "cancelError", cancel.toString());
                } catch (Exception close) {
                    throw new ToolException("RESET_FAILED", "Could not cancel (" + cancel.getMessage()
                            + ") or close (" + close.getMessage() + ") the session. Save and reopen the project in Cameo.");
                }
            }
        }, 30);
    }

    /**
     * cameo_undo / cameo_redo: steps through Cameo's command history on the EDT. Refuses while a
     * session is open, because undo inside a session would corrupt it.
     *
     * @param undo true to undo, false to redo
     * @param steps how many steps to try (stops early when history runs out)
     * @return {undone or redone, requested, more}
     * @throws ToolException SESSION_OPEN, or UNSUPPORTED when the project has no command history API
     * @throws Exception from the reflective calls or the EDT hand-off
     */
    static Map<String, Object> undoRedo(boolean undo, int steps) throws Exception {
        return Edt.onEdt(undo ? "Undo" : "Redo", project -> {
            if (SessionManager.getInstance().isSessionCreated(project)) {
                throw new ToolException("SESSION_OPEN", "An editing session is open; call cameo_reset_session first.");
            }
            Object history;
            try {
                history = project.getClass().getMethod("getCommandHistory").invoke(project);
            } catch (NoSuchMethodException e) {
                throw ToolException.unsupported("Undo/redo is not available through the API of this Cameo version.");
            }
            Method actM = history.getClass().getMethod(undo ? "undo" : "redo");
            int done = 0;
            for (int i = 0; i < steps; i++) {
                if (!canUndoRedo(history, undo)) {
                    break;
                }
                actM.invoke(history);
                done++;
            }
            return Json.obj(undo ? "undone" : "redone", done, "requested", steps,
                    "more", canUndoRedo(history, undo));
        }, 60);
    }

    /**
     * 2024x has {@code canUndo()}/{@code canRedo()}; 2026x removed them and exposes
     * {@code getCommandForUndo()}/{@code getCommandForRedo()}, which return null when there is nothing to do.
     *
     * @param history the project's command history
     * @param undo true for undo, false for redo
     * @return whether another step is available
     * @throws Exception if neither API variant exists
     */
    private static boolean canUndoRedo(Object history, boolean undo) throws Exception {
        try {
            return Boolean.TRUE.equals(history.getClass().getMethod(undo ? "canUndo" : "canRedo").invoke(history));
        } catch (NoSuchMethodException e) {
            Method next = history.getClass().getMethod(undo ? "getCommandForUndo" : "getCommandForRedo");
            return next.invoke(history) != null;
        }
    }

    /**
     * cameo_log: writes the agent's message to the Notification window, md.log and the activity list.
     *
     * @param a arguments: message (required), level (info, warn or error)
     * @return {logged: true}
     */
    static Map<String, Object> log(Args a) {
        String message = a.reqStr("message");
        String level = a.choice("level", "info", "info", "warn", "error");
        String text = "Agent: " + message;
        if ("error".equals(level)) {
            CameoLog.error(text, null);
        } else if ("warn".equals(level)) {
            CameoLog.warn(text);
        } else {
            CameoLog.info(text);
        }
        rt().event(level, text);
        return Json.obj("logged", true);
    }

    /**
     * For tests: the catalog's script tool list stays in sync with this family's assumptions.
     *
     * @return whether the catalog treats cameo_execute_macro as a script tool
     */
    static boolean macrosGuarded() {
        return Catalog.isScriptTool("cameo_execute_macro");
    }
}
