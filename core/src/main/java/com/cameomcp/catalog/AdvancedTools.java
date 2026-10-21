package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.util.Arrays;
import java.util.List;

import static com.cameomcp.mcp.ToolSpec.REQUIRED;
import static com.cameomcp.mcp.ToolSpec.tool;

/**
 * Native validation, Report Wizard, requirements import/export, criteria expressions and the
 * probe-first optional products (Simulation, Teamwork, DataHub, profiles, variants, safety/cyber
 * extensions, typed diagrams). Optional products are detected before use; gated operations
 * preview first and refuse writes without explicit allow flags.
 */
public final class AdvancedTools {

    /** Family name used in docs and in cameo_list_tools filters. */
    public static final String FAMILY = "advanced";

    /**
     * Appended to every capability-probe description so agents know those tools are safe to call any time.
     */
    private static final String PROBE_NOTE = " Probe-first: reports class/plugin evidence (available, status, classProbe, "
            + "pluginDirectoriesFound, projectState); never changes anything.";

    /** Static methods only. */
    private AdvancedTools() {
    }

    /**
     * A read-only, project-independent capability probe for one optional Cameo feature.
     *
     * @param name tool name
     * @param what the first part of the description: which feature the probe checks
     * @return the probe's spec
     */
    private static ToolSpec caps(String name, String what) {
        return tool(name, FAMILY).noProject().readOnly().desc(what + PROBE_NOTE);
    }

    /**
     * The family's specs, in the order clients see them. Descriptions and parameter help are the
     * text agents read when choosing a tool, so they say what the tool returns and which tool to call next.
     *
     * @return the specs, a fixed-size list built on every call
     */
    public static List<ToolSpec> specs() {
        return Arrays.asList(
                // ------------------------------------------------------------ validation
                caps("cameo_get_validation_capabilities",
                        "Check whether Cameo's native validation API (ValidationHelper, ValidationRunData, "
                                + "RuleViolationResult) is usable, and list the validation tools. Call before "
                                + "cameo_run_native_validation."),
                tool("cameo_list_validation_suites", FAMILY).readOnly()
                        .desc("List validation suites and constraints usable with cameo_run_native_validation: knownSuites "
                                + "(e.g. 'UML Standard Profile::Validation Profile::Active Validation::UML Correctness', found by "
                                + "qualified name) plus heuristic candidateSuites (packages) and candidateConstraints from the "
                                + "primary model whose name or owner mentions validation/constraint/suite/SysML/requirement "
                                + "(max 250 each). Use suiteId or constraintId values from the result."),
                tool("cameo_run_native_validation", FAMILY).readOnly().timeout(600)
                        .desc("Run Cameo native validation for a suite package or explicit constraints and return findings "
                                + "(message, rule, annotation with severity, target and parent elements). Give suite_id or "
                                + "constraint_ids; with neither, the built-in 'UML Correctness' suite runs. Scope: "
                                + "scope_element_ids limits the run to those elements (whole_project defaults to false then, true "
                                + "otherwise). The full result is cached (last 50 runs): the response carries runId, findingCount, "
                                + "countsBySeverity and the first max_findings findings; page the rest with "
                                + "cameo_get_validation_result. Large projects can take minutes; scope runs when possible.")
                        .str("suite_id", "Validation suite Package id from cameo_list_validation_suites.")
                        .str("suite_qualified_name", "Alternative to suite_id: suite qualified name, e.g. "
                                + "'UML Standard Profile::Validation Profile::Active Validation::UML Correctness'.")
                        .strArray("constraint_ids", "Explicit Constraint ids to evaluate instead of a suite.")
                        .strArray("scope_element_ids", "Elements to validate (default: the primary model).")
                        .bool("whole_project", "Validate the whole project. Default: true unless scope_element_ids is given.")
                        .bool("recursive", "Include owned elements of the scope recursively.", true)
                        .bool("exclude_read_only", "Skip read-only elements (used projects/libraries).", true)
                        .enumStr("minimum_severity", "Lowest severity to report (default any).", false,
                                "any", "debug", "info", "warning", "error", "fatal")
                        .bool("open_native_window", "Also show the results in Cameo's Validation Results window.", false)
                        .str("name", "Run name shown in Cameo.")
                        .integer("max_findings", "Findings returned inline (the rest stay cached).", 500, 0, 5000),
                tool("cameo_get_validation_result", FAMILY).noProject().readOnly()
                        .desc("Fetch a cached native validation run by run_id (from cameo_run_native_validation or "
                                + "cameo_run_validation), paging findings with offset/limit (hasMore/nextOffset). Only the last "
                                + "50 runs since Cameo started are kept; NOT_FOUND otherwise.")
                        .str("run_id", "runId returned by a validation run.", REQUIRED)
                        .integer("offset", "First finding to return.", 0)
                        .integer("limit", "Findings to return.", 500, 0, 5000),
                tool("cameo_run_validation", FAMILY).readOnly().timeout(620)
                        .desc("Bounded native validation with a time limit: like cameo_run_native_validation but with "
                                + "scope_mode ('project' = whole project, 'elements' = only scope_element_ids; inferred from "
                                + "scope_element_ids when omitted), min_severity and timeout_ms. On timeout a TIMEOUT error is "
                                + "returned (Cameo may still finish in the background). Results are cached for "
                                + "cameo_get_validation_result.")
                        .str("suite_id", "Validation suite Package id (default: UML Correctness).")
                        .strArray("constraint_ids", "Explicit Constraint ids instead of a suite.")
                        .enumStr("scope_mode", "project or elements (default project, or elements when scope_element_ids is given).",
                                false, "project", "elements")
                        .strArray("scope_element_ids", "Elements to validate when scope_mode=elements.")
                        .enumStr("min_severity", "Lowest severity to report (default any).", false,
                                "any", "debug", "info", "warning", "error", "fatal")
                        .integer("timeout_ms", "Time limit in milliseconds (1000-600000).", 30000)
                        .integer("max_findings", "Findings returned inline (the rest stay cached).", 500, 0, 5000),

                // ------------------------------------------------------------ Report Wizard
                caps("cameo_get_report_capabilities",
                        "Check whether the Report Wizard plugin API (TemplateHelper, GenerateTask) is available; lists known "
                                + "output formats (docx, pdf, html, xlsx, pptx)."),
                tool("cameo_list_report_templates", FAMILY).noProject().readOnly()
                        .desc("List Report Wizard templates (index/id, name, category, description, path, templateFileName, "
                                + "reportNames). Pass the id (index) or name as template_id/template_name to "
                                + "cameo_generate_report_preview and cameo_generate_report."),
                tool("cameo_generate_report_preview", FAMILY).noProject().readOnly()
                        .desc("Dry-run a Report Wizard generation: echoes the request, resolves the template, checks the output "
                                + "path (absolute, existing file) and reports availability. Writes nothing. Call before "
                                + "cameo_generate_report.")
                        .str("template_id", "Template index from cameo_list_report_templates (or a name/path).")
                        .str("template_name", "Template name, e.g. 'Model Report'.")
                        .str("report_name", "Report configuration inside the template (default: its default report).")
                        .str("output_path", "Absolute output file path, e.g. C:/reports/model.docx.")
                        .str("output_format", "docx, pdf, html, xlsx, pptx... (default: from the file extension).")
                        .strArray("scope_element_ids", "Packages/elements to report on (default: primary model).")
                        .bool("recursive", "Include nested packages (default true).")
                        .obj("parameters", "Extra template options; may also carry any of the keys above in camelCase "
                                + "(outputPath, templateName, showAuxiliary, ...)."),
                tool("cameo_generate_report", FAMILY).sideEffect().openWorld().timeout(620)
                        .desc("Generate a Report Wizard document from a native template into output_path. Refused unless "
                                + "allow_write=true (it writes a file; an existing file at output_path is replaced). Runs inline "
                                + "on Cameo's UI thread and returns jobId, outputPath, outputBytes. Preview first with "
                                + "cameo_generate_report_preview.")
                        .str("template_id", "Template index from cameo_list_report_templates (or a name/path).")
                        .str("template_name", "Template name.")
                        .str("report_name", "Report configuration inside the template.")
                        .str("output_path", "Absolute output file path (required).")
                        .str("output_format", "Output format (default: from the file extension, else docx).")
                        .strArray("scope_element_ids", "Packages/elements to report on (default: primary model).")
                        .bool("recursive", "Include nested packages (default true).")
                        .bool("display_in_viewer", "Open the result in the system viewer (default false).")
                        .obj("parameters", "Extra template options (camelCase keys).")
                        .bool("allow_write", "Must be true to write the report file.", false)
                        .integer("timeout_seconds", "Time limit (10-600).", 120),
                tool("cameo_get_report_job", FAMILY).noProject().readOnly()
                        .desc("Look up a completed report generation by jobId (generation runs inline, so jobs are only "
                                + "'completed'; the last 50 are kept). NOT_FOUND for unknown ids.")
                        .str("job_id", "jobId returned by cameo_generate_report.", REQUIRED),

                // ------------------------------------------------------------ requirements / import-export
                caps("cameo_get_requirements_capabilities",
                        "Check whether native Requirements/ReqIF APIs (ReqIFUtils, ReqIFMappingManager) and the requirements "
                                + "plugin folder are present (feature probe: available, installed, classesFound/Missing, warnings)."),
                caps("cameo_get_import_export_capabilities",
                        "Describe requirements import/export support: CSV/JSON is handled by this server (csvJsonBridgeOwned), "
                                + "native ReqIF is probed only (nativeReqifAvailable) and its apply stays gated."),
                tool("cameo_export_requirements", FAMILY).sideEffect()
                        .desc("Export requirement-like elements (SysML «requirement» or any element whose type/stereotype "
                                + "name contains 'requirement') under the given roots, depth-first, up to limit. format=json "
                                + "returns full element JSON (name, documentation, taggedValues incl. Id/Text); format=csv also "
                                + "returns csvText with columns id,name,requirementId,text,documentation,qualifiedName,ownerId,"
                                + "stereotypes,type; format=reqif is not written natively (JSON is returned). With output_path the "
                                + "export is also written to that absolute file; an existing file is never replaced unless "
                                + "overwrite=true.")
                        .strArray("scope_ids", "Root elements/packages to export from (default: primary model).")
                        .str("root_id", "Single root element id (combined with scope_ids).")
                        .str("package_id", "Alias of root_id.")
                        .enumStrDefault("format", "Export format.", "json", "json", "csv", "reqif")
                        .str("output_path", "Optional absolute file path to write the export to.")
                        .bool("overwrite", "Replace an existing file at output_path.", false)
                        .integer("limit", "Maximum requirements (1-5000).", 1000, 1, 5000),
                tool("cameo_preview_requirements_import", FAMILY).readOnly()
                        .desc("Preview a requirements import without writing: parses rows from requirements/source_rows "
                                + "(arrays of objects), csv_text (header row; comma, semicolon or tab) and/or source_path (absolute "
                                + ".csv/.json file, max 20 MB), normalizes each row (name <- name/title or the id; externalId <- "
                                + "externalId/requirementId/key/id; text <- text/description), validates (missing names, "
                                + "duplicates) and, with target_package_id, marks rows that already exist there. Returns a "
                                + "patchPlan to pass to cameo_apply_requirements_import.")
                        .str("source_path", "Absolute path of a .csv or .json file to import.")
                        .objArray("source_rows", "Requirement rows as objects, e.g. [{\"id\":\"R1\",\"name\":\"Speed\",\"text\":\"...\"}].")
                        .objArray("requirements", "Same as source_rows.")
                        .str("csv_text", "CSV text with a header row, e.g. 'id,name,text\\nR1,Speed,The car shall...'.")
                        .str("target_package_id", "Package the requirements would be created in."),
                tool("cameo_apply_requirements_import", FAMILY).write()
                        .desc("Create requirements in target_package_id from reviewed rows. Safety gates: dry_run defaults to "
                                + "true (returns the preview only); writing needs dry_run=false AND allow_write=true, otherwise "
                                + "PRECONDITION_FAILED. format=reqif is refused (native ReqIF apply is gated). Each row becomes a "
                                + "Class with the SysML Requirement stereotype (when SysML is loaded), Id/Text tags from "
                                + "externalId/text, and documentation; one undoable step. Rows come from requirements, rows, "
                                + "csv_text, or patch_plan.rows from cameo_preview_requirements_import. Every row is created, even "
                                + "ones that already exist: drop those first. Use a disposable package for first runs.")
                        .obj("patch_plan", "patchPlan from cameo_preview_requirements_import (rows and targetPackageId).")
                        .str("target_package_id", "Package to create requirements in (default: patch_plan.targetPackageId).")
                        .objArray("requirements", "Requirement rows as objects.")
                        .objArray("rows", "Same as requirements.")
                        .str("csv_text", "CSV text with a header row.")
                        .enumStrDefault("format", "Source format label; reqif is refused.", "json", "json", "csv", "reqif")
                        .bool("dry_run", "Preview only (no write).", true)
                        .bool("allow_write", "Must be true (with dry_run=false) to create elements.", false),
                tool("cameo_export_requirements_preview", FAMILY).readOnly()
                        .desc("Preview a requirements export without writing anything: probe-only contract (dryRun=true, "
                                + "executed=false, capabilityProbe) plus requirementCount, five sampleRows, the CSV columns and an "
                                + "outputPathCheck. Use cameo_export_requirements to export.")
                        .strArray("scope_ids", "Roots to export from (default: primary model).")
                        .str("root_id", "Single root element id (combined with scope_ids).")
                        .str("package_id", "Alias of root_id.")
                        .enumStrDefault("format", "Planned export format.", "csv", "csv", "json", "reqif")
                        .str("output_path", "Planned absolute output path (checked, not written)."),
                tool("cameo_import_requirements_preview", FAMILY).readOnly()
                        .desc("Probe-only requirements import preview (dryRun=true, executed=false) with the same parsing, "
                                + "validation and target comparison as cameo_preview_requirements_import; writes nothing.")
                        .str("source_path", "Absolute path of a .csv or .json file.")
                        .objArray("source_rows", "Requirement rows as objects.")
                        .str("target_package_id", "Package the requirements would be created in."),

                // ------------------------------------------------------------ simulation
                caps("cameo_get_simulation_capabilities",
                        "Check whether Cameo Simulation Toolkit runtime classes (SimulationManager, SimulationResult, "
                                + "SimulationSession) are present. Execution is not enabled through this server (executionEnabled=false)."),
                tool("cameo_list_simulation_configurations", FAMILY).readOnly()
                        .desc("Heuristically list simulation configuration candidates in the primary model (elements whose "
                                + "type, name or stereotype mentions simulation/config/parametric; max 500). Returns an "
                                + "'unsupported' payload when Simulation Toolkit is missing."),
                tool("cameo_run_simulation_preview", FAMILY).noProject().readOnly()
                        .desc("Record a simulation run request without running anything; checks configuration_id exists. "
                                + "Execution itself stays gated (requiresAllowExecute, requiresRuntimeClasses).")
                        .str("configuration_id", "SimulationConfig (or executable element) id.")
                        .integer("timeout_ms", "Intended time limit.", 30000),
                tool("cameo_run_simulation", FAMILY).sideEffect()
                        .desc("Guarded simulation execution endpoint. Always refused with UNSUPPORTED in this release (even "
                                + "with allow_execute=true): execution stays disabled until validated against a disposable "
                                + "executable model. Run simulations from the Cameo UI instead.")
                        .str("configuration_id", "SimulationConfig id.")
                        .str("target_id", "Element to execute.")
                        .integer("timeout_ms", "Intended time limit.", 30000)
                        .bool("allow_execute", "Explicit execution consent (still refused in this release).", false)
                        .bool("async_run", "Request asynchronous execution.", false),
                tool("cameo_get_simulation_result", FAMILY).noProject().readOnly()
                        .desc("Fetch a simulation run result. No runs are persisted while execution is disabled, so this "
                                + "returns NOT_FOUND.")
                        .str("run_id", "Simulation run id.", REQUIRED),
                tool("cameo_terminate_simulation", FAMILY).noProject().sideEffect()
                        .desc("Terminate an active simulation run. No job store exists while execution is disabled, so this "
                                + "returns NOT_FOUND.")
                        .str("run_id", "Simulation run id.", REQUIRED),

                // ------------------------------------------------------------ teamwork
                caps("cameo_get_teamwork_capabilities",
                        "Check Teamwork Cloud / Magic Collaboration Studio API availability (EsiUtils). Commit/update writes "
                                + "are not supported (writeSupported=false)."),
                tool("cameo_get_teamwork_project", FAMILY).readOnly()
                        .desc("Describe the open project's storage: projectMode (teamwork or local), its ProjectDescriptor and "
                                + "ESI diagnostics (logged user, server info) when available. Read-only."),
                tool("cameo_preview_teamwork_commit", FAMILY).noProject().readOnly()
                        .desc("Record a Teamwork commit request without committing (writeEnabled=false, no approval token). "
                                + "Commit from the Cameo UI.")
                        .str("message", "Intended commit message."),
                tool("cameo_preview_teamwork_update", FAMILY).noProject().readOnly()
                        .desc("Record a Teamwork update request without updating the local project (writeEnabled=false).")
                        .str("message", "Optional note."),
                tool("cameo_list_teamwork_descriptors", FAMILY).readOnly().openWorld()
                        .desc("List remote project descriptors via EsiUtils.getRemoteProjectDescriptors when Cameo is logged "
                                + "into a Teamwork Cloud server; status auth-or-server-unavailable otherwise."),
                tool("cameo_list_teamwork_branches", FAMILY).readOnly()
                        .desc("Teamwork branch readback probe: reports projectMode and status (probe-required for server "
                                + "projects, local-project otherwise). Typed branch lists are not available yet."),
                tool("cameo_get_teamwork_history", FAMILY).readOnly()
                        .desc("Teamwork history readback probe (projectMode/status only; typed history is not available yet)."),
                tool("cameo_get_teamwork_locks", FAMILY).readOnly()
                        .desc("Teamwork lock readback probe (projectMode/status only; typed lock lists are not available yet)."),

                // ------------------------------------------------------------ DataHub
                caps("cameo_get_datahub_capabilities",
                        "Check for DataHub / DOORS / ENOVIA integration (DataHubPlugin classes or plugin folders). Source "
                                + "readback and sync writes are not supported."),
                tool("cameo_list_datahub_sources", FAMILY).noProject().readOnly()
                        .desc("Diagnostic DataHub source inventory: returns the capability probe and an empty sources list "
                                + "(configured sources are not readable through the API yet)."),
                tool("cameo_preview_datahub_sync", FAMILY).noProject().readOnly()
                        .desc("Record a DataHub synchronization request without syncing anything; checks scope_id exists.")
                        .str("source_id", "DataHub source identifier.")
                        .str("scope_id", "Model element to synchronize."),

                // ------------------------------------------------------------ criteria
                caps("cameo_get_criteria_capabilities",
                        "Describe criteria expression support: bridge-owned templates verified by UI snapshot diff, native "
                                + "expression classes, valid directions and relationship kinds. Criteria writes are gated."),
                tool("cameo_list_criteria_templates", FAMILY).noProject().readOnly()
                        .desc("List criteria templates (id such as satisfy.both or refine.targetToSource, relationshipKind, "
                                + "direction, targetKinds, verifiedWithUiDiff). Filter with target = relationMap, matrix or table.")
                        .str("target", "Target kind filter: relationMap, matrix, table."),
                tool("cameo_build_criteria_expression", FAMILY).noProject().readOnly()
                        .desc("Build a criteria expression from relationship_kind + direction (or a template_id), normalizing "
                                + "spelling (e.g. 'derive' -> DeriveReqt, 'forward' -> sourceToTarget) and matching it to a "
                                + "template; verifiedWithUiDiff is true only for UI-verified templates. Writes nothing.")
                        .str("relationship_kind", "Relationship kind, e.g. Satisfy, Refine, DeriveReqt, Allocate, Verify, Trace, Dependency.")
                        .enumStrDefault("direction", "Traversal direction.", "both", "both", "sourceToTarget", "targetToSource")
                        .str("target", "Where it will be used: relationMap, matrix or table.")
                        .str("template_id", "Start from a template id from cameo_list_criteria_templates."),
                tool("cameo_parse_criteria_expression", FAMILY).noProject().readOnly()
                        .desc("Parse a criteria expression into normalized form with diagnostics. Accepts an object "
                                + "({relationshipKind, direction} or {templateId}, or a build result), a JSON string, a template id "
                                + "('satisfy.both') or text ('Satisfy:sourceToTarget', 'Refine both'). Returns parsed, "
                                + "criteriaExpression, matchedTemplateId, verifiedWithUiDiff and issues.")
                        .any("expression", "Expression object or string.", REQUIRED),
                tool("cameo_apply_criteria_template", FAMILY).write()
                        .desc("Gated: applying criteria to a matrix/table/map is refused (UNSUPPORTED) until UI-created native "
                                + "expressions are captured and replayed by snapshot diff. For Relation Maps use "
                                + "cameo_set_relation_map_criteria.")
                        .str("target_id", "Matrix, table or relation map id.", REQUIRED)
                        .str("template_id", "Template id.")
                        .obj("expression", "Expression object.")
                        .bool("refresh", "Refresh the target afterwards.", false),
                tool("cameo_capture_criteria_template_from_diff", FAMILY).readOnly()
                        .desc("Record a criteria template capture from before/after snapshots taken around a UI edit "
                                + "(cameo_create_snapshot before, edit criteria in Cameo, snapshot after). Attaches the snapshot "
                                + "diff as diffEvidence when snapshot tools are available; the template stays unverified.")
                        .str("before_snapshot_id", "Snapshot taken before the UI edit.", REQUIRED)
                        .str("after_snapshot_id", "Snapshot taken after the UI edit.", REQUIRED)
                        .str("target_kind", "relationMap, matrix or table."),

                // ------------------------------------------------------------ profiles
                caps("cameo_get_profile_capabilities",
                        "Describe profile/DSL authoring support: summary read is supported, typed profile writes are "
                                + "preview-only (use cameo_apply_profile / cameo_apply_stereotype / cameo_set_tagged_values for writes)."),
                tool("cameo_export_profile_summary", FAMILY).readOnly()
                        .desc("List Profile and Stereotype elements owned by the primary model (reference form: id, name, "
                                + "qualifiedName, owner). Profiles from used projects (SysML, UAF...) are not included."),
                tool("cameo_preview_profile_operation", FAMILY).noProject().readOnly()
                        .desc("Record a profile operation request without writing. operation: create-profile, "
                                + "create-stereotype, create-tag, apply-profile or set-tags; payload is echoed as the request.")
                        .enumStr("operation", "Profile operation.", REQUIRED,
                                "create-profile", "create-stereotype", "create-tag", "apply-profile", "set-tags")
                        .obj("payload", "Operation details, e.g. {\"name\":\"MyProfile\",\"ownerId\":\"...\"}."),

                // ------------------------------------------------------------ variants
                caps("cameo_get_variant_capabilities",
                        "Check for native variability / product-line plugins; bridge-owned preview analysis is always "
                                + "available, native writes are not."),
                tool("cameo_analyze_variants_preview", FAMILY).noProject().readOnly()
                        .desc("Preview variant/product-line analysis without writes: resolves configuration_ids and scope_ids "
                                + "(missing ids become warnings) and echoes the scope as includedElements. No variant rules are "
                                + "evaluated natively.")
                        .strArray("configuration_ids", "Variant configuration element ids.")
                        .strArray("scope_ids", "Elements in scope."),
                tool("cameo_install_variant_pattern_preview", FAMILY).noProject().readOnly()
                        .desc("Record a request to install variant stereotypes/patterns; nothing is installed.")
                        .obj("payload", "Pattern details."),
                tool("cameo_export_variant_configuration", FAMILY).noProject().readOnly()
                        .desc("Record a variant configuration export request (evidence payload preview); nothing is written "
                                + "and no model content is hidden or deleted.")
                        .obj("payload", "Configuration details."),

                // ------------------------------------------------------------ safety / cyber extensions
                caps("cameo_get_extension_capabilities",
                        "Check for safety/reliability/cyber/security plugins (folder names). Model scans are read-only; "
                                + "pattern writes and compliance claims are not supported."),
                tool("cameo_scan_extensions", FAMILY).readOnly()
                        .desc("Read-only evidence scan for safety/cyber modeling: profiles, stereotypes and elements (max 500) "
                                + "whose type or name contains the target terms (default safety, cyber, security, risk, hazard, "
                                + "failure, fmea, control, threat, classification). Evidence only, never a compliance claim.")
                        .strArray("targets", "Terms to match instead of the defaults, e.g. [\"hazard\",\"mitigation\"].")
                        .str("scope_id", "Element/package to scan (default: primary model)."),
                tool("cameo_list_extension_profiles", FAMILY).readOnly()
                        .desc("List profiles and stereotypes in the primary model whose names match the default safety/cyber terms."),
                tool("cameo_install_extension_pattern_preview", FAMILY).noProject().readOnly()
                        .desc("Record a request to install a safety/cyber extension pattern; nothing is installed.")
                        .obj("payload", "Pattern details."),
                tool("cameo_refuse_compliance_claim", FAMILY).noProject().readOnly()
                        .desc("Return the explicit refusal contract (refused=true, code COMPLIANCE_CLAIM_REFUSED) for safety, "
                                + "cyber, regulatory or certification compliance claims: this server reports evidence and gaps "
                                + "only. Use when asked to certify or declare compliance.")
                        .str("claim_type", "Claim requested, e.g. ISO 26262, DO-178C, IEC 62443.", REQUIRED)
                        .strArray("evidence_ids", "Evidence element ids offered for the claim."),

                // ------------------------------------------------------------ typed diagrams
                caps("cameo_get_typed_diagram_capabilities",
                        "Describe typed diagram support: native inspection plus diagram-type-gated write previews "
                                + "(sequence-message, state-transition, parametric-binding, legend-apply)."),
                tool("cameo_list_typed_diagrams", FAMILY).readOnly()
                        .desc("List all diagrams with id, name and diagramType (e.g. 'SysML Block Definition Diagram'), plus "
                                + "countsByType. Optional diagram_type substring filter.")
                        .str("diagram_type", "Case-insensitive substring of the diagram type, e.g. 'Sequence'."),
                tool("cameo_inspect_typed_diagram", FAMILY).readOnly()
                        .desc("Inspect one diagram's presentation tree: every shape/path with presentationId, shapeType, "
                                + "parentPresentationId, element reference, bounds and childCount, plus countsByShapeType "
                                + "(first limit presentations).")
                        .str("diagram_id", "Diagram id from cameo_list_typed_diagrams.", REQUIRED)
                        .integer("limit", "Maximum presentations returned.", 500, 1, 5000),
                tool("cameo_preview_typed_diagram_operation", FAMILY).noProject().readOnly()
                        .desc("Record a typed diagram write request without drawing anything. operation: sequence-message, "
                                + "state-transition, parametric-binding or legend-apply; payload describes it and is echoed.")
                        .enumStr("operation", "Typed diagram operation.", REQUIRED,
                                "sequence-message", "state-transition", "parametric-binding", "legend-apply")
                        .obj("payload", "Operation details, e.g. {\"diagramId\":\"...\",\"sourceId\":\"...\",\"targetId\":\"...\"}.", REQUIRED));
    }
}
