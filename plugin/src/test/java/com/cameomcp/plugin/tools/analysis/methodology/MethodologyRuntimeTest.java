// family: analysis
package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.plugin.tools.analysis.FakeBridge;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ArtifactRequirement;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ArtifactSnapshot;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ConformanceFinding;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ConformanceReport;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.EvidenceBundle;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.OperationReceipt;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.PackDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeExecutionPlan;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeExecutionResult;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeOperationDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RelationshipRequirement;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.WorkflowGuidance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MethodologyRuntime}: workflow guidance, plans that skip existing artifacts, execution
 * with bindings, primary-ID extraction, conformance and evidence bundles, semantic findings, binding errors
 * and receipts on a failed step.
 */
class MethodologyRuntimeTest {

    private static Map<String, Object> ref(String key) {
        return obj("ref", key);
    }

    static PackDefinition samplePack() {
        RecipeDefinition recipe = new RecipeDefinition("oosem.stakeholder_needs", "Stakeholder Needs Package", "analysis",
                "Create a starter OOSEM stakeholder needs package.",
                List.of(new ArtifactRequirement("stakeholder_needs_package", "Package", "Stakeholder Needs", "workspace"),
                        new ArtifactRequirement("stakeholder_actor", "Actor", "Stakeholder", "stakeholder_needs_package",
                                List.of("stakeholder"), null),
                        new ArtifactRequirement("stakeholder_need_usecase", "UseCase", "Capture Stakeholder Need",
                                "stakeholder_needs_package"),
                        new ArtifactRequirement("stakeholder_needs_diagram", "Use Case Diagram", "Stakeholder Needs",
                                "stakeholder_needs_package")),
                List.of(new RelationshipRequirement("Association", "stakeholder_actor", "stakeholder_need_usecase", "identifies")),
                List.of(new RecipeOperationDefinition("create_element", "stakeholder_needs_package",
                                obj("type", "Package", "name", "Stakeholder Needs", "parent_id", ref("workspace"))),
                        new RecipeOperationDefinition("create_element", "stakeholder_actor",
                                obj("type", "Actor", "name", "Stakeholder", "parent_id", ref("stakeholder_needs_package"),
                                        "stereotype", "stakeholder")),
                        new RecipeOperationDefinition("create_element", "stakeholder_need_usecase",
                                obj("type", "UseCase", "name", "Capture Stakeholder Need",
                                        "parent_id", ref("stakeholder_needs_package"))),
                        new RecipeOperationDefinition("create_diagram", "stakeholder_needs_diagram",
                                obj("type", "Use Case Diagram", "name", "Stakeholder Needs",
                                        "parent_id", ref("stakeholder_needs_package"))),
                        new RecipeOperationDefinition("add_to_diagram", null,
                                obj("diagram_id", ref("stakeholder_needs_diagram"), "element_id", ref("stakeholder_actor"),
                                        "x", 120, "y", 80)),
                        new RecipeOperationDefinition("add_to_diagram", null,
                                obj("diagram_id", ref("stakeholder_needs_diagram"),
                                        "element_id", ref("stakeholder_need_usecase"), "x", 320, "y", 80)),
                        new RecipeOperationDefinition("create_relationship", null,
                                obj("type", "Association", "source_id", ref("stakeholder_actor"),
                                        "target_id", ref("stakeholder_need_usecase"), "name", "identifies"))),
                List.of("Confirm the package, actor, use case, and diagram exist.",
                        "Verify the actor-to-use-case association is present."),
                List.of("scope", "trace", "diagram", "conformance"),
                "subject-with-usecases", null, null);
        return new PackDefinition("oosem", "OOSEM", "Opinionated starter pack for OOSEM-style analysis artifacts.",
                List.of("analysis", "architecture"), List.of(recipe), List.of("Pack definitions should drive the workflow."),
                List.of("sysml"));
    }

    private final PackDefinition pack = samplePack();
    private final RecipeDefinition recipe = pack.recipe("oosem.stakeholder_needs");
    private final ArtifactSnapshot workspace = new ArtifactSnapshot("workspace", "Package", "Workspace", "pkg-root");

    @Test
    void workflowGuidanceReportsMissingArtifactsAndActions() {
        WorkflowGuidance guidance = MethodologyRuntime.buildWorkflowGuidance(pack, List.of(workspace), null);
        assertEquals("oosem", guidance.packId());
        assertEquals("oosem.stakeholder_needs", guidance.recipeId());
        assertFalse(guidance.readyToExecute());
        assertTrue(guidance.missingArtifactKeys().contains("stakeholder_needs_package"));
        assertTrue(guidance.recommendedActions().contains("create Package 'Stakeholder Needs' under 'workspace'"));
        assertEquals(0.0, guidance.completionRatio());
        assertTrue(guidance.missingRelationships().contains("Association:stakeholder_actor->stakeholder_need_usecase"));
    }

    @Test
    void executionPlanSkipsExistingArtifacts() {
        RecipeExecutionPlan plan = MethodologyRuntime.buildRecipeExecutionPlan(pack, recipe.recipeId(), List.of(workspace,
                new ArtifactSnapshot("stakeholder_needs_package", "Package", "Stakeholder Needs", "pkg-1")));
        assertFalse(plan.readyToExecute());
        assertEquals("skipped", plan.plannedOperations().get(0).status());
        assertTrue(plan.plannedOperations().get(0).reason().contains("already exists"));
        assertEquals("pending", plan.plannedOperations().get(1).status());
        assertEquals("pkg-1", plan.artifactBindings().get("stakeholder_needs_package"));
        assertEquals("oosem.stakeholder_needs:1", plan.plannedOperations().get(0).stepId());
    }

    @Test
    void executeRecipeRunsBridgeOperationsAndTracksBindings() {
        RecipeExecutionPlan plan = MethodologyRuntime.buildRecipeExecutionPlan(pack, recipe.recipeId(), List.of(workspace));
        FakeBridge bridge = new FakeBridge();
        RecipeExecutionResult result = MethodologyRuntime.executeRecipe(plan, bridge);
        assertEquals(7, result.receipts().size());
        assertEquals("applied", result.receipts().get(0).status());
        assertTrue(result.artifactBindings().containsKey("stakeholder_needs_package"));
        assertTrue(result.artifactBindings().containsKey("stakeholder_needs_diagram"));
        assertFalse(bridge.callsOf("create_relationship").isEmpty());
        assertFalse(bridge.callsOf("add_to_diagram").isEmpty());
        assertEquals("pkg-root", bridge.callsOf("create_element").get(0).get("parent_id"));
        assertEquals("el-1", bridge.callsOf("create_element").get(1).get("parent_id"));
    }

    @Test
    void resultPrimaryIdPrefersPresentationIdForDiagramAdds() {
        assertEquals("pe-42", MethodologyRuntime.resultPrimaryId(obj("presentationId", "pe-42", "diagramId", "dia-9")));
    }

    @Test
    void resultPrimaryIdPrefersNestedPathPresentationIdOverDiagramId() {
        assertEquals("path-7", MethodologyRuntime.resultPrimaryId(obj("diagramId", "dia-9",
                "results", arr(obj("presentationId", "path-7", "created", true)))));
        assertEquals("el-3", MethodologyRuntime.resultPrimaryId(obj("element", obj("id", "el-3"))));
        assertEquals(null, MethodologyRuntime.resultPrimaryId(obj("updated", true)));
    }

    @Test
    void executeRecipeRecordsResolvedSpecificationProperties() {
        RecipeDefinition portRecipe = new RecipeDefinition("oosem.port_spec", "Port Spec", "architecture",
                "Exercise resolved specification values.",
                List.of(new ArtifactRequirement("workspace", "Package")), List.of(),
                List.of(new RecipeOperationDefinition("create_element", "interface_block",
                                obj("type", "InterfaceBlock", "name", "Port Type", "parent_id", ref("workspace"))),
                        new RecipeOperationDefinition("create_element", "system_port",
                                obj("type", "Port", "name", "system_port", "parent_id", ref("workspace"))),
                        new RecipeOperationDefinition("set_specification", "system_port",
                                obj("element_id", ref("system_port"),
                                        "properties", obj("type", obj("id", ref("interface_block")))))),
                List.of(), List.of(), null, null, null);
        PackDefinition portPack = new PackDefinition("oosem", "OOSEM", "Test pack", List.of("architecture"),
                List.of(portRecipe), null, null);
        RecipeExecutionPlan plan = MethodologyRuntime.buildRecipeExecutionPlan(portPack, portRecipe.recipeId(), List.of(workspace));
        RecipeExecutionResult result = MethodologyRuntime.executeRecipe(plan, new FakeBridge());
        assertEquals(obj("type", obj("id", "el-1")), result.updatedArtifacts().get(0).properties());
    }

    @Test
    void conformanceAndEvidenceBundleAreCompactAndUseful() {
        List<ArtifactSnapshot> current = List.of(
                new ArtifactSnapshot("stakeholder_needs_package", "Package", "Stakeholder Needs", "pkg-1"),
                new ArtifactSnapshot("stakeholder_actor", "Actor", "Stakeholder", "el-1"),
                new ArtifactSnapshot("stakeholder_need_usecase", "UseCase", "Capture Stakeholder Need", "el-2"),
                new ArtifactSnapshot("stakeholder_needs_diagram", "Use Case Diagram", "Stakeholder Needs", "dia-1"));
        WorkflowGuidance guidance = MethodologyRuntime.buildWorkflowGuidance(pack, List.of(workspace), null);
        RecipeExecutionPlan plan = MethodologyRuntime.buildRecipeExecutionPlan(pack, recipe.recipeId(), List.of(workspace));
        RecipeExecutionResult executionResult = new RecipeExecutionResult(pack.packId(), recipe.recipeId(), recipe.name(),
                recipe.phase(), List.of(new OperationReceipt("s1", "create_element", "applied", obj("id", "el-1"))),
                Map.of("stakeholder_actor", "el-1"), List.of(), List.of(), List.of());
        ConformanceReport conformance = MethodologyRuntime.runConformanceChecks(recipe, current, pack.packId());
        EvidenceBundle bundle = MethodologyRuntime.buildEvidenceBundle(pack, recipe, guidance, plan, executionResult,
                conformance, List.of("All artifacts created in a local Cameo session."), null, null);

        assertFalse(conformance.passed());
        assertTrue(conformance.findings().size() >= 1);
        assertEquals("oosem", bundle.packId());
        assertTrue(bundle.artifactKeys().contains("stakeholder_actor"));
        assertTrue(bundle.toMap().containsKey("conformance"));
        assertTrue(bundle.generatedAt().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}\\+00:00"));
    }

    @Test
    void semanticFindingsExtendConformanceReport() {
        ConformanceReport base = MethodologyRuntime.runConformanceChecks(recipe, List.of(workspace), pack.packId());
        List<ConformanceFinding> semantic = MethodologyRuntime.semanticValidationFindings(List.of(obj(
                "validatorId", "requirement_quality", "ok", false,
                "checks", arr(obj("name", "requirement-text-present", "ok", false,
                        "details", obj("blankTextIds", arr("req-1")))))));
        assertEquals(1, semantic.size());
        assertEquals("requirement_quality check 'requirement-text-present' failed: req-1", semantic.get(0).message());
        ConformanceReport merged = MethodologyRuntime.extendConformanceReport(base, semantic);
        assertFalse(merged.passed());
        assertTrue(merged.findings().size() > base.findings().size());
        assertTrue(merged.findings().stream().anyMatch(f -> f.ruleId().startsWith("semantic:requirement_quality")));
        assertEquals(merged.findings().size() + " issue(s) found", merged.summary());
    }

    @Test
    void semanticFailureMessageFallbacks() {
        assertEquals("explicit", MethodologyRuntime.semanticFailureMessage("v", "c", obj("message", " explicit ")));
        assertEquals("v check 'c' failed: a, b, c...",
                MethodologyRuntime.semanticFailureMessage("v", "c", obj("missing", arr("a", "b", "c", "d"))));
        assertEquals("v check 'binding resolution' failed",
                MethodologyRuntime.semanticFailureMessage("v", "binding-resolution", null));
    }

    @Test
    void unresolvedReferenceIsReportedAsBindingError() {
        MethodologyRuntime.BindingException e = assertThrows(MethodologyRuntime.BindingException.class,
                () -> MethodologyRuntime.resolveValue(obj("a", arr(ref("missing"))), Map.of()));
        assertEquals("Unknown artifact binding: missing", e.getMessage());
    }

    @Test
    void failedStepCarriesEarlierReceipts() {
        RecipeExecutionPlan plan = MethodologyRuntime.buildRecipeExecutionPlan(pack, recipe.recipeId(), List.of(workspace));
        FakeBridge failing = new FakeBridge() {
            @Override
            public Map<String, Object> invoke(String operation, Map<String, Object> params) {
                if ("create_diagram".equals(operation)) {
                    throw new IllegalStateException("boom");
                }
                return super.invoke(operation, params);
            }
        };
        MethodologyRuntime.StepFailedException e = assertThrows(MethodologyRuntime.StepFailedException.class,
                () -> MethodologyRuntime.executeRecipe(plan, failing));
        assertEquals("oosem.stakeholder_needs:4", e.stepId());
        assertEquals(3, e.receipts().size());
        assertTrue(e.getMessage().contains("boom"));
    }
}
