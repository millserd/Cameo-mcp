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

    private final String name;
    private final String family;
    private String title;
    private String description = "";
    private Kind kind = Kind.READ;
    private boolean requiresProject = true;
    private boolean idempotent;
    private boolean openWorld;
    private long timeoutSeconds = 60;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolSpec(String name, String family) {
        if (!name.matches("[a-z][a-z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Bad tool name: " + name);
        }
        this.name = name;
        this.family = family;
    }

    public static ToolSpec tool(String name, String family) {
        return new ToolSpec(name, family);
    }

    // ------------------------------------------------------------- metadata

    public ToolSpec title(String title) {
        this.title = title;
        return this;
    }

    public ToolSpec desc(String description) {
        this.description = description;
        return this;
    }

    public ToolSpec readOnly() {
        this.kind = Kind.READ;
        this.idempotent = true;
        return this;
    }

    public ToolSpec write() {
        this.kind = Kind.WRITE;
        return this;
    }

    public ToolSpec destructive() {
        this.kind = Kind.DESTRUCTIVE;
        return this;
    }

    public ToolSpec sideEffect() {
        this.kind = Kind.SIDE_EFFECT;
        return this;
    }

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

    public ToolSpec timeout(long seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }

    // ------------------------------------------------------------- parameters

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

    public ToolSpec str(String pname, String desc) {
        return str(pname, desc, false);
    }

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

    public ToolSpec enumStrDefault(String pname, String desc, String dflt, String... values) {
        return param(pname, Json.obj("type", "string", "enum", Arrays.asList(values), "default", dflt), desc, false);
    }

    public ToolSpec integer(String pname, String desc) {
        return param(pname, Json.obj("type", "integer"), desc, false);
    }

    public ToolSpec integer(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "integer"), desc, req);
    }

    public ToolSpec integer(String pname, String desc, long dflt) {
        return param(pname, Json.obj("type", "integer", "default", dflt), desc, false);
    }

    public ToolSpec integer(String pname, String desc, long dflt, long min, long max) {
        return param(pname, Json.obj("type", "integer", "default", dflt, "minimum", min, "maximum", max), desc, false);
    }

    public ToolSpec number(String pname, String desc) {
        return param(pname, Json.obj("type", "number"), desc, false);
    }

    public ToolSpec number(String pname, String desc, double dflt) {
        return param(pname, Json.obj("type", "number", "default", dflt), desc, false);
    }

    public ToolSpec number(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "number"), desc, req);
    }

    public ToolSpec bool(String pname, String desc) {
        return param(pname, Json.obj("type", "boolean"), desc, false);
    }

    public ToolSpec bool(String pname, String desc, boolean dflt) {
        return param(pname, Json.obj("type", "boolean", "default", dflt), desc, false);
    }

    public ToolSpec strArray(String pname, String desc) {
        return strArray(pname, desc, false);
    }

    public ToolSpec strArray(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "array", "items", Json.obj("type", "string")), desc, req);
    }

    /** Free-form JSON object. */
    public ToolSpec obj(String pname, String desc) {
        return obj(pname, desc, false);
    }

    public ToolSpec obj(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "object", "additionalProperties", true), desc, req);
    }

    /** Array of free-form objects. */
    public ToolSpec objArray(String pname, String desc) {
        return objArray(pname, desc, false);
    }

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

    public ToolSpec any(String pname, String desc, boolean req) {
        return param(pname, new LinkedHashMap<>(), desc, req);
    }

    /** Escape hatch: parameter with an explicit JSON schema. */
    public ToolSpec schema(String pname, Map<String, Object> schema, boolean req) {
        return param(pname, new LinkedHashMap<>(schema), null, req);
    }

    // ------------------------------------------------------------- accessors

    public String name() {
        return name;
    }

    public String family() {
        return family;
    }

    public String description() {
        return description;
    }

    public Kind kind() {
        return kind;
    }

    public boolean requiresProject() {
        return requiresProject;
    }

    public boolean isReadOnly() {
        return kind == Kind.READ;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    public Map<String, Object> parameters() {
        return Collections.unmodifiableMap(properties);
    }

    public List<String> requiredParameters() {
        return Collections.unmodifiableList(required);
    }

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
