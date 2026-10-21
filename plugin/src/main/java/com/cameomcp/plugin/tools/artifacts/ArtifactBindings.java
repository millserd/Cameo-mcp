package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolContext;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.OptionalModules;
import com.cameomcp.json.tree.JsonObject;

import java.util.Map;

import static com.cameomcp.plugin.tools.artifacts.ArtifactSupport.GENERIC_TABLE_SERVICE;
import static com.cameomcp.plugin.tools.artifacts.ArtifactSupport.MATRIX_SERVICE;
import static com.cameomcp.plugin.tools.artifacts.ArtifactSupport.RELATION_MAP_SERVICE;

/**
 * Matrices, Generic Tables, Relation Maps, traceability graph, snapshots and probes.
 * Anything that needs an optional Cameo plugin's API runs in the optional module
 * ({@code src/optional/java/com/cameomcp/optional/...}) through {@link OptionalModules}.
 */
public final class ArtifactBindings {

    /** A tool body taking canonicalized snake_case params and returning a JSON tree. */
    @FunctionalInterface
    private interface Impl {
        JsonObject call(JsonObject params) throws Exception;
    }

    private ArtifactBindings() {
    }

    /**
     * Registers every artifacts-family tool. "local" tools run in this jar; "optional" tools are
     * forwarded by name to an operation of a service class in the optional jar.
     */
    public static void bind(ToolRegistry r) {
        // matrices
        r.bind("cameo_list_matrix_kinds", (a, c) -> MatrixKinds.catalog());
        local(r, "cameo_list_matrices", ArtifactListings::listMatrices);
        optional(r, "cameo_get_matrix", MATRIX_SERVICE, "get");
        optional(r, "cameo_create_matrix", MATRIX_SERVICE, "create");

        // generic tables
        local(r, "cameo_list_generic_tables", p -> ArtifactListings.listGenericTables());
        optional(r, "cameo_get_generic_table", GENERIC_TABLE_SERVICE, "get");
        optional(r, "cameo_list_generic_table_columns", GENERIC_TABLE_SERVICE, "columns");
        optional(r, "cameo_create_generic_table", GENERIC_TABLE_SERVICE, "create");

        // relation maps
        local(r, "cameo_list_relation_maps", p -> ArtifactListings.listRelationMaps());
        optional(r, "cameo_get_relation_map", RELATION_MAP_SERVICE, "get");
        optional(r, "cameo_create_relation_map", RELATION_MAP_SERVICE, "create");
        optional(r, "cameo_configure_relation_map", RELATION_MAP_SERVICE, "configure");
        optional(r, "cameo_refresh_relation_map", RELATION_MAP_SERVICE, "refresh");
        optional(r, "cameo_dump_relation_map_raw_settings", RELATION_MAP_SERVICE, "rawSettings");
        optional(r, "cameo_list_relation_map_presentations", RELATION_MAP_SERVICE, "presentations");
        local(r, "cameo_list_relation_map_criteria_templates", p -> CriteriaTemplates.listing());
        optional(r, "cameo_set_relation_map_criteria", RELATION_MAP_SERVICE, "setCriteria");
        optional(r, "cameo_expand_relation_map", RELATION_MAP_SERVICE, "expand");
        optional(r, "cameo_collapse_relation_map", RELATION_MAP_SERVICE, "collapse");
        optional(r, "cameo_render_relation_map", RELATION_MAP_SERVICE, "render");
        optional(r, "cameo_verify_relation_map", RELATION_MAP_SERVICE, "verify");
        optional(r, "cameo_compare_relation_maps", RELATION_MAP_SERVICE, "compare");
        local(r, "cameo_get_traceability_graph", ArtifactBindings::traceabilityGraph);

        // snapshots
        local(r, "cameo_create_snapshot", Snapshots::create);
        local(r, "cameo_list_snapshots", p -> Snapshots.list());
        local(r, "cameo_get_snapshot", Snapshots::get);
        local(r, "cameo_delete_snapshot", Snapshots::delete);
        local(r, "cameo_diff_snapshots", Snapshots::diff);

        // probes
        local(r, "cameo_list_probe_templates", p -> Probes.templates());
        local(r, "cameo_execute_probe", Probes::execute);
    }

    /** Binds a tool implemented in the main plugin jar. */
    private static void local(ToolRegistry r, String tool, Impl impl) {
        r.bind(tool, (Args a, ToolContext c) -> out(impl.call(ArtifactSupport.params(a, c))));
    }

    /**
     * Binds a tool that delegates to {@code service.operation} in the optional jar; if that jar or the
     * Cameo plugin it needs is missing, OptionalModules turns the load failure into an UNSUPPORTED error.
     */
    private static void optional(ToolRegistry r, String tool, String service, String operation) {
        r.bind(tool, (Args a, ToolContext c) ->
                out(OptionalModules.call(service, operation, ArtifactSupport.params(a, c))));
    }

    /** Converts the tree result to the plain Map the registry serializes; null becomes {}. */
    private static Map<?, ?> out(JsonObject result) {
        return result == null ? Json.obj() : (Map<?, ?>) Gs.fromTree(result);
    }

    /**
     * Roots come from root_element_ids / context_element_id; with only relation_map_id the map's
     * context element (read by the Relation Map module) is the root.
     */
    // cameo_get_traceability_graph: only the map-context case needs the Relation Map API, so plain
    // graphs are built here and keep working when the optional Relation Map plugin is absent.
    static JsonObject traceabilityGraph(JsonObject p) throws Exception {
        String relationMapId = ArtifactSupport.optStr(p, "relation_map_id");
        if (relationMapId != null && !TraceabilityGraph.hasExplicitRoots(p)) {
            return OptionalModules.call(RELATION_MAP_SERVICE, "graph", p);
        }
        return Edt.read(project -> TraceabilityGraph.build(project, p, relationMapId, null));
    }
}
