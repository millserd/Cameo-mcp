package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonNull;
import com.cameomcp.json.tree.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Filtering, paging and projection of a flat diagram shape list (pure logic). */
public final class ShapeFilter {

    private ShapeFilter() {
    }

    /** Arguments of cameo_list_diagram_shapes; defaults match the tool's documented defaults. */
    public static final class Options {
        public int limit = 200;
        public int offset = 0;
        public String shapeType;
        public String elementType;
        public String parentPresentationId;
        public boolean includeBounds = true;
        public boolean includeChildCount = true;
        public boolean summaryOnly = false;
    }

    /*
     * Applies the type/parent filters, then pages the result with offset/limit. Counts describe the
     * filtered set, not the whole diagram. With summaryOnly, per-type counts are returned instead of
     * shapes; otherwise bounds/childCount are dropped from copies when not requested.
     */
    public static JsonObject filter(String diagramId, JsonArray shapes, Options o) {
        List<JsonObject> filtered = new ArrayList<>();
        for (JsonElement e : shapes) {
            if (e != null && e.isJsonObject() && matches(e.getAsJsonObject(), o)) {
                filtered.add(e.getAsJsonObject());
            }
        }
        // Clamp offset/limit so out-of-range paging returns an empty page instead of throwing.
        int start = Math.min(Math.max(o.offset, 0), filtered.size());
        int end = Math.min(start + Math.max(o.limit, 0), filtered.size());

        JsonObject response = new JsonObject();
        response.addProperty("diagramId", diagramId);
        response.addProperty("count", end - start);
        // "count"/"returned" and "totalCount"/"shapeCount" are duplicates kept for response compatibility.
        response.addProperty("returned", end - start);
        response.addProperty("totalCount", filtered.size());
        response.addProperty("shapeCount", filtered.size());
        response.addProperty("limit", o.limit);
        response.addProperty("offset", start);
        response.addProperty("hasMore", end < filtered.size());
        JsonObject filters = new JsonObject();
        addNullable(filters, "shapeType", o.shapeType);
        addNullable(filters, "elementType", o.elementType);
        addNullable(filters, "parentPresentationId", o.parentPresentationId);
        filters.addProperty("includeBounds", o.includeBounds);
        filters.addProperty("includeChildCount", o.includeChildCount);
        filters.addProperty("summaryOnly", o.summaryOnly);
        response.add("filters", filters);
        if (end < filtered.size()) {
            response.addProperty("nextOffset", end);
        }

        if (o.summaryOnly) {
            response.add("shapeTypeCounts", countByKey(filtered, "shapeType"));
            response.add("elementTypeCounts", countByKey(filtered, "elementType"));
            int parented = 0;
            for (JsonObject s : filtered) {
                if (nonEmpty(s, "parentPresentationId")) {
                    parented++;
                }
            }
            response.addProperty("parentedShapeCount", parented);
            return response;
        }

        JsonArray page = new JsonArray();
        for (JsonObject s : filtered.subList(start, end)) {
            JsonObject projected = s;
            // Copy before removing fields so the caller's shape objects are not mutated.
            if (!o.includeBounds || !o.includeChildCount) {
                projected = s.deepCopy();
                if (!o.includeBounds) {
                    projected.remove("bounds");
                }
                if (!o.includeChildCount) {
                    projected.remove("childCount");
                }
            }
            page.add(projected);
        }
        response.add("shapes", page);
        return response;
    }

    // Type filters are case-insensitive exact matches; the parent id must match exactly.
    private static boolean matches(JsonObject shape, Options o) {
        if (notBlank(o.shapeType) && !str(shape, "shapeType").toLowerCase(Locale.ROOT)
                .equals(o.shapeType.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (notBlank(o.elementType) && !str(shape, "elementType").toLowerCase(Locale.ROOT)
                .equals(o.elementType.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !notBlank(o.parentPresentationId) || str(shape, "parentPresentationId").equals(o.parentPresentationId);
    }

    /** Counts of a key's values, sorted by descending count then case-insensitive name. */
    public static JsonObject countByKey(List<JsonObject> items, String key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonObject item : items) {
            if (nonEmpty(item, key)) {
                counts.merge(str(item, key), 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().toLowerCase(Locale.ROOT).compareTo(b.getKey().toLowerCase(Locale.ROOT));
        });
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Integer> e : entries) {
            out.addProperty(e.getKey(), e.getValue());
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean nonEmpty(JsonObject o, String key) {
        return !str(o, key).isEmpty();
    }

    // Missing/null keys read as ""; non-primitive values fall back to their JSON text.
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return "";
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static void addNullable(JsonObject o, String key, String value) {
        if (value == null) {
            o.add(key, JsonNull.INSTANCE);
        } else {
            o.addProperty(key, value);
        }
    }
}
