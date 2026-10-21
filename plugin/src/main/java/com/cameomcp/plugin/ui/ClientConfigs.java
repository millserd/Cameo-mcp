package com.cameomcp.plugin.ui;

import com.cameomcp.json.Json;
import com.cameomcp.plugin.CameoMcpPlugin;
import com.cameomcp.plugin.McpRuntime;
import com.cameomcp.plugin.Settings;

import java.io.File;
import java.util.Map;

/** Ready-to-paste MCP client configurations for the running server. */
public final class ClientConfigs {

    /** Static methods only. */
    private ClientConfigs() {
    }

    /**
     * The configured bearer token, or null when the server runs without one.
     *
     * @param rt the runtime
     * @return the token, or null
     */
    static String token(McpRuntime rt) {
        String t = rt.settings().token;
        return t == null || t.isEmpty() ? null : t;
    }

    /**
     * Generic mcpServers JSON for clients with HTTP support (Cursor, VS Code, ...).
     *
     * @param rt the runtime, for the endpoint and token
     * @return pretty-printed JSON with one "cameo" server entry
     */
    public static String httpJson(McpRuntime rt) {
        Map<String, Object> server = Json.obj("type", "http", "url", rt.endpointUrl());
        String t = token(rt);
        if (t != null) {
            server.put("headers", Json.obj("Authorization", "Bearer " + t));
        }
        return Json.pretty(Json.obj("mcpServers", Json.obj("cameo", server)));
    }

    /**
     * mcpServers JSON that starts the stdio launcher with Cameo's own Java runtime.
     *
     * @param rt the runtime, for the port, host and token
     * @return pretty-printed JSON with one "cameo" server entry
     */
    public static String stdioJson(McpRuntime rt) {
        Map<String, Object> server = Json.obj("command", javaExecutable(),
                "args", Json.arr("-jar", launcherJar(), "--port", String.valueOf(rt.port())));
        String t = token(rt);
        if (t != null) {
            server.put("env", Json.obj("CAMEO_MCP_TOKEN", t));
        }
        // The launcher defaults to 127.0.0.1, so only a specific non-default bind address needs passing on.
        if (!rt.settings().host.equals(Settings.DEFAULT_HOST) && !rt.settings().host.equals("0.0.0.0")) {
            ((java.util.List<Object>) server.get("args")).add("--host");
            ((java.util.List<Object>) server.get("args")).add(rt.settings().host);
        }
        return Json.pretty(Json.obj("mcpServers", Json.obj("cameo", server)));
    }

    /**
     * Cameo's own java executable, so the launcher needs no separate Java install. Falls back to "java".
     *
     * @return an absolute path, or "java"
     */
    public static String javaExecutable() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File exe = new File(bin, System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return exe.isFile() ? exe.getAbsolutePath() : "java";
    }

    /**
     * Absolute path of the bundled stdio launcher jar, or a placeholder the user must fill in.
     *
     * @return the jar's absolute path, or "&lt;path-to&gt;/cameo-mcp-stdio.jar"
     */
    public static String launcherJar() {
        File dir = CameoMcpPlugin.pluginDirectory();
        File jar = dir == null ? null : new File(dir, "cameo-mcp-stdio.jar");
        return jar != null && jar.isFile() ? jar.getAbsolutePath() : "<path-to>/cameo-mcp-stdio.jar";
    }
}
