// family: artifacts
package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.catalog.ArtifactTools;
import com.cameomcp.catalog.Catalog;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolContext;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactCatalogTest {

    private static final List<String> TOOLS = Arrays.asList(
            "cameo_list_matrix_kinds", "cameo_list_matrices", "cameo_get_matrix", "cameo_create_matrix",
            "cameo_list_generic_tables", "cameo_get_generic_table", "cameo_list_generic_table_columns",
            "cameo_create_generic_table", "cameo_list_relation_maps", "cameo_get_relation_map",
            "cameo_create_relation_map", "cameo_configure_relation_map", "cameo_refresh_relation_map",
            "cameo_dump_relation_map_raw_settings", "cameo_list_relation_map_presentations",
            "cameo_list_relation_map_criteria_templates", "cameo_set_relation_map_criteria",
            "cameo_expand_relation_map", "cameo_collapse_relation_map", "cameo_render_relation_map",
            "cameo_verify_relation_map", "cameo_compare_relation_maps", "cameo_get_traceability_graph",
            "cameo_create_snapshot", "cameo_list_snapshots", "cameo_get_snapshot", "cameo_delete_snapshot",
            "cameo_diff_snapshots", "cameo_list_probe_templates", "cameo_execute_probe");

    private static ToolSpec spec(String name) {
        for (ToolSpec s : ArtifactTools.specs()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw new AssertionError("missing spec " + name);
    }

    @Test
    void familyHasExactlyTheReferenceTools() {
        List<String> names = new ArrayList<>();
        for (ToolSpec s : ArtifactTools.specs()) {
            names.add(s.name());
            assertEquals(ArtifactTools.FAMILY, s.family());
            assertFalse(s.description().isEmpty(), s.name());
        }
        assertEquals(TOOLS, names);
        assertEquals(30, Catalog.family("com.cameomcp.catalog.ArtifactTools").size());
    }

    @Test
    void parameterNamesMatchTheReference() {
        assertEquals(Arrays.asList("kind", "parent_id", "name", "scope_id", "row_scope_id", "column_scope_id",
                "row_types", "column_types"), new ArrayList<>(spec("cameo_create_matrix").parameters().keySet()));
        assertEquals(Arrays.asList("parent_id", "name", "context_element_id", "scope_ids", "element_type_ids",
                "dependency_criteria", "depth", "layout", "legend_enabled", "show_full_types", "show_stereotypes",
                "show_parameters", "show_element_numbers", "single_node_per_element", "short_node_names",
                "types_include_subtypes", "types_include_custom_types", "make_element_as_context"),
                new ArrayList<>(spec("cameo_create_relation_map").parameters().keySet()));
        assertEquals(Arrays.asList("root_element_ids", "context_element_id", "relation_map_id",
                "relationship_types", "direction", "max_depth", "max_nodes"),
                new ArrayList<>(spec("cameo_get_traceability_graph").parameters().keySet()));
        assertEquals(Arrays.asList("template", "mode", "script", "language", "timeout_ms", "requires_project",
                "description", "operation", "class_name", "method_name", "relation_map_id"),
                new ArrayList<>(spec("cameo_execute_probe").parameters().keySet()));
        assertEquals(Arrays.asList("relation_map_id", "refresh", "expand", "depth", "layout", "scale_percentage",
                "include_image", "include_presentation_summary"),
                new ArrayList<>(spec("cameo_render_relation_map").parameters().keySet()));
        assertEquals(Arrays.asList("before_snapshot_id", "after_snapshot_id"),
                spec("cameo_diff_snapshots").requiredParameters());
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultsMatchTheReference() {
        Map<String, Object> p = spec("cameo_list_relation_map_presentations").parameters();
        assertEquals(250L, ((Map<String, Object>) p.get("limit")).get("default"));
        assertEquals(true, ((Map<String, Object>) p.get("summary_only")).get("default"));
        Map<String, Object> r = spec("cameo_render_relation_map").parameters();
        assertEquals(200L, ((Map<String, Object>) r.get("scale_percentage")).get("default"));
        assertEquals("none", ((Map<String, Object>) r.get("expand")).get("default"));
        Map<String, Object> d = spec("cameo_diff_snapshots").parameters();
        assertEquals(500L, ((Map<String, Object>) d.get("max_changes")).get("default"));
    }

    @Test
    void kindsAreDeclared() {
        assertEquals(ToolSpec.Kind.READ, spec("cameo_get_matrix").kind());
        assertEquals(ToolSpec.Kind.WRITE, spec("cameo_create_matrix").kind());
        assertEquals(ToolSpec.Kind.WRITE, spec("cameo_set_relation_map_criteria").kind());
        assertFalse(spec("cameo_list_snapshots").requiresProject());
        assertFalse(spec("cameo_list_matrix_kinds").requiresProject());
        assertTrue(spec("cameo_create_snapshot").requiresProject());
        assertTrue(Catalog.isScriptTool("cameo_execute_probe"));
        assertTrue(spec("cameo_refresh_relation_map").timeoutSeconds() > 600);
    }

    @Test
    void everyToolIsBound() throws Exception {
        ToolRegistry r = new ToolRegistry().addSpecs(ArtifactTools.specs());
        ArtifactBindings.bind(r);
        assertTrue(r.unboundTools().isEmpty(), r.unboundTools().toString());

        ToolSpec kindsSpec = r.spec("cameo_list_matrix_kinds");
        Object kinds = r.handler("cameo_list_matrix_kinds").call(Args.of(), new ToolContext(kindsSpec, null, "t"));
        assertEquals(5, ((Map<?, ?>) kinds).get("count"));

        ToolSpec tSpec = r.spec("cameo_list_relation_map_criteria_templates");
        Map<?, ?> templates = (Map<?, ?>) r.handler(tSpec.name()).call(Args.of(), new ToolContext(tSpec, null, "t"));
        assertEquals(15L, ((Number) templates.get("count")).longValue());
        assertNotNull(templates.get("templates"));
    }

    @Test
    void camelCaseArgumentsAreCanonicalized() {
        ToolSpec s = spec("cameo_set_relation_map_criteria");
        JsonObject p = ArtifactSupport.params(Args.of("relationMapId", "rm-1", "mode", "append", "x", null),
                new ToolContext(s, null, "t"));
        assertEquals("rm-1", p.get("relation_map_id").getAsString());
        assertEquals("append", p.get("mode").getAsString());
        assertFalse(p.has("x"), "nulls are dropped");
        JsonObject noSpec = ArtifactSupport.params(Args.of("a", 1), null);
        assertEquals(1, noSpec.get("a").getAsInt());
    }
}
