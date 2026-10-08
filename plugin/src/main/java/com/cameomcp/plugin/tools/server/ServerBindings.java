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

    private ServerBindings() {
    }

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

    private static McpRuntime rt() {
        McpRuntime rt = McpRuntime.get();
        if (rt == null) {
            throw new ToolException("NOT_READY", "Cameo MCP runtime is not initialized.");
        }
        return rt;
    }

    // ------------------------------------------------------------------ status

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

    static String firstSentence(String text) {
        int i = text.indexOf(". ");
        return i < 0 ? text : text.substring(0, i + 1);
    }

    // ------------------------------------------------------------------ recovery

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
     */
    private static boolean canUndoRedo(Object history, boolean undo) throws Exception {
        try {
            return Boolean.TRUE.equals(history.getClass().getMethod(undo ? "canUndo" : "canRedo").invoke(history));
        } catch (NoSuchMethodException e) {
            Method next = history.getClass().getMethod(undo ? "getCommandForUndo" : "getCommandForRedo");
            return next.invoke(history) != null;
        }
    }

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

    /** For tests: the catalog's script tool list stays in sync with this family's assumptions. */
    static boolean macrosGuarded() {
        return Catalog.isScriptTool("cameo_execute_macro");
    }
}
