package com.cameomcp.catalog;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.ToolSpec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.cameomcp.mcp.ToolSpec.REQUIRED;
import static com.cameomcp.mcp.ToolSpec.tool;

/** Diagram creation, images, shapes, paths and presentation repair tools. */
public final class DiagramTools {

    // Family name used in docs and in cameo_list_tools filters.
    public static final String FAMILY = "diagrams";

    // Parameter help reused by many diagram tools, so every tool says the same thing.
    private static final String DIAGRAM_ID = "Diagram ID from cameo_list_diagrams or cameo_create_diagram.";
    private static final String PRESENTATION_IDS = "Optional presentationIds (from cameo_list_diagram_shapes) to "
            + "limit the operation to; omit to target every eligible presentation on the diagram.";
    private static final String DRY_RUN = "Preview what would change without modifying the diagram.";

    private DiagramTools() {
    }

    // Raw JSON-schema fragments for the item properties of objArray(...) parameters.
    private static Map<String, Object> str(String desc) {
        return Json.obj("type", "string", "description", desc);
    }

    private static Map<String, Object> num(String desc) {
        return Json.obj("type", "integer", "description", desc);
    }

    private static Map<String, Object> point(String desc) {
        return Json.obj("type", "object", "description", desc, "properties",
                Json.obj("x", Json.obj("type", "integer"), "y", Json.obj("type", "integer")), "required",
                Arrays.asList("x", "y"));
    }

    /**
     * The family's specs, in the order clients see them. Descriptions and parameter help are the
     * text agents read when choosing a tool, so they say what the tool returns and which tool to call next.
     */
    public static List<ToolSpec> specs() {
        return Arrays.asList(
                tool("cameo_list_diagram_types", FAMILY).noProject().readOnly()
                        .desc("List the validated diagram type tokens accepted by cameo_create_diagram: the canonical "
                                + "token (Class, Package, UseCase, Activity, Sequence, StateMachine, Component, "
                                + "Deployment, CompositeStructure, Object, Communication, InteractionOverview, Timing, "
                                + "Profile, BDD, IBD, Requirement Diagram, Parametric Diagram, RelationMap, Content "
                                + "Diagram), the native Cameo diagram type it creates, its family (uml/sysml/analysis) "
                                + "and the aliases normalized to it."),
                tool("cameo_list_diagrams", FAMILY).readOnly()
                        .desc("List every diagram in the open project with id, name, native type and owner "
                                + "(ownerId, ownerName). Use the id as diagram_id for the other diagram tools."),
                tool("cameo_create_diagram", FAMILY).write()
                        .desc("Create a SysML or UML diagram owned by a namespace. type accepts the tokens from "
                                + "cameo_list_diagram_types and common aliases (\"BDD\", \"IBD\", "
                                + "\"InternalBlockDiagram\", \"StateMachineDiagram\", \"Requirement Diagram\", "
                                + "\"RelationMap\"); any other string is passed to Cameo as a native type name such as "
                                + "\"SysML Activity Diagram\". SysML types need the SysML profile in the project. For a "
                                + "RelationMap the relation_map_* options are applied after creation through "
                                + "cameo_configure_relation_map (prefer cameo_create_relation_map for full control). "
                                + "Returns id, name, native type and parentId. Next: cameo_add_to_diagram.")
                        .str("type", "Diagram type token or alias, e.g. \"BDD\", \"IBD\", \"Activity\", "
                                + "\"StateMachine\", \"Requirement Diagram\".", REQUIRED)
                        .str("name", "Display name of the diagram.", REQUIRED)
                        .str("parent_id", "ID of the owning namespace (Package, Model, Block/Class, Activity, "
                                + "StateMachine...). Omit to use the project's root model.")
                        .str("relation_map_context_id", "RelationMap only: context/root element ID.")
                        .strArray("relation_map_scope_ids", "RelationMap only: scope root element IDs.")
                        .strArray("relation_map_element_types", "RelationMap only: metaclass or stereotype names to "
                                + "include, e.g. [\"Block\", \"Requirement\"].")
                        .strArray("relation_map_dependency_criteria", "RelationMap only: native structured-expression "
                                + "criteria strings for relationship filters.")
                        .integer("relation_map_depth", "RelationMap only: traversal depth, -1 for indefinite, else 0-100."),
                tool("cameo_add_to_diagram", FAMILY).write()
                        .desc("Place an existing model element on a diagram as a shape at (x, y) and return its "
                                + "presentationId and bounds. Leave width/height negative to keep Cameo's auto-size; "
                                + "if you set one you must set both. container_presentation_id nests the shape inside "
                                + "a container shape (system boundary, package, part, region). Adding an "
                                + "ActivityPartition creates or reuses a native swimlane holding all sibling "
                                + "partitions (do not pass a container for partitions). Cameo may auto-display "
                                + "related paths; tidy with cameo_prune_diagram_presentations. To draw relationships "
                                + "use cameo_add_diagram_paths.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .str("element_id", "Model element ID to show.", REQUIRED)
                        .integer("x", "Left position in pixels.", 100)
                        .integer("y", "Top position in pixels.", 100)
                        .integer("width", "Shape width in pixels; negative keeps auto-size.", -1)
                        .integer("height", "Shape height in pixels; negative keeps auto-size.", -1)
                        .str("container_presentation_id", "Optional presentationId of a container shape (from "
                                + "cameo_list_diagram_shapes)."),
                tool("cameo_get_diagram_image", FAMILY).sideEffect().idempotent().timeout(180)
                        .desc("Render a diagram and return it as an MCP image content block plus metadata (id, name, "
                                + "format, width, height, scalePercentage, imageBytes, mimeType). Large diagrams can "
                                + "exceed client limits: first call with include_image=false to get the size, then use "
                                + "max_width/max_height (aspect ratio kept, never enlarged) and format=\"jpeg\" to "
                                + "shrink the payload. scale_percentage renders natively at higher/lower resolution "
                                + "before any resize. webp is not available in Cameo's Java runtime and falls back to "
                                + "PNG (formatNote says so). To save the image to disk use cameo_export_diagram_image.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .bool("include_image", "false returns metadata only (no image block).", true)
                        .enumStrDefault("format", "Encoding of the returned image.", "png", "png", "jpeg", "jpg", "webp")
                        .integer("max_width", "Maximum width in pixels of the returned image.")
                        .integer("max_height", "Maximum height in pixels of the returned image.")
                        .integer("quality", "JPEG quality 1-100.", 85, 1, 100)
                        .integer("scale_percentage", "Native Cameo render scale, 25-1000 (default 100)."),
                tool("cameo_export_diagram_image", FAMILY).sideEffect().openWorld().timeout(300)
                        .desc("Export a diagram image straight to a file with Cameo's native exporter (PNG, JPEG or "
                                + "SVG) without sending the image through MCP. Returns path, bytes, width, height, "
                                + "format and the diagram bounds. Missing parent folders are created. Use for reports "
                                + "and documents; use cameo_get_diagram_image to look at a diagram.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .str("file_path", "Absolute output path, e.g. C:\\\\out\\\\context.png or /tmp/context.svg.",
                                REQUIRED)
                        .enumStr("format", "Output format; default from the file extension, else png.", false,
                                "png", "jpeg", "jpg", "svg")
                        .integer("scale_percentage", "Render scale, 25-1000 (default 100).")
                        .integer("dpi", "Raster resolution in DPI (36-1200); default is Cameo's.")
                        .bool("overwrite", "Replace an existing file.", true),
                tool("cameo_delete_diagram", FAMILY).destructive()
                        .desc("Delete a diagram from the model. Only the diagram and its symbols are removed; the "
                                + "model elements it showed stay in the containment tree (use cameo_delete_element for "
                                + "those). Undo with cameo_undo.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED),
                tool("cameo_rename_diagram", FAMILY).write().idempotent()
                        .desc("Rename a diagram. Returns the diagram with previousName.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .str("name", "New diagram name.", REQUIRED),
                tool("cameo_auto_layout", FAMILY).write().timeout(180)
                        .desc("Apply Cameo's automatic layout to all shapes of a diagram (same as Layout > Quick "
                                + "Diagram Layout). Returns layoutApplied. Manual positions are lost; undo with cameo_undo.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED),
                tool("cameo_list_diagram_shapes", FAMILY).readOnly()
                        .desc("List the shapes and paths on a diagram (flattened, nested ones carry "
                                + "parentPresentationId): presentationId, shapeType (presentation class such as "
                                + "ClassView, ControlFlowView, SwimlaneHeaderView), elementId/elementName/elementType "
                                + "and bounds. Call this before cameo_move_shapes, cameo_delete_shapes, "
                                + "cameo_add_diagram_paths or cameo_set_shape_properties: those need presentationId "
                                + "values, which are NOT model element IDs (one element can have a different "
                                + "presentation on each diagram). Paged with limit/offset (hasMore, nextOffset); "
                                + "summary_only returns counts by shape/element type instead of the list.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .integer("limit", "Maximum shapes to return.", 200)
                        .integer("offset", "Zero-based offset into the filtered list.", 0)
                        .str("shape_type", "Filter by presentation class, e.g. ControlFlowView (case-insensitive, exact).")
                        .str("element_type", "Filter by model element human type, e.g. OpaqueAction, Activity "
                                + "Partition (case-insensitive, exact).")
                        .str("parent_presentation_id", "Only children of this presentation element.")
                        .bool("include_bounds", "Include per-shape bounds.", true)
                        .bool("include_child_count", "Include per-shape child counts.", true)
                        .bool("summary_only", "Return counts/groupings instead of shapes.", false),
                tool("cameo_get_shape_properties", FAMILY).readOnly()
                        .desc("Read the presentation (display) properties of one diagram shape or path: properties "
                                + "(name -> value), propertyList (with ids and class types) and compartments (well-known "
                                + "toggles: name, stereotype, type, properties, operations, attributes, ports, "
                                + "constraints, tagged_values). Use the names with cameo_set_shape_properties.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .str("presentation_id", "presentationId from cameo_list_diagram_shapes.", REQUIRED),
                tool("cameo_move_shapes", FAMILY).write()
                        .desc("Move and/or resize shapes. Coordinates are pixels from the diagram's top-left (x right, "
                                + "y down). Each item needs presentationId, x, y, width and height (reuse current "
                                + "bounds from cameo_list_diagram_shapes to keep a size). Paths cannot be reshaped; use "
                                + "cameo_route_paths. Example: [{\"presentationId\": \"abc\", \"x\": 200, \"y\": 50, "
                                + "\"width\": 150, \"height\": 80}]. Returns a result per item.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .objArray("shapes", "Shape updates.", REQUIRED, Json.obj(
                                "presentationId", str("presentationId from cameo_list_diagram_shapes."),
                                "x", num("New left position."), "y", num("New top position."),
                                "width", num("New width."), "height", num("New height.")),
                                "presentationId", "x", "y", "width", "height"),
                tool("cameo_delete_shapes", FAMILY).destructive()
                        .desc("Remove shapes or paths from a diagram canvas only; the model elements stay in the "
                                + "model and can be shown again (use cameo_delete_element to delete from the model). "
                                + "Child symbols are removed with their parent. Returns a result per id.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", "presentationIds from cameo_list_diagram_shapes.", REQUIRED),
                tool("cameo_add_diagram_paths", FAMILY).write()
                        .desc("Draw existing model relationships as paths between shapes already on the diagram. The "
                                + "relationship must exist (cameo_create_relationship) and both ends must be shown; "
                                + "sourceShapeId/targetShapeId are presentationIds from cameo_list_diagram_shapes, NOT "
                                + "model element IDs. Example: [{\"relationshipId\": \"rel-1\", \"sourceShapeId\": "
                                + "\"pe-a\", \"targetShapeId\": \"pe-b\"}]. Returns the new path presentationIds.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .objArray("paths", "Paths to draw.", REQUIRED, Json.obj(
                                "relationshipId", str("Relationship model element ID."),
                                "sourceShapeId", str("presentationId of the source shape."),
                                "targetShapeId", str("presentationId of the target shape.")),
                                "relationshipId", "sourceShapeId", "targetShapeId"),
                tool("cameo_set_shape_properties", FAMILY).write()
                        .desc("Set presentation properties of one shape or path by exact Cameo property name (see "
                                + "cameo_get_shape_properties). Common: \"Show Name\", \"Show Stereotype\", \"Show Type\", "
                                + "\"Show Constraints\", \"Show Tagged Values\", \"Show Ports\", \"Suppress Attributes\", "
                                + "\"Suppress Operations\", \"Autosize\" (booleans); \"Fill Color\", \"Line Color\", "
                                + "\"Font Color\" (\"#RRGGBB\"). Unknown names are reported per property. For "
                                + "intent-level compartment toggles use cameo_set_shape_compartments.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .str("presentation_id", "presentationId from cameo_list_diagram_shapes.", REQUIRED)
                        .obj("properties", "Property name -> value, e.g. {\"Show Constraints\": true, \"Fill Color\": "
                                + "\"#E0F0FF\"}.", REQUIRED),
                tool("cameo_set_shape_compartments", FAMILY).write()
                        .desc("Show/hide compartments of one shape with normalized keys instead of raw property "
                                + "names: showAttributes, showOperations, showProperties, showPorts, showParts, "
                                + "showContent, showReferences, showValues, showFullPorts, showFlowProperties, "
                                + "showProxyPorts, showBehaviors, showReceptions, showStructure, showConstraints, "
                                + "showTaggedValues, showName, showStereotype, showType (\"suppressX\" works too). "
                                + "true always means visible; \"Suppress ...\" properties are inverted for you.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .str("presentation_id", "presentationId from cameo_list_diagram_shapes.", REQUIRED)
                        .obj("compartments", "Key -> boolean, e.g. {\"showAttributes\": false, \"showPorts\": true}.",
                                REQUIRED),
                tool("cameo_set_transition_label_presentation", FAMILY).write()
                        .desc("Apply a transition-label display preset (name, triggers, guard, effect) to transition "
                                + "paths of a state machine diagram, and optionally reset label positions. Safer than "
                                + "guessing raw property names. Each result lists supportedRequests/unsupportedRequests.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", PRESENTATION_IDS)
                        .bool("show_name", "Show the transition name.", true)
                        .bool("show_triggers", "Show triggers/events.", true)
                        .bool("show_guard", "Show guards.", false)
                        .bool("show_effect", "Show effects.", false)
                        .bool("reset_labels", "Reset label positions afterwards.", true),
                tool("cameo_set_item_flow_label_presentation", FAMILY).write()
                        .desc("Apply an item-flow / information-flow label preset (name, conveyed items, item "
                                + "property, direction, stereotype) to flow paths, e.g. connectors carrying item flows "
                                + "on an IBD.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", PRESENTATION_IDS)
                        .bool("show_name", "Show the flow name.", false)
                        .bool("show_conveyed", "Show conveyed classifiers.", true)
                        .bool("show_item_property", "Show the item property.", true)
                        .bool("show_direction", "Show the flow direction arrow.", true)
                        .bool("show_stereotype", "Show the stereotype.", false)
                        .bool("reset_labels", "Reset label positions afterwards.", true),
                tool("cameo_set_allocation_compartment_presentation", FAMILY).write()
                        .desc("Apply a SysML allocation / full-port display preset to shapes that support it "
                                + "(allocatedFrom/allocatedTo compartments, element properties, ports, full ports, SysML "
                                + "1.7 allocation compartment naming).")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", PRESENTATION_IDS)
                        .bool("show_allocated_elements", "Show allocated-elements compartments.", true)
                        .bool("show_element_properties", "Show element properties.", true)
                        .bool("show_ports", "Show ports.", true)
                        .bool("show_full_ports", "Show full ports.", true)
                        .bool("apply_allocation_naming", "Use SysML 1.7 allocation compartment naming.", true),
                tool("cameo_repair_hidden_labels", FAMILY).write()
                        .desc("Turn hidden labels back on with diagram-type-aware defaults (e.g. names and "
                                + "stereotypes on shapes; triggers/guards/effects on state-machine transitions). "
                                + "Use dry_run=true to preview. The result's repairDefaults shows the keys used.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", PRESENTATION_IDS)
                        .bool("dry_run", DRY_RUN, false),
                tool("cameo_repair_label_positions", FAMILY).write()
                        .desc("Reset path labels to Cameo's default positions, by default only on paths whose bounds "
                                + "(grown by overlap_padding px) overlap another path. Use after moving shapes or "
                                + "routing paths when labels pile up.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", PRESENTATION_IDS)
                        .bool("dry_run", DRY_RUN, false)
                        .bool("only_overlapping", "Only reset paths that overlap another path.", true)
                        .integer("overlap_padding", "Padding in pixels used for the overlap test.", 40),
                tool("cameo_repair_conveyed_item_labels", FAMILY).write()
                        .desc("Force conveyed-item / item-property / direction labels on flow paths that support them "
                                + "(diagram-type-aware) and optionally reset label positions.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", PRESENTATION_IDS)
                        .bool("dry_run", DRY_RUN, false)
                        .bool("reset_labels", "Reset label positions afterwards.", true),
                tool("cameo_normalize_compartment_presets", FAMILY).write()
                        .desc("Show the standard compartments for the diagram type on every eligible shape (BDD/class: "
                                + "properties, operations, constraints, tagged values, ports, attributes; IBD: ports, "
                                + "full ports, element properties, allocations; requirement: constraints, tagged "
                                + "values). Comments/notes are skipped. Use dry_run=true to preview.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", PRESENTATION_IDS)
                        .bool("dry_run", DRY_RUN, false),
                tool("cameo_prune_diagram_presentations", FAMILY).destructive()
                        .desc("Delete unwanted symbols from a diagram with keep/drop rules (the model is not "
                                + "changed). Typical use after cameo_add_to_diagram auto-displays extra relationship "
                                + "paths: keep_element_ids=[ids you want] removes every other element's symbols. "
                                + "drop_element_types / drop_shape_types match substrings of the element type or "
                                + "presentation class (e.g. \"Dependency\", \"RoleView\"); exclude_* protect symbols. "
                                + "At least one rule is required. Run with dry_run=true first.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("keep_element_ids", "Keep only symbols of these model element IDs.")
                        .strArray("drop_element_types", "Drop symbols whose element type / presentation class contains any of these.")
                        .strArray("drop_shape_types", "Drop symbols whose presentation class contains any of these.")
                        .strArray("exclude_element_ids", "Never drop symbols of these element IDs.")
                        .strArray("exclude_presentation_ids", "Never drop these presentationIds.")
                        .bool("dry_run", DRY_RUN, false),
                tool("cameo_prune_path_decorations", FAMILY).destructive()
                        .desc("Delete child decorations of paths (default: association end role labels, RoleView) "
                                + "while keeping the paths themselves. drop_child_shape_types matches presentation "
                                + "class substrings.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .strArray("presentation_ids", "Optional path presentationIds; omit for every path.")
                        .strArray("drop_child_shape_types", "Child presentation classes to delete (default [\"RoleView\"]).")
                        .bool("dry_run", DRY_RUN, false),
                tool("cameo_reparent_shapes", FAMILY).write()
                        .desc("Move existing symbols under a different container symbol on the same diagram (e.g. "
                                + "into a swimlane, region or system boundary). Each item: presentationId and "
                                + "parentPresentationId. Positions may need cameo_move_shapes afterwards.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .objArray("reparentings", "Reparent operations.", REQUIRED, Json.obj(
                                "presentationId", str("Symbol to move."),
                                "parentPresentationId", str("New container symbol.")),
                                "presentationId", "parentPresentationId"),
                tool("cameo_route_paths", FAMILY).write()
                        .desc("Set the route of existing paths: breakPoints (ordered bend points), optional "
                                + "sourcePoint/targetPoint (attachment points) and resetLabels (default true). An empty "
                                + "breakPoints list makes the path straight. Example: [{\"presentationId\": \"p1\", "
                                + "\"breakPoints\": [{\"x\": 300, \"y\": 120}]}].")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .objArray("routes", "Route updates.", REQUIRED, Json.obj(
                                "presentationId", str("Path presentationId."),
                                "breakPoints", Json.obj("type", "array", "items", point("Bend point.")),
                                "sourcePoint", point("Optional source attachment point."),
                                "targetPoint", point("Optional target attachment point."),
                                "resetLabels", Json.obj("type", "boolean", "description", "Reset label positions (default true).")),
                                "presentationId"),
                tool("cameo_dump_diagram_properties", FAMILY).readOnly().timeout(120)
                        .desc("Diagnostic dump of a diagram's settings and a paged list of its presentation elements "
                                + "(counts by type). Use for before/after snapshots of diagrams, matrices, tables and "
                                + "relation maps. include_presentation_properties adds every symbol's properties "
                                + "(large); include_raw adds class names and getter values.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .bool("include_raw", "Include raw class names and getter values.", false)
                        .bool("include_presentation_properties", "Include each presentation's property manager.", false)
                        .bool("summary_only", "Summarize collection/map values instead of listing them.", true)
                        .integer("limit", "Presentations per page.", 100)
                        .integer("offset", "Presentation offset.", 0),
                tool("cameo_dump_presentation_properties", FAMILY).readOnly()
                        .desc("Diagnostic dump of every property of one presentation element, with its children.")
                        .str("diagram_id", DIAGRAM_ID, REQUIRED)
                        .str("presentation_id", "presentationId from cameo_list_diagram_shapes.", REQUIRED)
                        .bool("include_raw", "Include raw class names and getter values.", false)
                        .bool("summary_only", "Summarize collection/map values instead of listing them.", false));
    }
}
