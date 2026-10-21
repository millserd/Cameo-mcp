package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;

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

    /** Not instantiable; all members are static. */
    private JsonDiff() {
    }

    /**
     * Diff of two JSON trees as {truncated, changeCount, added, removed, changed, arrayLengthChanges}.
     * maxChanges (at least 1) caps the total entries across all four lists; includeDetails adds before/after values.
     *
     * @param before the earlier tree, may be null
     * @param after the later tree, may be null
     * @param ignorePaths paths to skip, see {@link #ignored}
     * @param maxChanges the most change entries to report; values below 1 count as 1
     * @param includeDetails true to add before and after values to each entry
     * @return the diff
     */
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

    /**
     * Trimmed, non-blank ignore paths as a set (null input gives an empty set).
     *
     * @param paths the paths, may be null
     * @return a new set
     */
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

    /** Recursive walk; JSON null and a missing key are treated alike (both mean absent). */
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
        // Leaves, and nodes whose kind differs (e.g. object vs array), are compared by equality.
        if (!before.equals(after)) {
            addChange(changed, path, before, after, includeDetails, counter);
        }
    }

    /**
     * A path is ignored when it equals or starts with an ignore entry (prefix match, as in the bridge).
     *
     * @param path a path such as {@code $.a.b[0]}
     * @param ignorePaths the ignore entries
     * @return true when the path is ignored
     */
    static boolean ignored(String path, Set<String> ignorePaths) {
        // Plain string prefix: "$.a" also ignores "$.ab", not just "$.a.*" and "$.a[...]".
        for (String ignored : ignorePaths) {
            if (path.equals(ignored) || path.startsWith(ignored)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds one change entry, unless the cap is reached, in which case the diff is marked truncated.
     *
     * @param target the list to add to
     * @param path the changed path
     * @param before the old value, or null
     * @param after the new value, or null
     * @param includeDetails true to copy the values into the entry
     * @param counter the shared count and truncation flag
     */
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

    /** Appends while under the cap; the first entry over the cap sets truncated and stops the walk. */
    private static void addLimited(JsonArray target, JsonObject entry, Counter counter) {
        if (counter.count >= counter.max) {
            counter.truncated = true;
            return;
        }
        target.add(entry);
        counter.count++;
    }

    /** Shared mutable change count and truncation flag for one diff call. */
    private static final class Counter {
        /** Most entries allowed. */
        private final int max;
        /** Entries added so far. */
        private int count;
        /** Whether an entry was dropped because of the cap. */
        private boolean truncated;

        /**
         * Creates a counter.
         *
         * @param max the most entries allowed
         */
        private Counter(int max) {
            this.max = max;
        }
    }
}
