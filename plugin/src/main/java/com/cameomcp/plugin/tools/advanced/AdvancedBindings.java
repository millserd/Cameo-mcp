package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.tools.Bind;
import com.cameomcp.plugin.tools.TreeTool;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;

import java.util.ArrayList;
import java.util.Map;

/** Binds the advanced family: validation, reports, requirements, criteria and probe-first optional products. */
public final class AdvancedBindings {

    /** Not instantiable; all members are static. */
    private AdvancedBindings() {
    }

    /** Bind with snake_case aliases for camelCase argument names (Args accepts both; tree readers do not). */
    private static void tree(ToolRegistry r, String name, TreeTool tool) {
        Bind.tree(r, name, p -> tool.call(snakeAliases(p)));
    }

    /**
     * Adds a snake_case copy of every camelCase key, mutating and returning p (null becomes {}).
     * Unlike Bind's own aliasing this splits on lower/digit-to-upper boundaries only, so "elementID"
     * becomes "element_id", and it also fills a snake key the client sent as explicit JSON null.
     *
     * @param p the tool arguments, may be null
     * @return p with the added keys, or a new empty object for null
     */
    static JsonObject snakeAliases(JsonObject p) {
        if (p == null) {
            return new JsonObject();
        }
        for (Map.Entry<String, JsonElement> e : new ArrayList<>(p.entrySet())) {
            String key = e.getKey();
            if (!key.equals(key.toLowerCase(java.util.Locale.ROOT))) {
                String snake = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
                if (!p.has(snake) || p.get(snake).isJsonNull()) {
                    p.add(snake, e.getValue());
                }
            }
        }
        return p;
    }

    /**
     * Registers every advanced-family tool name against its service method. All go through {@link #tree}.
     *
     * @param r the registry to add to; every name must already be declared in the catalog
     */
    public static void bind(ToolRegistry r) {
        // validation
        tree(r, "cameo_get_validation_capabilities", p -> ValidationService.capabilities());
        tree(r, "cameo_list_validation_suites", p -> ValidationService.suites());
        tree(r, "cameo_run_native_validation", ValidationService::runNative);
        tree(r, "cameo_get_validation_result", ValidationService::result);
        tree(r, "cameo_run_validation", ValidationService::runBounded);

        // Report Wizard
        tree(r, "cameo_get_report_capabilities", p -> ReportService.capabilities());
        tree(r, "cameo_list_report_templates", p -> ReportService.templates());
        tree(r, "cameo_generate_report_preview", ReportService::preview);
        tree(r, "cameo_generate_report", ReportService::generate);
        tree(r, "cameo_get_report_job", ReportService::job);

        // requirements import/export
        tree(r, "cameo_get_requirements_capabilities", p -> RequirementsService.requirementsCapabilities());
        tree(r, "cameo_get_import_export_capabilities", p -> RequirementsService.capabilities());
        tree(r, "cameo_export_requirements", RequirementsService::exportRequirements);
        tree(r, "cameo_preview_requirements_import", p -> RequirementsService.importPreview(p,
                "Requirements import preview accepted. No model write was performed."));
        tree(r, "cameo_apply_requirements_import", RequirementsService::apply);
        tree(r, "cameo_export_requirements_preview", RequirementsService::exportRequirementsPreview);
        tree(r, "cameo_import_requirements_preview", RequirementsService::importRequirementsPreview);

        // simulation
        tree(r, "cameo_get_simulation_capabilities", p -> SimulationService.capabilities());
        tree(r, "cameo_list_simulation_configurations", p -> SimulationService.configurations());
        tree(r, "cameo_run_simulation_preview", SimulationService::preview);
        tree(r, "cameo_run_simulation", SimulationService::run);
        tree(r, "cameo_get_simulation_result", SimulationService::result);
        tree(r, "cameo_terminate_simulation", SimulationService::terminate);

        // teamwork
        tree(r, "cameo_get_teamwork_capabilities", p -> TeamworkService.capabilities());
        tree(r, "cameo_get_teamwork_project", p -> TeamworkService.project());
        // Teamwork commit/update are preview-only: the second argument just labels which operation is previewed.
        tree(r, "cameo_preview_teamwork_commit", p -> TeamworkService.preview(p, "commit"));
        tree(r, "cameo_preview_teamwork_update", p -> TeamworkService.preview(p, "update"));
        tree(r, "cameo_list_teamwork_descriptors", p -> TeamworkService.descriptors());
        // Branches, history and locks have no typed readback yet; placeholder() reports project mode and a probe-required status.
        tree(r, "cameo_list_teamwork_branches", p -> TeamworkService.placeholder("branches"));
        tree(r, "cameo_get_teamwork_history", p -> TeamworkService.placeholder("history"));
        tree(r, "cameo_get_teamwork_locks", p -> TeamworkService.placeholder("locks"));

        // DataHub
        tree(r, "cameo_get_datahub_capabilities", p -> ProductServices.dataHubCapabilities());
        tree(r, "cameo_list_datahub_sources", p -> ProductServices.dataHubSources());
        tree(r, "cameo_preview_datahub_sync", ProductServices::dataHubSyncPreview);

        // criteria
        tree(r, "cameo_get_criteria_capabilities", p -> CriteriaService.capabilities());
        tree(r, "cameo_list_criteria_templates", CriteriaService::templates);
        tree(r, "cameo_build_criteria_expression", CriteriaService::build);
        tree(r, "cameo_parse_criteria_expression", CriteriaService::parse);
        tree(r, "cameo_apply_criteria_template", CriteriaService::apply);
        tree(r, "cameo_capture_criteria_template_from_diff", CriteriaService::capture);

        // profiles
        tree(r, "cameo_get_profile_capabilities", p -> ProductServices.profileCapabilities());
        tree(r, "cameo_export_profile_summary", p -> ProductServices.profileSummary());
        tree(r, "cameo_preview_profile_operation", ProductServices::profilePreview);

        // variants
        tree(r, "cameo_get_variant_capabilities", p -> ProductServices.variantCapabilities());
        tree(r, "cameo_analyze_variants_preview", ProductServices::analyzeVariants);
        tree(r, "cameo_install_variant_pattern_preview", p -> ProductServices.variantPreview(p, "install-pattern"));
        tree(r, "cameo_export_variant_configuration", p -> ProductServices.variantPreview(p, "export-configuration"));

        // safety / cyber extensions
        tree(r, "cameo_get_extension_capabilities", p -> ProductServices.extensionCapabilities());
        tree(r, "cameo_scan_extensions", ProductServices::scanExtensions);
        tree(r, "cameo_list_extension_profiles", p -> ProductServices.extensionProfiles());
        tree(r, "cameo_install_extension_pattern_preview", ProductServices::extensionInstallPreview);
        tree(r, "cameo_refuse_compliance_claim", ProductServices::refuseComplianceClaim);

        // typed diagrams
        tree(r, "cameo_get_typed_diagram_capabilities", p -> TypedDiagramService.capabilities());
        tree(r, "cameo_list_typed_diagrams", TypedDiagramService::list);
        tree(r, "cameo_inspect_typed_diagram", TypedDiagramService::inspect);
        tree(r, "cameo_preview_typed_diagram_operation", TypedDiagramService::preview);
    }
}
