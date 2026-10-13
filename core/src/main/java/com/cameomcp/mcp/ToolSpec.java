package com.cameomcp.mcp;

import com.cameomcp.json.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The public contract of one MCP tool: name, description and JSON input schema.
 * <p>
 * Specs live in the Cameo-free {@code core} module so the stdio launcher can list
 * tools even while Cameo is closed, and so tests can check the catalog without Cameo.
 * Build them with the fluent API:
 * <pre>
 * ToolSpec.tool("cameo_get_element", "elements")
 *     .desc("Get full details of one element.")
 *     .str("element_id", "Element ID from a query result.", REQUIRED)
 *     .readOnly();
 * </pre>
 */
public final class ToolSpec {

    /** Marker for required parameters. */
    public static final boolean REQUIRED = true;

    /** How a tool touches the model; drives MCP annotations and threading. */
    public enum Kind {
        /** Reads only; never changes the model or the UI. */
        READ,
        /** Changes the model (runs inside an undoable Cameo session). */
        WRITE,
        /** Changes the model and can remove information. */
        DESTRUCTIVE,
        /** Touches UI or files but not the model (exports, refreshes). */
        SIDE_EFFECT
    }

    // Tool names are the public API agents call: lower snake_case, prefixed cameo_ by convention.
    private final String name;
    // Catalog family (model, diagrams, ...) used to group tools in docs and in cameo_list_tools.
    private final String family;
    private String title;
    private String description = "";
    private Kind kind = Kind.READ;
    private boolean requiresProject = true;
    private boolean idempotent;
    private boolean openWorld;
    // Upper bound the plugin's CallGuard enforces; long operations (reports, simulation) raise it.
    private long timeoutSeconds = 60;
    // JSON Schema "properties", in declaration order so tools/list reads naturally.
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolSpec(String name, String family) {
        if (!name.matches("[a-z][a-z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Bad tool name: " + name);
        }
        this.name = name;
        this.family = family;
    }

    /** Starts a spec. The name must be 3 to 64 characters of lower-case letters, digits and underscores. */
    public static ToolSpec tool(String name, String family) {
        return new ToolSpec(name, family);
    }

    // ------------------------------------------------------------- metadata

    /** Overrides the human-readable title derived from the name. */
    public ToolSpec title(String title) {
        this.title = title;
        return this;
    }

    /** The description agents read to decide when and how to call the tool. Write it for them. */
    public ToolSpec desc(String description) {
        this.description = description;
        return this;
    }

    /** Marks the tool read-only and idempotent (the default kind). */
    public ToolSpec readOnly() {
        this.kind = Kind.READ;
        this.idempotent = true;
        return this;
    }

    /** Marks the tool as changing the model. */
    public ToolSpec write() {
        this.kind = Kind.WRITE;
        return this;
    }

    /** Marks the tool as changing the model in a way that can lose information (delete, overwrite). */
    public ToolSpec destructive() {
        this.kind = Kind.DESTRUCTIVE;
        return this;
    }

    /** Marks the tool as touching files or the UI but not the model. */
    public ToolSpec sideEffect() {
        this.kind = Kind.SIDE_EFFECT;
        return this;
    }

    /** Hints that repeating the call with the same arguments has no further effect. */
    public ToolSpec idempotent() {
        this.idempotent = true;
        return this;
    }

    /** The tool reaches outside the model (file system, network, external products). */
    public ToolSpec openWorld() {
        this.openWorld = true;
        return this;
    }

    /** The tool works without an open project (status, diagnostics, catalogs). */
    public ToolSpec noProject() {
        this.requiresProject = false;
        return this;
    }

    /** Overrides the 60-second default time limit. */
    public ToolSpec timeout(long seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }

    // ------------------------------------------------------------- parameters

    // Adds one property to the input schema. Every typed helper below funnels through here.
    private ToolSpec param(String pname, Map<String, Object> schema, String desc, boolean req) {
        if (properties.containsKey(pname)) {
            throw new IllegalArgumentException(name + ": duplicate parameter " + pname);
        }
        if (desc != null && !desc.isEmpty()) {
            schema.put("description", desc);
        }
        properties.put(pname, schema);
        if (req) {
            required.add(pname);
        }
        return this;
    }

    /** Optional string parameter. */
    public ToolSpec str(String pname, String desc) {
        return str(pname, desc, false);
    }

    /** String parameter; pass {@link #REQUIRED} as {@code req} to make it mandatory. */
    public ToolSpec str(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "string"), desc, req);
    }

    /** Optional string with a default value. */
    public ToolSpec str(String pname, String desc, String dflt) {
        return param(pname, Json.obj("type", "string", "default", dflt), desc, false);
    }

    /** String limited to the given values; the first value is NOT implied as default. */
    public ToolSpec enumStr(String pname, String desc, boolean req, String... values) {
        return param(pname, Json.obj("type", "string", "enum", Arrays.asList(values)), desc, req);
    }

    /** Optional string limited to the given values, with a default. */
    public ToolSpec enumStrDefault(String pname, String desc, String dflt, String... values) {
        return param(pname, Json.obj("type", "string", "enum", Arrays.asList(values), "default", dflt), desc, false);
    }

    /** Optional integer parameter. */
    public ToolSpec integer(String pname, String desc) {
        return param(pname, Json.obj("type", "integer"), desc, false);
    }

    /** Integer parameter, optionally required. */
    public ToolSpec integer(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "integer"), desc, req);
    }

    /** Optional integer with a default. */
    public ToolSpec integer(String pname, String desc, long dflt) {
        return param(pname, Json.obj("type", "integer", "default", dflt), desc, false);
    }

    /** Optional integer with a default and an inclusive range. */
    public ToolSpec integer(String pname, String desc, long dflt, long min, long max) {
        return param(pname, Json.obj("type", "integer", "default", dflt, "minimum", min, "maximum", max), desc, false);
    }

    /** Optional number parameter. */
    public ToolSpec number(String pname, String desc) {
        return param(pname, Json.obj("type", "number"), desc, false);
    }

    /** Optional number with a default. */
    public ToolSpec number(String pname, String desc, double dflt) {
        return param(pname, Json.obj("type", "number", "default", dflt), desc, false);
    }

    /** Number parameter, optionally required. */
    public ToolSpec number(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "number"), desc, req);
    }

    /** Optional boolean parameter. */
    public ToolSpec bool(String pname, String desc) {
        return param(pname, Json.obj("type", "boolean"), desc, false);
    }

    /** Optional boolean with a default. */
    public ToolSpec bool(String pname, String desc, boolean dflt) {
        return param(pname, Json.obj("type", "boolean", "default", dflt), desc, false);
    }

    /** Optional array of strings. */
    public ToolSpec strArray(String pname, String desc) {
        return strArray(pname, desc, false);
    }

    /** Array of strings, optionally required. */
    public ToolSpec strArray(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "array", "items", Json.obj("type", "string")), desc, req);
    }

    /** Free-form JSON object. */
    public ToolSpec obj(String pname, String desc) {
        return obj(pname, desc, false);
    }

    /** Free-form JSON object, optionally required. */
    public ToolSpec obj(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "object", "additionalProperties", true), desc, req);
    }

    /** Array of free-form objects. */
    public ToolSpec objArray(String pname, String desc) {
        return objArray(pname, desc, false);
    }

    /** Array of free-form objects, optionally required. */
    public ToolSpec objArray(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "array", "items", Json.obj("type", "object", "additionalProperties", true)), desc, req);
    }

    /** Array of objects with a declared item schema (properties map of JSON schemas). */
    public ToolSpec objArray(String pname, String desc, boolean req, Map<String, Object> itemProperties, String... itemRequired) {
        Map<String, Object> item = Json.obj("type", "object", "properties", itemProperties);
        if (itemRequired.length > 0) {
            item.put("required", Arrays.asList(itemRequired));
        }
        return param(pname, Json.obj("type", "array", "items", item), desc, req);
    }

    /** Any JSON value (string, number, boolean, object, array). */
    public ToolSpec any(String pname, String desc) {
        return param(pname, new LinkedHashMap<>(), desc, false);
    }

    /** Any JSON value, optionally required. */
    public ToolSpec any(String pname, String desc, boolean req) {
        return param(pname, new LinkedHashMap<>(), desc, req);
    }

    /** Escape hatch: parameter with an explicit JSON schema. */
    public ToolSpec schema(String pname, Map<String, Object> schema, boolean req) {
        return param(pname, new LinkedHashMap<>(schema), null, req);
    }

    // ------------------------------------------------------------- accessors

    /** The tool name agents call. */
    public String name() {
        return name;
    }

    /** Catalog family, such as "model" or "diagrams". */
    public String family() {
        return family;
    }

    public String description() {
        return description;
    }

    /** How the tool touches the model. */
    public Kind kind() {
        return kind;
    }

    /** False for tools that work without an open project. */
    public boolean requiresProject() {
        return requiresProject;
    }

    /** True for {@link Kind#READ} tools. */
    public boolean isReadOnly() {
        return kind == Kind.READ;
    }

    /** Time limit for one call, in seconds. */
    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    /** Parameter name to JSON schema, read-only. */
    public Map<String, Object> parameters() {
        return Collections.unmodifiableMap(properties);
    }

    /** Names of required parameters, read-only. */
    public List<String> requiredParameters() {
        return Collections.unmodifiableList(required);
    }

    /** The explicit title, or one derived from the name: cameo_get_element becomes "Get element". */
    public String title() {
        if (title != null) {
            return title;
        }
        String base = name.startsWith("cameo_") ? name.substring(6) : name;
        StringBuilder sb = new StringBuilder();
        for (String w : base.split("_")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(sb.length() == 0 ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : w);
        }
        return sb.toString();
    }

    /** The JSON Schema object for the tool's arguments. Properties are copied so callers can't mutate the spec. */
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = Json.obj("type", "object", "properties", Json.copy(properties));
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        return schema;
    }

    /** The MCP {@code Tool} object as returned by {@code tools/list}. */
    public Map<String, Object> toMcp() {
        Map<String, Object> annotations = Json.obj(
                "title", title(),
                "readOnlyHint", kind == Kind.READ,
                "destructiveHint", kind == Kind.DESTRUCTIVE,
                "idempotentHint", idempotent,
                "openWorldHint", openWorld);
        return Json.obj(
                "name", name,
                "title", title(),
                "description", description,
                "inputSchema", inputSchema(),
                "annotations", annotations);
    }

    @Override
    public String toString() {
        return "ToolSpec[" + name + "]";
    }
}
