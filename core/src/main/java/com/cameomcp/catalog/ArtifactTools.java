package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.util.Arrays;
import java.util.List;

import static com.cameomcp.mcp.ToolSpec.REQUIRED;
import static com.cameomcp.mcp.ToolSpec.tool;

/**
 * Native analysis artifacts: dependency matrices, Generic Tables, Relation Maps and the
 * traceability graph, plus in-memory inspection snapshots/diffs and reflection probes used
 * to investigate what Cameo's UI changes.
 */
public final class ArtifactTools {

    // Family name used in docs and in cameo_list_tools filters.
    public static final String FAMILY = "artifacts";

    /** Seconds a relation-map refresh/expand/render may take at most (the tools clamp to 600). */
    private static final long LONG_TIMEOUT = 620;

    private static final String MATRIX_KINDS = "refine (Refine Requirement Matrix), derive (Derive Requirement Matrix), "
            + "satisfy (Satisfy Requirement Matrix), allocation (SysML Allocation Matrix), dependency (Dependency Matrix)";

    private ArtifactTools() {
    }

    /**
     * The family's specs, in the order clients see them. Descriptions and parameter help are the
     * text agents read when choosing a tool, so they say what the tool returns and which tool to call next.
     */
    public static List<ToolSpec> specs() {
        return Arrays.asList(
                // ------------------------------------------------------------ matrices
                tool("cameo_list_matrix_kinds", FAMILY).noProject().readOnly()
                        .desc("List the validated native matrix kinds (" + MATRIX_KINDS + ") with their accepted "
                                + "aliases and example row/column type domains. Use the 'kind' token with "
                                + "cameo_create_matrix and cameo_list_matrices."),
                tool("cameo_list_matrices", FAMILY).readOnly()
                        .desc("List supported native matrix artifacts in the open project (id, name, kind, native "
                                + "matrixType, owner). This targets Cameo's native matrix artifacts, not diagrams or "
                                + "Generic Tables. Read one with cameo_get_matrix.")
                        .str("kind", "Optional kind filter: refine, derive, satisfy, allocation or dependency. Aliases "
                                + "such as 'Refine Requirement Matrix' or 'System Allocation Matrix' are normalized.")
                        .str("owner_id", "Optional package/namespace ID that owns the matrix artifact."),
                tool("cameo_get_matrix", FAMILY).readOnly().timeout(150)
                        .desc("Read one native matrix (" + MATRIX_KINDS + ") with row/column scopes and type "
                                + "domains, the row and column elements, and only the populated cells with the "
                                + "relationships that cause them (populatedCells[].dependencies[].cause). Empty cells "
                                + "are omitted: infer gaps from the rows x columns cross-product (totalCellCount). "
                                + "Needs the Dependency Matrix plugin.")
                        .str("matrix_id", "Diagram ID of the matrix artifact (from cameo_list_matrices).", REQUIRED),
                tool("cameo_create_matrix", FAMILY).write().timeout(150)
                        .desc("Create a native matrix artifact: " + MATRIX_KINDS + ". The matrix is configured "
                                + "row-to-column and shows ALL rows/columns inside the scope so missing traceability "
                                + "stays visible. Returns the created matrix with its initial rows, columns and cells "
                                + "plus a receipt. Example: kind='satisfy', parent_id=<package>, row_types=['Block'], "
                                + "column_types=['Requirement']. Needs the Dependency Matrix plugin.")
                        .str("kind", "Matrix kind: refine, derive, satisfy, allocation or dependency (aliases such as "
                                + "'Satisfy Requirement Matrix' are accepted).", REQUIRED)
                        .str("parent_id", "Namespace/package ID that will own the matrix.", REQUIRED)
                        .str("name", "Display name. Defaults to Cameo's native matrix type name.")
                        .str("scope_id", "Shared scope root for rows and columns. Defaults to parent_id when "
                                + "row_scope_id/column_scope_id are omitted.")
                        .str("row_scope_id", "Explicit row scope root (overrides scope_id for rows).")
                        .str("column_scope_id", "Explicit column scope root (overrides scope_id for columns).")
                        .strArray("row_types", "Row-domain type tokens: a UML metaclass (Activity, Property, UseCase) "
                                + "or a stereotype (Block, Requirement, valueProperty). Omit for the kind's defaults; "
                                + "an empty list is rejected.")
                        .strArray("column_types", "Column-domain type tokens, same rules as row_types."),

                // ------------------------------------------------------------ generic tables
                tool("cameo_list_generic_tables", FAMILY).readOnly()
                        .desc("List native Generic Table artifacts in the open project (id, name, type, owner). "
                                + "Read one with cameo_get_generic_table."),
                tool("cameo_get_generic_table", FAMILY).readOnly().timeout(150)
                        .desc("Get one native Generic Table: scope, row elements, columns (id + display name) and "
                                + "every cell value as text (cells[].row, columnId, value). The table is refreshed "
                                + "first so values are current. Needs the Generic Table plugin.")
                        .str("table_id", "ID of the Generic Table diagram (from cameo_list_generic_tables).", REQUIRED),
                tool("cameo_list_generic_table_columns", FAMILY).readOnly()
                        .desc("List the native Generic Table column IDs that are possible for an element or element "
                                + "type, to pass as column_ids to cameo_create_generic_table. Provide element_id or "
                                + "element_type.")
                        .str("element_id", "Basis element ID, such as an existing Block.")
                        .str("element_type", "Element type token, such as 'Block' (stereotype) or 'Class' (metaclass)."),
                tool("cameo_create_generic_table", FAMILY).write().timeout(150)
                        .desc("Create and configure a native Generic Table: element types, scope, explicit rows and "
                                + "columns; then refresh it and return the populated table. Get valid column ids from "
                                + "cameo_list_generic_table_columns first. Needs the Generic Table plugin.")
                        .str("parent_id", "Namespace/package ID that will own the table.", REQUIRED)
                        .str("name", "Display name (default 'Generic Table').")
                        .strArray("element_types", "Table element types, such as ['Block'] or ['Requirement'] "
                                + "(stereotype or metaclass names).")
                        .strArray("scope_ids", "Table scope element IDs (packages whose contents fill the table).")
                        .strArray("row_element_ids", "Explicit row element IDs.")
                        .strArray("column_ids", "Generic Table column IDs to display."),

                // ------------------------------------------------------------ relation maps
                tool("cameo_list_relation_maps", FAMILY).readOnly()
                        .desc("List native Relation Map artifacts in the open project (id, name, type, owner)."),
                tool("cameo_get_relation_map", FAMILY).readOnly()
                        .desc("Get one native Relation Map with its persisted graph settings: context element, scope "
                                + "roots, element types, dependency criteria, depth, layout, display flags and "
                                + "whether Cameo considers the settings valid. Needs the Relation Map plugin.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED),
                relationMapSettings(tool("cameo_create_relation_map", FAMILY).write().timeout(150)
                        .desc("Create and configure a native Relation Map. Prefer this over cameo_create_diagram "
                                + "(type RelationMap) when the map must render relationships: it writes Cameo's native "
                                + "graph settings (context element, scope roots, element-type filters, dependency "
                                + "criteria, depth, layout, display flags). Afterwards use cameo_set_relation_map_criteria "
                                + "with templates, then cameo_verify_relation_map / cameo_render_relation_map.")
                        .str("parent_id", "Namespace/package ID that will own the Relation Map.", REQUIRED)
                        .str("name", "Display name (default 'Relation Map').")),
                relationMapSettings(tool("cameo_configure_relation_map", FAMILY).write().timeout(150)
                        .desc("Update native graph settings of an existing Relation Map. Only the parameters you pass "
                                + "change; returns the map with its resulting settings.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)),
                tool("cameo_refresh_relation_map", FAMILY).write().timeout(LONG_TIMEOUT)
                        .desc("Refresh a native Relation Map after model or settings changes (runs Cameo's own "
                                + "refresh). Can block Cameo for a long time on large maps, so call it deliberately.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .number("timeout", "Timeout in seconds (1-600).", 120.0),
                tool("cameo_dump_relation_map_raw_settings", FAMILY).readOnly()
                        .desc("Dump native Relation Map settings for UI-diff investigation: the sanitized settings plus "
                                + "every reflected public no-arg GraphSettings getter. Use before and after a human "
                                + "changes the map in the Cameo UI (or use cameo_create_snapshot/cameo_diff_snapshots).")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .bool("include_raw", "Include class names and nested getter values of non-JSON values.", false)
                        .bool("summary_only", "Summarize collections and maps as counts only.", false),
                tool("cameo_list_relation_map_presentations", FAMILY).readOnly()
                        .desc("List loaded Relation Map presentation elements (nodes, paths, legend symbols) with "
                                + "counts by type, paged. Use it to tell graph traversal success apart from rendered "
                                + "expansion/export failure. Property dumps are opt-in and summarized by default.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .bool("include_properties", "Include each presentation's property manager and children.", false)
                        .bool("include_raw", "Include raw class names/getter values in property dumps.", false)
                        .bool("summary_only", "Summarize collection-valued properties as counts.", true)
                        .integer("limit", "Page size.", 250)
                        .integer("offset", "Page start.", 0),
                tool("cameo_list_relation_map_criteria_templates", FAMILY).noProject().readOnly()
                        .desc("List built-in Relation Map criteria templates (e.g. satisfy.both, refine.both, "
                                + "deriveReqt.both, allocate.both, dependency.direct) for cameo_set_relation_map_criteria. "
                                + "Templates with verifiedWithUiDiff=false are placeholders until a snapshot diff from "
                                + "the Cameo UI confirms the exact native expression."),
                tool("cameo_set_relation_map_criteria", FAMILY).write().timeout(LONG_TIMEOUT)
                        .desc("Apply Relation Map dependency criteria. Each criteria entry is a raw expression string "
                                + "or an object: {\"template\": \"satisfy.both\"} (see "
                                + "cameo_list_relation_map_criteria_templates), {\"rawExpression\": \"...\"} (copied from a "
                                + "UI-made map via cameo_dump_relation_map_raw_settings), or {\"relationshipType\": "
                                + "\"Satisfy\", \"direction\": \"both\"}. Returns before/after criteria.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .enumStrDefault("mode", "replace = set exactly these, append = add, remove = delete these.",
                                "replace", "replace", "append", "remove")
                        .any("criteria", "List of criteria: strings or objects with template, rawExpression or "
                                + "relationshipType (+ optional direction).")
                        .bool("refresh", "Run a native refresh afterwards (can be slow).", false),
                tool("cameo_expand_relation_map", FAMILY).write().timeout(LONG_TIMEOUT)
                        .desc("Try to expand native Relation Map nodes through the open map display (opens the diagram "
                                + "if needed) and report presentation/graph/display counts before and after plus the "
                                + "attempts made. 'supported' is false with an unsupportedReason when expansion fails.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .str("mode", "Expansion mode, echoed in the result ('all' expands every node).", "all")
                        .strArray("element_ids", "Element IDs to expand. Per-element expansion is not available "
                                + "through the API; recorded in the result only.")
                        .integer("depth", "Optional traversal depth written to the map settings before expanding.")
                        .bool("refresh", "Run a native refresh after a successful expansion.", false)
                        .str("layout", "Optional layout token applied after expansion (re-lays out the map).")
                        .number("timeout", "Timeout in seconds (1-600).", 120.0),
                tool("cameo_collapse_relation_map", FAMILY).write().timeout(LONG_TIMEOUT)
                        .desc("Try to collapse native Relation Map nodes and report before/after counts. No collapse API "
                                + "of the Relation Map display has been validated, so this currently reports "
                                + "supported=false with the reason instead of closing the diagram.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .str("mode", "Collapse mode, echoed in the result.", "all")
                        .strArray("element_ids", "Element IDs to collapse (recorded in the result only).")
                        .bool("refresh", "Run a native refresh after a successful collapse.", false)
                        .number("timeout", "Timeout in seconds (1-600).", 120.0),
                tool("cameo_render_relation_map", FAMILY).sideEffect().timeout(LONG_TIMEOUT)
                        .desc("Render/export a Relation Map as PNG (base64 in image.image) with presentation counts and "
                                + "a traversal summary. Native refresh is opt-in because it can block Cameo; expand, "
                                + "refresh, depth or layout run inside an undoable session, a plain render does not. "
                                + "Use include_image=false to get only sizes and counts.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .bool("refresh", "Run a native refresh before rendering.", false)
                        .str("expand", "'none' (default) or 'all' to expand the map display before rendering.", "none")
                        .integer("depth", "Optional traversal depth written to the map settings before rendering.")
                        .str("layout", "Optional layout token applied before rendering.")
                        .integer("scale_percentage", "Image scale in percent (25-1000).", 200)
                        .bool("include_image", "Include the base64 PNG; false returns only width/height/bytes.", true)
                        .bool("include_presentation_summary", "Include presentation counts by type.", true),
                tool("cameo_verify_relation_map", FAMILY).readOnly().timeout(150)
                        .desc("Verify a Relation Map in three separate checks: model graph traversal from its context "
                                + "element (nodes/edges >= expected minimums), native settings validity, and rendered "
                                + "presentation count. ok is true only when all pass; the graph and settings are "
                                + "returned for diagnosis.")
                        .str("relation_map_id", "ID of the Relation Map diagram element.", REQUIRED)
                        .integer("expected_min_nodes", "Minimum traversal node count.", 0)
                        .integer("expected_min_edges", "Minimum traversal edge count.", 0)
                        .integer("expected_rendered_nodes", "Minimum rendered presentation element count.", 0)
                        .strArray("relationship_types", "Relationship names/stereotypes to traverse, e.g. "
                                + "['Satisfy', 'Refine']. Omit for all.")
                        .integer("max_depth", "Traversal depth (1-12).", 3),
                tool("cameo_compare_relation_maps", FAMILY).readOnly().timeout(150)
                        .desc("Compare two Relation Maps, usually one built in the Cameo UI and one built through MCP: "
                                + "captures settings, reflected GraphSettings getters and presentations of both and "
                                + "returns a path-level JSON diff (added/removed/changed/arrayLengthChanges).")
                        .str("left_relation_map_id", "First Relation Map ID (e.g. the UI-made reference).", REQUIRED)
                        .str("right_relation_map_id", "Second Relation Map ID.", REQUIRED)
                        .bool("include_presentations", "Include presentation summaries in the comparison.", true)
                        .bool("include_raw", "Include raw getter values (class names, nested getters).", false),
                tool("cameo_get_traceability_graph", FAMILY).readOnly().timeout(150)
                        .desc("Build a read-only relationship graph for traceability analysis by breadth-first "
                                + "traversal from root elements, independent of Relation Map criteria. Follows directed "
                                + "relationships (Refine, DeriveReqt, Satisfy, Allocate, Dependency, ...), activity "
                                + "flows, associations and connectors. Returns nodes (with depth/root) and edges (with "
                                + "sources, targets, traversalTargets). Provide root_element_ids, context_element_id, or "
                                + "a relation_map_id whose context element becomes the root.")
                        .strArray("root_element_ids", "One or more element IDs to start from.")
                        .str("context_element_id", "Single root element ID (alternative to root_element_ids).")
                        .str("relation_map_id", "Relation Map ID; without roots its persisted context element is used.")
                        .strArray("relationship_types", "Relationship names/metaclasses/stereotypes to include, such as "
                                + "['Refine', 'DeriveReqt', 'Satisfy', 'Allocate', 'Dependency']. Matching ignores case "
                                + "and punctuation. Omit for all.")
                        .enumStrDefault("direction", "Edge direction to follow from each element.", "both",
                                "incoming", "outgoing", "both")
                        .integer("max_depth", "Traversal depth (1-12).", 3)
                        .integer("max_nodes", "Safety cap on returned nodes (1-1000).", 250),

                // ------------------------------------------------------------ snapshots
                tool("cameo_create_snapshot", FAMILY).sideEffect()
                        .desc("Capture an in-memory inspection snapshot for before/after diffs of UI actions (the "
                                + "model is not changed). target_type: project, element, diagram, relationMap or ui. "
                                + "Diagram/relationMap snapshots include presentation summaries and diagram properties "
                                + "by default. Workflow: snapshot, let a human change something in Cameo, snapshot "
                                + "again, then cameo_diff_snapshots. Snapshots live until Cameo restarts.")
                        .enumStr("target_type", "What to capture.", REQUIRED, "project", "element", "diagram",
                                "relationMap", "ui")
                        .str("target_id", "Element or diagram ID (required for element, diagram and relationMap).")
                        .str("name", "Optional label for the snapshot.")
                        .bool("include_raw", "Include raw class names/getter values in property dumps.", false)
                        .bool("include_presentations", "Include presentation summaries (diagram/relationMap; default true).")
                        .bool("include_properties", "Include the diagram property manager dump (default true)."),
                tool("cameo_list_snapshots", FAMILY).noProject().readOnly()
                        .desc("List in-memory snapshots available for diffing (id, createdAt, name, target, options)."),
                tool("cameo_get_snapshot", FAMILY).noProject().readOnly()
                        .desc("Get one in-memory snapshot including its full captured payload.")
                        .str("snapshot_id", "Snapshot ID from cameo_create_snapshot or cameo_list_snapshots.", REQUIRED),
                tool("cameo_delete_snapshot", FAMILY).noProject().sideEffect().idempotent()
                        .desc("Delete an in-memory snapshot. Does not change the Cameo model.")
                        .str("snapshot_id", "Snapshot ID to delete.", REQUIRED),
                tool("cameo_diff_snapshots", FAMILY).noProject().readOnly()
                        .desc("Diff two snapshot payloads to reveal what a Cameo UI action changed. Returns stable "
                                + "JSON paths such as $.relationMapSettings.dependencyCriteria[0] grouped as added, "
                                + "removed, changed and arrayLengthChanges, with before/after values when "
                                + "include_details is true.")
                        .str("before_snapshot_id", "Snapshot taken before the change.", REQUIRED)
                        .str("after_snapshot_id", "Snapshot taken after the change.", REQUIRED)
                        .strArray("ignore_paths", "Path prefixes to skip, e.g. ['$.presentations', '$.diagram.name'].")
                        .bool("include_details", "Include before/after values for each change.", true)
                        .integer("max_changes", "Stop after this many changes (truncated=true).", 500),

                // ------------------------------------------------------------ probes
                tool("cameo_list_probe_templates", FAMILY).noProject().readOnly()
                        .desc("List the safe built-in Cameo API discovery probes for cameo_execute_probe "
                                + "(Relation Map GraphSettings methods and criteria classes, presentation classes, "
                                + "UI selection APIs)."),
                tool("cameo_execute_probe", FAMILY).noProject().sideEffect()
                        .desc("Execute a controlled read-only discovery probe: a built-in template (see "
                                + "cameo_list_probe_templates), or language=javaReflection with operation listMethods "
                                + "(class_name), invokeStaticNoArg (class_name + method_name) or "
                                + "invokeGraphSettingsGetter (relation_map_id + method_name). Classes are limited to "
                                + "Cameo API packages. Arbitrary scripts are refused (use cameo_execute_macro instead); "
                                + "mode other than 'read' needs -Dcameo.mcp.probes.write.enabled=true.")
                        .str("template", "Built-in probe template key, e.g. relationMap.listGraphSettingsMethods.")
                        .str("mode", "Probe mode; only 'read' is enabled by default.", "read")
                        .str("script", "Script text. Refused: arbitrary script execution is disabled for probes.")
                        .str("language", "'javaReflection' for reflection operations.", "javaReflection")
                        .integer("timeout_ms", "Timeout hint in milliseconds.", 5000)
                        .bool("requires_project", "Fail when no project is open.", true)
                        .str("description", "Free-text note on why the probe is run (echoed).")
                        .enumStr("operation", "Reflection operation (default listMethods).", false, "listMethods",
                                "invokeStaticNoArg", "invokeGraphSettingsGetter")
                        .str("class_name", "Fully qualified class name in an allowed com.nomagic package.")
                        .str("method_name", "Public no-arg method name (getters only for invokeGraphSettingsGetter).")
                        .str("relation_map_id", "Relation Map ID for invokeGraphSettingsGetter."));
    }

    /** Graph-setting parameters shared by create/configure relation map. */
    private static ToolSpec relationMapSettings(ToolSpec s) {
        return s.str("context_element_id", "Root/context model element ID the map starts from.")
                .strArray("scope_ids", "Scope root element IDs.")
                .strArray("element_type_ids", "Element IDs of metaclasses/stereotypes used as element-type filters.")
                .strArray("dependency_criteria", "Raw Cameo relation-map criterion expressions (replaces the list). "
                        + "Prefer cameo_set_relation_map_criteria with templates.")
                .integer("depth", "Traversal depth (Cameo's native value semantics).")
                .str("layout", "Native relation-map layout token.")
                .bool("legend_enabled", "Show the legend.")
                .bool("show_full_types", "Show fully qualified type names.")
                .bool("show_stereotypes", "Show stereotypes.")
                .bool("show_parameters", "Show parameters.")
                .bool("show_element_numbers", "Show element numbers.")
                .bool("single_node_per_element", "Collapse repeated appearances to one node per element.")
                .bool("short_node_names", "Shorten displayed node names.")
                .bool("types_include_subtypes", "Element-type filters include subtypes.")
                .bool("types_include_custom_types", "Element-type filters include custom types.")
                .bool("make_element_as_context", "Show the context element as the map's context node "
                        + "(default true when context_element_id is set).");
    }
}
