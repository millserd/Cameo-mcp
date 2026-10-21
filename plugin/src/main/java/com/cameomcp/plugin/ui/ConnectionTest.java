package com.cameomcp.plugin.ui;

import com.cameomcp.json.Json;
import com.cameomcp.json.JsonPath;
import com.cameomcp.plugin.McpRuntime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-to-end self test, exactly as an MCP client would connect: health check, initialize,
 * list tools, call cameo_status, close the session. Runs off the EDT.
 */
public final class ConnectionTest {

    /** One step of the test. */
    public static final class Step {
        /** Step label shown in the report. */
        public final String name;
        /** Whether the step passed. */
        public final boolean ok;
        /** How long the step took, in milliseconds. */
        public final long millis;
        /** What was observed (status code, counts) or why it failed; may be null. */
        public final String detail;

        /**
         * Creates a step result.
         *
         * @param name step label
         * @param ok whether it passed
         * @param millis duration
         * @param detail observation or failure reason
         */
        Step(String name, boolean ok, long millis, String detail) {
            this.name = name;
            this.ok = ok;
            this.millis = millis;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (ok ? "PASS  " : "FAIL  ") + name + " (" + millis + " ms)" + (detail == null ? "" : ": " + detail);
        }
    }

    /** Static methods only. */
    private ConnectionTest() {
    }

    /**
     * Runs every step in order against the live endpoint and returns their results. Steps after a
     * failure still run, so the report shows everything that is wrong at once. Blocks; call off the EDT.
     *
     * @param rt the runtime whose endpoint and token are tested
     * @return one entry per step; a single failed step when the server is not running
     */
    public static List<Step> run(McpRuntime rt) {
        List<Step> steps = new ArrayList<>();
        if (!rt.isRunning()) {
            steps.add(new Step("Server running", false, 0, "the server is " + rt.state()
                    + (rt.lastError() == null ? "" : " - " + rt.lastError())));
            return steps;
        }
        String endpoint = rt.endpointUrl();
        String token = rt.settings().token;
        // Holder so the lambdas below can share the session id created by the initialize step.
        String[] session = {null};

        steps.add(step("Health check GET /status", () -> {
            Response r = http("GET", endpoint.replace("/mcp", "/status"), null, token, null);
            return r.code == 200 ? "HTTP 200" : fail("HTTP " + r.code + " " + r.body);
        }));
        steps.add(step("MCP initialize", () -> {
            Response r = http("POST", endpoint, Json.write(Json.obj("jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Json.obj("protocolVersion", "2025-06-18", "capabilities", Json.obj(),
                            "clientInfo", Json.obj("name", "cameo-mcp-self-test", "version", "1")))), token, null);
            if (r.code != 200) {
                return fail("HTTP " + r.code + " " + r.body);
            }
            session[0] = r.sessionId;
            Object v = JsonPath.get(Json.parse(r.body), "result", "protocolVersion");
            return "protocol " + v + ", session " + (r.sessionId == null ? "none" : r.sessionId.substring(0, 8) + "...");
        }));
        steps.add(step("initialized notification", () -> {
            Response r = http("POST", endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", token, session[0]);
            return r.code == 202 ? "HTTP 202" : fail("HTTP " + r.code);
        }));
        steps.add(step("tools/list", () -> {
            Response r = http("POST", endpoint, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", token, session[0]);
            if (r.code != 200) {
                return fail("HTTP " + r.code);
            }
            return JsonPath.list(Json.parse(r.body), "result", "tools").size() + " tools";
        }));
        steps.add(step("tools/call cameo_status", () -> {
            Response r = http("POST", endpoint, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                    + "{\"name\":\"cameo_status\",\"arguments\":{}}}", token, session[0]);
            if (r.code != 200) {
                return fail("HTTP " + r.code);
            }
            Map<String, Object> sc = JsonPath.map(Json.parse(r.body), "result", "structuredContent");
            if (sc == null || Boolean.TRUE.equals(JsonPath.get(Json.parse(r.body), "result", "isError"))) {
                return fail(r.body);
            }
            return "projectOpen=" + sc.get("projectOpen");
        }));
        steps.add(step("Close session", () -> {
            if (session[0] == null) {
                return "no session";
            }
            Response r = http("DELETE", endpoint, null, token, session[0]);
            return r.code == 204 ? "HTTP 204" : fail("HTTP " + r.code);
        }));
        return steps;
    }

    // ------------------------------------------------------------------ helpers

    /** A step body: returns a detail string on success, or throws Failure via fail(...). */
    private interface Check {
        /**
         * Runs the check.
         *
         * @return the detail text for a passing step
         * @throws Exception a {@link Failure} for an expected failure, anything else for an unexpected one
         */
        String run() throws Exception;
    }

    /** An expected failure with a readable message (as opposed to an unexpected exception). */
    private static final class Failure extends Exception {
        /**
         * Creates the failure.
         *
         * <p>Creates the failure.
         *
         * @param m the message shown in the report
         */
        Failure(String m) {
            super(m);
        }
    }

    /**
     * Fails the current check. Declared to return String so a check can write "return fail(...)".
     *
     * @param message why the check failed
     * @return never returns
     * @throws Failure always
     */
    private static String fail(String message) throws Failure {
        throw new Failure(message);
    }

    /**
     * Times one check and turns its outcome into a Step.
     *
     * @param name step label
     * @param check the check
     * @return the step result; exceptions become failed steps
     */
    private static Step step(String name, Check check) {
        long t0 = System.nanoTime();
        try {
            String detail = check.run();
            return new Step(name, true, (System.nanoTime() - t0) / 1_000_000L, detail);
        } catch (Failure f) {
            return new Step(name, false, (System.nanoTime() - t0) / 1_000_000L, f.getMessage());
        } catch (Exception e) {
            return new Step(name, false, (System.nanoTime() - t0) / 1_000_000L, e.toString());
        }
    }

    /** Status, body and Mcp-Session-Id header of one HTTP exchange. */
    private static final class Response {
        /** HTTP status code. */
        int code;
        /** Response body (error stream for 4xx/5xx); empty when there is none. */
        String body;
        /** Mcp-Session-Id response header, or null. */
        String sessionId;
    }

    /**
     * Plain HttpURLConnection with no proxy, exactly what a local MCP client would send. Timeouts:
     * 3 s to connect, 15 s to read.
     *
     * @param method HTTP method
     * @param url target URL
     * @param body JSON body, or null for none
     * @param token bearer token, or null/empty for none
     * @param sessionId Mcp-Session-Id to send, or null
     * @return the status, body and session header
     * @throws Exception on connection or I/O failure
     */
    private static Response http(String method, String url, String body, String token, String sessionId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (sessionId != null) {
            c.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Response r = new Response();
        r.code = c.getResponseCode();
        r.sessionId = c.getHeaderField("Mcp-Session-Id");
        InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream is = in) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    b.write(buf, 0, n);
                }
            }
        }
        r.body = new String(b.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }
}
