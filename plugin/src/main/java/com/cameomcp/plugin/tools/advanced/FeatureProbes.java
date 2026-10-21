package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Probe-first contracts for optional CATIA/Cameo product surfaces (port of the reference
 * AdvancedCapabilityHandler). Never compiles against optional product APIs: it reports which
 * classes and plugin folders exist so agents can fail closed before native work is attempted.
 */
final class FeatureProbes {

    /** One optional product: its probe mode label, the classes that must load and plugin-folder name hints. */
    static final class Feature {
        /** Feature name, such as "validation" or "reports". */
        final String name;
        /** Probe mode label reported to agents, such as "native" or "optional-native". */
        final String mode;
        /** Classes that must all load for the feature to count as available. */
        final List<String> classNames;
        /** Plugin folder name fragments, one of which must exist; empty for features in core Cameo. */
        final List<String> pluginHints;

        /**
         * Creates a feature.
         *
         * @param name the feature name
         * @param mode the probe mode label
         * @param classNames the required classes
         * @param pluginHints the plugin folder hints
         */
        Feature(String name, String mode, List<String> classNames, List<String> pluginHints) {
            this.name = name;
            this.mode = mode;
            this.classNames = classNames;
            this.pluginHints = pluginHints;
        }
    }

    /**
     * The probe table. An empty pluginHints list means the feature ships with core Cameo (validation), so no
     * folder is required.
     */
    static final List<Feature> FEATURES = Collections.unmodifiableList(Arrays.asList(
            new Feature("validation", "native",
                    Arrays.asList("com.nomagic.magicdraw.validation.ValidationHelper",
                            "com.nomagic.magicdraw.validation.ValidationRunData",
                            "com.nomagic.magicdraw.validation.RuleViolationResult"),
                    Collections.emptyList()),
            new Feature("reports", "optional-native",
                    Arrays.asList("com.nomagic.magicdraw.magicreport.helper.TemplateHelper",
                            "com.nomagic.magicdraw.magicreport.GenerateTask"),
                    Collections.singletonList("com.nomagic.magicdraw.reportwizard")),
            new Feature("requirements", "optional-native",
                    Arrays.asList("com.nomagic.requirements.reqif.ReqIFUtils",
                            "com.nomagic.requirements.reqif.mapping.ReqIFMappingManager"),
                    Collections.singletonList("com.nomagic.requirements")),
            new Feature("simulation", "probe-only",
                    Arrays.asList("com.nomagic.magicdraw.simulation.SimulationManager",
                            "com.nomagic.magicdraw.simulation.execution.SimulationResult"),
                    Collections.singletonList("simulation")),
            new Feature("teamwork", "read-only-probe",
                    Collections.singletonList("com.nomagic.magicdraw.esi.EsiUtils"),
                    Arrays.asList("teamwork", "collaboration")),
            new Feature("datahub", "probe-only",
                    Collections.singletonList("com.nomagic.datahub.DataHubPlugin"),
                    Collections.singletonList("datahub")),
            new Feature("variants", "probe-only",
                    Collections.singletonList("com.nomagic.magicdraw.variants.VariantsPlugin"),
                    Arrays.asList("variant", "productline", "product-line")),
            new Feature("extensions", "read-only-probe",
                    Arrays.asList("com.nomagic.magicdraw.safety.SafetyPlugin",
                            "com.nomagic.magicdraw.cyber.CyberPlugin"),
                    Arrays.asList("safety", "cyber"))));

    /** Not instantiable; all members are static. */
    private FeatureProbes() {
    }

    /**
     * Looks up a {@link #FEATURES} entry by name.
     *
     * @param name the feature name
     * @return the feature
     * @throws IllegalArgumentException for an unknown name, which is a programming error, not a user error
     */
    static Feature feature(String name) {
        for (Feature f : FEATURES) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown feature " + name);
    }

    /**
     * Starts an answer for a feature; "path" names the MCP tool this answer belongs to.
     *
     * @param feature the feature
     * @param operation the tool name
     * @return {@code feature} and {@code path}
     */
    private static JsonObject base(Feature feature, String operation) {
        JsonObject response = new JsonObject();
        response.addProperty("feature", feature.name);
        response.addProperty("path", operation);
        return response;
    }

    /**
     * Capability answer for one feature: available only when every class loads and, if hints exist, a
     * matching plugin folder is present. Licensing cannot be checked without touching the product API, so it
     * is reported as unknown.
     *
     * @param featureName the feature name
     * @return the classes found and missing, the plugin folders found, and the availability verdict
     */
    static JsonObject capability(String featureName) {
        Feature feature = feature(featureName);
        JsonObject response = base(feature, "cameo_get_" + feature.name + "_capabilities");
        JsonArray classesFound = new JsonArray();
        JsonArray classesMissing = new JsonArray();
        for (String className : feature.classNames) {
            if (Support.classAvailable(className)) {
                classesFound.add(className);
            } else {
                classesMissing.add(className);
            }
        }
        JsonArray pluginDirs = Support.pluginDirectoryPaths(feature.pluginHints);
        boolean installed = feature.pluginHints.isEmpty() || pluginDirs.size() > 0;
        boolean classesAvailable = classesMissing.size() == 0;
        boolean available = installed && classesAvailable;
        response.addProperty("available", available);
        response.addProperty("installed", installed);
        response.addProperty("licensed", "unknown");
        response.addProperty("mode", available ? feature.mode : "unsupported");
        response.add("classesFound", classesFound);
        response.add("classesMissing", classesMissing);
        response.add("pluginDirectoriesFound", pluginDirs);
        JsonArray warnings = new JsonArray();
        if (!installed) {
            warnings.add("No matching plugin directory was found under the local CATIA/Cameo plugins folder.");
        }
        if (!classesAvailable) {
            warnings.add("One or more expected native API classes were not loadable from the plugin class loaders.");
        }
        if (available && !"validation".equals(feature.name)) {
            warnings.add("Capability probe is positive; typed native operations still require live validation before writes are enabled.");
        }
        response.add("warnings", warnings);
        response.addProperty("nextProbe", "cameo_execute_probe");
        return response;
    }

    /**
     * Guarded action placeholder: echoes the request, never executes.
     *
     * @param featureName the feature name
     * @param operation the tool name
     * @param request the tool arguments to echo
     * @return the echo, the capability probe, {@code dryRun: true} and {@code executed: false}
     */
    static JsonObject guardedAction(String featureName, String operation, JsonObject request) {
        Feature feature = feature(featureName);
        JsonObject response = base(feature, operation);
        response.add("requestEcho", request);
        response.add("capabilityProbe", capability(featureName));
        response.addProperty("dryRun", true);
        response.addProperty("executed", false);
        response.addProperty("message", "This operation is intentionally preview/probe-only until native live evidence promotes it.");
        return response;
    }
}
