package com.cameomcp.plugin.tools.artifacts;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory inspection snapshots (lost when Cameo restarts). Bounded so a long agent
 * session cannot exhaust memory: the oldest snapshots are evicted beyond {@link #MAX_SNAPSHOTS}.
 */
public final class SnapshotStore {

    public static final int MAX_SNAPSHOTS = 200;
    // LinkedHashMap in insertion order, so iterating keys from the start visits the oldest first.
    private static final Map<String, JsonObject> SNAPSHOTS = new LinkedHashMap<>();

    private SnapshotStore() {
    }

    /** Store the snapshot, stamping snapshotId and createdAt; returns a copy. */
    // Note: the caller's object itself is stored (and stamped), so callers must not mutate it afterwards.
    public static synchronized JsonObject create(JsonObject snapshot) {
        String id = UUID.randomUUID().toString();
        snapshot.addProperty("snapshotId", id);
        snapshot.addProperty("createdAt", Instant.now().toString());
        SNAPSHOTS.put(id, snapshot);
        // Evict oldest entries until back under the cap.
        Iterator<String> it = SNAPSHOTS.keySet().iterator();
        while (SNAPSHOTS.size() > MAX_SNAPSHOTS && it.hasNext()) {
            it.next();
            it.remove();
        }
        // Return a copy so callers cannot mutate the stored snapshot.
        return snapshot.deepCopy();
    }

    /** Summaries (snapshotId, createdAt, name, target, summary) in creation order. */
    public static synchronized List<JsonObject> list() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject snapshot : SNAPSHOTS.values()) {
            JsonObject summary = new JsonObject();
            copyIfPresent(snapshot, summary, "snapshotId");
            copyIfPresent(snapshot, summary, "createdAt");
            copyIfPresent(snapshot, summary, "name");
            copyIfPresent(snapshot, summary, "target");
            copyIfPresent(snapshot, summary, "summary");
            out.add(summary);
        }
        return out;
    }

    /** Deep copy of the stored snapshot, or null when the id is unknown or was evicted. */
    public static synchronized JsonObject get(String id) {
        JsonObject s = SNAPSHOTS.get(id);
        return s != null ? s.deepCopy() : null;
    }

    /** Removes a snapshot; false when the id was not present. */
    public static synchronized boolean delete(String id) {
        return SNAPSHOTS.remove(id) != null;
    }

    /** Test hook (package-private): empties the store between tests. */
    static synchronized void clear() {
        SNAPSHOTS.clear();
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
        if (source.has(key)) {
            target.add(key, source.get(key).deepCopy());
        }
    }
}
