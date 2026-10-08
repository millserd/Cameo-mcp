package com.cameomcp.plugin.ui;

import com.cameomcp.json.Json;
import com.cameomcp.plugin.CameoMcpPlugin;
import com.cameomcp.plugin.McpRuntime;
import com.cameomcp.plugin.Settings;

import java.io.File;
import java.util.Map;

/** Ready-to-paste MCP client configurations for the running server. */
public final class ClientConfigs {

    private ClientConfigs() {
    }

    static String token(McpRuntime rt) {
        String t = rt.settings().token;
        return t == null || t.isEmpty() ? null : t;
    }

    /** {@code claude mcp add} command for Claude Code (HTTP transport). */
    public static String claudeCode(McpRuntime rt) {
        String cmd = "claude mcp add --transport http cameo " + rt.endpointUrl();
        String t = token(rt);
        if (t != null) {
            cmd += " --header \"Authorization: Bearer " + t + "\"";
        }
        return cmd;
    }

    /** Generic mcpServers JSON for clients with HTTP support (Claude Code, Cursor, VS Code, ...). */
    public static String httpJson(McpRuntime rt) {
        Map<String, Object> server = Json.obj("type", "http", "url", rt.endpointUrl());
        String t = token(rt);
        if (t != null) {
            server.put("headers", Json.obj("Authorization", "Bearer " + t));
        }
        return Json.pretty(Json.obj("mcpServers", Json.obj("cameo", server)));
    }

    /** mcpServers JSON that starts the stdio launcher with Cameo's own Java runtime. */
    public static String stdioJson(McpRuntime rt) {
        Map<String, Object> server = Json.obj("command", javaExecutable(),
                "args", Json.arr("-jar", launcherJar(), "--port", String.valueOf(rt.port())));
        String t = token(rt);
        if (t != null) {
            server.put("env", Json.obj("CAMEO_MCP_TOKEN", t));
        }
        if (!rt.settings().host.equals(Settings.DEFAULT_HOST) && !rt.settings().host.equals("0.0.0.0")) {
            ((java.util.List<Object>) server.get("args")).add("--host");
            ((java.util.List<Object>) server.get("args")).add(rt.settings().host);
        }
        return Json.pretty(Json.obj("mcpServers", Json.obj("cameo", server)));
    }

    public static String javaExecutable() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File exe = new File(bin, System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return exe.isFile() ? exe.getAbsolutePath() : "java";
    }

    public static String launcherJar() {
        File dir = CameoMcpPlugin.pluginDirectory();
        File jar = dir == null ? null : new File(dir, "cameo-mcp-stdio.jar");
        return jar != null && jar.isFile() ? jar.getAbsolutePath() : "<path-to>/cameo-mcp-stdio.jar";
    }
}
