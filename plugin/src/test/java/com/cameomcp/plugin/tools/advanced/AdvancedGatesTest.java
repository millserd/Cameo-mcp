// family: advanced
package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.catalog.AdvancedTools;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog wiring plus the safety gates that must hold without Cameo running. */
class AdvancedGatesTest {

    private static final List<String> REFERENCE_TOOLS = Arrays.asList(
            "cameo_get_validation_capabilities", "cameo_list_validation_suites", "cameo_run_native_validation",
            "cameo_get_validation_result", "cameo_run_validation", "cameo_get_report_capabilities",
            "cameo_list_report_templates", "cameo_generate_report_preview", "cameo_generate_report", "cameo_get_report_job",
            "cameo_get_requirements_capabilities", "cameo_get_import_export_capabilities", "cameo_export_requirements",
            "cameo_preview_requirements_import", "cameo_apply_requirements_import", "cameo_export_requirements_preview",
            "cameo_import_requirements_preview", "cameo_get_simulation_capabilities", "cameo_list_simulation_configurations",
            "cameo_run_simulation_preview", "cameo_run_simulation", "cameo_get_simulation_result",
            "cameo_terminate_simulation", "cameo_get_teamwork_capabilities", "cameo_get_teamwork_project",
            "cameo_preview_teamwork_commit", "cameo_preview_teamwork_update", "cameo_list_teamwork_descriptors",
            "cameo_list_teamwork_branches", "cameo_get_teamwork_history", "cameo_get_teamwork_locks",
            "cameo_get_datahub_capabilities", "cameo_list_datahub_sources", "cameo_preview_datahub_sync",
            "cameo_get_criteria_capabilities", "cameo_list_criteria_templates", "cameo_build_criteria_expression",
            "cameo_parse_criteria_expression", "cameo_apply_criteria_template", "cameo_capture_criteria_template_from_diff",
            "cameo_get_profile_capabilities", "cameo_export_profile_summary", "cameo_preview_profile_operation",
            "cameo_get_variant_capabilities", "cameo_analyze_variants_preview", "cameo_install_variant_pattern_preview",
            "cameo_export_variant_configuration", "cameo_get_extension_capabilities", "cameo_scan_extensions",
            "cameo_list_extension_profiles", "cameo_install_extension_pattern_preview",
            "cameo_get_typed_diagram_capabilities", "cameo_list_typed_diagrams", "cameo_inspect_typed_diagram",
            "cameo_preview_typed_diagram_operation", "cameo_refuse_compliance_claim");

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void everyReferenceToolIsSpecifiedAndBound() {
        ToolRegistry r = new ToolRegistry().addSpecs(AdvancedTools.specs());
        AdvancedBindings.bind(r);
        assertTrue(r.unboundTools().isEmpty(), "unbound: " + r.unboundTools());
        Set<String> names = new HashSet<>();
        for (ToolSpec s : AdvancedTools.specs()) {
            assertTrue(names.add(s.name()), "duplicate " + s.name());
            assertEquals("advanced", s.family());
            assertFalse(s.description().isEmpty(), s.name());
            for (String p : s.parameters().keySet()) {
                assertTrue(p.matches("[a-z][a-z0-9_]*"), s.name() + "." + p);
            }
        }
        assertEquals(new HashSet<>(REFERENCE_TOOLS), names);
        assertEquals(56, r.size());
    }

    @Test
    void writeGatesAreMarked() {
        ToolRegistry r = new ToolRegistry().addSpecs(AdvancedTools.specs());
        assertEquals(ToolSpec.Kind.WRITE, r.spec("cameo_apply_requirements_import").kind());
        assertEquals(ToolSpec.Kind.SIDE_EFFECT, r.spec("cameo_generate_report").kind());
        assertEquals(ToolSpec.Kind.SIDE_EFFECT, r.spec("cameo_export_requirements").kind());
        assertEquals(true, r.spec("cameo_apply_requirements_import").inputSchema().toString().contains("dry_run"));
    }

    @Test
    void requirementsApplyDefaultsToDryRun() throws Exception {
        JsonObject out = RequirementsService.apply(json(
                "{\"target_package_id\":\"P1\",\"csv_text\":\"id,name,text\\nR1,Speed,Fast\\n,,\"}"));
        assertTrue(out.get("dryRun").getAsBoolean());
        assertFalse(out.get("writePerformed").getAsBoolean());
        assertEquals(1, out.get("rowCount").getAsInt());
        JsonObject plan = out.getAsJsonObject("patchPlan");
        assertEquals("P1", plan.get("targetPackageId").getAsString());
        assertEquals("Speed", plan.getAsJsonArray("rows").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void requirementsApplyNeedsAllowWrite() {
        ToolException e = assertThrows(ToolException.class, () -> RequirementsService.apply(json(
                "{\"target_package_id\":\"P1\",\"rows\":[{\"name\":\"A\"}],\"dry_run\":false}")));
        assertEquals("PRECONDITION_FAILED", e.code());
        assertNotNull(e.details());
        assertEquals("unsupported", e.details().get("status"));
        assertTrue(e.getMessage().contains("allow_write=true"));
    }

    @Test
    void reqifApplyIsRefusedEvenWithAllowWrite() {
        ToolException e = assertThrows(ToolException.class, () -> RequirementsService.apply(json(
                "{\"format\":\"reqif\",\"dry_run\":false,\"allow_write\":true}")));
        assertEquals("UNSUPPORTED", e.code());
        assertTrue(String.valueOf(e.details().get("reason")).contains("ReqIF"));
    }

    @Test
    void patchPlanRowsFeedApply() {
        JsonObject preview = json("{\"patch_plan\":{\"targetPackageId\":\"P9\",\"rows\":[{\"row\":0,\"name\":\"N\","
                + "\"raw\":{\"name\":\"N\",\"id\":\"R1\"}}]}}");
        assertEquals("R1", RequirementsCodec.externalId(RequirementsService.rows(preview).get(0)));
    }

    @Test
    void reportGenerationNeedsAllowWrite() {
        ToolException e = assertThrows(ToolException.class,
                () -> ReportService.generate(json("{\"output_path\":\"/tmp/x.docx\"}")));
        assertEquals("PRECONDITION_FAILED", e.code());
        assertEquals("reportWizard", e.details().get("family"));
    }

    @Test
    void unknownJobsAndRunsAreNotFound() {
        assertEquals("NOT_FOUND", assertThrows(ToolException.class,
                () -> ReportService.job(json("{\"job_id\":\"inline-1\"}"))).code());
        assertEquals("NOT_FOUND", assertThrows(ToolException.class,
                () -> ValidationService.result(json("{\"run_id\":\"nope\"}"))).code());
        assertEquals("NOT_FOUND", assertThrows(ToolException.class,
                () -> SimulationService.result(json("{\"run_id\":\"r\"}"))).code());
        assertEquals("NOT_FOUND", assertThrows(ToolException.class,
                () -> SimulationService.terminate(json("{\"run_id\":\"r\"}"))).code());
    }

    @Test
    void simulationExecutionIsRefused() {
        ToolException e = assertThrows(ToolException.class,
                () -> SimulationService.run(json("{\"allow_execute\":true,\"configuration_id\":\"c\"}")));
        assertEquals("UNSUPPORTED", e.code());
        assertNotNull(e.details().get("capabilities"));
    }

    @Test
    void criteriaApplyIsRefused() {
        assertEquals("UNSUPPORTED", assertThrows(ToolException.class,
                () -> CriteriaService.apply(json("{\"target_id\":\"m1\",\"template_id\":\"satisfy.both\"}"))).code());
    }

    @Test
    void complianceClaimRefusalIsTheResult() {
        JsonObject out = ProductServices.refuseComplianceClaim(json("{\"claim_type\":\"ISO 26262\"}"));
        assertTrue(out.get("refused").getAsBoolean());
        assertEquals("COMPLIANCE_CLAIM_REFUSED", out.get("code").getAsString());
        assertFalse(out.get("available").getAsBoolean());
    }

    @Test
    void previewOperationsAreValidated() {
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> ProductServices.profilePreview(json("{\"operation\":\"drop-profile\"}"))).code());
        JsonObject ok = ProductServices.profilePreview(json("{\"operation\":\"create-tag\",\"payload\":{\"name\":\"t\"}}"));
        assertEquals("tag-create", ok.get("operation").getAsString());
        assertFalse(ok.get("writePerformed").getAsBoolean());
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> TypedDiagramService.preview(json("{\"operation\":\"sequence-message\"}"))).code());
        assertEquals("legend-apply", TypedDiagramService.preview(
                json("{\"operation\":\"legend-apply\",\"payload\":{}}")).get("operation").getAsString());
    }

    @Test
    void capabilityProbesWorkOutsideCameo() {
        JsonObject req = RequirementsService.requirementsCapabilities();
        assertFalse(req.get("available").getAsBoolean());
        assertEquals("unsupported", req.get("mode").getAsString());
        JsonObject validation = ValidationService.capabilities();
        assertFalse(validation.getAsJsonObject("projectState").get("open").getAsBoolean());
        assertTrue(CriteriaService.templates(json("{\"target\":\"matrix\"}")).get("count").getAsInt() == 1);
    }

    @Test
    void camelCaseArgumentsGetSnakeAliases() {
        JsonObject p = AdvancedBindings.snakeAliases(json("{\"targetPackageId\":\"P\",\"dry_run\":true,\"allowWrite\":true}"));
        assertEquals("P", p.get("target_package_id").getAsString());
        assertTrue(p.get("allow_write").getAsBoolean());
        JsonObject keep = AdvancedBindings.snakeAliases(json("{\"runId\":\"a\",\"run_id\":\"b\"}"));
        assertEquals("b", keep.get("run_id").getAsString());
    }
}
