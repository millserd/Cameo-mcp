// family: analysis
package com.cameomcp.plugin.tools.analysis;

import com.cameomcp.catalog.AnalysisTools;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolContext;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cameomcp.plugin.tools.analysis.Fixtures.pngPayload;
import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AnalysisBindings}: every analysis spec is bound and well formed, and each tool wrapper
 * passes its arguments through and shapes its result as expected.
 */
class AnalysisBindingsTest {

    private static final List<String> TOOLS = List.of(
            "cameo_verify_matrix_consistency", "cameo_verify_diagram_visual", "cameo_verify_activity_flow_semantics",
            "cameo_verify_port_boundary_consistency", "cameo_verify_requirement_quality",
            "cameo_verify_cross_diagram_traceability", "cameo_detect_cross_diagram_inconsistencies",
            "cameo_build_cross_diagram_remediation_plan", "cameo_proof_model_text", "cameo_apply_proofing_patch_plan",
            "cameo_list_methodology_packs", "cameo_get_methodology_pack", "cameo_get_methodology_guidance",
            "cameo_execute_methodology_recipe", "cameo_validate_methodology_recipe", "cameo_generate_review_packet",
            "cameo_compare_expected_artifact_list", "cameo_validate_methodology_package",
            "cameo_export_required_diagrams", "cameo_assemble_ppt_pdf");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    private static ToolRegistry registry(FakeBridge bridge) {
        ToolRegistry r = new ToolRegistry().addSpecs(AnalysisTools.specs());
        AnalysisBindings.bind(r, bridge);
        return r;
    }

    private static Map<String, Object> call(ToolRegistry r, String name, Object... kv) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            raw.put((String) kv[i], kv[i + 1]);
        }
        return m(r.handler(name).call(new Args(raw), new ToolContext(r.spec(name), "s", "test")));
    }

    @Test
    void everyAnalysisSpecIsBound() {
        ToolRegistry r = new ToolRegistry().addSpecs(AnalysisTools.specs());
        AnalysisBindings.bind(r);
        assertTrue(r.unboundTools().isEmpty(), "unbound: " + r.unboundTools());
        assertEquals(20, r.size());
    }

    @Test
    void specsAreWellFormed() {
        Set<String> names = new HashSet<>();
        for (ToolSpec s : AnalysisTools.specs()) {
            assertTrue(names.add(s.name()), "duplicate " + s.name());
            assertEquals("analysis", s.family());
            assertFalse(s.description().isEmpty(), s.name());
            for (Object p : s.parameters().keySet()) {
                assertTrue(((String) p).matches("[a-z][a-z0-9_]*"), s.name() + "." + p);
            }
        }
        assertEquals(new HashSet<>(TOOLS), names);
        assertTrue(AnalysisTools.specs().stream().filter(s -> s.name().equals("cameo_execute_methodology_recipe"))
                .noneMatch(ToolSpec::isReadOnly));
        assertTrue(AnalysisTools.specs().stream().filter(s -> s.name().startsWith("cameo_verify_"))
                .allMatch(ToolSpec::isReadOnly));
    }

    @Test
    void verifyMatrixAttachesMatrixPayload() throws Exception {
        FakeBridge bridge = new FakeBridge();
        Map<String, Object> matrix = obj("rows", arr(obj("id", "row-1")), "columns", arr(obj("id", "col-1")),
                "populatedCells", arr(obj("rowId", "row-1", "columnId", "col-1",
                        "dependencies", arr(obj("name", "Refine")))));
        bridge.matrixFn = id -> matrix;
        Map<String, Object> result = call(registry(bridge), "cameo_verify_matrix_consistency",
                "matrix_id", "mx-1", "expected_dependency_names", arr("Refine"), "min_populated_cell_count", 1);
        assertEquals(true, result.get("ok"));
        assertEquals(matrix, result.get("matrix"));
    }

    @Test
    void verifyDiagramVisualAttachesImageAndShapes() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.imageFn = id -> pngPayload(100, 80);
        bridge.shapes.put("dia-1", obj("shapes", arr(obj("presentationId", "pe-1", "elementId", "el-1",
                "bounds", obj("x", 10, "y", 10, "width", 30, "height", 30)))));
        Map<String, Object> result = call(registry(bridge), "cameo_verify_diagram_visual",
                "diagram_id", "dia-1", "expected_element_ids", arr("el-1"), "min_shape_count", 1);
        assertTrue(result.containsKey("diagramImage"));
        assertTrue(result.containsKey("diagramShapes"));
        assertTrue(result.containsKey("checks"));
    }

    @Test
    void methodologyCatalogWrappers() throws Exception {
        ToolRegistry r = registry(new FakeBridge());
        Map<String, Object> packs = call(r, "cameo_list_methodology_packs");
        assertTrue(J.list(packs.get("packs")).size() >= 2);
        Map<String, Object> pack = call(r, "cameo_get_methodology_pack", "pack_id", "oosem");
        assertEquals("oosem", pack.get("id"));
        ToolException e = assertThrows(ToolException.class,
                () -> call(r, "cameo_get_methodology_pack", "pack_id", "missing"));
        assertEquals("NOT_FOUND", e.code());
        Map<String, Object> guidance = call(r, "cameo_get_methodology_guidance", "pack_id", "oosem");
        assertTrue(guidance.containsKey("guidance"));
    }

    @Test
    void executeRecipeRequiresParameters() {
        ToolRegistry r = registry(new FakeBridge());
        ToolException e = assertThrows(ToolException.class, () -> call(r, "cameo_execute_methodology_recipe",
                "pack_id", "oosem", "recipe_id", "use_case_model", "root_package_id", "root-1"));
        assertEquals("INVALID_PARAMS", e.code());
    }

    @Test
    void compareAndValidatePackageWrappers() throws Exception {
        ToolRegistry r = registry(new FakeBridge());
        Map<String, Object> compare = call(r, "cameo_compare_expected_artifact_list",
                "expected_artifacts", arr(obj("key", "workspace", "kind", "Package", "name", "Workspace"),
                        obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD")),
                "current_artifacts", arr(obj("key", "workspace", "kind", "Package", "name", "Workspace",
                        "element_id", "pkg-1")));
        assertEquals(List.of("logical_ibd"), compare.get("missingArtifactKeys"));
        ToolException missing = assertThrows(ToolException.class,
                () -> call(r, "cameo_compare_expected_artifact_list"));
        assertEquals("INVALID_PARAMS", missing.code());

        Map<String, Object> validation = call(r, "cameo_validate_methodology_package", "pack_id", "oosem",
                "current_artifacts", arr(obj("key", "workspace", "kind", "Package", "name", "Workspace",
                        "element_id", "pkg-1")),
                "expected_artifacts", arr(obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD")));
        assertEquals(false, validation.get("ready"));
        assertTrue(validation.containsKey("recommendedActions"));
    }

    @Test
    void exportAndAssembleWrappersWriteFiles(@TempDir Path tmp) throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.imageFn = id -> pngPayload(60, 40);
        ToolRegistry r = registry(bridge);
        Object current = arr(obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD", "element_id", "dia-1"));
        Object expected = arr(obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD"));

        Map<String, Object> dry = call(r, "cameo_export_required_diagrams", "pack_id", "oosem",
                "current_artifacts", current, "expected_artifacts", expected);
        assertEquals(true, dry.get("dryRun"));
        assertEquals("png", dry.get("exportFormat"));

        Map<String, Object> export = call(r, "cameo_export_required_diagrams", "pack_id", "oosem",
                "current_artifacts", current, "expected_artifacts", expected, "output_dir", tmp.toString());
        assertEquals(1, ((Number) export.get("exportedCount")).intValue());
        assertEquals("png", bridge.lastImageOptions().get("format"));

        Map<String, Object> deck = call(r, "cameo_assemble_ppt_pdf", "pack_id", "oosem",
                "current_artifacts", current, "expected_artifacts", expected,
                "output_dir", tmp.resolve("deck").toString(), "title", "Demo Deck");
        List<Map<String, Object>> created = J.maps(deck.get("createdFiles"));
        assertEquals(2, created.size());
        for (Map<String, Object> file : created) {
            assertTrue(Files.size(Paths.get(J.str(file.get("path")))) > 0);
        }
    }

    @Test
    void remediationWrappers() throws Exception {
        ToolRegistry r = registry(AutoRemediationTest.remediationBridge());
        Map<String, Object> plan = call(r, "cameo_build_cross_diagram_remediation_plan");
        assertEquals(true, plan.get("ok"));
        Map<String, Object> detected = call(r, "cameo_detect_cross_diagram_inconsistencies",
                "activity_diagram_id", "act-1", "interface_block_ids", arr("if-1", "if-2"),
                "ibd_diagram_id", "ibd-1", "requirement_ids", arr("req-1", "req-2"),
                "architecture_element_ids", arr("blk-1"));
        assertEquals(false, detected.get("ok"));
    }

    @Test
    void proofingWrappers() throws Exception {
        FakeBridge bridge = ProofingTest.proofingBridge();
        ToolRegistry r = registry(bridge);
        Map<String, Object> report = call(r, "cameo_proof_model_text", "root_package_id", "pkg-1");
        assertEquals(false, report.get("ok"));
        Map<String, Object> applied = call(r, "cameo_apply_proofing_patch_plan", "patch_plan", report.get("patch_plan"));
        assertEquals(true, applied.get("ok"));
        assertFalse(bridge.callsOf("modify_element").isEmpty());
        ToolException e = assertThrows(ToolException.class, () -> call(r, "cameo_apply_proofing_patch_plan"));
        assertEquals("INVALID_PARAMS", e.code());
    }
    @Test
    void semanticVerifyWrappersPassParameters() throws Exception {
        FakeBridge bridge = AutoRemediationTest.remediationBridge();
        ToolRegistry r = registry(bridge);

        Map<String, Object> activity = call(r, "cameo_verify_activity_flow_semantics", "diagram_id", "act-1",
                "max_partition_depth", 2, "allow_stereotype_partition_labels", true);
        assertTrue(activity.containsKey("checks"));

        Map<String, Object> ports = call(r, "cameo_verify_port_boundary_consistency",
                "interface_block_ids", arr("if-1", "if-2"), "allow_shared_flow_property_names", arr("Available Slots"));
        assertEquals(List.of(List.of("if-1", "if-2")), bridge.interfaceFlowCalls);
        assertTrue(ports.containsKey("checks"));
        assertThrows(ToolException.class, () -> call(r, "cameo_verify_port_boundary_consistency"));

        Map<String, Object> strict = call(r, "cameo_verify_requirement_quality", "requirement_ids", arr("req-2"));
        Map<String, Object> lenient = call(r, "cameo_verify_requirement_quality", "requirement_ids", arr("req-2"),
                "require_id", false, "require_measurement", false, "min_text_length", 10);
        assertEquals(false, strict.get("ok"));
        assertTrue(J.maps(lenient.get("checks")).size() > 0);

        Map<String, Object> trace = call(r, "cameo_verify_cross_diagram_traceability",
                "activity_diagram_id", "act-1", "interface_block_ids", arr("if-1"), "ibd_diagram_id", "ibd-1",
                "requirement_ids", arr("req-1"), "architecture_element_ids", arr("blk-1"));
        assertTrue(trace.containsKey("metrics"));
    }
}
