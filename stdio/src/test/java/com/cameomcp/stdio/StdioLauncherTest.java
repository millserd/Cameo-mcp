// family: server
package com.cameomcp.stdio;

import com.cameomcp.catalog.Catalog;
import com.cameomcp.json.Json;
import com.cameomcp.json.JsonPath;
import com.cameomcp.mcp.McpHttpTransport;
import com.cameomcp.mcp.McpServer;
import com.cameomcp.mcp.ServerInfo;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StdioLauncher}: the embedded catalog is served while Cameo is down, calls are forwarded
 * and recover after a restart, and diagnose fails cleanly when nothing listens.
 */
class StdioLauncherTest {

    static Map<Object, Map<String, Object>> run(StdioLauncher l, ByteArrayOutputStream buf, String... lines) throws Exception {
        String input = String.join("\n", lines) + "\n";
        l.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        Map<Object, Map<String, Object>> byId = new HashMap<>();
        for (String line : buf.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.trim().isEmpty()) {
                Map<String, Object> m = Json.parseObject(line);
                byId.put(m.get("id"), m);
            }
        }
        return byId;
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static McpServer echoServer() {
        ToolRegistry r = new ToolRegistry().addSpecs(Collections.singletonList(
                ToolSpec.tool("cameo_status", "server").noProject().readOnly().desc("status")));
        r.bind("cameo_status", (a, c) -> Json.obj("ok", true, "client", c.clientName()));
        return new McpServer(new ServerInfo("cameo-mcp", "test", "hi"), r);
    }

    static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1\"}}}";
    static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

    @Test
    void servesCatalogWhileCameoIsDown() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StdioLauncher l = new StdioLauncher("http://127.0.0.1:" + freePort() + "/mcp", null,
                new PrintStream(buf, true, "UTF-8"), 5000);
        Map<Object, Map<String, Object>> out = run(l, buf, INIT, INITIALIZED,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"cameo_get_project\",\"arguments\":{}}}");
        assertEquals("cameo-mcp", JsonPath.str(out.get(1L), "result", "serverInfo", "name"));
        assertEquals(Catalog.all().size(), JsonPath.list(out.get(2L), "result", "tools").size());
        assertEquals(Boolean.TRUE, JsonPath.get(out.get(3L), "result", "isError"));
        assertEquals("CAMEO_NOT_RUNNING", JsonPath.str(out.get(3L), "result", "structuredContent", "code"));
        assertEquals(3, out.size(), "notifications must not produce output");
    }

    @Test
    void forwardsToCameoAndRecoversFromRestart() throws Exception {
        int port = freePort();
        McpHttpTransport t = new McpHttpTransport(echoServer(), "127.0.0.1", port, null, null);
        t.start();
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            StdioLauncher l = new StdioLauncher("http://127.0.0.1:" + port + "/mcp", null,
                    new PrintStream(buf, true, "UTF-8"), 5000);
            Map<Object, Map<String, Object>> out = run(l, buf, INIT, INITIALIZED,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"cameo_status\"}}");
            assertEquals("test", JsonPath.str(out.get(1L), "result", "serverInfo", "version"));
            assertEquals("test-client", JsonPath.str(out.get(2L), "result", "structuredContent", "client"));

            // Cameo restarts: the old session is gone. The launcher must re-initialize on its own.
            t.stop();
            t = new McpHttpTransport(echoServer(), "127.0.0.1", port, null, null);
            t.start();
            buf.reset();
            l.handle("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"cameo_status\"}}");
            Map<String, Object> again = Json.parseObject(buf.toString(StandardCharsets.UTF_8).trim());
            assertEquals(Boolean.TRUE, JsonPath.get(again, "result", "structuredContent", "ok"));
        } finally {
            t.stop();
        }
    }

    @Test
    void diagnoseFailsCleanlyWhenNothingListens() throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, "UTF-8"));
        try {
            assertEquals(1, StdioLauncher.diagnose("http://127.0.0.1:" + freePort() + "/mcp", null));
        } finally {
            System.setOut(old);
        }
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("nothing is listening"));
    }

    /** --print-config emits a parseable mcpServers block with Windows paths escaped, extra options and the token. */
    @Test
    void printConfigBuildsClientBlock() {
        String json = StdioLauncher.printConfig("C:\\Java\\bin\\java.exe", "C:\\Cameo\\cameo-mcp-stdio.jar",
                java.util.Arrays.asList("--port", "18741"), "secret");
        Map<String, Object> cameo = JsonPath.map(Json.parseObject(json), "mcpServers", "cameo");
        assertEquals("C:\\Java\\bin\\java.exe", cameo.get("command"));
        assertEquals(java.util.Arrays.asList("-jar", "C:\\Cameo\\cameo-mcp-stdio.jar", "--port", "18741"), cameo.get("args"));
        assertEquals("secret", JsonPath.str(cameo, "env", "CAMEO_MCP_TOKEN"));
        Map<String, Object> plain = JsonPath.map(Json.parseObject(
                StdioLauncher.printConfig("/usr/bin/java", "/x.jar", Collections.emptyList(), null)), "mcpServers", "cameo");
        assertNull(plain.get("env"));
    }

    /** --call arguments: none, inline JSON, stdin, key=value with JSON-or-string values; bad words are rejected. */
    @Test
    void parsesCallArguments() throws Exception {
        java.io.InputStream none = new ByteArrayInputStream(new byte[0]);
        assertEquals(Json.obj(), StdioLauncher.parseCallArgs(java.util.Collections.emptyList(), none));
        assertEquals(Json.obj("id", "_1"), StdioLauncher.parseCallArgs(java.util.Arrays.asList("{\"id\":\"_1\"}"), none));
        assertEquals(Json.obj("limit", 5L), StdioLauncher.parseCallArgs(java.util.Arrays.asList("-"),
                new ByteArrayInputStream("\uFEFF{\"limit\":5}".getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> kv = StdioLauncher.parseCallArgs(
                java.util.Arrays.asList("name=Engine Block", "limit=5", "full=true", "ids=[\"a\"]"), none);
        assertEquals("Engine Block", kv.get("name"));
        assertEquals(5L, kv.get("limit"));
        assertEquals(Boolean.TRUE, kv.get("full"));
        assertEquals(java.util.Arrays.asList("a"), kv.get("ids"));
        assertThrows(IllegalArgumentException.class,
                () -> StdioLauncher.parseCallArgs(java.util.Arrays.asList("oops"), none));
        assertThrows(IllegalArgumentException.class,
                () -> StdioLauncher.parseCallArgs(java.util.Arrays.asList("[1]"), none));
    }

    /** --call prints the tool's structured result against a live server, and exits 1 offline. */
    @Test
    void oneShotCallsAndLists() throws Exception {
        int port = freePort();
        String url = "http://127.0.0.1:" + port + "/mcp";
        McpHttpTransport t = new McpHttpTransport(echoServer(), "127.0.0.1", port, null, null);
        t.start();
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            assertEquals(0, StdioLauncher.oneShot("--call", "cameo_status", Collections.emptyList(), url, null, 5000,
                    new PrintStream(buf, true, "UTF-8")));
            Map<String, Object> r = Json.parseObject(buf.toString(StandardCharsets.UTF_8));
            assertEquals(Boolean.TRUE, r.get("ok"));
            assertEquals("cameo-mcp-cli", r.get("client"));
        } finally {
            t.stop();
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assertEquals(1, StdioLauncher.oneShot("--call", "cameo_get_project", Collections.emptyList(), url, null, 5000,
                new PrintStream(buf, true, "UTF-8")));
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("CAMEO_NOT_RUNNING"));
        buf.reset();
        assertEquals(0, StdioLauncher.oneShot("--list", null, Collections.emptyList(), url, null, 5000,
                new PrintStream(buf, true, "UTF-8")));
        assertEquals(Catalog.all().size(), buf.toString(StandardCharsets.UTF_8).trim().split("\n").length);
        buf.reset();
        assertEquals(0, StdioLauncher.oneShot("--describe", "cameo_get_element", Collections.emptyList(), url, null,
                5000, new PrintStream(buf, true, "UTF-8")));
        assertNotNull(JsonPath.map(Json.parseObject(buf.toString(StandardCharsets.UTF_8)), "inputSchema"));
    }
}
