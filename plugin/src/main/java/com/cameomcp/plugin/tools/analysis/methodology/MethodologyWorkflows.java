package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.tools.analysis.Bridge;
import com.cameomcp.plugin.tools.analysis.J;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ArtifactRequirement;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ArtifactSnapshot;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.PackDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.cameomcp.plugin.tools.analysis.J.obj;

/**
 * Methodology package workflows: compare current artifacts against an expected artifact list,
 * validate a methodology package, plan/execute export of required diagrams, and plan/assemble a
 * PPTX + PDF review deck. Dry-run by default; files are written only when an output directory is given.
 */
public final class MethodologyWorkflows {

    private static final Pattern KIND_SEPARATORS = Pattern.compile("[\\s_-]+");

    private MethodologyWorkflows() {
    }

    // ------------------------------------------------------------------ helpers

    static String optionalStr(Object value) {
        if (value == null) {
            return null;
        }
        String text = J.str(value).strip();
        return text.isEmpty() ? null : text;
    }

    static String normalizeText(Object value) {
        return J.normalizedText(value);
    }

    static String normalizeKind(Object value) {
        return KIND_SEPARATORS.matcher(normalizeText(value)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    static String slugify(Object value) {
        String text = normalizeText(value).toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-");
        text = J.strip(text, "-");
        return text.isEmpty() ? "artifact" : text;
    }

    static boolean isDiagramKind(Object kind) {
        String normalized = normalizeKind(kind);
        return normalized.equals("bdd") || normalized.equals("ibd") || normalized.contains("diagram");
    }

    static String artifactKey(Map<String, Object> payload) {
        for (String key : new String[]{"key", "artifactKey", "element_id", "elementId", "id"}) {
            Object value = payload.get(key);
            if (value != null && !J.str(value).strip().isEmpty()) {
                return J.str(value).strip();
            }
        }
        throw ToolException.invalid("Artifact is missing a stable key (key, artifactKey, element_id or id): "
                + J.repr(payload));
    }

    static Map<String, Object> coerceArtifact(Object item, String role) {
        Map<String, Object> payload = J.map(item);
        if (payload == null) {
            throw ToolException.invalid(role + " artifact must be an object, got " + J.repr(item));
        }
        Map<String, Object> properties = J.map(payload.get("properties"));
        List<Object> stereotypes = new ArrayList<>();
        for (String s : J.strings(J.truthy(payload.get("stereotypes")) ? payload.get("stereotypes") : null)) {
            String n = normalizeText(s);
            if (!n.isEmpty()) {
                stereotypes.add(n);
            }
        }
        return obj(
                "key", artifactKey(payload),
                "kind", normalizeText(J.or(payload.get("kind"), payload.get("type"), payload.get("humanType"), "")),
                "name", optionalStr(J.or(payload.get("name"), payload.get("elementName"))),
                "parent_key", optionalStr(J.or(payload.get("parent_key"), payload.get("parentKey"), payload.get("ownerId"))),
                "element_id", optionalStr(J.or(payload.get("element_id"), payload.get("elementId"))),
                "stereotypes", stereotypes,
                "properties", properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties),
                "recipe_id", optionalStr(J.or(payload.get("recipe_id"), payload.get("recipeId"))),
                "source_recipe_id", optionalStr(J.or(payload.get("source_recipe_id"), payload.get("sourceRecipeId"))));
    }

    private static List<Map<String, Object>> coerceAll(List<?> items, String role) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items != null) {
            for (Object item : items) {
                out.add(coerceArtifact(item, role));
            }
        }
        return out;
    }

    /**
     * Expected artifacts of a pack (all recipes) or one recipe, derived from the executable recipe
     * definitions materialized without parameters. Names that depend on a recipe parameter
     * (for example "{subject_name} Needs") are left null so only kind/parent are compared.
     */
    static List<Map<String, Object>> buildExpectedArtifacts(String packId, String recipeId) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        PackDefinition runtimePack = MethodologyService.buildRuntimePack(pack, new LinkedHashMap<>(), recipeId, false);
        List<Map<String, Object>> expected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecipeDefinition recipe : runtimePack.recipes()) {
            for (ArtifactRequirement req : recipe.requiredArtifacts()) {
                if (!seen.add(req.key())) {
                    continue;
                }
                String name = req.name();
                if (name != null && name.matches(".*<[a-z_]+>.*")) {
                    name = null;
                }
                expected.add(obj(
                        "key", req.key(),
                        "kind", req.kind(),
                        "name", name,
                        "parent_key", req.parentKey(),
                        "stereotypes", new ArrayList<Object>(req.stereotypes()),
                        "properties", new LinkedHashMap<>(req.properties()),
                        "recipe_id", recipe.recipeId(),
                        "recipe_name", recipe.name(),
                        "phase_id", recipe.phase()));
            }
        }
        return expected;
    }

    static List<Map<String, Object>> expectedDiagrams(List<Map<String, Object>> expected) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : expected) {
            if (isDiagramKind(item.get("kind"))) {
                out.add(new LinkedHashMap<>(item));
            }
        }
        return out;
    }

    static String comparisonStatus(List<String> reasons) {
        if (reasons.isEmpty()) {
            return "match";
        }
        for (String[] probe : new String[][]{{"missing", "missing"}, {"kind", "kind_mismatch"},
                {"name", "name_mismatch"}, {"parent", "parent_mismatch"}, {"stereotype", "stereotype_mismatch"},
                {"property", "property_mismatch"}}) {
            for (String reason : reasons) {
                if (reason.contains(probe[0])) {
                    return probe[1];
                }
            }
        }
        return "mismatch";
    }

    private static boolean anyContains(List<String> reasons, String word) {
        for (String r : reasons) {
            if (r.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String nameOrKey(Map<String, Object> artifact) {
        return J.str(J.or(artifact.get("name"), artifact.get("key")));
    }

    static List<Map<String, Object>> suggestedActions(Map<String, Object> expected, Map<String, Object> actual,
                                                      List<String> reasons) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (actual == null) {
            String parent = J.truthy(expected.get("parent_key")) ? " under '" + J.str(expected.get("parent_key")) + "'" : "";
            actions.add(obj("action", "create_artifact", "targetKey", expected.get("key"), "targetKind", expected.get("kind"),
                    "preview", "Create " + J.str(expected.get("kind")) + " '" + nameOrKey(expected) + "'" + parent + "."));
            return actions;
        }
        String actualName = nameOrKey(actual);
        if (anyContains(reasons, "kind")) {
            actions.add(obj("action", "replace_or_retype_artifact", "targetKey", expected.get("key"),
                    "targetKind", expected.get("kind"),
                    "preview", "Replace or retype '" + actualName + "' to match " + J.str(expected.get("kind")) + "."));
        }
        if (anyContains(reasons, "name")) {
            actions.add(obj("action", "rename_artifact", "targetKey", expected.get("key"),
                    "preview", "Rename '" + actualName + "' to '" + nameOrKey(expected) + "'."));
        }
        if (anyContains(reasons, "parent")) {
            actions.add(obj("action", "reparent_artifact", "targetKey", expected.get("key"),
                    "preview", "Move '" + actualName + "' under '"
                            + J.str(J.or(expected.get("parent_key"), "the expected package")) + "'."));
        }
        if (anyContains(reasons, "stereotype")) {
            actions.add(obj("action", "update_stereotypes", "targetKey", expected.get("key"),
                    "preview", "Update stereotypes on '" + actualName + "' to match the methodology expectation."));
        }
        if (anyContains(reasons, "property")) {
            actions.add(obj("action", "update_properties", "targetKey", expected.get("key"),
                    "preview", "Update properties on '" + actualName + "' to match the methodology expectation."));
        }
        return actions;
    }

    private static void addSteps(List<Object> patchPlan, List<Map<String, Object>> actions, String reason) {
        for (Map<String, Object> action : actions) {
            Map<String, Object> step = obj("stepId", "methodology:" + (patchPlan.size() + 1));
            step.putAll(action);
            step.put("status", "preview");
            step.put("reason", reason);
            patchPlan.add(step);
        }
    }

    private static Set<String> casefoldSet(Object values) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : J.strings(values)) {
            if (!s.strip().isEmpty()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ comparison

    /** Compare current artifacts against an expected artifact list (dry run: diff plus preview patch plan). */
    public static Map<String, Object> compareAgainstExpectedArtifactList(List<?> expectedArtifacts, List<?> currentArtifacts) {
        List<Map<String, Object>> expectedItems = coerceAll(expectedArtifacts, "expected");
        List<Map<String, Object>> actualItems = coerceAll(currentArtifacts, "current");
        Map<String, Map<String, Object>> expectedIndex = new LinkedHashMap<>();
        for (Map<String, Object> e : expectedItems) {
            expectedIndex.put(J.str(e.get("key")), e);
        }
        Map<String, Map<String, Object>> actualIndex = new LinkedHashMap<>();
        for (Map<String, Object> a : actualItems) {
            actualIndex.put(J.str(a.get("key")), a);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Object> patchPlan = new ArrayList<>();

        for (Map<String, Object> expected : expectedItems) {
            String key = J.str(expected.get("key"));
            Map<String, Object> actual = actualIndex.get(key);
            if (actual == null) {
                List<String> reasons = new ArrayList<>(List.of("missing artifact"));
                List<Map<String, Object>> actions = suggestedActions(expected, null, reasons);
                entries.add(obj("key", key, "status", "missing", "reasons", reasons, "expected", expected,
                        "actual", null, "fixable", true, "suggestedActions", actions));
                addSteps(patchPlan, actions, "missing artifact");
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (!normalizeKind(actual.get("kind")).equals(normalizeKind(expected.get("kind")))) {
                reasons.add("kind mismatch");
            }
            if (expected.get("name") != null && !normalizeText(actual.get("name")).equals(normalizeText(expected.get("name")))) {
                reasons.add("name mismatch");
            }
            if (expected.get("parent_key") != null
                    && !java.util.Objects.equals(optionalStr(actual.get("parent_key")), optionalStr(expected.get("parent_key")))) {
                reasons.add("parent mismatch");
            }
            Set<String> expectedStereotypes = casefoldSet(expected.get("stereotypes"));
            Set<String> actualStereotypes = casefoldSet(actual.get("stereotypes"));
            if (!expectedStereotypes.isEmpty() && !actualStereotypes.containsAll(expectedStereotypes)) {
                reasons.add("stereotype mismatch");
            }
            Map<String, Object> expectedProps = J.mapOrEmpty(expected.get("properties"));
            Map<String, Object> actualProps = J.mapOrEmpty(actual.get("properties"));
            Map<String, Object> propertyMismatches = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : expectedProps.entrySet()) {
                if (!J.pyEquals(actualProps.get(e.getKey()), e.getValue())) {
                    propertyMismatches.put(e.getKey(), obj("expected", e.getValue(), "actual", actualProps.get(e.getKey())));
                }
            }
            if (!propertyMismatches.isEmpty()) {
                reasons.add("property mismatch");
            }
            String status = comparisonStatus(reasons);
            List<Map<String, Object>> actions = suggestedActions(expected, actual, reasons);
            boolean fixable = Set.of("match", "name_mismatch", "parent_mismatch", "stereotype_mismatch",
                    "property_mismatch").contains(status);
            entries.add(obj("key", key, "status", status, "reasons", reasons, "expected", expected, "actual", actual,
                    "propertyMismatches", propertyMismatches, "fixable", fixable, "suggestedActions", actions));
            if (!"match".equals(status)) {
                addSteps(patchPlan, actions, reasons.isEmpty() ? "comparison mismatch" : String.join(", ", reasons));
            }
        }

        List<Map<String, Object>> unexpected = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : actualIndex.entrySet()) {
            if (expectedIndex.containsKey(e.getKey())) {
                continue;
            }
            Map<String, Object> actual = e.getValue();
            unexpected.add(obj("key", e.getKey(), "status", "unexpected",
                    "reasons", J.arr("artifact not listed in methodology"), "expected", null, "actual", actual,
                    "fixable", false,
                    "suggestedActions", J.arr(obj("action", "review_or_remove_artifact", "targetKey", e.getKey(),
                            "preview", "Review whether '" + J.str(J.or(actual.get("name"), e.getKey()))
                                    + "' belongs in the methodology or should be removed."))));
        }
        entries.addAll(unexpected);
        for (Map<String, Object> entry : unexpected) {
            addSteps(patchPlan, J.maps(entry.get("suggestedActions")), "unexpected artifact");
        }

        boolean ready = unexpected.isEmpty();
        List<Object> missingKeys = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (entry.get("expected") != null && !"match".equals(entry.get("status"))) {
                ready = false;
            }
            if ("missing".equals(entry.get("status"))) {
                missingKeys.add(entry.get("key"));
            }
        }
        List<Object> unexpectedKeys = new ArrayList<>();
        for (Map<String, Object> entry : unexpected) {
            unexpectedKeys.add(entry.get("key"));
        }
        return obj(
                "expectedArtifactCount", expectedItems.size(),
                "currentArtifactCount", actualItems.size(),
                "ready", ready,
                "missingArtifactKeys", missingKeys,
                "unexpectedArtifactKeys", unexpectedKeys,
                "entries", entries,
                "patchPlan", patchPlan);
    }

    // ------------------------------------------------------------------ validate / export / assemble (dry run)

    private static List<Map<String, Object>> expectedOrBuilt(String packId, String recipeId, List<?> expectedArtifacts) {
        return expectedArtifacts != null ? coerceAll(expectedArtifacts, "expected") : buildExpectedArtifacts(packId, recipeId);
    }

    public static Map<String, Object> validateMethodologyPackage(String packId, String recipeId, List<?> currentArtifacts,
                                                                 List<?> expectedArtifacts) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        List<Map<String, Object>> expected = expectedOrBuilt(packId, recipeId, expectedArtifacts);
        Map<String, Object> comparison = compareAgainstExpectedArtifactList(expected, currentArtifacts);
        Map<String, Object> guidanceResult = MethodologyService.getWorkflowGuidance(packId, recipeId, new LinkedHashMap<>(),
                coerceAll(currentArtifacts, "current"));
        Map<String, Object> guidance = J.map(guidanceResult.get("guidance"));
        boolean ready = J.truthy(comparison.get("ready")) && J.truthy(guidance.get("ready_to_execute"));
        List<Object> actions = new ArrayList<>(J.list(guidance.get("recommended_actions")));
        for (Map<String, Object> entry : J.maps(comparison.get("entries"))) {
            for (Map<String, Object> action : J.maps(entry.get("suggestedActions"))) {
                actions.add(action.get("preview"));
            }
        }
        return obj(
                "pack", pack,
                "scope", recipeId != null ? "recipe" : "pack",
                "recipeId", recipeId,
                "workflowGuidance", guidance,
                "expectedArtifacts", expected,
                "artifactComparison", comparison,
                "ready", ready,
                "recommendedActions", actions,
                "dryRun", true);
    }

    static String stripDot(String format) {
        String f = format == null ? "png" : format;
        int i = 0;
        while (i < f.length() && f.charAt(i) == '.') {
            i++;
        }
        return f.substring(i);
    }

    public static Map<String, Object> exportRequiredDiagrams(String packId, String recipeId, List<?> currentArtifacts,
                                                             List<?> expectedArtifacts, String exportFormat) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        List<Map<String, Object>> expected = expectedOrBuilt(packId, recipeId, expectedArtifacts);
        Map<String, Object> comparison = compareAgainstExpectedArtifactList(expected, currentArtifacts);
        String format = stripDot(exportFormat);
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> artifact : expectedDiagrams(expected)) {
            index++;
            Map<String, Object> entry = null;
            for (Map<String, Object> e : J.maps(comparison.get("entries"))) {
                if (J.pyEquals(e.get("key"), artifact.get("key"))) {
                    entry = e;
                    break;
                }
            }
            Map<String, Object> actual = entry == null ? null : J.map(entry.get("actual"));
            String name = J.str(J.or(artifact.get("name"), artifact.get("key")));
            items.add(obj(
                    "stepId", "export:" + index,
                    "artifactKey", artifact.get("key"),
                    "diagramName", name,
                    "diagramKind", artifact.get("kind"),
                    "recipeId", artifact.get("recipe_id"),
                    "status", entry != null && "match".equals(entry.get("status")) ? "ready" : "blocked",
                    "diagramId", J.truthy(actual) ? actual.get("element_id") : null,
                    "outputFile", slugify(name) + "." + format,
                    "format", format,
                    "reasons", entry != null ? new ArrayList<>(J.list(entry.get("reasons"))) : J.arr("artifact not found")));
        }
        boolean ready = true;
        List<Object> blocked = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!"ready".equals(item.get("status"))) {
                ready = false;
                blocked.add(item.get("artifactKey"));
            }
        }
        return obj(
                "pack", pack,
                "scope", recipeId != null ? "recipe" : "pack",
                "recipeId", recipeId,
                "exportFormat", format,
                "expectedDiagrams", expectedDiagrams(expected),
                "artifactComparison", comparison,
                "exportItems", items,
                "readyToExport", ready,
                "blockedDiagramKeys", blocked,
                "dryRun", true);
    }

    public static Map<String, Object> assemblePptPdf(String packId, String recipeId, List<?> currentArtifacts,
                                                     List<?> expectedArtifacts, Map<String, Object> exportPlan,
                                                     String title, String pptxName, String pdfName, String exportFormat) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        Map<String, Object> exportResult = exportPlan != null ? new LinkedHashMap<>(exportPlan)
                : exportRequiredDiagrams(packId, recipeId, currentArtifacts, expectedArtifacts, exportFormat);
        String deckTitle = title != null && !title.isEmpty() ? title : J.str(pack.get("title"));
        String base = slugify(deckTitle);
        String pptxFile = pptxName != null && !pptxName.isEmpty() ? pptxName : base + ".pptx";
        String pdfFile = pdfName != null && !pdfName.isEmpty() ? pdfName : base + ".pdf";
        List<Object> slides = new ArrayList<>();
        slides.add(obj("slideNumber", 1, "kind", "title", "title", deckTitle,
                "subtitle", J.str(pack.get("domain")) + " methodology workflow",
                "notes", "Cover slide generated from the methodology workflow plan."));
        int number = 1;
        for (Map<String, Object> item : J.maps(exportResult.get("exportItems"))) {
            number++;
            List<String> reasons = J.strings(item.get("reasons"));
            slides.add(obj("slideNumber", number, "kind", "diagram", "title", item.get("diagramName"),
                    "artifactKey", item.get("artifactKey"), "diagramId", item.get("diagramId"),
                    "sourceFile", item.get("outputFile"), "status", item.get("status"),
                    "notes", String.join("; ", reasons)));
        }
        slides.add(obj("slideNumber", slides.size() + 1, "kind", "appendix", "title", "Methodology Comparison",
                "notes", "Summary of missing, unexpected, and blocked artifacts."));
        return obj(
                "pack", pack,
                "scope", recipeId != null ? "recipe" : "pack",
                "recipeId", recipeId,
                "presentationPlan", obj(
                        "title", deckTitle,
                        "pptxFile", pptxFile,
                        "pdfFile", pdfFile,
                        "slideCount", slides.size(),
                        "slides", slides,
                        "readyToAssemble", J.truthy(exportResult.get("readyToExport")),
                        "blockedDiagramKeys", new ArrayList<>(J.list(exportResult.get("blockedDiagramKeys")))),
                "exportPlan", exportResult,
                "dryRun", true);
    }

    // ------------------------------------------------------------------ live variants

    static Map<String, Object> artifactSnapshotToMap(ArtifactSnapshot artifact) {
        return artifact.toMap();
    }

    /** Discover live artifacts for a pack or recipe (seeded with the workspace and any given artifacts). */
    public static List<Map<String, Object>> discoverCurrentArtifacts(String packId, String recipeId, String rootPackageId,
                                                                     List<Map<String, Object>> currentArtifacts,
                                                                     Map<String, Object> recipeParameters, Bridge bridge) {
        Map<String, Object> params = recipeParameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(recipeParameters);
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        PackDefinition runtimePack = MethodologyService.buildRuntimePack(pack, params, recipeId, false);
        String seedRoot = rootPackageId != null && !rootPackageId.isEmpty() ? rootPackageId
                : J.text(params.get("root_package_id"));
        List<ArtifactSnapshot> discovered = MethodologyService.seedCompletedArtifacts(seedRoot, currentArtifacts);
        String root = rootPackageId;
        if ((root == null || root.isEmpty()) && J.truthy(params.get("root_package_id"))) {
            root = J.str(params.get("root_package_id"));
        }
        for (RecipeDefinition recipe : runtimePack.recipes()) {
            Set<String> existing = new HashSet<>();
            for (ArtifactSnapshot a : discovered) {
                existing.add(a.key());
            }
            List<ArtifactSnapshot> next = new ArrayList<>(discovered);
            for (ArtifactSnapshot ref : MethodologyService.referenceArtifacts(recipe.recipeId(), params)) {
                if (!existing.contains(ref.key())) {
                    next.add(ref);
                }
            }
            discovered = MethodologyService.discoverLiveArtifacts(recipe, next, params, root, bridge);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ArtifactSnapshot a : discovered) {
            out.add(artifactSnapshotToMap(a));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> discoveredOrGiven(String packId, String recipeId, String rootPackageId,
                                                               List<?> currentArtifacts, Bridge bridge) {
        if (currentArtifacts != null) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : currentArtifacts) {
                Map<String, Object> m = J.map(item);
                if (m == null) {
                    throw ToolException.invalid("current_artifacts entries must be objects, got " + J.repr(item));
                }
                out.add(m);
            }
            return out;
        }
        return discoverCurrentArtifacts(packId, recipeId, rootPackageId, null, null, bridge);
    }

    public static Map<String, Object> validateMethodologyPackageLive(String packId, String recipeId, String rootPackageId,
                                                                     List<?> currentArtifacts, List<?> expectedArtifacts,
                                                                     Bridge bridge) {
        List<Map<String, Object>> discovered = discoveredOrGiven(packId, recipeId, rootPackageId, currentArtifacts, bridge);
        Map<String, Object> result = validateMethodologyPackage(packId, recipeId, discovered, expectedArtifacts);
        result.put("currentArtifacts", discovered);
        result.put("dryRun", true);
        return result;
    }

    public static Map<String, Object> exportRequiredDiagramsLive(String packId, String recipeId, String rootPackageId,
                                                                 List<?> currentArtifacts, List<?> expectedArtifacts,
                                                                 String exportFormat, String outputDir, Bridge bridge)
            throws Exception {
        List<Map<String, Object>> discovered = discoveredOrGiven(packId, recipeId, rootPackageId, currentArtifacts, bridge);
        Map<String, Object> plan = exportRequiredDiagrams(packId, recipeId, discovered, expectedArtifacts, exportFormat);
        plan.put("currentArtifacts", discovered);
        if (outputDir == null || outputDir.isEmpty()) {
            return plan;
        }
        Path outDir = Paths.get(outputDir);
        Files.createDirectories(outDir);
        String format = stripDot(exportFormat);
        List<Map<String, Object>> exports = new ArrayList<>();
        int exported = 0;
        for (Map<String, Object> item : J.maps(plan.get("exportItems"))) {
            Path target = outDir.resolve(J.str(item.get("outputFile")));
            if (!"ready".equals(item.get("status")) || !J.truthy(item.get("diagramId"))) {
                Object reasons = J.truthy(item.get("reasons")) ? item.get("reasons") : J.arr("diagram not ready");
                exports.add(obj("artifactKey", item.get("artifactKey"), "status", "blocked", "reasons", reasons,
                        "outputFile", target.toString()));
                continue;
            }
            String diagramId = J.str(item.get("diagramId"));
            Map<String, Object> payload = bridge.getDiagramImage(diagramId, obj("format", exportFormat));
            byte[] bytes = decodeExportedImage(payload);
            Files.write(target, bytes);
            exported++;
            exports.add(obj("artifactKey", item.get("artifactKey"), "diagramId", diagramId, "status", "exported",
                    "outputFile", target.toString(), "format", format, "width", payload.get("width"),
                    "height", payload.get("height"), "byteCount", bytes.length));
        }
        plan.put("dryRun", false);
        plan.put("outputDir", outDir.toString());
        plan.put("exports", exports);
        plan.put("exportedCount", exported);
        plan.put("blockedCount", exports.size() - exported);
        return plan;
    }

    static byte[] decodeExportedImage(Map<String, Object> payload) {
        Object image = payload == null ? null : payload.get("image");
        if (!(image instanceof String) || ((String) image).isEmpty()) {
            throw new ToolException("TOOL_FAILED", "Bridge did not return a base64-encoded diagram image.");
        }
        return Base64.getMimeDecoder().decode((String) image);
    }

    public static Map<String, Object> assemblePptPdfLive(String packId, String recipeId, String rootPackageId,
                                                         List<?> currentArtifacts, List<?> expectedArtifacts,
                                                         String outputDir, String title, String pptxName, String pdfName,
                                                         String exportFormat, Bridge bridge) throws Exception {
        Map<String, Object> exportResult = exportRequiredDiagramsLive(packId, recipeId, rootPackageId, currentArtifacts,
                expectedArtifacts, exportFormat, outputDir, bridge);
        Map<String, Object> plan = assemblePptPdf(packId, recipeId, J.maps(exportResult.get("currentArtifacts")),
                expectedArtifacts, exportResult, title, pptxName, pdfName, exportFormat);
        if (outputDir == null || outputDir.isEmpty()) {
            return plan;
        }
        Path outDir = Paths.get(outputDir);
        Files.createDirectories(outDir);
        List<Path> exportedPaths = new ArrayList<>();
        for (Map<String, Object> item : J.maps(exportResult.get("exports"))) {
            if ("exported".equals(item.get("status"))) {
                exportedPaths.add(Paths.get(J.str(item.get("outputFile"))));
            }
        }
        Map<String, Object> presentation = J.map(plan.get("presentationPlan"));
        Path pdfPath = outDir.resolve(J.str(presentation.get("pdfFile")));
        Path pptxPath = outDir.resolve(J.str(presentation.get("pptxFile")));
        List<Object> created = new ArrayList<>();
        if (!exportedPaths.isEmpty()) {
            List<OfficeImages.Image> images = OfficeImages.load(exportedPaths);
            PdfWriter.write(images, pdfPath);
            created.add(obj("kind", "pdf", "path", pdfPath.toString()));
            PptxWriter.write(images, pptxPath, J.str(presentation.get("title")));
            created.add(obj("kind", "pptx", "path", pptxPath.toString()));
        }
        plan.put("dryRun", false);
        plan.put("outputDir", outDir.toString());
        plan.put("createdFiles", created);
        plan.put("ready", !exportedPaths.isEmpty());
        return plan;
    }
}
