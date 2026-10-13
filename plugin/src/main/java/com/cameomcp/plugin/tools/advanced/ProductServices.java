package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    private static final int SCAN_LIMIT = 250_000;
    private static final int ELEMENT_FINDING_LIMIT = 500;

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

    static JsonObject dataHubSources() {
        JsonObject response = dataHubCapabilities();
        response.addProperty("sourceInventoryMode", "diagnostic");
        response.add("sources", new JsonArray());
        response.addProperty("message", "Configured DataHub source listing requires installed DataHub APIs; open "
                + "DataHub Explorer in Cameo to see sources.");
        return response;
    }

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

    static JsonObject variantPreview(JsonObject p, String operation) {
        JsonObject payload = Gs.object(p, "payload");
        JsonObject response = Support.previewAccepted("variants", payload == null ? new JsonObject() : payload,
                "Variant " + operation + " preview recorded. No model content was hidden, deleted, or suppressed.");
        response.addProperty("operation", operation);
        response.add("capabilities", variantCapabilities());
        return response;
    }

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

    static JsonObject extensionProfiles() throws Exception {
        return Edt.read(project -> scan(project, null, EXTENSION_TERMS, false));
    }

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
