import com.cameomcp.json.tree.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * Runs a scenario against a live Cameo MCP server: {@code java LiveTest.java <dir> [scenario-file]}.
 * scenario.json writes results.json + results.txt; scenario-NAME.json writes results-NAME.json/.txt.
 * A step passes when isError matches expectError and every "expect" (path -> value) and
 * "expectContains" (path -> substring) check holds. "allowError": true accepts either outcome.
 */
public class LiveTest {
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static String url = "http://127.0.0.1:18740/mcp";
    static String session;
    static int nextId = 1;

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a.length > 0 ? a[0] : ".");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("testdir", dir.toAbsolutePath().toString().replace("\\", "\\\\"));
        String scenario = a.length > 1 ? a[1] : "scenario.json";
        String suffix = scenario.equals("scenario.json") ? ""
                : "-" + scenario.replaceFirst("^scenario-", "").replaceFirst("\\.json$", "");
        JsonArray steps = JsonParser.parseString(Files.readString(dir.resolve(scenario))).getAsJsonArray();
        JsonArray results = new JsonArray();
        StringBuilder txt = new StringBuilder();
        int pass = 0, fail = 0, skip = 0;

        JsonObject init = rpc("initialize", obj("protocolVersion", "2025-06-18", "capabilities", new JsonObject(),
                "clientInfo", obj("name", "cameo-mcp-livetest", "version", "1")), true);
        txt.append("initialize: ").append(trim(init.toString(), 300)).append("\n");
        rpc("notifications/initialized", null, false);
        JsonObject list = rpc("tools/list", new JsonObject(), true);
        int toolCount = list.getAsJsonObject("result").getAsJsonArray("tools").size();
        txt.append("tools/list: ").append(toolCount).append(" tools\n\n");

        for (JsonElement e : steps) {
            JsonObject step = e.getAsJsonObject();
            String name = step.get("name").getAsString();
            String tool = step.get("tool").getAsString();
            String argsText = step.has("args") ? step.get("args").toString() : "{}";
            JsonObject r = new JsonObject();
            r.addProperty("name", name);
            r.addProperty("tool", tool);
            Matcher m = Pattern.compile("\\$\\{(\\w+)\\}").matcher(argsText);
            StringBuffer sb = new StringBuffer();
            String missing = null;
            while (m.find()) {
                String v = vars.get(m.group(1));
                if (v == null) { missing = m.group(1); v = ""; }
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            m.appendTail(sb);
            if (missing != null) {
                r.addProperty("status", "SKIPPED");
                r.addProperty("reason", "needs ${" + missing + "} from an earlier step");
                skip++;
                results.add(r);
                txt.append(String.format("SKIP  %-34s %s%n", name, r.get("reason").getAsString()));
                continue;
            }
            long t0 = System.currentTimeMillis();
            JsonObject resp;
            try {
                resp = rpc("tools/call", obj("name", tool, "arguments", JsonParser.parseString(sb.toString())), true);
            } catch (Exception ex) {
                resp = obj("transportError", ex.toString());
            }
            long ms = System.currentTimeMillis() - t0;
            r.addProperty("ms", ms);
            JsonObject res = resp.has("result") ? resp.getAsJsonObject("result") : null;
            boolean isError = res == null || (res.has("isError") && res.get("isError").getAsBoolean());
            JsonElement sc = res != null && res.has("structuredContent") ? res.get("structuredContent") : null;
            String text = "";
            int images = 0;
            if (res != null && res.has("content")) {
                for (JsonElement c : res.getAsJsonArray("content")) {
                    JsonObject co = c.getAsJsonObject();
                    if ("text".equals(co.get("type").getAsString())) text += co.get("text").getAsString();
                    if ("image".equals(co.get("type").getAsString())) images++;
                }
            }
            if (sc == null && !text.isEmpty()) {
                try { sc = JsonParser.parseString(text); } catch (Exception ignored) { }
            }
            boolean expectError = step.has("expectError") && step.get("expectError").getAsBoolean();
            boolean allowError = step.has("allowError") && step.get("allowError").getAsBoolean();
            boolean ok = allowError || isError == expectError;
            String why = ok ? "" : (isError ? "unexpected error" : "expected an error");
            if (ok && step.has("expect")) {
                for (Map.Entry<String, JsonElement> x : step.getAsJsonObject("expect").entrySet()) {
                    String got = sc == null ? null : path(sc, x.getKey());
                    String want = x.getValue().getAsString();
                    if (!want.equals(got)) {
                        ok = false;
                        why = x.getKey() + " is " + got + ", expected " + want;
                        break;
                    }
                }
            }
            if (ok && step.has("expectContains")) {
                for (Map.Entry<String, JsonElement> x : step.getAsJsonObject("expectContains").entrySet()) {
                    String got = sc == null ? null : path(sc, x.getKey());
                    String want = x.getValue().getAsString();
                    if (got == null || !got.contains(want)) {
                        ok = false;
                        why = x.getKey() + " does not contain \"" + want + "\"";
                        break;
                    }
                }
            }
            r.addProperty("status", ok ? "PASS" : "FAIL");
            if (!ok) r.addProperty("why", why);
            if (images > 0) r.addProperty("images", images);
            r.addProperty("result", trim(res != null ? text : resp.toString(), 4000));
            if (ok) pass++; else fail++;
            if (ok && step.has("save") && sc != null) {
                for (Map.Entry<String, JsonElement> s : step.getAsJsonObject("save").entrySet()) {
                    String v = path(sc, s.getValue().getAsString());
                    if (v != null) vars.put(s.getKey(), v);
                }
            }
            results.add(r);
            txt.append(String.format("%-5s %-34s %-36s %6d ms  %s%s%n", ok ? "PASS" : "FAIL", name, tool, ms,
                    ok ? "" : "[" + why + "] ", trim(text.replaceAll("\\s+", " "), ok ? 160 : 600)));
        }
        String head = String.format("Cameo MCP live test: %d pass, %d fail, %d skipped (%d steps), %d tools listed%n%n",
                pass, fail, skip, steps.size(), toolCount);
        Files.writeString(dir.resolve("results" + suffix + ".txt"), head + txt);
        JsonObject all = new JsonObject();
        all.addProperty("pass", pass);
        all.addProperty("fail", fail);
        all.addProperty("skipped", skip);
        JsonObject varsJson = new JsonObject();
        vars.forEach(varsJson::addProperty);
        all.add("vars", varsJson);
        all.add("steps", results);
        Files.writeString(dir.resolve("results" + suffix + ".json"), all.toPrettyString());
        System.out.println(head);
    }

    static String path(JsonElement root, String p) {
        JsonElement cur = root;
        for (String part : p.split("\\.")) {
            if (cur == null) break;
            if (cur.isJsonArray()) {
                int i = Integer.parseInt(part);
                cur = i < cur.getAsJsonArray().size() ? cur.getAsJsonArray().get(i) : null;
            } else if (cur.isJsonObject()) {
                cur = cur.getAsJsonObject().get(part);
            } else {
                cur = null;
            }
        }
        if (cur == null || !cur.isJsonPrimitive()) {
            String last = p.substring(p.lastIndexOf('.') + 1);
            cur = deep(root, last);
        }
        return cur != null && cur.isJsonPrimitive() ? cur.getAsString() : null;
    }

    static JsonElement deep(JsonElement e, String key) {
        if (e == null) return null;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has(key) && o.get(key).isJsonPrimitive()) return o.get(key);
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                JsonElement f = deep(en.getValue(), key);
                if (f != null) return f;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) {
                JsonElement f = deep(c, key);
                if (f != null) return f;
            }
        }
        return null;
    }

    static JsonObject rpc(String method, JsonObject params, boolean request) throws Exception {
        JsonObject msg = obj("jsonrpc", "2.0", "method", method);
        if (params != null) msg.add("params", params);
        if (request) msg.addProperty("id", nextId++);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .POST(HttpRequest.BodyPublishers.ofString(msg.toString(), StandardCharsets.UTF_8));
        if (session != null) b.header("Mcp-Session-Id", session);
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
        if (!request || resp.body().isBlank()) return new JsonObject();
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof JsonElement) o.add((String) kv[i], (JsonElement) v);
            else o.addProperty((String) kv[i], String.valueOf(v));
        }
        return o;
    }

    static String trim(String s, int n) {
        s = s.replaceAll("\"data\"\\s*:\\s*\"[A-Za-z0-9+/=]{200,}\"", "\"data\":\"<base64 image>\"");
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
