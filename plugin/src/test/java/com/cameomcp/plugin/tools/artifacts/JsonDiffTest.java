// family: artifacts
package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsonDiff}: added, removed and changed paths, null as absent, array comparison, type
 * changes, ignored path prefixes, change limits and detail copies.
 */
class JsonDiffTest {

    private static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject diff(String before, String after) {
        return JsonDiff.diff(j(before), j(after), Collections.emptySet(), 500, true);
    }

    @Test
    void identicalDocumentsHaveNoChanges() {
        JsonObject d = diff("{\"a\":1,\"b\":[1,2],\"c\":{\"x\":true}}", "{\"c\":{\"x\":true},\"b\":[1,2],\"a\":1}");
        assertEquals(0, d.get("changeCount").getAsInt());
        assertFalse(d.get("truncated").getAsBoolean());
        assertEquals(0, d.getAsJsonArray("added").size());
    }

    @Test
    void reportsAddedRemovedChangedWithPaths() {
        JsonObject d = diff("{\"keep\":1,\"gone\":\"x\",\"nested\":{\"v\":1}}",
                "{\"keep\":1,\"nested\":{\"v\":2},\"fresh\":[1]}");
        assertEquals(3, d.get("changeCount").getAsInt());
        assertEquals("$.fresh", d.getAsJsonArray("added").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("$.gone", d.getAsJsonArray("removed").get(0).getAsJsonObject().get("path").getAsString());
        JsonObject changed = d.getAsJsonArray("changed").get(0).getAsJsonObject();
        assertEquals("$.nested.v", changed.get("path").getAsString());
        assertEquals(1, changed.get("before").getAsInt());
        assertEquals(2, changed.get("after").getAsInt());
    }

    @Test
    void nullCountsAsAbsent() {
        JsonObject d = diff("{\"a\":null}", "{\"a\":5}");
        assertEquals("$.a", d.getAsJsonArray("added").get(0).getAsJsonObject().get("path").getAsString());
        d = diff("{\"a\":5}", "{\"a\":null}");
        assertEquals("$.a", d.getAsJsonArray("removed").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(0, diff("{\"a\":null}", "{}").get("changeCount").getAsInt());
    }

    @Test
    void arraysCompareByIndexAndReportLengthChange() {
        JsonObject d = diff("{\"c\":[\"a\",\"b\"]}", "{\"c\":[\"a\",\"B\",\"c\"]}");
        JsonArray len = d.getAsJsonArray("arrayLengthChanges");
        assertEquals(1, len.size());
        assertEquals("$.c", len.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(2, len.get(0).getAsJsonObject().get("beforeLength").getAsInt());
        assertEquals(3, len.get(0).getAsJsonObject().get("afterLength").getAsInt());
        assertEquals("$.c[1]", d.getAsJsonArray("changed").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(2, d.get("changeCount").getAsInt());
    }

    @Test
    void typeChangeIsAChange() {
        JsonObject d = diff("{\"a\":{\"x\":1}}", "{\"a\":[1]}");
        assertEquals("$.a", d.getAsJsonArray("changed").get(0).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void ignorePathsArePrefixes() {
        JsonObject d = JsonDiff.diff(j("{\"presentations\":[1],\"diagram\":{\"name\":\"a\",\"id\":\"1\"}}"),
                j("{\"presentations\":[2,3],\"diagram\":{\"name\":\"b\",\"id\":\"2\"}}"),
                JsonDiff.ignoreSet(Arrays.asList("$.presentations", "$.diagram.name", " ")), 500, true);
        assertEquals(1, d.get("changeCount").getAsInt());
        assertEquals("$.diagram.id", d.getAsJsonArray("changed").get(0).getAsJsonObject().get("path").getAsString());
        assertTrue(JsonDiff.ignored("$.presentations[3].x", new HashSet<>(Arrays.asList("$.presentations"))));
    }

    @Test
    void maxChangesTruncates() {
        JsonObject d = JsonDiff.diff(j("{\"a\":1,\"b\":1,\"c\":1}"), j("{\"a\":2,\"b\":2,\"c\":2}"),
                null, 2, true);
        assertTrue(d.get("truncated").getAsBoolean());
        assertEquals(2, d.get("changeCount").getAsInt());
        // keys are visited in sorted order
        assertEquals("$.a", d.getAsJsonArray("changed").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("$.b", d.getAsJsonArray("changed").get(1).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void maxChangesBelowOneMeansOne() {
        JsonObject d = JsonDiff.diff(j("{\"a\":1,\"b\":1}"), j("{\"a\":2,\"b\":2}"), null, 0, true);
        assertEquals(1, d.get("changeCount").getAsInt());
        assertTrue(d.get("truncated").getAsBoolean());
    }

    @Test
    void withoutDetailsOnlyPaths() {
        JsonObject d = JsonDiff.diff(j("{\"a\":1}"), j("{\"a\":2}"), null, 10, false);
        JsonObject c = d.getAsJsonArray("changed").get(0).getAsJsonObject();
        assertEquals(1, c.size());
        assertEquals("$.a", c.get("path").getAsString());
    }

    @Test
    void detailsAreCopies() {
        JsonObject after = j("{\"a\":{\"x\":1}}");
        JsonObject d = JsonDiff.diff(j("{}"), after, null, 10, true);
        after.getAsJsonObject("a").addProperty("x", 99);
        assertEquals(1, d.getAsJsonArray("added").get(0).getAsJsonObject()
                .getAsJsonObject("after").get("x").getAsInt());
    }
}
