package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.util.Arrays;
import java.util.List;

import static com.cameomcp.mcp.ToolSpec.REQUIRED;
import static com.cameomcp.mcp.ToolSpec.tool;

/**
 * Verification, semantic validation, cross-diagram remediation, text proofing and methodology
 * (OOSEM / UAF) workflow tools. They compose the public model/diagram tools.
 */
public final class AnalysisTools {

    // Family name used in docs and in cameo_list_tools filters.
    public static final String FAMILY = "analysis";

    // Parameter help shared by the methodology and comparison tools.
    private static final String PACK_ID = "Methodology pack id: \"oosem\" or \"uaf\" (see cameo_list_methodology_packs).";
    private static final String RECIPE_ID = "Recipe id from the pack, e.g. \"use_case_model\", \"system_requirements_package\", "
            + "\"logical_activity_flow\", \"logical_port_bdd\" (see cameo_get_methodology_pack).";
    private static final String RECIPE_PARAMS = "Recipe parameter values by name (e.g. {\"subject_name\": \"ATM\", "
            + "\"actor_names\": [\"Customer\"]}); they materialize the expected artifact names. Parameters are listed "
            + "per recipe by cameo_get_methodology_pack. root_package_id here also seeds the workspace.";
    private static final String ARTIFACTS = "Normalized artifact snapshots, each {\"key\", \"kind\", \"name\", \"element_id\"} "
            + "plus optional parent_key, stereotypes, properties, relationships. Keys are the recipe's artifact keys "
            + "(e.g. \"workspace\", \"use_case_package\").";
    private static final String CMP_ARTIFACTS = "Artifact objects {\"key\" (or element_id/id), \"kind\" (or type/humanType), "
            + "\"name\", \"parent_key\", \"element_id\", \"stereotypes\", \"properties\"}.";

    private AnalysisTools() {
    }

    /**
     * The family's specs, in the order clients see them. Descriptions and parameter help are the
     * text agents read when choosing a tool, so they say what the tool returns and which tool to call next.
     */
    public static List<ToolSpec> specs() {
        return Arrays.asList(
                // ---------------------------------------------------------- verification
                tool("cameo_verify_matrix_consistency", FAMILY).readOnly()
                        .desc("Run quantitative consistency checks against one native requirement matrix (wraps "
                                + "cameo_get_matrix): expected row/column membership, dependency names in populated cells, "
                                + "populated-cell count and density over the full row x column product. Returns ok, checks "
                                + "(name/ok/details), metrics and the matrix readback. Find matrix ids with cameo_list_matrices.")
                        .str("matrix_id", "Diagram id of the matrix artifact to validate.", REQUIRED)
                        .strArray("expected_row_ids", "Element ids that must appear in the row domain.")
                        .strArray("expected_column_ids", "Element ids that must appear in the column domain.")
                        .strArray("expected_dependency_names", "Relationship names that must appear in populated cells.")
                        .integer("min_populated_cell_count", "Minimum populated-cell count required.", 0)
                        .number("min_density", "Minimum populated-cell density (0-1) over the full row/column cross-product.", 0.0),
                tool("cameo_verify_diagram_visual", FAMILY).readOnly().timeout(180)
                        .desc("Run reusable visual verification checks against one diagram: combines "
                                + "cameo_get_diagram_image and cameo_list_diagram_shapes and checks image payload validity, "
                                + "rendered size, non-background content coverage, expected element/path presence, "
                                + "shape counts and coarse sibling-overlap risk. Returns ok, checks, imageMetrics, "
                                + "shapeMetrics plus diagramImage and diagramShapes readback.")
                        .str("diagram_id", "The id of the diagram to validate.", REQUIRED)
                        .strArray("expected_element_ids", "Model element ids that must appear on the diagram canvas.")
                        .strArray("expected_relationship_ids", "Relationship element ids that must appear as relationship paths.")
                        .integer("min_shape_count", "Minimum number of presentation elements expected.", 0)
                        .integer("min_relationship_shape_count", "Minimum number of relationship paths expected.", 0)
                        .integer("min_width", "Minimum rendered image width in pixels.", 1)
                        .integer("min_height", "Minimum rendered image height in pixels.", 1)
                        .integer("min_image_bytes", "Minimum PNG payload size in bytes.", 1)
                        .number("min_content_coverage_ratio", "Minimum non-background pixel coverage ratio over the image area.", 0.0)
                        .number("max_overlap_ratio", "Maximum allowed sibling-shape overlap ratio.", 1.0),
                tool("cameo_verify_activity_flow_semantics", FAMILY).readOnly().timeout(180)
                        .desc("Validate whether one activity diagram behaves like a coherent workflow: initial/final "
                                + "nodes, reachable actions, no isolated action islands, partition nesting depth, "
                                + "container-only partitions and stereotype-looking partition labels. Find the diagram with "
                                + "cameo_list_diagrams (inspect it with cameo_list_diagram_shapes). Use it when an activity "
                                + "diagram has the right nouns but may lack a real control/object flow. Returns checks, "
                                + "diagram readback (elements, relationships, diagramShapes) and the flattened flow graph.")
                        .str("diagram_id", "Activity diagram id.", REQUIRED)
                        .integer("max_partition_depth", "Maximum allowed nested activity-partition depth.", 1)
                        .bool("allow_stereotype_partition_labels", "When false, flags partition labels that look like "
                                + "stereotype notation such as «allocate».", false),
                tool("cameo_verify_port_boundary_consistency", FAMILY).readOnly()
                        .desc("Validate interface-block flow-property ownership and duplication: the same flow property "
                                + "copied onto several interfaces, direction conflicts, missing directions and orphan "
                                + "properties. Find interface blocks with cameo_query_elements(stereotype=\"InterfaceBlock\"). "
                                + "Returns checks plus the interfaceBlocks and flowProperties readback.")
                        .strArray("interface_block_ids", "Interface block ids to inspect together as one boundary set.", REQUIRED)
                        .strArray("allow_shared_flow_property_names", "Flow property names allowed on more than one interface block."),
                tool("cameo_verify_requirement_quality", FAMILY).readOnly()
                        .desc("Validate whether SysML requirements contain real requirement content: requirement id "
                                + "present, non-blank text, minimum length, directive (shall/must/will) and measurable wording. "
                                + "Collect ids with cameo_query_elements(stereotype=\"Requirement\"). Returns per-requirement "
                                + "assessments, aggregate checks and the requirement readback.")
                        .strArray("requirement_ids", "Requirement element ids to evaluate.", REQUIRED)
                        .bool("require_id", "Fail requirements that have no requirement id.", true)
                        .bool("require_measurement", "Fail requirements that do not look directive and measurable.", true)
                        .integer("min_text_length", "Minimum text length for a non-trivial requirement statement.", 20),
                tool("cameo_verify_cross_diagram_traceability", FAMILY).readOnly().timeout(180)
                        .desc("Validate traceability across activity, port, IBD and requirement views: activity terms "
                                + "covered by interface flow properties and by IBD flows/ports, and requirements traced "
                                + "into the architecture element set. Every input is optional; only applicable checks run. "
                                + "Find diagrams with cameo_list_diagrams, interface blocks / requirements with "
                                + "cameo_query_elements(stereotype=\"InterfaceBlock\" / \"Requirement\").")
                        .str("activity_diagram_id", "Activity diagram to extract behavior-side terms from.")
                        .strArray("interface_block_ids", "Interface blocks to compare against the activity vocabulary.")
                        .str("ibd_diagram_id", "IBD to compare against the activity vocabulary.")
                        .strArray("requirement_ids", "Requirements to trace into the architecture element set.")
                        .strArray("architecture_element_ids", "Architecture element ids that count as valid requirement trace targets."),
                tool("cameo_detect_cross_diagram_inconsistencies", FAMILY).readOnly().timeout(300)
                        .desc("Detect cross-diagram inconsistencies and return a previewable remediation plan. "
                                + "Non-mutating: runs the activity, port-boundary, requirement-quality and traceability "
                                + "validators on the supplied artifacts, then returns structured receipts (with confidence) "
                                + "plus a patchPlan.steps preview to inspect before any change. Pass any subset of the ids.")
                        .str("activity_diagram_id", "Activity diagram id.")
                        .strArray("interface_block_ids", "Interface block ids.")
                        .str("ibd_diagram_id", "Internal block diagram id.")
                        .strArray("requirement_ids", "Requirement ids.")
                        .strArray("architecture_element_ids", "Architecture element ids that count as trace targets.")
                        .strArray("allow_shared_flow_property_names", "Flow property names allowed on several interfaces.")
                        .bool("require_id", "Requirements must carry an id.", true)
                        .bool("require_measurement", "Requirements must be directive and measurable.", true)
                        .integer("min_requirement_text_length", "Minimum requirement text length.", 20)
                        .integer("max_partition_depth", "Maximum nested activity-partition depth.", 1)
                        .bool("allow_stereotype_partition_labels", "Allow stereotype-looking partition labels.", false),
                tool("cameo_build_cross_diagram_remediation_plan", FAMILY).readOnly().noProject()
                        .desc("Build a previewable remediation plan from existing validation payloads (the results of "
                                + "cameo_verify_activity_flow_semantics, cameo_verify_port_boundary_consistency, "
                                + "cameo_verify_requirement_quality and cameo_verify_cross_diagram_traceability) without "
                                + "reading the model again. Returns receipts and patchPlan.steps.")
                        .obj("activity_validation", "Result of cameo_verify_activity_flow_semantics.")
                        .obj("port_validation", "Result of cameo_verify_port_boundary_consistency.")
                        .obj("requirement_validation", "Result of cameo_verify_requirement_quality.")
                        .obj("trace_validation", "Result of cameo_verify_cross_diagram_traceability.")
                        .objArray("architecture_elements", "Architecture elements ({id, name}) to suggest as trace targets."),

                // ---------------------------------------------------------- proofing
                tool("cameo_proof_model_text", FAMILY).write().timeout(300)
                        .desc("Proof model text across requirements, comments, states, transitions and diagram labels: "
                                + "spelling (built-in engineering lexicon), naming style (Title Case state and diagram-label "
                                + "names, PascalCase transition names, sentence case for requirement/comment text), "
                                + "placeholder text (TODO/TBD/FIXME), whitespace and stray punctuation. Give root_package_id for a package-wide sweep or explicit "
                                + "ids for narrower analysis. Read-only unless auto_apply=true, which applies the safe "
                                + "rename/text edits (one undo step each) and returns apply receipts. Otherwise review "
                                + "patch_plan and pass it to cameo_apply_proofing_patch_plan.")
                        .str("root_package_id", "Package whose subtree (elements and diagrams) is proofed.")
                        .strArray("requirement_ids", "Requirement ids to proof.")
                        .strArray("comment_ids", "Comment ids to proof.")
                        .strArray("state_ids", "State ids to proof.")
                        .strArray("transition_ids", "Transition ids to proof.")
                        .strArray("diagram_ids", "Diagrams whose shape labels are proofed.")
                        .bool("auto_apply", "Apply safe fixes immediately.", false),
                tool("cameo_apply_proofing_patch_plan", FAMILY).write().timeout(300)
                        .desc("Apply a previously generated proofing patch plan (the patch_plan of cameo_proof_model_text) "
                                + "to the live model: name fields through cameo_modify_element, documentation through "
                                + "cameo_modify_element, other text fields through cameo_set_specification. Returns one "
                                + "receipt per operation and failedCount; each edit is its own undo step.")
                        .obj("patch_plan", "Patch plan object with an operations list (from cameo_proof_model_text).", REQUIRED),

                // ---------------------------------------------------------- methodology packs
                tool("cameo_list_methodology_packs", FAMILY).readOnly().noProject()
                        .desc("List the built-in methodology packs (OOSEM viewpoint pack, UAF starter pack) with each "
                                + "pack's phases, recipes, naming rules, mandatory relationships, review sections and "
                                + "evidence expectations. Start here before cameo_get_methodology_guidance."),
                tool("cameo_get_methodology_pack", FAMILY).readOnly().noProject()
                        .desc("Get one methodology pack definition: recipes (with parameters, prerequisites, created "
                                + "artifacts, mandatory relationships, steps), phases, naming rules and review/evidence structure.")
                        .str("pack_id", PACK_ID, REQUIRED),
                tool("cameo_get_methodology_guidance", FAMILY).readOnly().noProject()
                        .desc("Explain what artifact work is missing next for a methodology pack: blockers, missing "
                                + "artifacts and relationships, completion ratio and recommended next actions. Omit "
                                + "recipe_id to assess the first pending recipe. Pure planning: it does not read the model, "
                                + "so pass completed_artifacts you already know.")
                        .str("pack_id", PACK_ID, REQUIRED)
                        .str("recipe_id", "Recipe to assess; omit for the first pending recipe.")
                        .obj("recipe_parameters", RECIPE_PARAMS)
                        .objArray("completed_artifacts", "Artifacts already present. " + ARTIFACTS),
                tool("cameo_execute_methodology_recipe", FAMILY).write().timeout(600)
                        .desc("Execute a bounded OOSEM/UAF methodology recipe through structured model operations "
                                + "(cameo_create_element, cameo_create_relationship, cameo_create_diagram, "
                                + "cameo_add_to_diagram, cameo_add_diagram_paths, cameo_set_specification, "
                                + "cameo_set_shape_compartments, cameo_route_paths, cameo_set_usecase_subject). Existing "
                                + "artifacts (given or discovered by name under root_package_id) are reused, not duplicated. "
                                + "Returns workflow guidance, the execution plan, receipts, conformance (structural plus "
                                + "semantic validators), an evidence bundle and review-packet markdown. Each operation is its "
                                + "own undo step; if a step fails the error lists the steps already applied. Check required "
                                + "parameters with cameo_get_methodology_pack first.")
                        .str("pack_id", PACK_ID, REQUIRED)
                        .str("recipe_id", RECIPE_ID, REQUIRED)
                        .str("root_package_id", "Package/model that will own the new artifact set.", REQUIRED)
                        .obj("recipe_parameters", "Parameter values for the recipe (required ones must be present), e.g. "
                                + "{\"subject_name\": \"ATM\", \"actor_names\": [\"Customer\"], \"use_case_names\": [\"Withdraw Cash\"]}.",
                                REQUIRED)
                        .objArray("completed_artifacts", "Artifacts already present and available for trace/allocation linkage. " + ARTIFACTS)
                        .strArray("assumptions", "Assumptions to include in the evidence bundle."),
                tool("cameo_validate_methodology_recipe", FAMILY).readOnly().timeout(300)
                        .desc("Run methodology conformance checks for one recipe without changing the model: required "
                                + "artifacts (given or discovered under root_package_id), kinds, names, parents, stereotypes, "
                                + "relationships, plus the recipe's semantic validators (requirement quality, activity flow, "
                                + "port boundary, traceability). Returns workflowGuidance, conformance and semanticValidations.")
                        .str("pack_id", PACK_ID, REQUIRED)
                        .str("recipe_id", RECIPE_ID, REQUIRED)
                        .obj("recipe_parameters", RECIPE_PARAMS)
                        .objArray("current_artifacts", "Artifacts to validate. " + ARTIFACTS),
                tool("cameo_generate_review_packet", FAMILY).readOnly().timeout(300)
                        .desc("Generate a compact methodology review packet without mutating the model: an evidence "
                                + "bundle (guidance, plan, conformance, semantic validations, live artifacts, diagram "
                                + "snapshot metadata) plus Markdown review-packet text with findings and assumptions.")
                        .str("pack_id", PACK_ID, REQUIRED)
                        .str("recipe_id", RECIPE_ID, REQUIRED)
                        .obj("recipe_parameters", RECIPE_PARAMS)
                        .objArray("current_artifacts", "Artifacts to include. " + ARTIFACTS)
                        .strArray("assumptions", "Assumptions to include in the packet.")
                        .strArray("notes", "Free-form notes to include in the packet."),

                // ---------------------------------------------------------- methodology workflows
                tool("cameo_compare_expected_artifact_list", FAMILY).readOnly().noProject()
                        .desc("Compare discovered/current artifacts against an expected methodology artifact list. Dry "
                                + "run: returns per-artifact status (match, missing, kind_mismatch, name_mismatch, "
                                + "parent_mismatch, stereotype_mismatch, property_mismatch, unexpected), suggested actions, a "
                                + "preview patchPlan, missingArtifactKeys, unexpectedArtifactKeys and ready.")
                        .objArray("expected_artifacts", "Expected artifacts. " + CMP_ARTIFACTS, REQUIRED)
                        .objArray("current_artifacts", "Current artifacts. " + CMP_ARTIFACTS),
                tool("cameo_validate_methodology_package", FAMILY).readOnly().timeout(300)
                        .desc("Validate a whole pack or one recipe scope against the methodology definition (dry run). "
                                + "Without current_artifacts the live model is searched under root_package_id. Expected "
                                + "artifacts default to the recipes' required artifacts (names that depend on recipe "
                                + "parameters are not compared); pass expected_artifacts to use your own list. Returns "
                                + "workflowGuidance, expectedArtifacts, artifactComparison, recommendedActions, ready and "
                                + "currentArtifacts.")
                        .str("pack_id", PACK_ID, REQUIRED)
                        .str("recipe_id", "Recipe scope; omit to validate the whole pack.")
                        .str("root_package_id", "Package searched for live artifacts when current_artifacts is omitted.")
                        .objArray("current_artifacts", "Current artifacts; omit to discover them. " + CMP_ARTIFACTS)
                        .objArray("expected_artifacts", "Custom expected artifact list. " + CMP_ARTIFACTS),
                tool("cameo_export_required_diagrams", FAMILY).sideEffect().openWorld().timeout(600)
                        .desc("Plan or execute export of the diagrams a methodology pack/recipe requires. Omit output_dir "
                                + "for a dry-run export queue (exportItems with status ready/blocked, outputFile names, "
                                + "blockedDiagramKeys). With output_dir, ready diagrams are rendered through "
                                + "cameo_get_diagram_image and written there (directory is created); returns exports, "
                                + "exportedCount and blockedCount.")
                        .str("pack_id", PACK_ID, REQUIRED)
                        .str("recipe_id", "Recipe scope; omit for the whole pack.")
                        .str("root_package_id", "Package searched for live artifacts when current_artifacts is omitted.")
                        .objArray("current_artifacts", "Current artifacts; omit to discover them. " + CMP_ARTIFACTS)
                        .objArray("expected_artifacts", "Custom expected artifact list. " + CMP_ARTIFACTS)
                        .enumStrDefault("export_format", "Image format.", "png", "png", "jpeg", "jpg", "webp")
                        .str("output_dir", "Absolute directory to write images into; omit for a dry run."),
                tool("cameo_assemble_ppt_pdf", FAMILY).sideEffect().openWorld().timeout(600)
                        .desc("Plan or assemble a PowerPoint + PDF review package from the methodology-required diagrams. "
                                + "Omit output_dir for a dry-run presentationPlan (title slide, one slide per required "
                                + "diagram, appendix). With output_dir, ready diagrams are exported there and a .pptx "
                                + "(title slide plus one picture slide per diagram) and a .pdf (one page per diagram) are "
                                + "written; returns createdFiles and ready. Use export_format png or jpeg for assembly.")
                        .str("pack_id", PACK_ID, REQUIRED)
                        .str("recipe_id", "Recipe scope; omit for the whole pack.")
                        .str("root_package_id", "Package searched for live artifacts when current_artifacts is omitted.")
                        .objArray("current_artifacts", "Current artifacts; omit to discover them. " + CMP_ARTIFACTS)
                        .objArray("expected_artifacts", "Custom expected artifact list. " + CMP_ARTIFACTS)
                        .str("output_dir", "Absolute directory for images, PPTX and PDF; omit for a dry run.")
                        .str("title", "Deck title (default: the pack title).")
                        .str("pptx_name", "PPTX file name (default: <slugified title>.pptx).")
                        .str("pdf_name", "PDF file name (default: <slugified title>.pdf).")
                        .enumStrDefault("export_format", "Image format of the exported diagrams.", "png", "png", "jpeg", "jpg", "webp"));
    }
}
