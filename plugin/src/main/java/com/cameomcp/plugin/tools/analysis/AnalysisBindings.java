package com.cameomcp.plugin.tools.analysis;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyService;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyWorkflows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.analysis.J.obj;

/** Binds the analysis family: verification, semantic validation, remediation, proofing, methodology. */
public final class AnalysisBindings {

    private AnalysisBindings() {
    }

    /** Tool handler body that may use the bridge. */
    interface Body {
        Object run(Args a, Bridge bridge) throws Exception;
    }

    public static void bind(ToolRegistry r) {
        bind(r, new InternalBridge());
    }

    static void bind(ToolRegistry r, Bridge bridge) {
        on(r, bridge, "cameo_verify_matrix_consistency", AnalysisBindings::verifyMatrix);
        on(r, bridge, "cameo_verify_diagram_visual", AnalysisBindings::verifyDiagramVisual);
        on(r, bridge, "cameo_verify_activity_flow_semantics", (a, b) -> SemanticValidation.activityFlowForDiagram(
                a.reqStr("diagram_id"), a.integer("max_partition_depth", 1),
                a.bool("allow_stereotype_partition_labels", false), b));
        on(r, bridge, "cameo_verify_port_boundary_consistency", (a, b) -> SemanticValidation.portBoundaryForInterfaces(
                a.reqStrList("interface_block_ids"), a.strList("allow_shared_flow_property_names"), b));
        on(r, bridge, "cameo_verify_requirement_quality", (a, b) -> SemanticValidation.requirementQualityForIds(
                a.reqStrList("requirement_ids"), a.bool("require_id", true), a.bool("require_measurement", true),
                a.integer("min_text_length", 20), b));
        on(r, bridge, "cameo_verify_cross_diagram_traceability", (a, b) -> SemanticValidation.crossDiagramTraceability(
                blankToNull(a.str("activity_diagram_id")), a.strList("interface_block_ids"),
                blankToNull(a.str("ibd_diagram_id")), a.strList("requirement_ids"),
                a.strList("architecture_element_ids"), b));
        on(r, bridge, "cameo_detect_cross_diagram_inconsistencies", AnalysisBindings::detect);
        on(r, bridge, "cameo_build_cross_diagram_remediation_plan", (a, b) -> AutoRemediation.buildRemediationPlan(
                a.map("activity_validation"), a.map("port_validation"), a.map("requirement_validation"),
                a.map("trace_validation"), a.maps("architecture_elements")));

        on(r, bridge, "cameo_proof_model_text", (a, b) -> Proofing.proofModelText(
                blankToNull(a.str("root_package_id")), a.strList("requirement_ids"), a.strList("comment_ids"),
                a.strList("state_ids"), a.strList("transition_ids"), a.strList("diagram_ids"),
                a.bool("auto_apply", false), b));
        on(r, bridge, "cameo_apply_proofing_patch_plan", (a, b) -> {
            Map<String, Object> plan = a.map("patch_plan");
            if (plan == null) {
                throw ToolException.invalid("patch_plan is required: pass the patch_plan object returned by "
                        + "cameo_proof_model_text.");
            }
            return Proofing.applyPatchPlan(plan, b);
        });

        on(r, bridge, "cameo_list_methodology_packs", (a, b) -> MethodologyService.listMethodologyPacks());
        on(r, bridge, "cameo_get_methodology_pack", (a, b) -> MethodologyService.getMethodologyPack(a.reqStr("pack_id")));
        on(r, bridge, "cameo_get_methodology_guidance", (a, b) -> MethodologyService.getWorkflowGuidance(
                a.reqStr("pack_id"), blankToNull(a.str("recipe_id")), a.map("recipe_parameters"),
                optionalMaps(a, "completed_artifacts")));
        on(r, bridge, "cameo_execute_methodology_recipe", AnalysisBindings::executeRecipe);
        on(r, bridge, "cameo_validate_methodology_recipe", (a, b) -> MethodologyService.validateMethodologyRecipe(
                a.reqStr("pack_id"), a.reqStr("recipe_id"), a.map("recipe_parameters"),
                optionalMaps(a, "current_artifacts"), b));
        on(r, bridge, "cameo_generate_review_packet", (a, b) -> MethodologyService.generateReviewPacket(
                a.reqStr("pack_id"), a.reqStr("recipe_id"), a.map("recipe_parameters"),
                optionalMaps(a, "current_artifacts"), a.strList("assumptions"), a.strList("notes"), b));

        on(r, bridge, "cameo_compare_expected_artifact_list", (a, b) -> {
            if (!a.has("expected_artifacts")) {
                throw ToolException.invalid("expected_artifacts is required: a list of {key, kind, name, ...} objects.");
            }
            return MethodologyWorkflows.compareAgainstExpectedArtifactList(a.maps("expected_artifacts"),
                    optionalMaps(a, "current_artifacts"));
        });
        on(r, bridge, "cameo_validate_methodology_package", (a, b) -> MethodologyWorkflows.validateMethodologyPackageLive(
                a.reqStr("pack_id"), blankToNull(a.str("recipe_id")), blankToNull(a.str("root_package_id")),
                optionalMaps(a, "current_artifacts"), optionalMaps(a, "expected_artifacts"), b));
        on(r, bridge, "cameo_export_required_diagrams", (a, b) -> MethodologyWorkflows.exportRequiredDiagramsLive(
                a.reqStr("pack_id"), blankToNull(a.str("recipe_id")), blankToNull(a.str("root_package_id")),
                optionalMaps(a, "current_artifacts"), optionalMaps(a, "expected_artifacts"),
                exportFormat(a), blankToNull(a.str("output_dir")), b));
        on(r, bridge, "cameo_assemble_ppt_pdf", (a, b) -> MethodologyWorkflows.assemblePptPdfLive(
                a.reqStr("pack_id"), blankToNull(a.str("recipe_id")), blankToNull(a.str("root_package_id")),
                optionalMaps(a, "current_artifacts"), optionalMaps(a, "expected_artifacts"),
                blankToNull(a.str("output_dir")), blankToNull(a.str("title")), blankToNull(a.str("pptx_name")),
                blankToNull(a.str("pdf_name")), exportFormat(a), b));
    }

    private static void on(ToolRegistry r, Bridge bridge, String name, Body body) {
        r.bind(name, (a, c) -> {
            try {
                return body.run(a, bridge);
            } catch (MethodologyRuntime.StepFailedException e) {
                List<Object> receipts = new ArrayList<>();
                for (MethodologyRuntime.OperationReceipt receipt : e.receipts()) {
                    receipts.add(receipt.toMap());
                }
                String code = e.getCause() instanceof ToolException ? ((ToolException) e.getCause()).code() : "TOOL_FAILED";
                throw new ToolException(code, e.getMessage() + ". " + receipts.size() + " earlier step(s) were applied "
                        + "(see details.receipts); each is one undo step, so use cameo_undo to roll them back, or fix the "
                        + "cause and re-run: existing artifacts are reused.",
                        obj("stepId", e.stepId(), "operationKind", e.operationKind(), "receipts", receipts), e);
            } catch (IllegalArgumentException e) {
                throw new ToolException("INVALID_PARAMS", e.getMessage(), null, e);
            }
        });
    }

    // ------------------------------------------------------------------ handlers

    private static Object verifyMatrix(Args a, Bridge bridge) throws Exception {
        Map<String, Object> matrix = bridge.getMatrix(a.reqStr("matrix_id"));
        Map<String, Object> result = Verification.verifyMatrixConsistency(matrix,
                a.strList("expected_row_ids"), a.strList("expected_column_ids"),
                a.strList("expected_dependency_names"), a.integer("min_populated_cell_count", 0),
                a.dbl("min_density", 0.0));
        result.put("matrix", matrix);
        return result;
    }

    private static Object verifyDiagramVisual(Args a, Bridge bridge) throws Exception {
        String diagramId = a.reqStr("diagram_id");
        Map<String, Object> image = bridge.getDiagramImage(diagramId, obj());
        Map<String, Object> shapes = bridge.listDiagramShapes(diagramId);
        Verification.VisualOptions o = new Verification.VisualOptions();
        o.expectedElementIds = a.strList("expected_element_ids");
        o.expectedRelationshipIds = a.strList("expected_relationship_ids");
        o.minShapeCount = a.integer("min_shape_count", 0);
        o.minRelationshipShapeCount = a.integer("min_relationship_shape_count", 0);
        o.minWidth = a.integer("min_width", 1);
        o.minHeight = a.integer("min_height", 1);
        o.minImageBytes = a.integer("min_image_bytes", 1);
        o.minContentCoverageRatio = a.dbl("min_content_coverage_ratio", 0.0);
        o.maxOverlapRatio = a.dbl("max_overlap_ratio", 1.0);
        Map<String, Object> result = Verification.verifyDiagramVisual(image, shapes, o);
        result.put("diagramImage", image);
        result.put("diagramShapes", shapes);
        return result;
    }

    private static Object detect(Args a, Bridge bridge) throws Exception {
        AutoRemediation.DetectOptions o = new AutoRemediation.DetectOptions();
        o.activityDiagramId = blankToNull(a.str("activity_diagram_id"));
        o.interfaceBlockIds = a.strList("interface_block_ids");
        o.ibdDiagramId = blankToNull(a.str("ibd_diagram_id"));
        o.requirementIds = a.strList("requirement_ids");
        o.architectureElementIds = a.strList("architecture_element_ids");
        o.allowSharedFlowPropertyNames = a.has("allow_shared_flow_property_names")
                ? a.strList("allow_shared_flow_property_names") : null;
        o.requireId = a.bool("require_id", true);
        o.requireMeasurement = a.bool("require_measurement", true);
        o.minRequirementTextLength = a.integer("min_requirement_text_length", 20);
        o.maxPartitionDepth = a.integer("max_partition_depth", 1);
        o.allowStereotypePartitionLabels = a.bool("allow_stereotype_partition_labels", false);
        return AutoRemediation.detect(o, bridge);
    }

    private static Object executeRecipe(Args a, Bridge bridge) {
        Map<String, Object> params = a.map("recipe_parameters");
        if (params == null) {
            throw ToolException.invalid("recipe_parameters is required (an object of recipe parameter values; "
                    + "see cameo_get_methodology_pack for the recipe's parameters).");
        }
        return MethodologyService.executeMethodologyRecipe(a.reqStr("pack_id"), a.reqStr("recipe_id"), params,
                a.reqStr("root_package_id"), optionalMaps(a, "completed_artifacts"), a.strList("assumptions"), bridge);
    }

    // ------------------------------------------------------------------ helpers

    private static List<Map<String, Object>> optionalMaps(Args a, String name) {
        return a.get(name) == null ? null : a.maps(name);
    }

    private static String exportFormat(Args a) {
        String format = a.str("export_format");
        return format == null || format.trim().isEmpty() ? "png" : format.trim();
    }

    static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
