package com.cameomcp.plugin.tools.artifacts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Path-level JSON diff used by snapshot diffs and relation-map comparison.
 * Paths look like {@code $.settings.dependencyCriteria[0]}; object keys are visited in sorted
 * order so results are stable. Array elements are compared by index; a size difference is
 * reported once under {@code arrayLengthChanges}.
 */
public final class JsonDiff {

    private JsonDiff() {
    }

    public static JsonObject diff(JsonElement before, JsonElement after, Set<String> ignorePaths, int maxChanges,
                                  boolean includeDetails) {
        JsonObject result = new JsonObject();
        JsonArray added = new JsonArray();
        JsonArray removed = new JsonArray();
        JsonArray changed = new JsonArray();
        JsonArray arrayLengthChanges = new JsonArray();
        Counter counter = new Counter(Math.max(1, maxChanges));
        compare("$", before, after, ignorePaths != null ? ignorePaths : Collections.emptySet(), includeDetails,
                counter, added, removed, changed, arrayLengthChanges);
        result.addProperty("truncated", counter.truncated);
        result.addProperty("changeCount", added.size() + removed.size() + changed.size() + arrayLengthChanges.size());
        result.add("added", added);
        result.add("removed", removed);
        result.add("changed", changed);
        result.add("arrayLengthChanges", arrayLengthChanges);
        return result;
    }

    public static Set<String> ignoreSet(Iterable<String> paths) {
        Set<String> ignored = new HashSet<>();
        if (paths != null) {
            for (String p : paths) {
                if (p != null && !p.trim().isEmpty()) {
                    ignored.add(p.trim());
                }
            }
        }
        return ignored;
    }

    private static void compare(String path, JsonElement before, JsonElement after, Set<String> ignorePaths,
                                boolean includeDetails, Counter counter, JsonArray added, JsonArray removed,
                                JsonArray changed, JsonArray arrayLengthChanges) {
        if (counter.truncated || ignored(path, ignorePaths)) {
            return;
        }
        if (before == null || before.isJsonNull()) {
            if (after != null && !after.isJsonNull()) {
                addChange(added, path, null, after, includeDetails, counter);
            }
            return;
        }
        if (after == null || after.isJsonNull()) {
            addChange(removed, path, before, null, includeDetails, counter);
            return;
        }
        if (before.isJsonObject() && after.isJsonObject()) {
            JsonObject b = before.getAsJsonObject();
            JsonObject a = after.getAsJsonObject();
            Set<String> keys = new TreeSet<>();
            for (Map.Entry<String, JsonElement> e : b.entrySet()) {
                keys.add(e.getKey());
            }
            for (Map.Entry<String, JsonElement> e : a.entrySet()) {
                keys.add(e.getKey());
            }
            for (String key : keys) {
                compare(path + "." + key, b.get(key), a.get(key), ignorePaths, includeDetails, counter,
                        added, removed, changed, arrayLengthChanges);
                if (counter.truncated) {
                    return;
                }
            }
            return;
        }
        if (before.isJsonArray() && after.isJsonArray()) {
            JsonArray b = before.getAsJsonArray();
            JsonArray a = after.getAsJsonArray();
            if (b.size() != a.size()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("path", path);
                entry.addProperty("beforeLength", b.size());
                entry.addProperty("afterLength", a.size());
                addLimited(arrayLengthChanges, entry, counter);
            }
            int common = Math.min(b.size(), a.size());
            for (int i = 0; i < common; i++) {
                compare(path + "[" + i + "]", b.get(i), a.get(i), ignorePaths, includeDetails, counter,
                        added, removed, changed, arrayLengthChanges);
                if (counter.truncated) {
                    return;
                }
            }
            return;
        }
        if (!before.equals(after)) {
            addChange(changed, path, before, after, includeDetails, counter);
        }
    }

    /** A path is ignored when it equals or starts with an ignore entry (prefix match, as in the bridge). */
    static boolean ignored(String path, Set<String> ignorePaths) {
        for (String ignored : ignorePaths) {
            if (path.equals(ignored) || path.startsWith(ignored)) {
                return true;
            }
        }
        return false;
    }

    private static void addChange(JsonArray target, String path, JsonElement before, JsonElement after,
                                  boolean includeDetails, Counter counter) {
        JsonObject entry = new JsonObject();
        entry.addProperty("path", path);
        if (includeDetails) {
            if (before != null) {
                entry.add("before", before.deepCopy());
            }
            if (after != null) {
                entry.add("after", after.deepCopy());
            }
        }
        addLimited(target, entry, counter);
    }

    private static void addLimited(JsonArray target, JsonObject entry, Counter counter) {
        if (counter.count >= counter.max) {
            counter.truncated = true;
            return;
        }
        target.add(entry);
        counter.count++;
    }

    private static final class Counter {
        private final int max;
        private int count;
        private boolean truncated;

        private Counter(int max) {
            this.max = max;
        }
    }
}
