// family: server
package com.cameomcp.mcp;

import com.cameomcp.json.Json;
import com.cameomcp.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpHttpTransport} on a real loopback socket: a full session lifecycle, the Origin and
 * bearer-token checks, and which origins count as local.
 */
class McpHttpTransportTest {

    private McpHttpTransport transport;

    @AfterEach
    void stop() {
        if (transport != null) {
            transport.stop();
        }
    }

    static final class Resp {
        int code;
        String body;
        String session;
    }

    private static final java.net.http.HttpClient CLIENT = java.net.http.HttpClient.newBuilder()
            .proxy(java.net.ProxySelector.of(null)).build();

    static Resp http(String method, String url, String body, Map<String, String> headers) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                        : java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (headers != null) {
            headers.forEach(b::header);
        }
        java.net.http.HttpResponse<String> res = CLIENT.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        Resp r = new Resp();
        r.code = res.statusCode();
        r.body = res.body();
        r.session = res.headers().firstValue("Mcp-Session-Id").orElse(null);
        return r;
    }

    @Test
    void fullSessionLifecycleOverHttp() throws Exception {
        McpServer server = McpServerTest.server();
        transport = new McpHttpTransport(server, "127.0.0.1", 0, null, () -> Json.obj("state", "running"));
        transport.start();
        String url = transport.endpointUrl();

        Resp init = http("POST", url, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        assertEquals(200, init.code);
        assertNotNull(init.session);

        Resp note = http("POST", url, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                Map.of("Mcp-Session-Id", init.session));
        assertEquals(202, note.code);

        Resp call = http("POST", url, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"echo_tool\",\"arguments\":{\"text\":\"hey\"}}}", Map.of("Mcp-Session-Id", init.session));
        assertEquals("hey", JsonPath.str(Json.parse(call.body), "result", "structuredContent", "echo"));

        assertEquals(405, http("GET", url, null, null).code);
        assertEquals(404, http("POST", url, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}",
                Map.of("Mcp-Session-Id", "bogus")).code);
        assertEquals(204, http("DELETE", url, null, Map.of("Mcp-Session-Id", init.session)).code);
        assertEquals(404, http("POST", url, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}",
                Map.of("Mcp-Session-Id", init.session)).code);

        Resp status = http("GET", url.replace("/mcp", "/status"), null, null);
        assertEquals("running", JsonPath.str(Json.parse(status.body), "state"));
        assertEquals(url, JsonPath.str(Json.parse(status.body), "endpoint"));
    }

    @Test
    void rejectsForeignOriginsAndChecksToken() throws Exception {
        transport = new McpHttpTransport(McpServerTest.server(), "127.0.0.1", 0, "s3cret", null);
        transport.start();
        String url = transport.endpointUrl();
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(403, http("POST", url, ping, Map.of("Origin", "https://evil.example")).code);
        assertEquals(401, http("POST", url, ping, null).code);
        assertEquals(401, http("POST", url, ping, Map.of("Authorization", "Bearer wrong")).code);
        assertEquals(200, http("POST", url, ping, Map.of("Authorization", "Bearer s3cret",
                "Origin", "http://localhost:3000")).code);
    }

    @Test
    void localOriginRules() {
        assertTrue(McpHttpTransport.isLocalOrigin(null));
        assertTrue(McpHttpTransport.isLocalOrigin("http://127.0.0.1:8080"));
        assertTrue(McpHttpTransport.isLocalOrigin("http://localhost"));
        assertFalse(McpHttpTransport.isLocalOrigin("http://localhost.evil.com"));
        assertFalse(McpHttpTransport.isLocalOrigin("null"));
    }
}
