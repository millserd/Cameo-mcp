// family: artifacts
package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotsTest {

    @BeforeEach
    void reset() {
        SnapshotStore.clear();
    }

    private static String store(String name, String payload) {
        JsonObject s = new JsonObject();
        s.addProperty("name", name);
        s.add("target", Gs.obj("targetType", "relationMap", "targetId", "rm-1"));
        s.add("payload", JsonParser.parseString(payload));
        s.add("summary", Gs.obj("includeRaw", false));
        return SnapshotStore.create(s).get("snapshotId").getAsString();
    }

    @Test
    void storeStampsIdAndTimeAndListsSummaries() {
        String id = store("before", "{\"x\":1}");
        List<JsonObject> list = SnapshotStore.list();
        assertEquals(1, list.size());
        JsonObject summary = list.get(0);
        assertEquals(id, summary.get("snapshotId").getAsString());
        assertNotNull(summary.get("createdAt"));
        assertEquals("before", summary.get("name").getAsString());
        assertFalse(summary.has("payload"), "list returns summaries only");
    }

    @Test
    void getReturnsCopies() {
        String id = store("s", "{\"x\":1}");
        JsonObject copy = SnapshotStore.get(id);
        copy.getAsJsonObject("payload").addProperty("x", 2);
        assertEquals(1, SnapshotStore.get(id).getAsJsonObject("payload").get("x").getAsInt());
        assertNull(SnapshotStore.get("nope"));
    }

    @Test
    void deleteIsIdempotent() {
        String id = store("s", "{}");
        JsonObject first = Snapshots.delete(Gs.obj("snapshot_id", id));
        assertTrue(first.get("deleted").getAsBoolean());
        JsonObject second = Snapshots.delete(Gs.obj("snapshot_id", id));
        assertFalse(second.get("deleted").getAsBoolean());
        assertEquals(id, second.get("snapshotId").getAsString());
    }

    @Test
    void oldestSnapshotsAreEvicted() {
        String first = store("0", "{}");
        for (int i = 1; i <= SnapshotStore.MAX_SNAPSHOTS; i++) {
            store(String.valueOf(i), "{}");
        }
        assertEquals(SnapshotStore.MAX_SNAPSHOTS, SnapshotStore.list().size());
        assertNull(SnapshotStore.get(first));
    }

    @Test
    void diffComparesPayloads() {
        String before = store("before", "{\"relationMapSettings\":{\"dependencyCriteria\":[],\"depth\":2},"
                + "\"presentations\":[1]}");
        String after = store("after", "{\"relationMapSettings\":{\"dependencyCriteria\":[\"Satisfy\"],\"depth\":3},"
                + "\"presentations\":[1,2]}");
        JsonObject p = new JsonObject();
        p.addProperty("before_snapshot_id", before);
        p.addProperty("after_snapshot_id", after);
        JsonArray ignore = new JsonArray();
        ignore.add("$.presentations");
        p.add("ignore_paths", ignore);
        JsonObject d = Snapshots.diff(p);
        assertEquals(before, d.get("beforeSnapshotId").getAsString());
        assertEquals(after, d.get("afterSnapshotId").getAsString());
        assertEquals(2, d.get("changeCount").getAsInt());
        assertEquals("$.relationMapSettings.dependencyCriteria",
                d.getAsJsonArray("arrayLengthChanges").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("$.relationMapSettings.depth",
                d.getAsJsonArray("changed").get(0).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void diffHonoursMaxChangesAndDetails() {
        String before = store("b", "{\"a\":1,\"b\":1}");
        String after = store("a", "{\"a\":2,\"b\":2}");
        JsonObject d = Snapshots.diff(Gs.obj("before_snapshot_id", before, "after_snapshot_id", after,
                "max_changes", 1, "include_details", false));
        assertTrue(d.get("truncated").getAsBoolean());
        assertEquals(1, d.getAsJsonArray("changed").get(0).getAsJsonObject().size());
    }

    @Test
    void diffNeedsBothSnapshots() {
        String before = store("b", "{}");
        ToolException e = assertThrows(ToolException.class, () -> Snapshots.diff(
                Gs.obj("before_snapshot_id", before, "after_snapshot_id", "missing")));
        assertEquals("NOT_FOUND", e.code());
        assertTrue(e.getMessage().contains("missing"));
    }

    @Test
    void getUnknownSnapshotIsNotFound() {
        ToolException e = assertThrows(ToolException.class, () -> Snapshots.get(Gs.obj("snapshot_id", "x")));
        assertEquals("NOT_FOUND", e.code());
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> Snapshots.get(new JsonObject())).code());
    }

    @Test
    void listWrapsCount() {
        store("a", "{}");
        store("b", "{}");
        JsonObject l = Snapshots.list();
        assertEquals(2, l.get("count").getAsInt());
        assertEquals("a", l.getAsJsonArray("snapshots").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void targetTypesAreCanonicalized() {
        assertEquals("relationMap", Snapshots.canonicalTarget("relationmap"));
        assertEquals("relationMap", Snapshots.canonicalTarget("relation_map"));
        assertEquals("project", Snapshots.canonicalTarget("PROJECT"));
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> Snapshots.canonicalTarget("table")).code());
    }

    @Test
    void createValidatesBeforeTouchingCameo() {
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> Snapshots.create(Gs.obj("target_type", "element"))).code());
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> Snapshots.create(Gs.obj("target_type", "bogus", "target_id", "x"))).code());
    }
}
