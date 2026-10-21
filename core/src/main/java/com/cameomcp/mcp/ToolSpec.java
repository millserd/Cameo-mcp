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

    /** Tool name: the public API agents call. Lower snake_case, prefixed cameo_ by convention. */
    private final String name;
    /** Catalog family (model, diagrams, ...) used to group tools in docs and in cameo_list_tools. */
    private final String family;
    /** Explicit display title, or null to derive one from the name. */
    private String title;
    /** Description agents read; may be empty. */
    private String description = "";
    /** How the tool touches the model; READ by default. */
    private Kind kind = Kind.READ;
    /** False for tools that work without an open project. */
    private boolean requiresProject = true;
    /** MCP idempotentHint: repeating the call has no further effect. */
    private boolean idempotent;
    /** MCP openWorldHint: the tool reaches outside the model. */
    private boolean openWorld;
    /**
     * Declared time limit, published for reference. Nothing enforces it centrally; Edt applies its own
     * limits.
     */
    private long timeoutSeconds = 60;
    /** JSON Schema "properties", in declaration order so tools/list reads naturally. */
    private final Map<String, Object> properties = new LinkedHashMap<>();
    /** Names of required parameters, in declaration order. */
    private final List<String> required = new ArrayList<>();

    /**
     * Validates the name and creates an empty READ spec.
     *
     * @param name tool name
     * @param family catalog family
     * @throws IllegalArgumentException if the name is not 3 to 64 lower-case letters, digits or underscores
     *     starting with a letter
     */
    private ToolSpec(String name, String family) {
        if (!name.matches("[a-z][a-z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Bad tool name: " + name);
        }
        this.name = name;
        this.family = family;
    }

    /**
     * Starts a spec. The name must be 3 to 64 characters of lower-case letters, digits and underscores.
     *
     * @param name tool name, for example cameo_get_element
     * @param family catalog family, for example "model"
     * @return a new READ spec with no parameters
     * @throws IllegalArgumentException if the name is malformed
     */
    public static ToolSpec tool(String name, String family) {
        return new ToolSpec(name, family);
    }

    // ------------------------------------------------------------- metadata

    /**
     * Overrides the human-readable title derived from the name.
     *
     * @param title the display title
     * @return this spec
     */
    public ToolSpec title(String title) {
        this.title = title;
        return this;
    }

    /**
     * The description agents read to decide when and how to call the tool. Write it for them.
     *
     * @param description the description, plain text, one paragraph
     * @return this spec
     */
    public ToolSpec desc(String description) {
        this.description = description;
        return this;
    }

    /**
     * Marks the tool read-only and idempotent (the default kind).
     *
     * @return this spec
     */
    public ToolSpec readOnly() {
        this.kind = Kind.READ;
        this.idempotent = true;
        return this;
    }

    /**
     * Marks the tool as changing the model.
     *
     * @return this spec
     */
    public ToolSpec write() {
        this.kind = Kind.WRITE;
        return this;
    }

    /**
     * Marks the tool as changing the model in a way that can lose information (delete, overwrite).
     *
     * @return this spec
     */
    public ToolSpec destructive() {
        this.kind = Kind.DESTRUCTIVE;
        return this;
    }

    /**
     * Marks the tool as touching files or the UI but not the model.
     *
     * @return this spec
     */
    public ToolSpec sideEffect() {
        this.kind = Kind.SIDE_EFFECT;
        return this;
    }

    /**
     * Hints that repeating the call with the same arguments has no further effect.
     *
     * @return this spec
     */
    public ToolSpec idempotent() {
        this.idempotent = true;
        return this;
    }

    /**
     * The tool reaches outside the model (file system, network, external products).
     *
     * @return this spec
     */
    public ToolSpec openWorld() {
        this.openWorld = true;
        return this;
    }

    /**
     * The tool works without an open project (status, diagnostics, catalogs).
     *
     * @return this spec
     */
    public ToolSpec noProject() {
        this.requiresProject = false;
        return this;
    }

    /**
     * Overrides the declared 60-second time limit.
     *
     * @param seconds the limit, in seconds
     * @return this spec
     */
    public ToolSpec timeout(long seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }

    // ------------------------------------------------------------- parameters

    /**
     * Adds one property to the input schema. Every typed helper below funnels through here.
     *
     * @param pname parameter name
     * @param schema the JSON schema of the parameter; the description is added to it
     * @param desc the parameter description, or null/empty for none
     * @param req whether the parameter is required
     * @return this spec
     * @throws IllegalArgumentException if the parameter is already declared
     */
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

    /**
     * Optional string parameter.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec str(String pname, String desc) {
        return str(pname, desc, false);
    }

    /**
     * String parameter; pass {@link #REQUIRED} as {@code req} to make it mandatory.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @return this spec
     */
    public ToolSpec str(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "string"), desc, req);
    }

    /**
     * Optional string with a default value.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param dflt the default advertised in the schema (documentation only; the handler applies its own default)
     * @return this spec
     */
    public ToolSpec str(String pname, String desc, String dflt) {
        return param(pname, Json.obj("type", "string", "default", dflt), desc, false);
    }

    /**
     * String limited to the given values; the first value is NOT implied as default.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @param values the allowed values
     * @return this spec
     */
    public ToolSpec enumStr(String pname, String desc, boolean req, String... values) {
        return param(pname, Json.obj("type", "string", "enum", Arrays.asList(values)), desc, req);
    }

    /**
     * Optional string limited to the given values, with a default.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param dflt the default advertised in the schema (documentation only; the handler applies its own default)
     * @param values the allowed values
     * @return this spec
     */
    public ToolSpec enumStrDefault(String pname, String desc, String dflt, String... values) {
        return param(pname, Json.obj("type", "string", "enum", Arrays.asList(values), "default", dflt), desc, false);
    }

    /**
     * Optional integer parameter.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec integer(String pname, String desc) {
        return param(pname, Json.obj("type", "integer"), desc, false);
    }

    /**
     * Integer parameter, optionally required.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @return this spec
     */
    public ToolSpec integer(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "integer"), desc, req);
    }

    /**
     * Optional integer with a default.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param dflt the default advertised in the schema (documentation only; the handler applies its own default)
     * @return this spec
     */
    public ToolSpec integer(String pname, String desc, long dflt) {
        return param(pname, Json.obj("type", "integer", "default", dflt), desc, false);
    }

    /**
     * Optional integer with a default and an inclusive range.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param dflt the default advertised in the schema (documentation only; the handler applies its own default)
     * @param min smallest allowed value
     * @param max largest allowed value
     * @return this spec
     */
    public ToolSpec integer(String pname, String desc, long dflt, long min, long max) {
        return param(pname, Json.obj("type", "integer", "default", dflt, "minimum", min, "maximum", max), desc, false);
    }

    /**
     * Optional number parameter.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec number(String pname, String desc) {
        return param(pname, Json.obj("type", "number"), desc, false);
    }

    /**
     * Optional number with a default.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param dflt the default advertised in the schema (documentation only; the handler applies its own default)
     * @return this spec
     */
    public ToolSpec number(String pname, String desc, double dflt) {
        return param(pname, Json.obj("type", "number", "default", dflt), desc, false);
    }

    /**
     * Number parameter, optionally required.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @return this spec
     */
    public ToolSpec number(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "number"), desc, req);
    }

    /**
     * Optional boolean parameter.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec bool(String pname, String desc) {
        return param(pname, Json.obj("type", "boolean"), desc, false);
    }

    /**
     * Optional boolean with a default.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param dflt the default advertised in the schema (documentation only; the handler applies its own default)
     * @return this spec
     */
    public ToolSpec bool(String pname, String desc, boolean dflt) {
        return param(pname, Json.obj("type", "boolean", "default", dflt), desc, false);
    }

    /**
     * Optional array of strings.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec strArray(String pname, String desc) {
        return strArray(pname, desc, false);
    }

    /**
     * Array of strings, optionally required.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @return this spec
     */
    public ToolSpec strArray(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "array", "items", Json.obj("type", "string")), desc, req);
    }

    /**
     * Free-form JSON object.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec obj(String pname, String desc) {
        return obj(pname, desc, false);
    }

    /**
     * Free-form JSON object, optionally required.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @return this spec
     */
    public ToolSpec obj(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "object", "additionalProperties", true), desc, req);
    }

    /**
     * Array of free-form objects.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec objArray(String pname, String desc) {
        return objArray(pname, desc, false);
    }

    /**
     * Array of free-form objects, optionally required.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @return this spec
     */
    public ToolSpec objArray(String pname, String desc, boolean req) {
        return param(pname, Json.obj("type", "array", "items", Json.obj("type", "object", "additionalProperties", true)), desc, req);
    }

    /**
     * Array of objects with a declared item schema (properties map of JSON schemas).
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @param itemProperties item property name to JSON schema
     * @param itemRequired names of required item properties
     * @return this spec
     */
    public ToolSpec objArray(String pname, String desc, boolean req, Map<String, Object> itemProperties, String... itemRequired) {
        Map<String, Object> item = Json.obj("type", "object", "properties", itemProperties);
        if (itemRequired.length > 0) {
            item.put("required", Arrays.asList(itemRequired));
        }
        return param(pname, Json.obj("type", "array", "items", item), desc, req);
    }

    /**
     * Any JSON value (string, number, boolean, object, array).
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @return this spec
     */
    public ToolSpec any(String pname, String desc) {
        return param(pname, new LinkedHashMap<>(), desc, false);
    }

    /**
     * Any JSON value, optionally required.
     *
     * @param pname parameter name (snake_case)
     * @param desc what the agent should pass
     * @param req true (or {@link #REQUIRED}) to make the parameter mandatory
     * @return this spec
     */
    public ToolSpec any(String pname, String desc, boolean req) {
        return param(pname, new LinkedHashMap<>(), desc, req);
    }

    /**
     * Escape hatch: parameter with an explicit JSON schema.
     *
     * @param pname parameter name (snake_case)
     * @param schema the full JSON schema, copied (shallowly) into the spec; include its own description
     * @param req whether the parameter is required
     * @return this spec
     */
    public ToolSpec schema(String pname, Map<String, Object> schema, boolean req) {
        return param(pname, new LinkedHashMap<>(schema), null, req);
    }

    // ------------------------------------------------------------- accessors

    /**
     * The tool name agents call.
     *
     * @return the tool name
     */
    public String name() {
        return name;
    }

    /**
     * Catalog family, such as "model" or "diagrams".
     *
     * @return the family name
     */
    public String family() {
        return family;
    }

    /**
     * Returns the description agents read, possibly empty.
     *
     * @return the description agents read, possibly empty
     */
    public String description() {
        return description;
    }

    /**
     * How the tool touches the model.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * False for tools that work without an open project.
     *
     * @return whether the tool needs an open project
     */
    public boolean requiresProject() {
        return requiresProject;
    }

    /**
     * True for {@link Kind#READ} tools.
     *
     * @return whether the kind is READ
     */
    public boolean isReadOnly() {
        return kind == Kind.READ;
    }

    /**
     * Declared time limit for one call, in seconds.
     *
     * @return seconds
     */
    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Parameter name to JSON schema, read-only.
     *
     * @return an unmodifiable view of the parameter schemas
     */
    public Map<String, Object> parameters() {
        return Collections.unmodifiableMap(properties);
    }

    /**
     * Names of required parameters, read-only.
     *
     * @return an unmodifiable view of the required names
     */
    public List<String> requiredParameters() {
        return Collections.unmodifiableList(required);
    }

    /**
     * The explicit title, or one derived from the name: cameo_get_element becomes "Get element".
     *
     * @return the display title
     */
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

    /**
     * The JSON Schema object for the tool's arguments. Properties are copied so callers can't mutate the
     * spec.
     *
     * @return a new schema map
     */
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = Json.obj("type", "object", "properties", Json.copy(properties));
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        return schema;
    }

    /**
     * The MCP {@code Tool} object as returned by {@code tools/list}.
     *
     * @return a new map with name, title, description, inputSchema and annotations
     */
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
