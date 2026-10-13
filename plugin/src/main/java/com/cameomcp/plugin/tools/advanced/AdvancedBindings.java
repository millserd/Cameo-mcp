package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.tools.Bind;
import com.cameomcp.plugin.tools.GsonTool;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Map;

/** Binds the advanced family: validation, reports, requirements, criteria and probe-first optional products. */
public final class AdvancedBindings {

    private AdvancedBindings() {
    }

    /** Bind with snake_case aliases for camelCase argument names (Args accepts both; Gson readers do not). */
    private static void gson(ToolRegistry r, String name, GsonTool tool) {
        Bind.gson(r, name, p -> tool.call(snakeAliases(p)));
    }

    /*
     * Adds a snake_case copy of every camelCase key, mutating and returning p (null becomes {}).
     * Unlike Bind's own aliasing this splits on lower/digit-to-upper boundaries only, so "elementID"
     * becomes "element_id", and it also fills a snake key the client sent as explicit JSON null.
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

    /** Registers every advanced-family tool name against its service method. All go through {@link #gson}. */
    public static void bind(ToolRegistry r) {
        // validation
        gson(r, "cameo_get_validation_capabilities", p -> ValidationService.capabilities());
        gson(r, "cameo_list_validation_suites", p -> ValidationService.suites());
        gson(r, "cameo_run_native_validation", ValidationService::runNative);
        gson(r, "cameo_get_validation_result", ValidationService::result);
        gson(r, "cameo_run_validation", ValidationService::runBounded);

        // Report Wizard
        gson(r, "cameo_get_report_capabilities", p -> ReportService.capabilities());
        gson(r, "cameo_list_report_templates", p -> ReportService.templates());
        gson(r, "cameo_generate_report_preview", ReportService::preview);
        gson(r, "cameo_generate_report", ReportService::generate);
        gson(r, "cameo_get_report_job", ReportService::job);

        // requirements import/export
        gson(r, "cameo_get_requirements_capabilities", p -> RequirementsService.requirementsCapabilities());
        gson(r, "cameo_get_import_export_capabilities", p -> RequirementsService.capabilities());
        gson(r, "cameo_export_requirements", RequirementsService::exportRequirements);
        gson(r, "cameo_preview_requirements_import", p -> RequirementsService.importPreview(p,
                "Requirements import preview accepted. No model write was performed."));
        gson(r, "cameo_apply_requirements_import", RequirementsService::apply);
        gson(r, "cameo_export_requirements_preview", RequirementsService::exportRequirementsPreview);
        gson(r, "cameo_import_requirements_preview", RequirementsService::importRequirementsPreview);

        // simulation
        gson(r, "cameo_get_simulation_capabilities", p -> SimulationService.capabilities());
        gson(r, "cameo_list_simulation_configurations", p -> SimulationService.configurations());
        gson(r, "cameo_run_simulation_preview", SimulationService::preview);
        gson(r, "cameo_run_simulation", SimulationService::run);
        gson(r, "cameo_get_simulation_result", SimulationService::result);
        gson(r, "cameo_terminate_simulation", SimulationService::terminate);

        // teamwork
        gson(r, "cameo_get_teamwork_capabilities", p -> TeamworkService.capabilities());
        gson(r, "cameo_get_teamwork_project", p -> TeamworkService.project());
        // Teamwork commit/update are preview-only: the second argument just labels which operation is previewed.
        gson(r, "cameo_preview_teamwork_commit", p -> TeamworkService.preview(p, "commit"));
        gson(r, "cameo_preview_teamwork_update", p -> TeamworkService.preview(p, "update"));
        gson(r, "cameo_list_teamwork_descriptors", p -> TeamworkService.descriptors());
        // Branches, history and locks have no typed readback yet; placeholder() reports project mode and a probe-required status.
        gson(r, "cameo_list_teamwork_branches", p -> TeamworkService.placeholder("branches"));
        gson(r, "cameo_get_teamwork_history", p -> TeamworkService.placeholder("history"));
        gson(r, "cameo_get_teamwork_locks", p -> TeamworkService.placeholder("locks"));

        // DataHub
        gson(r, "cameo_get_datahub_capabilities", p -> ProductServices.dataHubCapabilities());
        gson(r, "cameo_list_datahub_sources", p -> ProductServices.dataHubSources());
        gson(r, "cameo_preview_datahub_sync", ProductServices::dataHubSyncPreview);

        // criteria
        gson(r, "cameo_get_criteria_capabilities", p -> CriteriaService.capabilities());
        gson(r, "cameo_list_criteria_templates", CriteriaService::templates);
        gson(r, "cameo_build_criteria_expression", CriteriaService::build);
        gson(r, "cameo_parse_criteria_expression", CriteriaService::parse);
        gson(r, "cameo_apply_criteria_template", CriteriaService::apply);
        gson(r, "cameo_capture_criteria_template_from_diff", CriteriaService::capture);

        // profiles
        gson(r, "cameo_get_profile_capabilities", p -> ProductServices.profileCapabilities());
        gson(r, "cameo_export_profile_summary", p -> ProductServices.profileSummary());
        gson(r, "cameo_preview_profile_operation", ProductServices::profilePreview);

        // variants
        gson(r, "cameo_get_variant_capabilities", p -> ProductServices.variantCapabilities());
        gson(r, "cameo_analyze_variants_preview", ProductServices::analyzeVariants);
        gson(r, "cameo_install_variant_pattern_preview", p -> ProductServices.variantPreview(p, "install-pattern"));
        gson(r, "cameo_export_variant_configuration", p -> ProductServices.variantPreview(p, "export-configuration"));

        // safety / cyber extensions
        gson(r, "cameo_get_extension_capabilities", p -> ProductServices.extensionCapabilities());
        gson(r, "cameo_scan_extensions", ProductServices::scanExtensions);
        gson(r, "cameo_list_extension_profiles", p -> ProductServices.extensionProfiles());
        gson(r, "cameo_install_extension_pattern_preview", ProductServices::extensionInstallPreview);
        gson(r, "cameo_refuse_compliance_claim", ProductServices::refuseComplianceClaim);

        // typed diagrams
        gson(r, "cameo_get_typed_diagram_capabilities", p -> TypedDiagramService.capabilities());
        gson(r, "cameo_list_typed_diagrams", TypedDiagramService::list);
        gson(r, "cameo_inspect_typed_diagram", TypedDiagramService::inspect);
        gson(r, "cameo_preview_typed_diagram_operation", TypedDiagramService::preview);
    }
}
