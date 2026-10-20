package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.Finder;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Probe-first product families that never write: DataHub, profiles/DSL authoring, variants and
 * safety/cyber extensions (reference DataHubHandler, ProfileHandler, VariantHandler, ExtensionProbeHandler).
 */
final class ProductServices {

    // Bounds for the extension scan: max elements walked, and max element findings returned.
    private static final int SCAN_LIMIT = 250_000;
    private static final int ELEMENT_FINDING_LIMIT = 500;

    // Default name/type terms for the safety/cyber scan when the caller supplies no targets.
    static final List<String> EXTENSION_TERMS = Arrays.asList("safety", "cyber", "security", "risk", "hazard",
            "failure", "fmea", "control", "threat", "classification");

    /** Tool operation name -> reference operation label. */
    static final Map<String, String> PROFILE_OPERATIONS = new LinkedHashMap<>();

    static {
        PROFILE_OPERATIONS.put("create-profile", "profile-create");
        PROFILE_OPERATIONS.put("create-stereotype", "stereotype-create");
        PROFILE_OPERATIONS.put("create-tag", "tag-create");
        PROFILE_OPERATIONS.put("apply-profile", "profile-apply");
        PROFILE_OPERATIONS.put("set-tags", "tag-set");
    }

    private ProductServices() {
    }

    // ------------------------------------------------------------------ DataHub

    /*
     * cameo_get_datahub_capabilities: DataHub counts as present if either known plugin class loads or a
     * datahub/doors/enovia plugin folder exists. Source readback and sync are never claimed.
     */
    static JsonObject dataHubCapabilities() {
        JsonObject response = Support.baseCapabilities("datahub", "probe-first");
        JsonObject probe = Support.classProbe("com.nomagic.datahub.DataHubPlugin", "com.nomagic.magicdraw.datahub.DataHubPlugin");
        JsonArray dirs = Support.pluginDirectories("datahub", "doors", "enovia");
        boolean available = probe.get("classesFound").getAsJsonArray().size() > 0 || dirs.size() > 0;
        response.addProperty("available", available);
        response.addProperty("status", available ? "probe-required" : "missing-plugin");
        response.add("classProbe", probe);
        response.add("pluginDirectoriesFound", dirs);
        response.addProperty("sourceReadbackSupported", false);
        response.addProperty("syncWriteSupported", false);
        return response;
    }

    // cameo_list_datahub_sources: always an empty list; DataHub's source APIs are not wired in, so it points to the UI.
    static JsonObject dataHubSources() {
        JsonObject response = dataHubCapabilities();
        response.addProperty("sourceInventoryMode", "diagnostic");
        response.add("sources", new JsonArray());
        response.addProperty("message", "Configured DataHub source listing requires installed DataHub APIs; open "
                + "DataHub Explorer in Cameo to see sources.");
        return response;
    }

    // cameo_preview_datahub_sync: echoes the request and resolves scope_id (if a project is open) so bad IDs surface early.
    static JsonObject dataHubSyncPreview(JsonObject p) {
        JsonObject response = Support.previewAccepted("datahub", Support.request(p),
                "DataHub sync preview recorded. No external synchronization was performed.");
        response.add("capabilities", dataHubCapabilities());
        String scopeId = Gs.str(p, "scope_id");
        Project project = Support.currentProject();
        if (scopeId != null && project != null) {
            Element e = Support.element(project, scopeId);
            if (e == null) {
                response.addProperty("scopeProblem", "Scope element '" + scopeId + "' not found.");
            } else {
                response.add("scope", Support.ref(e));
            }
        }
        return response;
    }

    // ------------------------------------------------------------------ profiles

    // cameo_get_profile_capabilities: profiles use core Cameo API, so they are always available, but only as previews.
    static JsonObject profileCapabilities() {
        JsonObject response = Support.baseCapabilities("profiles", "core-profile-api");
        response.addProperty("available", true);
        response.addProperty("status", "available");
        response.addProperty("writeMode", "preview-first");
        response.addProperty("summaryReadSupported", true);
        response.addProperty("typedProfileWriteSupported", false);
        response.add("previewOperations", Gs.strings(PROFILE_OPERATIONS.keySet()));
        response.add("classProbe", Support.classProbe(
                "com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile",
                "com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype",
                "com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper",
                "com.nomagic.uml2.ext.jmi.helpers.TagsHelper"));
        return response;
    }

    // cameo_preview_profile_operation: validates the operation name and echoes the payload; writes go through the element tools.
    static JsonObject profilePreview(JsonObject p) {
        String operation = Gs.reqStr(p, "operation").trim().toLowerCase(Locale.ROOT);
        String label = PROFILE_OPERATIONS.get(operation);
        if (label == null) {
            throw ToolException.invalid("Unsupported profile operation '" + operation + "'. Use one of "
                    + String.join(", ", PROFILE_OPERATIONS.keySet()) + ".");
        }
        JsonObject payload = Gs.object(p, "payload");
        JsonObject response = Support.previewAccepted("profiles", payload == null ? new JsonObject() : payload,
                "Profile operation preview recorded. Use existing element stereotype/tag tools (cameo_apply_stereotype, "
                        + "cameo_set_tagged_values, cameo_apply_profile) for writes.");
        response.addProperty("operation", label);
        response.addProperty("writePerformed", false);
        response.add("capabilities", profileCapabilities());
        return response;
    }

    // cameo_export_profile_summary: profiles and stereotypes owned under the primary model, as element references.
    static JsonObject profileSummary() throws Exception {
        return Edt.read(project -> {
            JsonArray profiles = new JsonArray();
            JsonArray stereotypes = new JsonArray();
            Element root = project.getPrimaryModel();
            if (root != null) {
                Collection<? extends Element> profileElements = Finder.byTypeRecursively().find(root, new Class[]{Profile.class});
                for (Element element : profileElements) {
                    if (element instanceof Profile) {
                        profiles.add(Support.ref(element));
                    }
                }
                Collection<? extends Element> stereotypeElements = Finder.byTypeRecursively().find(root, new Class[]{Stereotype.class});
                for (Element element : stereotypeElements) {
                    if (element instanceof Stereotype) {
                        stereotypes.add(Support.ref(element));
                    }
                }
            }
            JsonObject response = profileCapabilities();
            response.addProperty("profileCount", profiles.size());
            response.addProperty("stereotypeCount", stereotypes.size());
            response.add("profiles", profiles);
            response.add("stereotypes", stereotypes);
            response.addProperty("scope", "primary model (profiles from used projects such as SysML are not listed)");
            return response;
        });
    }

    // ------------------------------------------------------------------ variants

    /*
     * cameo_get_variant_capabilities: variant analysis is bridge-owned (always available); a native product-line
     * plugin folder is only reported, never driven.
     */
    static JsonObject variantCapabilities() {
        JsonObject response = Support.baseCapabilities("variants", "bridge-owned-plus-native-probe");
        JsonArray dirs = Support.pluginDirectories("variant", "variability", "productline", "product-line", "ple");
        response.addProperty("available", true);
        response.addProperty("bridgeOwnedAvailable", true);
        response.addProperty("nativeAvailable", dirs.size() > 0);
        response.addProperty("status", dirs.size() > 0 ? "native-plugin-detected" : "bridge-owned");
        response.addProperty("nativeWriteSupported", false);
        response.addProperty("configurationEvaluationMode", "bridge-owned-preview");
        response.add("pluginDirectoriesFound", dirs);
        response.add("classProbe", Support.classProbe(
                "com.nomagic.magicdraw.variants.VariantsPlugin",
                "com.nomagic.magicdraw.productline.ProductLinePlugin"));
        return response;
    }

    // cameo_install_variant_pattern_preview / cameo_export_variant_configuration: echo only, nothing hidden or deleted.
    static JsonObject variantPreview(JsonObject p, String operation) {
        JsonObject payload = Gs.object(p, "payload");
        JsonObject response = Support.previewAccepted("variants", payload == null ? new JsonObject() : payload,
                "Variant " + operation + " preview recorded. No model content was hidden, deleted, or suppressed.");
        response.addProperty("operation", operation);
        response.add("capabilities", variantCapabilities());
        return response;
    }

    /*
     * cameo_analyze_variants_preview: checks that configuration_ids and scope_ids resolve and echoes the scope elements.
     * No variability rules are evaluated, so excludedElements is always empty.
     */
    static JsonObject analyzeVariants(JsonObject p) {
        JsonObject response = new JsonObject();
        response.addProperty("family", "variants");
        response.addProperty("evaluated", true);
        response.addProperty("mode", "bridge-owned");
        response.addProperty("writePerformed", false);
        response.add("request", Support.request(p));
        JsonArray included = new JsonArray();
        JsonArray warnings = new JsonArray();
        Project project = Support.currentProject();
        if (project != null) {
            for (String key : new String[]{"configuration_ids", "scope_ids"}) {
                for (String id : Gs.strList(p, key)) {
                    Element e = Support.element(project, id);
                    if (e == null) {
                        warnings.add(key + ": element '" + id + "' not found.");
                    } else if ("scope_ids".equals(key)) {
                        included.add(Support.ref(e));
                    }
                }
            }
        }
        response.add("includedElements", included);
        response.add("excludedElements", new JsonArray());
        warnings.add("Variant rule evaluation is not performed natively; only the referenced scope is echoed.");
        response.add("warnings", warnings);
        response.add("capabilities", variantCapabilities());
        return response;
    }

    // ------------------------------------------------------------------ extensions

    // cameo_get_extension_capabilities: safety/cyber support is detected by plugin folder name only.
    static JsonObject extensionCapabilities() {
        JsonObject response = Support.baseCapabilities("extensions", "profile-and-plugin-probe");
        JsonArray dirs = Support.pluginDirectories("safety", "reliability", "cyber", "security", "risk", "fmea", "fmeca", "hazard");
        response.addProperty("available", true);
        response.addProperty("nativeExtensionDetected", dirs.size() > 0);
        response.addProperty("status", dirs.size() > 0 ? "native-plugin-detected" : "profile-scan-only");
        response.add("pluginDirectoriesFound", dirs);
        response.addProperty("readOnlyScanSupported", true);
        response.addProperty("nativePatternWriteSupported", false);
        response.addProperty("complianceClaimsSupported", false);
        return response;
    }

    // cameo_list_extension_profiles: whole-model scan with the default terms, profiles and stereotypes only.
    static JsonObject extensionProfiles() throws Exception {
        return Edt.read(project -> scan(project, null, EXTENSION_TERMS, false));
    }

    // cameo_scan_extensions: term scan (default EXTENSION_TERMS) under scope_id or the primary model, with element findings.
    static JsonObject scanExtensions(JsonObject p) throws Exception {
        List<String> targets = Gs.strList(p, "targets");
        List<String> terms = new ArrayList<>();
        for (String t : targets) {
            if (!t.trim().isEmpty()) {
                terms.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (terms.isEmpty()) {
            terms = EXTENSION_TERMS;
        }
        String scopeId = Gs.str(p, "scope_id");
        List<String> finalTerms = terms;
        // Edt.read supplies the open project (NO_PROJECT error when none) and runs on the calling thread.
        return Edt.read(project -> {
            Element scope = scopeId == null ? null : Support.requireElement(project, scopeId, "Scope element");
            JsonObject response = scan(project, scope, finalTerms, true);
            response.add("terms", Gs.strings(finalTerms));
            response.add("request", Support.request(p));
            response.addProperty("claim", "evidence-only");
            response.addProperty("complianceClaim", false);
            return response;
        });
    }

    /*
     * Depth-first walk of the ownership tree from scope (or the primary model) collecting term matches.
     * Stops at SCAN_LIMIT elements; element findings are capped separately and both set truncated.
     */
    private static JsonObject scan(Project project, Element scope, List<String> terms, boolean includeElements) {
        JsonObject response = extensionCapabilities();
        JsonArray profiles = new JsonArray();
        JsonArray stereotypes = new JsonArray();
        JsonArray elements = new JsonArray();
        Element root = scope != null ? scope : project.getPrimaryModel();
        boolean truncated = false;
        if (root != null) {
            Deque<Element> stack = new ArrayDeque<>();
            stack.push(root);
            int visited = 0;
            while (!stack.isEmpty()) {
                Element element = stack.pop();
                if (++visited > SCAN_LIMIT) {
                    truncated = true;
                    break;
                }
                if (matches(element, terms)) {
                    if (element instanceof Profile) {
                        profiles.add(Support.ref(element));
                    } else if (element instanceof Stereotype) {
                        stereotypes.add(Support.ref(element));
                    }
                    if (includeElements) {
                        if (elements.size() < ELEMENT_FINDING_LIMIT) {
                            elements.add(Support.ref(element));
                        } else {
                            truncated = true;
                        }
                    }
                }
                Collection<Element> children = element.getOwnedElement();
                if (children != null) {
                    // Push children in reverse so they pop in owned order (keeps output order stable and model-like).
                    List<Element> list = new ArrayList<>(children);
                    for (int i = list.size() - 1; i >= 0; i--) {
                        stack.push(list.get(i));
                    }
                }
            }
        }
        response.addProperty("profileCount", profiles.size());
        response.addProperty("stereotypeCount", stereotypes.size());
        response.addProperty("elementFindingCount", elements.size());
        response.addProperty("truncated", truncated);
        if (scope != null) {
            response.addProperty("scopeId", scope.getID());
        }
        response.add("profiles", profiles);
        response.add("stereotypes", stereotypes);
        response.add("elements", elements);
        return response;
    }

    /*
     * Case-insensitive substring match of terms against the element's runtime class simple name and its name.
     * The class name is the implementation class, so broad terms like "control" also hit e.g. control-flow elements.
     */
    static boolean matches(Element element, List<String> terms) {
        StringBuilder text = new StringBuilder(element.getClass().getSimpleName()).append(' ');
        if (element instanceof NamedElement) {
            String name = ((NamedElement) element).getName();
            if (name != null) {
                text.append(name).append(' ');
            }
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    // cameo_install_extension_pattern_preview: echo only; no profile is installed.
    static JsonObject extensionInstallPreview(JsonObject p) {
        JsonObject payload = Gs.object(p, "payload");
        JsonObject response = Support.previewAccepted("extensions", payload == null ? new JsonObject() : payload,
                "Extension pattern install preview recorded. No safety/cyber profile was installed.");
        response.add("capabilities", extensionCapabilities());
        return response;
    }

    /** The refusal contract itself is this tool's result. */
    static JsonObject refuseComplianceClaim(JsonObject p) {
        Gs.reqStr(p, "claim_type");
        JsonObject response = Support.unsupported("extensions",
                "The bridge reports safety/cyber evidence and traceability gaps only; it does not make compliance claims.",
                "Use cameo_scan_extensions and native validation evidence as input to a human compliance process.");
        response.addProperty("refused", true);
        response.addProperty("code", "COMPLIANCE_CLAIM_REFUSED");
        response.add("request", Support.request(p));
        return response;
    }
}
