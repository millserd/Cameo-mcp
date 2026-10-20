package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonObject;

import java.util.List;

/** Binds the diagrams family (catalog: {@code com.cameomcp.catalog.DiagramTools}). */
public final class DiagramBindings {

    private DiagramBindings() {
    }

    /*
     * Registers every diagram MCP tool handler. Handlers only unpack arguments; the work happens in
     * DiagramService, DiagramImageService, PresentationRepairService and PropertyDumpService.
     */
    public static void bind(ToolRegistry r) {
        r.bind("cameo_list_diagram_types", (a, c) -> out(DiagramTypes.listJson()));
        r.bind("cameo_list_diagrams", (a, c) -> out(DiagramService.listDiagrams()));
        // cameo_create_diagram: relation_map_* arguments are only used when the type resolves to a Relation Map.
        r.bind("cameo_create_diagram", (a, c) -> {
            DiagramService.RelationMapOptions rm = new DiagramService.RelationMapOptions();
            rm.contextId = a.optTrim("relation_map_context_id");
            rm.scopeIds = optList(a, "relation_map_scope_ids");
            rm.elementTypes = optList(a, "relation_map_element_types");
            rm.dependencyCriteria = optList(a, "relation_map_dependency_criteria");
            rm.depth = a.integerOrNull("relation_map_depth");
            return out(DiagramService.createDiagram(a.reqStr("type"), a.str("name"), a.optTrim("parent_id"), rm));
        });
        r.bind("cameo_add_to_diagram", (a, c) -> out(DiagramService.addToDiagram(diagramId(a), a.reqStr("element_id"),
                a.integerOrNull("x"), a.integerOrNull("y"), a.integerOrNull("width"), a.integerOrNull("height"),
                a.optTrim("container_presentation_id"))));
        // The image tools take the raw Args (and context) because they shape their own response
        // (inline image content / file export) instead of returning a plain JSON object.
        r.bind("cameo_get_diagram_image", DiagramImageService::getImage);
        r.bind("cameo_export_diagram_image", (a, c) -> DiagramImageService.exportToFile(a));
        r.bind("cameo_delete_diagram", (a, c) -> out(DiagramService.deleteDiagram(diagramId(a))));
        r.bind("cameo_rename_diagram", (a, c) -> out(DiagramService.renameDiagram(diagramId(a), a.str("name"))));
        r.bind("cameo_auto_layout", (a, c) -> out(DiagramService.autoLayout(diagramId(a))));

        r.bind("cameo_list_diagram_shapes", (a, c) -> {
            ShapeFilter.Options o = new ShapeFilter.Options();
            o.limit = a.integer("limit", 200);
            o.offset = a.integer("offset", 0);
            o.shapeType = a.optTrim("shape_type");
            o.elementType = a.optTrim("element_type");
            o.parentPresentationId = a.optTrim("parent_presentation_id");
            o.includeBounds = a.bool("include_bounds", true);
            o.includeChildCount = a.bool("include_child_count", true);
            o.summaryOnly = a.bool("summary_only", false);
            return out(DiagramService.listShapes(diagramId(a), o));
        });
        r.bind("cameo_get_shape_properties", (a, c) ->
                out(DiagramService.getShapeProperties(diagramId(a), a.reqStr("presentation_id"))));
        r.bind("cameo_move_shapes", (a, c) ->
                out(DiagramService.moveShapes(diagramId(a), DiagramSupport.objectArray(Gs.toElement(a.get("shapes")), "shapes"))));
        r.bind("cameo_delete_shapes", (a, c) ->
                out(DiagramService.deleteShapes(diagramId(a), a.reqStrList("presentation_ids"))));
        r.bind("cameo_add_diagram_paths", (a, c) ->
                out(DiagramService.addPaths(diagramId(a), DiagramSupport.objectArray(Gs.toElement(a.get("paths")), "paths"))));
        r.bind("cameo_set_shape_properties", (a, c) -> out(DiagramService.setShapeProperties(diagramId(a),
                a.reqStr("presentation_id"), DiagramSupport.objectParam(Gs.toElement(a.get("properties")), "properties"))));
        r.bind("cameo_set_shape_compartments", (a, c) -> out(DiagramService.setShapeCompartments(diagramId(a),
                a.reqStr("presentation_id"),
                DiagramSupport.objectParam(Gs.toElement(a.get("compartments")), "compartments"))));
        r.bind("cameo_reparent_shapes", (a, c) -> out(DiagramService.reparentShapes(diagramId(a),
                DiagramSupport.objectArray(Gs.toElement(a.get("reparentings")), "reparentings"))));
        r.bind("cameo_route_paths", (a, c) -> out(DiagramService.routePaths(diagramId(a),
                DiagramSupport.objectArray(Gs.toElement(a.get("routes")), "routes"))));

        // Label and compartment presentation repair tools; a null presentation_ids list means "all on the diagram".
        r.bind("cameo_set_transition_label_presentation", (a, c) -> out(PresentationRepairService.transitionLabels(
                diagramId(a), optList(a, "presentation_ids"), a.bool("show_name", true), a.bool("show_triggers", true),
                a.bool("show_guard", false), a.bool("show_effect", false), a.bool("reset_labels", true))));
        r.bind("cameo_set_item_flow_label_presentation", (a, c) -> out(PresentationRepairService.itemFlowLabels(
                diagramId(a), optList(a, "presentation_ids"), a.bool("show_name", false),
                a.bool("show_conveyed", true), a.bool("show_item_property", true), a.bool("show_direction", true),
                a.bool("show_stereotype", false), a.bool("reset_labels", true))));
        r.bind("cameo_set_allocation_compartment_presentation", (a, c) -> out(
                PresentationRepairService.allocationCompartments(diagramId(a), optList(a, "presentation_ids"),
                        a.bool("show_allocated_elements", true), a.bool("show_element_properties", true),
                        a.bool("show_ports", true), a.bool("show_full_ports", true),
                        a.bool("apply_allocation_naming", true))));
        r.bind("cameo_repair_hidden_labels", (a, c) -> out(PresentationRepairService.repairHiddenLabels(
                diagramId(a), optList(a, "presentation_ids"), a.bool("dry_run", false))));
        r.bind("cameo_repair_label_positions", (a, c) -> out(PresentationRepairService.repairLabelPositions(
                diagramId(a), optList(a, "presentation_ids"), a.bool("dry_run", false),
                a.bool("only_overlapping", true), a.integer("overlap_padding", 40))));
        r.bind("cameo_repair_conveyed_item_labels", (a, c) -> out(PresentationRepairService.repairConveyedItemLabels(
                diagramId(a), optList(a, "presentation_ids"), a.bool("dry_run", false), a.bool("reset_labels", true))));
        r.bind("cameo_normalize_compartment_presets", (a, c) -> out(
                PresentationRepairService.normalizeCompartmentPresets(diagramId(a), optList(a, "presentation_ids"),
                        a.bool("dry_run", false))));
        r.bind("cameo_prune_diagram_presentations", (a, c) -> out(PresentationRepairService.prunePresentations(
                diagramId(a), optList(a, "keep_element_ids"), optList(a, "drop_element_types"),
                optList(a, "drop_shape_types"), optList(a, "exclude_element_ids"),
                optList(a, "exclude_presentation_ids"), a.bool("dry_run", false))));
        r.bind("cameo_prune_path_decorations", (a, c) -> out(PresentationRepairService.prunePathDecorations(
                diagramId(a), optList(a, "presentation_ids"), optList(a, "drop_child_shape_types"),
                a.bool("dry_run", false))));

        // Diagnostics: raw presentation-property dumps for working out which native property names to set.
        r.bind("cameo_dump_diagram_properties", (a, c) -> out(PropertyDumpService.dumpDiagram(diagramId(a),
                a.bool("include_raw", false), a.bool("include_presentation_properties", false),
                a.bool("summary_only", true), a.integer("limit", 100), a.integer("offset", 0))));
        r.bind("cameo_dump_presentation_properties", (a, c) -> out(PropertyDumpService.dumpPresentation(
                diagramId(a), a.reqStr("presentation_id"), a.bool("include_raw", false),
                a.bool("summary_only", false))));
    }

    // Trimmed because callers often paste ids with surrounding whitespace.
    private static String diagramId(Args a) {
        return a.reqStr("diagram_id").trim();
    }

    /** Null when absent (so "not given" differs from an empty list), else the string list. */
    static List<String> optList(Args a, String name) {
        return a.has(name) ? a.strList(name) : null;
    }

    // Converts the tree result into plain Java maps/lists for the MCP layer; null becomes an empty object.
    private static Object out(JsonObject json) {
        return json == null ? new java.util.LinkedHashMap<String, Object>() : Gs.fromTree(json);
    }
}
