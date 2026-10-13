// family: server
package com.cameomcp.mcp;

import com.cameomcp.json.Json;
import com.cameomcp.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    static McpServer server() {
        ToolRegistry r = new ToolRegistry().addSpecs(Arrays.asList(
                ToolSpec.tool("echo_tool", "test").readOnly().desc("Echo").str("text", "Text", ToolSpec.REQUIRED),
                ToolSpec.tool("fail_tool", "test").write().desc("Fails"),
                ToolSpec.tool("crash_tool", "test").write().desc("Crashes")));
        r.bind("echo_tool", (a, c) -> Json.obj("echo", a.reqStr("text"), "client", c.clientName()));
        r.bind("fail_tool", (a, c) -> {
            throw new ToolException("NOT_FOUND", "Element 'x' not found.");
        });
        r.bind("crash_tool", (a, c) -> {
            throw new IllegalStateException("boom");
        });
        McpServer s = new McpServer(new ServerInfo("t", "1", "Use echo."), r);
        s.addResource(new McpResource("cameo://guide", "Guide", "d", "text/markdown", () -> "# Guide"));
        s.addPrompt(new McpPrompt("p", "P", "desc", args -> "Hello " + args.get("who")).arg("who", "Who", true));
        return s;
    }

    static Map<String, Object> call(McpServer s, String sid, String json) {
        McpServer.Reply r = s.handle(json, sid);
        return r.body == null ? null : Json.parseObject(r.body);
    }

    @Test
    void idleSessionsExpireAndAnswer404() {
        McpServer s = server();
        String sid = s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).sessionId;
        s.expireIdleSessions(System.currentTimeMillis() + McpServer.SESSION_IDLE_MILLIS - 60_000);
        assertEquals(1, s.sessions().size());
        s.expireIdleSessions(System.currentTimeMillis() + McpServer.SESSION_IDLE_MILLIS + 60_000);
        assertEquals(0, s.sessions().size());
        assertTrue(s.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", sid).unknownSession);
    }

    @Test
    void initializeNegotiatesProtocolAndCreatesSession() {
        McpServer s = server();
        McpServer.Reply r = s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-03-26\",\"clientInfo\":{\"name\":\"c\",\"version\":\"9\"}}}", null);
        Map<String, Object> m = Json.parseObject(r.body);
        assertEquals("2025-03-26", JsonPath.str(m, "result", "protocolVersion"));
        assertEquals("Use echo.", JsonPath.str(m, "result", "instructions"));
        assertNotNull(r.sessionId);
        assertEquals("c", s.sessions().iterator().next().clientName());

        Map<String, Object> unknown = Json.parseObject(s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}", null).body);
        assertEquals(McpServer.LATEST_PROTOCOL, JsonPath.str(unknown, "result", "protocolVersion"));
    }

    @Test
    void notificationsProduceNoBody() {
        McpServer s = server();
        String sid = s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).sessionId;
        McpServer.Reply r = s.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sid);
        assertNull(r.body);
        assertTrue(s.sessions().iterator().next().initialized());
    }

    @Test
    void unknownSessionIsReported() {
        McpServer.Reply r = server().handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "nope");
        assertTrue(r.unknownSession);
    }

    @Test
    void toolsListAndCall() {
        McpServer s = server();
        Map<String, Object> list = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        List<Map<String, Object>> tools = JsonPath.maps(list, "result", "tools");
        assertEquals(3, tools.size());
        assertEquals(Boolean.TRUE, JsonPath.get(tools.get(0), "annotations", "readOnlyHint"));
        assertEquals(Arrays.asList("text"), JsonPath.get(tools.get(0), "inputSchema", "required"));

        Map<String, Object> ok = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"echo_tool\",\"arguments\":{\"text\":\"hi\"}}}");
        assertEquals("hi", JsonPath.str(ok, "result", "structuredContent", "echo"));
        assertEquals("text", JsonPath.str(ok, "result", "content", 0, "type"));
        assertNull(JsonPath.get(ok, "result", "isError"));
    }

    @Test
    void toolErrorsAreResultsNotProtocolErrors() {
        McpServer s = server();
        Map<String, Object> fail = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"fail_tool\"}}");
        assertEquals(Boolean.TRUE, JsonPath.get(fail, "result", "isError"));
        assertEquals("NOT_FOUND", JsonPath.str(fail, "result", "structuredContent", "code"));

        Map<String, Object> crash = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"crash_tool\"}}");
        assertEquals("TOOL_FAILED", JsonPath.str(crash, "result", "structuredContent", "code"));
        assertTrue(JsonPath.str(crash, "result", "structuredContent", "message").contains("boom"));

        Map<String, Object> missingArg = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"echo_tool\",\"arguments\":{}}}");
        assertEquals("INVALID_PARAMS", JsonPath.str(missingArg, "result", "structuredContent", "code"));

        Map<String, Object> unknown = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"nope\"}}");
        assertEquals(-32602L, JsonPath.get(unknown, "error", "code"));
    }

    @Test
    void guardCanRefuseCalls() {
        McpServer s = server();
        s.guard(new CallGuard() {
            @Override
            public void before(ToolSpec spec, Args args) {
                throw ToolException.noProject();
            }
        });
        Map<String, Object> r = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"echo_tool\",\"arguments\":{\"text\":\"x\"}}}");
        assertEquals("NO_PROJECT", JsonPath.str(r, "result", "structuredContent", "code"));
    }

    @Test
    void protocolErrors() {
        McpServer s = server();
        assertEquals(-32700L, JsonPath.get(call(s, null, "{not json"), "error", "code"));
        assertEquals(-32601L, JsonPath.get(call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x/y\"}"), "error", "code"));
    }

    @Test
    void batchesResourcesAndPrompts() {
        McpServer s = server();
        McpServer.Reply r = s.handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\",\"params\":{\"uri\":\"cameo://guide\"}}]", null);
        List<?> replies = (List<?>) Json.parse(r.body);
        assertEquals(2, replies.size());
        assertEquals("# Guide", JsonPath.str(replies.get(1), "result", "contents", 0, "text"));

        Map<String, Object> p = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/get\",\"params\":"
                + "{\"name\":\"p\",\"arguments\":{\"who\":\"Jason\"}}}");
        assertEquals("Hello Jason", JsonPath.str(p, "result", "messages", 0, "content", "text"));
        Map<String, Object> missing = call(s, null, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/get\",\"params\":"
                + "{\"name\":\"p\"}}");
        assertEquals(-32602L, JsonPath.get(missing, "error", "code"));
    }

    @Test
    void argsAcceptCamelCaseAndCoerce() {
        Args a = new Args(Json.obj("elementId", "E1", "limit", "25", "flag", "true", "ids", "a, b"));
        assertEquals("E1", a.str("element_id"));
        assertEquals(25, a.integer("limit", 0));
        assertTrue(a.bool("flag", false));
        assertEquals(Arrays.asList("a", "b"), a.strList("ids"));
        assertEquals("compact", a.choice("view", "compact", "compact", "full"));
        assertThrows(ToolException.class, () -> new Args(Json.obj("view", "huge")).choice("view", "compact", "compact", "full"));
    }
}
