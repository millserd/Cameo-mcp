// family: server
package com.cameomcp.plugin;

import com.cameomcp.catalog.Catalog;
import com.cameomcp.json.Json;
import com.cameomcp.json.JsonPath;
import com.cameomcp.mcp.ToolSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the real plugin runtime (catalog + every family's bindings + HTTP transport)
 * outside Cameo, against the API stubs, and talks to it like an MCP client.
 */
class PluginRuntimeTest {

    static McpRuntime rt;
    static final HttpClient HTTP = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build();
    static String session;

    @BeforeAll
    static void boot() throws Exception {
        System.setProperty("cameo.mcp.home", System.getProperty("java.io.tmpdir") + "/cameo-mcp-test-home");
        Settings s = new Settings();
        s.port = 0;
        rt = McpRuntime.init(s);
        assertTrue(rt.start(), "server should start: " + rt.lastError());
        Map<String, Object> init = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"junit\",\"version\":\"1\"}}}");
        assertEquals("cameo-mcp", JsonPath.str(init, "result", "serverInfo", "name"));
    }

    @AfterAll
    static void shutdown() {
        rt.stop();
    }

    static Map<String, Object> post(String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(rt.endpointUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        r.headers().firstValue("Mcp-Session-Id").ifPresent(v -> session = v);
        return r.body().isEmpty() ? null : Json.parseObject(r.body());
    }

    static Map<String, Object> callTool(String name, String argsJson) throws Exception {
        return post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + argsJson + "}}");
    }

    @Test
    void everyCatalogToolIsBound() {
        assertEquals(List.of(), rt.registry().unboundTools());
    }

    @Test
    void catalogIsWellFormed() {
        Set<String> names = new HashSet<>();
        for (ToolSpec s : Catalog.all()) {
            assertTrue(s.name().startsWith("cameo_"), s.name());
            assertTrue(names.add(s.name()), "duplicate " + s.name());
            assertTrue(s.description().length() >= 20, "description too short: " + s.name());
            for (Map.Entry<String, Object> p : s.parameters().entrySet()) {
                assertTrue(p.getKey().matches("[a-z][a-z0-9_]*"), s.name() + " param " + p.getKey() + " not snake_case");
                assertNotNull(((Map<?, ?>) p.getValue()).get("description"), s.name() + " param " + p.getKey() + " undocumented");
            }
        }
        assertTrue(names.size() >= 162, "expected at least the reference's 162 tools, got " + names.size());
    }

    @Test
    void toolsListMatchesCatalog() throws Exception {
        Map<String, Object> list = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(Catalog.all().size(), JsonPath.list(list, "result", "tools").size());
    }

    @Test
    void statusWorksWithoutCameoProject() throws Exception {
        Map<String, Object> r = callTool("cameo_status", "{}");
        assertNull(JsonPath.get(r, "result", "isError"), String.valueOf(r));
        assertEquals(Boolean.FALSE, JsonPath.get(r, "result", "structuredContent", "projectOpen"));
        assertEquals(Boolean.TRUE, JsonPath.get(r, "result", "structuredContent", "running"));
    }

    @Test
    void modelToolsReportNoProject() throws Exception {
        Map<String, Object> r = callTool("cameo_get_project", "{}");
        assertEquals("NO_PROJECT", JsonPath.str(r, "result", "structuredContent", "code"));
    }

    @Test
    void listToolsFiltersByFamily() throws Exception {
        Map<String, Object> r = callTool("cameo_list_tools", "{\"family\":\"server\"}");
        assertTrue(((Number) JsonPath.get(r, "result", "structuredContent", "count")).intValue() >= 5);
    }

    @Test
    void guideAndPromptsAreServed() throws Exception {
        Map<String, Object> guide = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{\"uri\":\"cameo://guide\"}}");
        assertTrue(JsonPath.str(guide, "result", "contents", 0, "text").contains("cameo_status"));
        Map<String, Object> prompts = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\"}");
        assertTrue(JsonPath.list(prompts, "result", "prompts").size() >= 4);
    }

    @Test
    void macrosCanBeBlocked() throws Exception {
        rt.settings().allowMacros = false;
        try {
            Map<String, Object> r = callTool("cameo_execute_macro", "{\"script\":\"1+1\"}");
            assertEquals("MACROS_DISABLED", JsonPath.str(r, "result", "structuredContent", "code"));
        } finally {
            rt.settings().allowMacros = true;
        }
    }
}
