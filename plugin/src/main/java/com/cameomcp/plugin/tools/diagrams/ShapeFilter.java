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

    /** Not instantiable; all members are static. */
    private ShapeFilter() {
    }

    /** Arguments of cameo_list_diagram_shapes; defaults match the tool's documented defaults. */
    public static final class Options {
        /** Page size; default 200. */
        public int limit = 200;
        /** Shapes to skip; default 0. */
        public int offset = 0;
        /** Keep only this presentation class simple name, ignoring case; null or empty keeps all. */
        public String shapeType;
        /** Keep only shapes whose element has this type, ignoring case; null or empty keeps all. */
        public String elementType;
        /** Keep only direct children of this symbol; null or empty keeps all. */
        public String parentPresentationId;
        /** Whether returned shapes keep their bounds; default true. */
        public boolean includeBounds = true;
        /** Whether returned shapes keep their child count; default true. */
        public boolean includeChildCount = true;
        /** Return per-type counts instead of shapes; default false. */
        public boolean summaryOnly = false;
    }

    /**
     * Applies the type and parent filters, then pages the result with offset and limit. Counts describe the
     * filtered set, not the whole diagram. With summaryOnly, per-type counts are returned instead of shapes;
     * otherwise bounds and childCount are dropped from copies when not requested. Out-of-range paging gives
     * an empty page.
     *
     * @param diagramId the diagram id, echoed in the result
     * @param shapes the flat shape list from DiagramSupport
     * @param o the options
     * @return {@code diagramId}, the counts, {@code limit}, {@code offset}, {@code hasMore}, {@code
     *     nextOffset} when
     * there is more, {@code filters}, and either the type counts or the {@code shapes} page
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

    /**
     * Checks one shape against the filters. Type filters are exact matches ignoring case; the parent id must
     * match exactly.
     *
     * @param shape the shape JSON
     * @param o the options
     * @return true when the shape passes every filter
     */
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

    /**
     * Counts of a key's values, sorted by descending count then case-insensitive name.
     *
     * @param items the objects to count over
     * @param key the field whose values are counted; missing or empty values are skipped
     * @return value to count, in the sorted order
     */
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

    /**
     * Whether a string has content.
     *
     * @param s the string, may be null
     * @return true when non-null and non-empty
     */
    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * Whether a field has a non-empty value.
     *
     * @param o the object
     * @param key the field name
     * @return true when {@link #str} gives a non-empty string
     */
    private static boolean nonEmpty(JsonObject o, String key) {
        return !str(o, key).isEmpty();
    }

    /**
     * Reads a field as a string. Missing and null keys read as ""; non-primitive values fall back to their
     * JSON text.
     *
     * @param o the object
     * @param key the field name
     * @return the string, never null
     */
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return "";
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /**
     * Adds a string field, or JSON null when the value is null.
     *
     * @param o the object to add to
     * @param key the field name
     * @param value the value, may be null
     */
    private static void addNullable(JsonObject o, String key, String value) {
        if (value == null) {
            o.add(key, JsonNull.INSTANCE);
        } else {
            o.addProperty(key, value);
        }
    }
}
