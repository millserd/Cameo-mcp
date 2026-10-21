package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.tools.analysis.Bridge;
import com.cameomcp.plugin.tools.analysis.J;
import com.cameomcp.plugin.tools.analysis.SemanticValidation;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ArtifactRequirement;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ArtifactSnapshot;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ConformanceFinding;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ConformanceReport;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.EvidenceBundle;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.OperationReceipt;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.PackDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.PlannedOperation;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeExecutionPlan;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeExecutionResult;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RecipeOperationDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RelationshipRequirement;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.RelationshipSnapshot;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.SemanticValidationDefinition;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.WorkflowGuidance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static com.cameomcp.plugin.tools.analysis.J.obj;

/**
 * High-level methodology services: pack listing, workflow guidance, recipe execution through
 * the bridge, recipe validation with semantic validators, and review packets.
 */
public final class MethodologyService {

    /** Static methods only. */
    private MethodologyService() {
    }

    // ------------------------------------------------------------------ public services

    /**
     * cameo_list_methodology_packs.
     *
     * @return count and deep copies of every pack
     */
    public static Map<String, Object> listMethodologyPacks() {
        List<Map<String, Object>> packs = MethodologyRegistry.listPacks();
        return obj("count", packs.size(), "packs", packs);
    }

    /**
     * cameo_get_methodology_pack: the full pack definition (deep copy).
     *
     * @param packId methodology pack ID
     * @return the pack
     * @throws ToolException NOT_FOUND for an unknown pack
     */
    public static Map<String, Object> getMethodologyPack(String packId) {
        return MethodologyRegistry.getPack(packId);
    }

    /**
     * cameo_get_methodology_guidance: progress of a recipe (or the first pending one) given the
     * artifacts the caller says are complete. Parameters are not required, so missing ones become placeholders.
     *
     * @param packId methodology pack ID
     * @param recipeId recipe ID, or null for the first pending recipe
     * @param recipeParameters recipe parameter values, or null
     * @param completedArtifacts artifacts the caller reports as done, or null
     * @return the pack and the workflow guidance
     * @throws ToolException NOT_FOUND for an unknown pack or recipe, INVALID_PARAMS for a malformed artifact
     */
    public static Map<String, Object> getWorkflowGuidance(String packId, String recipeId,
                                                          Map<String, Object> recipeParameters,
                                                          List<Map<String, Object>> completedArtifacts) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        Map<String, Object> params = recipeParameters == null ? new LinkedHashMap<>() : recipeParameters;
        PackDefinition runtimePack = buildRuntimePack(pack, params, recipeId, false);
        List<ArtifactSnapshot> artifacts = artifactSnapshots(completedArtifacts);
        artifacts.addAll(referenceArtifacts(recipeId, params));
        WorkflowGuidance guidance = MethodologyRuntime.buildWorkflowGuidance(runtimePack, artifacts, recipeId);
        return obj("pack", pack, "guidance", guidance.toMap());
    }

    /**
     * cameo_execute_methodology_recipe: the full pipeline. Seeds the workspace binding, discovers artifacts
     * that already exist under the root package (so re-runs reuse them), plans and executes the remaining
     * steps, re-discovers the result, then runs conformance and semantic checks and builds the evidence
     * bundle and Markdown review packet. Required parameters are enforced (strict) here.
     *
     * <p>Runs the recipe's model changes through the bridge, one undo step per operation.
     *
     * @param packId methodology pack ID
     * @param recipeId recipe ID
     * @param recipeParameters recipe parameter values; required ones must be present
     * @param rootPackageId package the recipe builds under (the "workspace" artifact)
     * @param completedArtifacts artifacts the caller reports as done, or null
     * @param assumptions assumptions recorded in the evidence bundle, or null
     * @param bridge model access
     * @return pack, recipe, guidance, plan, execution result, conformance, semantic results, evidence bundle
     *     and review packet
     * @throws ToolException NOT_FOUND for an unknown pack or recipe, INVALID_PARAMS for a missing required
     *     parameter
     * @throws MethodologyRuntime.StepFailedException when a step fails; it carries the receipts of the steps
     *     already applied
     */
    public static Map<String, Object> executeMethodologyRecipe(String packId, String recipeId,
                                                               Map<String, Object> recipeParameters,
                                                               String rootPackageId,
                                                               List<Map<String, Object>> completedArtifacts,
                                                               List<String> assumptions, Bridge bridge) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        Map<String, Object> params = recipeParameters == null ? new LinkedHashMap<>() : recipeParameters;
        PackDefinition runtimePack = buildRuntimePack(pack, params, recipeId, true);
        RecipeDefinition recipe = runtimePack.recipe(recipeId);
        List<ArtifactSnapshot> artifacts = seedCompletedArtifacts(rootPackageId, completedArtifacts);
        artifacts.addAll(referenceArtifacts(recipeId, params));
        artifacts = discoverLiveArtifacts(recipe, artifacts, params, rootPackageId, bridge);
        RecipeExecutionPlan plan = MethodologyRuntime.buildRecipeExecutionPlan(runtimePack, recipeId, artifacts);
        // Diagram snapshots taken before execution go into the evidence bundle as the "before" state.
        List<String> beforeIds = diagramIdsForEvidence(plan.artifactBindings());
        List<Map<String, Object>> beforeSnapshots = captureDiagramSnapshots(beforeIds, bridge);
        RecipeExecutionResult result = MethodologyRuntime.executeRecipe(plan, bridge);
        List<ArtifactSnapshot> current = mergeCurrentArtifacts(recipe, artifacts, plan, result);
        current = discoverLiveArtifacts(recipe, current, params, rootPackageId, bridge);
        WorkflowGuidance guidance = MethodologyRuntime.buildWorkflowGuidance(runtimePack, current, recipeId);
        ConformanceReport conformance = MethodologyRuntime.runConformanceChecks(recipe, current, runtimePack.packId());
        List<Map<String, Object>> semantic = runSemanticValidations(recipe, current, bridge);
        conformance = MethodologyRuntime.extendConformanceReport(conformance,
                MethodologyRuntime.semanticValidationFindings(semantic));
        List<String> afterIds = diagramIdsForEvidence(result.artifactBindings());
        Map<String, Object> evidence = buildBridgeEvidence(current, result, beforeSnapshots, afterIds, semantic, bridge);
        EvidenceBundle bundle = MethodologyRuntime.buildEvidenceBundle(runtimePack, recipe, guidance, plan, result,
                conformance, assumptions, null, evidence);
        return obj(
                "pack", pack,
                "recipe", MethodologyRegistry.getRecipe(packId, recipeId),
                "workflowGuidance", guidance.toMap(),
                "executionPlan", plan.toMap(),
                "executionResult", result.toMap(),
                "conformance", conformance.toMap(),
                "semanticValidations", semantic,
                "evidenceBundle", bundle.toMap(),
                "reviewPacketMarkdown", renderReviewPacket(pack, bundle));
    }

    /**
     * cameo_validate_methodology_recipe: read-only conformance and semantic validation of the current
     * artifacts (given plus discovered) against a recipe.
     *
     * @param packId methodology pack ID
     * @param recipeId recipe ID
     * @param recipeParameters recipe parameter values, or null
     * @param currentArtifacts artifacts the caller reports, or null
     * @param bridge model access
     * @return pack, recipe, guidance, conformance and semantic results
     * @throws ToolException NOT_FOUND for an unknown pack or recipe, INVALID_PARAMS for a malformed artifact
     */
    public static Map<String, Object> validateMethodologyRecipe(String packId, String recipeId,
                                                                Map<String, Object> recipeParameters,
                                                                List<Map<String, Object>> currentArtifacts,
                                                                Bridge bridge) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        Map<String, Object> params = recipeParameters == null ? new LinkedHashMap<>() : recipeParameters;
        PackDefinition runtimePack = buildRuntimePack(pack, params, recipeId, false);
        RecipeDefinition recipe = runtimePack.recipe(recipeId);
        List<ArtifactSnapshot> artifacts = artifactSnapshots(currentArtifacts);
        artifacts.addAll(referenceArtifacts(recipeId, params));
        artifacts = discoverLiveArtifacts(recipe, artifacts, params, rootPackageId(params, artifacts), bridge);
        WorkflowGuidance guidance = MethodologyRuntime.buildWorkflowGuidance(runtimePack, artifacts, recipeId);
        ConformanceReport conformance = MethodologyRuntime.runConformanceChecks(recipe, artifacts, runtimePack.packId());
        List<Map<String, Object>> semantic = runSemanticValidations(recipe, artifacts, bridge);
        conformance = MethodologyRuntime.extendConformanceReport(conformance,
                MethodologyRuntime.semanticValidationFindings(semantic));
        return obj(
                "pack", pack,
                "recipe", MethodologyRegistry.getRecipe(packId, recipeId),
                "workflowGuidance", guidance.toMap(),
                "conformance", conformance.toMap(),
                "semanticValidations", semantic);
    }

    /**
     * cameo_generate_review_packet: evidence bundle and Markdown review packet for the current state,
     * without executing anything (an empty execution result stands in for a run).
     *
     * @param packId methodology pack ID
     * @param recipeId recipe ID
     * @param recipeParameters recipe parameter values, or null
     * @param currentArtifacts artifacts the caller reports, or null
     * @param assumptions assumptions to record, or null
     * @param notes reviewer notes to record, or null
     * @param bridge model access
     * @return pack, recipe, semantic results, evidence bundle and review packet
     * @throws ToolException NOT_FOUND for an unknown pack or recipe, INVALID_PARAMS for a malformed artifact
     */
    public static Map<String, Object> generateReviewPacket(String packId, String recipeId,
                                                           Map<String, Object> recipeParameters,
                                                           List<Map<String, Object>> currentArtifacts,
                                                           List<String> assumptions, List<String> notes,
                                                           Bridge bridge) {
        Map<String, Object> pack = MethodologyRegistry.getPack(packId);
        Map<String, Object> params = recipeParameters == null ? new LinkedHashMap<>() : recipeParameters;
        PackDefinition runtimePack = buildRuntimePack(pack, params, recipeId, false);
        RecipeDefinition recipe = runtimePack.recipe(recipeId);
        List<ArtifactSnapshot> artifacts = artifactSnapshots(currentArtifacts);
        artifacts.addAll(referenceArtifacts(recipeId, params));
        artifacts = discoverLiveArtifacts(recipe, artifacts, params, rootPackageId(params, artifacts), bridge);
        WorkflowGuidance guidance = MethodologyRuntime.buildWorkflowGuidance(runtimePack, artifacts, recipeId);
        RecipeExecutionPlan plan = MethodologyRuntime.buildRecipeExecutionPlan(runtimePack, recipeId, artifacts);
        RecipeExecutionResult empty = new RecipeExecutionResult(runtimePack.packId(), recipe.recipeId(), recipe.name(),
                recipe.phase(), List.of(), MethodologyRuntime.bindingsOf(artifacts), List.of(), List.of(), List.of());
        ConformanceReport conformance = MethodologyRuntime.runConformanceChecks(recipe, artifacts, runtimePack.packId());
        List<Map<String, Object>> semantic = runSemanticValidations(recipe, artifacts, bridge);
        conformance = MethodologyRuntime.extendConformanceReport(conformance,
                MethodologyRuntime.semanticValidationFindings(semantic));
        List<String> diagramIds = diagramIdsForEvidence(plan.artifactBindings());
        Map<String, Object> evidence = buildBridgeEvidence(artifacts, empty,
                captureDiagramSnapshots(diagramIds, bridge), diagramIds, semantic, bridge);
        EvidenceBundle bundle = MethodologyRuntime.buildEvidenceBundle(runtimePack, recipe, guidance, plan, empty,
                conformance, assumptions, notes, evidence);
        return obj(
                "pack", pack,
                "recipe", MethodologyRegistry.getRecipe(packId, recipeId),
                "semanticValidations", semantic,
                "evidenceBundle", bundle.toMap(),
                "reviewPacketMarkdown", renderReviewPacket(pack, bundle));
    }

    // ------------------------------------------------------------------ artifact snapshots

    /**
     * Parses caller-supplied artifact objects (key, kind and name required) into snapshots.
     *
     * @param artifacts artifact maps (camelCase or snake_case), or null
     * @return the snapshots; empty for null
     * @throws ToolException INVALID_PARAMS when an artifact lacks key, kind or name
     */
    static List<ArtifactSnapshot> artifactSnapshots(List<Map<String, Object>> artifacts) {
        List<ArtifactSnapshot> snapshots = new ArrayList<>();
        if (artifacts == null) {
            return snapshots;
        }
        int index = 0;
        for (Map<String, Object> artifact : artifacts) {
            for (String required : new String[]{"key", "kind", "name"}) {
                if (!artifact.containsKey(required)) {
                    throw ToolException.invalid("Artifact snapshot #" + (index + 1) + " is missing '" + required
                            + "'. Each artifact must look like {\"key\", \"kind\", \"name\", \"element_id\"}.");
                }
            }
            index++;
            List<RelationshipSnapshot> relationships = new ArrayList<>();
            for (Map<String, Object> rel : J.maps(artifact.get("relationships"))) {
                relationships.add(new RelationshipSnapshot(
                        J.text(J.or(rel.get("relationship_type"), rel.get("relationshipType"), "")),
                        J.text(J.or(rel.get("source_key"), rel.get("sourceKey"), "")),
                        J.text(J.or(rel.get("target_key"), rel.get("targetKey"), "")),
                        optionalStr(J.or(rel.get("relationship_id"), rel.get("relationshipId"))),
                        optionalStr(rel.get("name")),
                        optionalStr(rel.get("guard"))));
            }
            snapshots.add(new ArtifactSnapshot(
                    J.str(artifact.get("key")),
                    J.str(artifact.get("kind")),
                    J.str(artifact.get("name")),
                    optionalStr(J.or(artifact.get("element_id"), artifact.get("elementId"))),
                    optionalStr(J.or(artifact.get("parent_key"), artifact.get("parentKey"))),
                    J.strings(artifact.get("stereotypes")),
                    J.copy(artifact.get("properties")),
                    relationships));
        }
        return snapshots;
    }

    /**
     * Caller artifacts plus a "workspace" Package bound to the root package (unless the caller supplied one).
     * Every recipe creates its top-level package under {"ref": "workspace"}.
     *
     * @param rootPackageId the root package ID; blank means no workspace binding
     * @param completed caller artifacts, or null
     * @return the snapshots
     */
    static List<ArtifactSnapshot> seedCompletedArtifacts(String rootPackageId, List<Map<String, Object>> completed) {
        List<ArtifactSnapshot> artifacts = artifactSnapshots(completed);
        boolean hasWorkspace = false;
        for (ArtifactSnapshot a : artifacts) {
            if ("workspace".equals(a.key())) {
                hasWorkspace = true;
                break;
            }
        }
        if (!hasWorkspace) {
            artifacts.add(0, new ArtifactSnapshot("workspace", "Package", "Workspace", rootPackageId));
        }
        return artifacts;
    }

    /**
     * Existing model elements that some recipes take as ID parameters (source needs, requirements),
     * exposed as artifacts so relationship requirements can refer to them by key.
     *
     * @param recipeId recipe ID
     * @param params materialized recipe parameters
     * @return reference artifacts bound to existing element IDs
     */
    static List<ArtifactSnapshot> referenceArtifacts(String recipeId, Map<String, Object> params) {
        List<ArtifactSnapshot> out = new ArrayList<>();
        if ("system_requirements_package".equals(recipeId)) {
            int i = 0;
            for (String id : stringList(params.get("source_need_ids"))) {
                i++;
                out.add(new ArtifactSnapshot("source_need_" + i, "Requirement", "Source Need " + i, id));
            }
        } else if ("verification_evidence_scaffold".equals(recipeId)
                || "requirements_to_architecture_allocation".equals(recipeId)) {
            int i = 0;
            for (String id : stringList(params.get("requirement_ids"))) {
                i++;
                out.add(new ArtifactSnapshot("requirement_ref_" + i, "Requirement", id, id));
            }
        }
        return out;
    }

    /**
     * The artifact list after execution: required artifacts that got bound, relationships created by
     * the plan's create_relationship steps (attached to their source artifact), and the created and
     * updated snapshots from the result, keeping richer existing data where present.
     *
     * @param recipe the recipe
     * @param existing artifacts before execution
     * @param plan the executed plan
     * @param result the execution result
     * @return the merged artifacts
     */
    static List<ArtifactSnapshot> mergeCurrentArtifacts(RecipeDefinition recipe, List<ArtifactSnapshot> existing,
                                                        RecipeExecutionPlan plan, RecipeExecutionResult result) {
        Map<String, ArtifactSnapshot> index = new LinkedHashMap<>();
        for (ArtifactSnapshot a : existing) {
            index.put(a.key(), a);
        }
        Map<String, String> bindings = new LinkedHashMap<>(result.artifactBindings());

        for (ArtifactRequirement req : recipe.requiredArtifacts()) {
            String binding = bindings.get(req.key());
            if (binding == null) {
                continue;
            }
            ArtifactSnapshot prior = index.get(req.key());
            index.put(req.key(), new ArtifactSnapshot(req.key(), req.kind(), nameOrKey(req.name(), req.key()), binding,
                    req.parentKey(), req.stereotypes(), req.properties(),
                    prior == null ? List.of() : prior.relationships()));
        }

        Map<String, String> reverse = reverseBindings(bindings);
        Map<String, List<RelationshipSnapshot>> bySource = new LinkedHashMap<>();
        for (PlannedOperation step : plan.plannedOperations()) {
            if (!"create_relationship".equals(step.operation().kind()) || "skipped".equals(step.status())) {
                continue;
            }
            Map<String, Object> resolved = resolveRuntimeParameters(step.operation().parameters(), bindings);
            String sourceKey = reverse.getOrDefault(J.str(resolved.get("source_id")), "");
            String targetKey = reverse.getOrDefault(J.str(resolved.get("target_id")), "");
            bySource.computeIfAbsent(sourceKey, k -> new ArrayList<>()).add(new RelationshipSnapshot(
                    J.str(resolved.get("type")), sourceKey, targetKey, null, optionalStr(resolved.get("name")), null));
        }
        for (Map.Entry<String, List<RelationshipSnapshot>> e : bySource.entrySet()) {
            ArtifactSnapshot a = index.get(e.getKey());
            if (a == null) {
                continue;
            }
            index.put(a.key(), new ArtifactSnapshot(a.key(), a.kind(), a.name(), a.elementId(), a.parentKey(),
                    a.stereotypes(), a.properties(), MethodologyRuntime.concat(a.relationships(), e.getValue())));
        }

        for (ArtifactSnapshot a : result.createdArtifacts()) {
            ArtifactSnapshot ex = index.get(a.key());
            index.put(a.key(), new ArtifactSnapshot(a.key(), a.kind(), a.name(),
                    a.elementId() != null && !a.elementId().isEmpty() ? a.elementId() : (ex != null ? ex.elementId() : null),
                    ex != null ? ex.parentKey() : a.parentKey(),
                    ex != null && !ex.stereotypes().isEmpty() ? ex.stereotypes() : a.stereotypes(),
                    ex != null ? ex.properties() : a.properties(),
                    ex != null ? ex.relationships() : a.relationships()));
        }

        for (ArtifactSnapshot a : result.updatedArtifacts()) {
            ArtifactSnapshot ex = index.get(a.key());
            if (ex == null) {
                index.put(a.key(), a);
                continue;
            }
            Map<String, Object> props = new LinkedHashMap<>(ex.properties());
            props.putAll(a.properties());
            index.put(a.key(), new ArtifactSnapshot(ex.key(), ex.kind(), ex.name(),
                    ex.elementId() != null && !ex.elementId().isEmpty() ? ex.elementId() : a.elementId(),
                    ex.parentKey(), ex.stereotypes(), props, ex.relationships()));
        }
        return new ArrayList<>(index.values());
    }

    /**
     * Display label: the name when set, else the key.
     *
     * @param name artifact name, or null
     * @param key artifact key
     * @return the label
     */
    private static String nameOrKey(String name, String key) {
        return name != null && !name.isEmpty() ? name : key;
    }

    /**
     * Element ID to artifact key, skipping unbound keys.
     *
     * @param bindings artifact key to element ID
     * @return the reverse map
     */
    private static Map<String, String> reverseBindings(Map<String, String> bindings) {
        Map<String, String> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                reverse.put(e.getValue(), e.getKey());
            }
        }
        return reverse;
    }

    /**
     * Resolves only top-level {"ref": key} values; unlike MethodologyRuntime.resolveValue an unknown key
     * becomes null.
     *
     * @param parameters operation parameters
     * @param bindings artifact key to element ID
     * @return a new map with the refs replaced
     */
    static Map<String, Object> resolveRuntimeParameters(Map<String, Object> parameters, Map<String, String> bindings) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            Map<String, Object> m = J.map(e.getValue());
            if (m != null && m.size() == 1 && m.containsKey("ref")) {
                resolved.put(e.getKey(), bindings.get(J.str(m.get("ref"))));
            } else {
                resolved.put(e.getKey(), e.getValue());
            }
        }
        return resolved;
    }

    // ------------------------------------------------------------------ runtime pack

    /**
     * Turns the pack JSON into runtime definitions: one recipe (when targetRecipeId is set) or all recipes
     * in pack order. strict=true makes missing required parameters an error instead of a placeholder.
     *
     * @param pack pack map from the registry
     * @param params recipe parameter values
     * @param targetRecipeId one recipe ID, or null for all
     * @param strict whether missing required parameters are an error
     * @return the runtime pack
     * @throws ToolException NOT_FOUND for an unknown recipe, INVALID_PARAMS for a missing parameter in strict
     *     mode
     */
    static PackDefinition buildRuntimePack(Map<String, Object> pack, Map<String, Object> params,
                                           String targetRecipeId, boolean strict) {
        String packId = J.str(pack.get("id"));
        List<Map<String, Object>> recipes = targetRecipeId != null
                ? List.of(MethodologyRegistry.getRecipe(packId, targetRecipeId))
                : MethodologyRegistry.recipes(pack);
        List<RecipeDefinition> built = new ArrayList<>();
        for (Map<String, Object> recipe : recipes) {
            built.add(buildRuntimeRecipe(packId, recipe, params, strict));
        }
        List<String> phases = new ArrayList<>();
        for (Map<String, Object> phase : J.maps(pack.get("method_phases"))) {
            phases.add(J.str(phase.get("id")));
        }
        List<String> checklist = new ArrayList<>();
        for (Map<String, Object> item : J.maps(pack.get("checklist_items"))) {
            checklist.add(J.str(item.get("title")));
        }
        return new PackDefinition(packId, J.str(pack.get("title")), J.str(pack.get("domain")) + " methodology pack",
                phases, built, checklist, J.strings(pack.get("required_profiles")));
    }

    /**
     * Builds one runtime recipe. Recipe structure (artifacts, operations, relationships) is defined in code
     * per recipe ID; the pack JSON supplies the metadata and parameter definitions.
     *
     * @param packId pack ID
     * @param recipe the recipe entry from the pack JSON
     * @param provided caller parameter values
     * @param strict whether missing required parameters are an error
     * @return the runtime recipe
     * @throws ToolException INVALID_PARAMS for a missing parameter in strict mode, UNSUPPORTED for a recipe ID
     *     without a built-in definition
     */
    static RecipeDefinition buildRuntimeRecipe(String packId, Map<String, Object> recipe, Map<String, Object> provided,
                                               boolean strict) {
        Map<String, Object> params = materializeParameters(J.maps(recipe.get("parameters")), provided, strict);
        String id = J.str(recipe.get("id"));
        switch (id) {
            case "stakeholder_needs_package":
                return stakeholderNeeds(recipe, params);
            case "use_case_model":
            case "use_case_subject_containment":
                return useCase(recipe, params);
            case "system_requirements_package":
                return systemRequirements(recipe, params);
            case "logical_architecture_scaffold":
                return architecture(recipe, params);
            case "logical_activity_flow":
                return logicalActivityFlow(recipe, params);
            case "logical_port_bdd":
                return logicalPortBdd(recipe, params);
            case "logical_ibd_traceability":
                return logicalIbdTraceability(recipe, params);
            case "requirements_to_architecture_allocation":
                return requirementsToArchitecture(recipe, params);
            case "verification_evidence_scaffold":
                return verification(recipe, params);
            case "uaf_operational_activity_starter":
                return uafOperationalActivity(recipe, params);
            default:
                throw ToolException.unsupported("Unsupported executable methodology recipe: " + id);
        }
    }

    /**
     * Parameter values: provided, else the definition's default, else (for required parameters) a
     * "<name>" placeholder when not strict. Extra provided values are passed through.
     *
     * @param definitions parameter definitions from the pack JSON
     * @param provided caller values
     * @param strict whether a missing required parameter is an error
     * @return the parameter values
     * @throws ToolException INVALID_PARAMS for a missing required parameter in strict mode
     */
    static Map<String, Object> materializeParameters(List<Map<String, Object>> definitions, Map<String, Object> provided,
                                                     boolean strict) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> def : definitions) {
            String name = J.str(def.get("name"));
            if (provided.containsKey(name)) {
                values.put(name, provided.get(name));
            } else if (def.get("default") != null) {
                values.put(name, def.get("default"));
            } else if (J.truthy(def.get("required"))) {
                if (strict) {
                    throw ToolException.invalid("Missing required recipe parameter: " + name
                            + ". Pass it in recipe_parameters (see cameo_get_methodology_pack for the recipe's parameters).");
                }
                values.put(name, "<" + name + ">");
            }
        }
        for (Map.Entry<String, Object> e : provided.entrySet()) {
            values.putIfAbsent(e.getKey(), e.getValue());
        }
        return values;
    }

    // ------------------------------------------------------------------ recipe builder helpers

    /** {"ref": key} placeholder resolved to the bound element ID at execution time. */
    private static Map<String, Object> ref(String key) {
        return obj("ref", key);
    }

    /**
     * {"ref": key} placeholders for each key.
     *
     * @param keys artifact keys
     * @return the placeholders
     */
    private static List<Object> refs(List<String> keys) {
        List<Object> out = new ArrayList<>();
        for (String k : keys) {
            out.add(ref(k));
        }
        return out;
    }

    /**
     * Shorthand for an {@link ArtifactRequirement}.
     *
     * @param key artifact key
     * @param kind expected element or diagram kind
     * @param name expected name
     * @param parent parent artifact key, or null
     * @return the requirement
     */
    private static ArtifactRequirement art(String key, String kind, String name, String parent) {
        return new ArtifactRequirement(key, kind, name, parent);
    }

    /** An operation with its parameters given as alternating key/value pairs. */
    private static RecipeOperationDefinition op(String kind, String artifactKey, Object... kv) {
        return new RecipeOperationDefinition(kind, artifactKey, obj(kv));
    }

    /**
     * A create_element operation under a parent artifact.
     *
     * @param key artifact key the new element binds to
     * @param type element type
     * @param name element name
     * @param parentKey parent artifact key
     * @return the operation
     */
    private static RecipeOperationDefinition createElement(String key, String type, String name, String parentKey) {
        return op("create_element", key, "type", type, "name", name, "parent_id", ref(parentKey));
    }

    /**
     * A create_diagram operation under a parent artifact.
     *
     * @param key artifact key the new diagram binds to
     * @param type diagram type
     * @param name diagram name
     * @param parentKey owner artifact key
     * @return the operation
     */
    private static RecipeOperationDefinition createDiagram(String key, String type, String name, String parentKey) {
        return op("create_diagram", key, "type", type, "name", name, "parent_id", ref(parentKey));
    }

    /** Draws an existing relationship as a path between two already placed shapes. */
    private static RecipeOperationDefinition addPath(String key, String diagramKey, String relKey, String sourceShape,
                                                     String targetShape) {
        return op("add_diagram_paths", key, "diagram_id", ref(diagramKey), "paths", J.arr(obj(
                "relationshipId", ref(relKey), "sourceShapeId", ref(sourceShape), "targetShapeId", ref(targetShape))));
    }

    /** Re-routes a path and resets its label positions so labels do not overlap after placement. */
    private static RecipeOperationDefinition routePath(String diagramKey, String pathKey) {
        return op("route_paths", null, "diagram_id", ref(diagramKey), "routes", J.arr(obj(
                "presentationId", ref(pathKey), "resetLabels", true)));
    }

    /** Wraps built artifacts/operations with the recipe's metadata from the pack JSON. */
    private static RecipeDefinition recipe(Map<String, Object> recipe, List<ArtifactRequirement> artifacts,
                                           List<RelationshipRequirement> relationships,
                                           List<RecipeOperationDefinition> operations,
                                           List<SemanticValidationDefinition> validations) {
        return new RecipeDefinition(J.str(recipe.get("id")), J.str(recipe.get("title")), J.str(recipe.get("phase_id")),
                J.text(recipe.get("goal")), artifacts, relationships, operations,
                J.strings(recipe.get("review_sections")), J.strings(recipe.get("evidence_sections")),
                recipe.get("layout_profile") == null ? null : J.str(recipe.get("layout_profile")), List.of(), validations);
    }

    /** prefix1 .. prefixN. */
    private static List<String> keys(String prefix, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(prefix + i);
        }
        return out;
    }

    // ------------------------------------------------------------------ recipe builders

    /**
     * Stakeholder needs: a package and requirement diagram, one Actor per stakeholder, one Requirement
     * per need statement (placed on the diagram), and "originates" Trace links from stakeholders to needs.
     *
     * @param recipe the recipe entry from the pack JSON
     * @param params materialized recipe parameters
     * @return the runtime recipe
     */
    private static RecipeDefinition stakeholderNeeds(Map<String, Object> recipe, Map<String, Object> params) {
        String subject = J.str(params.get("subject_name"));
        String packageName = subject + " Needs";
        List<String> needs = stringList(params.get("need_statements"));
        List<String> stakeholders = stringList(params.get("stakeholder_names"));
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("stakeholder_needs_package", "Package", packageName, "workspace"),
                art("stakeholder_needs_diagram", "SysML Requirement Diagram", packageName, "stakeholder_needs_package")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("stakeholder_needs_package", "Package", packageName, "workspace"),
                createDiagram("stakeholder_needs_diagram", "SysML Requirement Diagram", packageName, "stakeholder_needs_package")));
        List<RelationshipRequirement> rels = new ArrayList<>();
        for (int i = 1; i <= stakeholders.size(); i++) {
            String key = "stakeholder_" + i;
            arts.add(art(key, "Actor", stakeholders.get(i - 1), "stakeholder_needs_package"));
            ops.add(createElement(key, "Actor", stakeholders.get(i - 1), "stakeholder_needs_package"));
        }
        for (int i = 1; i <= needs.size(); i++) {
            String key = "need_" + i;
            String statement = needs.get(i - 1);
            ops.add(op("create_element", key, "type", "Requirement", "name", statement,
                    "parent_id", ref("stakeholder_needs_package"), "documentation", statement));
            ops.add(op("add_to_diagram", null, "diagram_id", ref("stakeholder_needs_diagram"), "element_id", ref(key),
                    "x", 180, "y", 80 + (i - 1) * 110));
            arts.add(art(key, "Requirement", statement, "stakeholder_needs_package"));
        }
        for (String[] pair : broadcastPairs(keys("stakeholder_", stakeholders.size()), keys("need_", needs.size()))) {
            rels.add(new RelationshipRequirement("Trace", pair[0], pair[1], "originates", true));
            ops.add(op("create_relationship", null, "type", "Trace", "source_id", ref(pair[0]),
                    "target_id", ref(pair[1]), "name", "originates"));
        }
        return recipe(recipe, arts, rels, ops, List.of());
    }

    /**
     * Use case model with an explicit subject: the subject Block is drawn as a boundary, each use case
     * gets its subject set and is placed inside the boundary shape, and actors are associated with use cases.
     *
     * @param recipe the recipe entry from the pack JSON
     * @param params materialized recipe parameters
     * @return the runtime recipe
     */
    private static RecipeDefinition useCase(Map<String, Object> recipe, Map<String, Object> params) {
        String subject = J.str(params.get("subject_name"));
        String packageName = subject + " Use Cases";
        List<String> actors = stringList(params.get("actor_names"));
        List<String> useCases = stringList(params.get("use_case_names"));
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("use_case_package", "Package", packageName, "workspace"),
                art("subject_boundary", "Block", subject, "use_case_package"),
                art("use_case_diagram", "Use Case Diagram", packageName, "use_case_package")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("use_case_package", "Package", packageName, "workspace"),
                createElement("subject_boundary", "Block", subject, "use_case_package"),
                createDiagram("use_case_diagram", "Use Case Diagram", packageName, "use_case_package"),
                op("add_to_diagram", "subject_boundary_shape", "diagram_id", ref("use_case_diagram"),
                        "element_id", ref("subject_boundary"), "x", 280, "y", 60, "width", 420,
                        "height", Math.max(220, 140 + useCases.size() * 90)),
                // Hide the subject block's compartments so it reads as a plain system boundary.
                op("set_shape_compartments", null, "diagram_id", ref("use_case_diagram"),
                        "presentation_id", ref("subject_boundary_shape"),
                        "compartments", obj("properties", false, "operations", false, "attributes", false, "stereotype", false))));
        List<RelationshipRequirement> rels = new ArrayList<>();
        for (int i = 1; i <= actors.size(); i++) {
            String key = "actor_" + i;
            arts.add(art(key, "Actor", actors.get(i - 1), "use_case_package"));
            ops.add(createElement(key, "Actor", actors.get(i - 1), "use_case_package"));
            ops.add(op("add_to_diagram", key + "_shape", "diagram_id", ref("use_case_diagram"), "element_id", ref(key),
                    "x", 100, "y", 100 + (i - 1) * 120));
        }
        for (int i = 1; i <= useCases.size(); i++) {
            String key = "use_case_" + i;
            arts.add(art(key, "UseCase", useCases.get(i - 1), "use_case_package"));
            ops.add(createElement(key, "UseCase", useCases.get(i - 1), "use_case_package"));
            ops.add(op("set_usecase_subject", null, "element_id", ref(key), "subject_ids", J.arr(ref("subject_boundary")),
                    "append", false));
            ops.add(op("add_to_diagram", key + "_shape", "diagram_id", ref("use_case_diagram"), "element_id", ref(key),
                    "container_presentation_id", ref("subject_boundary_shape"), "x", 360, "y", 100 + (i - 1) * 95));
            rels.add(new RelationshipRequirement("Subject", "subject_boundary", key, "subject", true));
        }
        int pairIndex = 0;
        for (String[] pair : broadcastPairs(keys("actor_", actors.size()), keys("use_case_", useCases.size()))) {
            pairIndex++;
            String relKey = "association_" + pairIndex;
            rels.add(new RelationshipRequirement("Association", pair[0], pair[1], "participates in", true));
            ops.add(op("create_relationship", relKey, "type", "Association", "source_id", ref(pair[0]),
                    "target_id", ref(pair[1]), "name", "participates in"));
            ops.add(addPath(relKey + "_path", "use_case_diagram", relKey, pair[0] + "_shape", pair[1] + "_shape"));
            ops.add(routePath("use_case_diagram", relKey + "_path"));
        }
        return recipe(recipe, arts, rels, ops, List.of());
    }

    /**
     * System requirements with IDs and texts on a requirement diagram; when source need IDs are given,
     * each requirement refines the need at the same position. Adds a requirement-quality semantic check.
     *
     * @param recipe the recipe entry from the pack JSON
     * @param params materialized recipe parameters
     * @return the runtime recipe
     */
    private static RecipeDefinition systemRequirements(Map<String, Object> recipe, Map<String, Object> params) {
        String system = J.str(params.get("system_name"));
        String packageName = system + " Requirements";
        List<String> ids = stringList(params.get("requirement_ids"));
        List<String> texts = stringList(params.get("requirement_texts"));
        List<String> sourceNeeds = stringList(params.get("source_need_ids"));
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("requirements_package", "Package", packageName, "workspace"),
                art("requirements_diagram", "SysML Requirement Diagram", packageName, "requirements_package")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("requirements_package", "Package", packageName, "workspace"),
                createDiagram("requirements_diagram", "SysML Requirement Diagram", packageName, "requirements_package")));
        List<RelationshipRequirement> rels = new ArrayList<>();
        List<String> requirementKeys = new ArrayList<>();
        List<String> coalesced = coalesceRequirementIds(ids, texts);
        for (int i = 1; i <= coalesced.size(); i++) {
            String key = "requirement_" + i;
            String reqId = coalesced.get(i - 1);
            requirementKeys.add(key);
            String text = i - 1 < texts.size() ? texts.get(i - 1) : reqId;
            arts.add(art(key, "Requirement", reqId, "requirements_package"));
            ops.add(op("create_element", key, "type", "Requirement", "name", reqId,
                    "parent_id", ref("requirements_package"), "documentation", text));
            ops.add(op("add_to_diagram", null, "diagram_id", ref("requirements_diagram"), "element_id", ref(key),
                    "x", 180, "y", 80 + (i - 1) * 110));
            if (i - 1 < sourceNeeds.size()) {
                rels.add(new RelationshipRequirement("Refine", "source_need_" + i, key, "refines", true));
                // Source needs are existing elements, so their raw ID is passed instead of a ref.
                ops.add(op("create_relationship", null, "type", "Refine", "source_id", sourceNeeds.get(i - 1),
                        "target_id", ref(key), "name", "refines"));
            }
        }
        List<SemanticValidationDefinition> validations = new ArrayList<>();
        if (!requirementKeys.isEmpty()) {
            validations.add(new SemanticValidationDefinition("requirement_quality", obj(
                    "requirement_ids", refs(requirementKeys), "require_id", true, "require_measurement", true,
                    "min_text_length", 20)));
        }
        return recipe(recipe, arts, rels, ops, validations);
    }

    /** Block definition diagram with one Block per name; optional Satisfy links from existing requirement IDs. */
    private static RecipeDefinition architecture(Map<String, Object> recipe, Map<String, Object> params) {
        String name = J.str(params.get("architecture_name"));
        String packageName = name + " Architecture";
        List<String> blocks = stringList(params.get("block_names"));
        List<String> targets = stringList(params.get("allocation_targets"));
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("architecture_package", "Package", packageName, "workspace"),
                art("architecture_bdd", "SysML Block Definition Diagram", packageName, "architecture_package")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("architecture_package", "Package", packageName, "workspace"),
                createDiagram("architecture_bdd", "SysML Block Definition Diagram", packageName, "architecture_package")));
        for (int i = 1; i <= blocks.size(); i++) {
            String key = "block_" + i;
            arts.add(art(key, "Block", blocks.get(i - 1), "architecture_package"));
            ops.add(createElement(key, "Block", blocks.get(i - 1), "architecture_package"));
            ops.add(op("add_to_diagram", null, "diagram_id", ref("architecture_bdd"), "element_id", ref(key),
                    "x", 180 + (i - 1) * 180, "y", 160));
            if (i - 1 < targets.size()) {
                ops.add(op("create_relationship", null, "type", "Satisfy", "source_id", targets.get(i - 1),
                        "target_id", ref(key), "name", "satisfies"));
            }
        }
        return recipe(recipe, arts, List.of(), ops, List.of());
    }

    /**
     * Logical activity: one swimlane partition per performer (each representing a performer Block), actions
     * distributed round-robin across lanes, and a single control-flow chain Start -> actions -> Done.
     * Default performer and action names are generated when none are given. Validated by the
     * activity-flow semantic check.
     *
     * @param recipe the recipe entry from the pack JSON
     * @param params materialized recipe parameters
     * @return the runtime recipe
     */
    private static RecipeDefinition logicalActivityFlow(Map<String, Object> recipe, Map<String, Object> params) {
        String activity = J.str(params.get("activity_name"));
        String packageName = activity + " Activity";
        List<String> performers = stringList(params.get("performer_names"));
        if (performers.isEmpty()) {
            performers = List.of(activity + " Performer");
        }
        List<String> actions = stringList(params.get("action_names"));
        if (actions.isEmpty()) {
            actions = List.of("Capture " + activity + " Request", "Perform " + activity, "Confirm " + activity + " Outcome");
        }
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("logical_activity_package", "Package", packageName, "workspace"),
                art("logical_activity", "Activity", activity, "logical_activity_package"),
                art("logical_activity_diagram", "Activity Diagram", packageName, "logical_activity")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("logical_activity_package", "Package", packageName, "workspace"),
                createElement("logical_activity", "Activity", activity, "logical_activity_package"),
                createDiagram("logical_activity_diagram", "Activity Diagram", packageName, "logical_activity")));

        // Lanes grow with the number of actions so every action row fits inside its partition.
        int laneHeight = Math.max(240, 140 + actions.size() * 90);
        int performerCount = Math.max(performers.size(), 1);
        int laneWidth = 220;
        List<String> actionShapeKeys = new ArrayList<>();
        List<RecipeOperationDefinition> partitionVisuals = new ArrayList<>();
        for (int i = 1; i <= performers.size(); i++) {
            String performerKey = "performer_" + i;
            String partitionKey = "partition_" + i;
            int laneX = 80 + (i - 1) * laneWidth;
            ops.add(createElement(performerKey, "Block", performers.get(i - 1), "logical_activity_package"));
            ops.add(op("create_element", partitionKey, "type", "ActivityPartition", "name", performers.get(i - 1),
                    "parent_id", ref("logical_activity"), "represents_id", ref(performerKey)));
            // Partition shapes are added after every partition element exists.
            partitionVisuals.add(op("add_to_diagram", partitionKey + "_shape", "diagram_id", ref("logical_activity_diagram"),
                    "element_id", ref(partitionKey), "x", laneX, "y", 80, "width", laneWidth, "height", laneHeight));
        }
        ops.addAll(partitionVisuals);
        ops.add(op("create_element", "initial_node", "type", "InitialNode", "name", "Start", "parent_id", ref("logical_activity")));
        ops.add(op("create_element", "activity_final", "type", "ActivityFinalNode", "name", "Done", "parent_id", ref("logical_activity")));
        ops.add(op("add_to_diagram", "initial_node_shape", "diagram_id", ref("logical_activity_diagram"),
                "element_id", ref("initial_node"), "x", 104, "y", 112));
        ops.add(op("add_to_diagram", "activity_final_shape", "diagram_id", ref("logical_activity_diagram"),
                // The final node sits right of the last lane, level with the last action row.
                "element_id", ref("activity_final"), "x", 80 + (performerCount - 1) * laneWidth + 150,
                "y", 124 + (Math.max(actions.size(), 1) - 1) * 80));

        for (int i = 1; i <= actions.size(); i++) {
            String actionKey = "action_" + i;
            String shapeKey = actionKey + "_shape";
            actionShapeKeys.add(shapeKey);
            // Round-robin lane placement: action i goes to lane (i-1) mod lanes, next row after each full sweep.
            int laneIndex = (i - 1) % performerCount;
            int rowIndex = (i - 1) / performerCount;
            int laneX = 80 + laneIndex * laneWidth;
            ops.add(createElement(actionKey, "OpaqueAction", actions.get(i - 1), "logical_activity"));
            ops.add(op("add_to_diagram", shapeKey, "diagram_id", ref("logical_activity_diagram"), "element_id", ref(actionKey),
                    "x", laneX + 56, "y", 164 + rowIndex * 90, "width", 140, "height", 48));
        }
        List<String> sources = new ArrayList<>(List.of("initial_node"));
        sources.addAll(keys("action_", actions.size()));
        List<String> targets = new ArrayList<>(keys("action_", actions.size()));
        targets.add("activity_final");
        List<String> sourceShapes = new ArrayList<>(List.of("initial_node_shape"));
        sourceShapes.addAll(actionShapeKeys);
        List<String> targetShapes = new ArrayList<>(actionShapeKeys);
        targetShapes.add("activity_final_shape");
        for (int i = 1; i <= sources.size(); i++) {
            String flowKey = "control_flow_" + i;
            String pathKey = flowKey + "_path";
            ops.add(op("create_relationship", flowKey, "type", "ControlFlow", "source_id", ref(sources.get(i - 1)),
                    "target_id", ref(targets.get(i - 1))));
            ops.add(addPath(pathKey, "logical_activity_diagram", flowKey, sourceShapes.get(i - 1), targetShapes.get(i - 1)));
            ops.add(routePath("logical_activity_diagram", pathKey));
        }
        return recipe(recipe, arts, List.of(), ops, List.of(new SemanticValidationDefinition("activity_flow_semantics",
                obj("diagram_id", ref("logical_activity_diagram"), "max_partition_depth", 1,
                        "allow_stereotype_partition_labels", false))));
    }

    /**
     * Port BDD: a system Block shown with its ports compartment, one InterfaceBlock per interface definition
     * (with directed flow properties) and one typed Port on the system per interface, placed on the block border.
     * A default request/response interface is used when none is given. Validated by the port-boundary check.
     *
     * @param recipe the recipe entry from the pack JSON
     * @param params materialized recipe parameters
     * @return the runtime recipe
     */
    @SuppressWarnings("unchecked")
    private static RecipeDefinition logicalPortBdd(Map<String, Object> recipe, Map<String, Object> params) {
        String system = J.str(params.get("system_name"));
        String packageName = system + " Ports";
        List<Map<String, Object>> interfaces = interfaceDefinitions(params.get("interface_definitions"));
        if (interfaces.isEmpty()) {
            interfaces = List.of(obj("name", system + " Port Type", "port_name", defaultPortName(system),
                    "flow_properties", J.arr(obj("name", "Request", "direction", "in"), obj("name", "Response", "direction", "out"))));
        }
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("logical_port_package", "Package", packageName, "workspace"),
                art("logical_port_system", "Block", system, "logical_port_package"),
                art("logical_port_bdd", "SysML Block Definition Diagram", packageName, "logical_port_package")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("logical_port_package", "Package", packageName, "workspace"),
                createElement("logical_port_system", "Block", system, "logical_port_package"),
                createDiagram("logical_port_bdd", "SysML Block Definition Diagram", packageName, "logical_port_package"),
                op("add_to_diagram", "logical_port_system_shape", "diagram_id", ref("logical_port_bdd"),
                        "element_id", ref("logical_port_system"), "x", 360, "y", 140, "width", 320,
                        "height", Math.max(220, 120 + interfaces.size() * 80)),
                op("set_shape_compartments", null, "diagram_id", ref("logical_port_bdd"),
                        "presentation_id", ref("logical_port_system_shape"),
                        "compartments", obj("properties", true, "ports", true, "operations", false, "attributes", false,
                                "stereotype", false))));
        List<String> interfaceKeys = new ArrayList<>();
        for (int i = 1; i <= interfaces.size(); i++) {
            Map<String, Object> def = interfaces.get(i - 1);
            List<Map<String, Object>> flows = J.maps(def.get("flow_properties"));
            String interfaceKey = "interface_block_" + i;
            interfaceKeys.add(interfaceKey);
            // Interfaces alternate left and right of the system block; ports alternate between its left and right edges.
            int x = i % 2 != 0 ? 100 : 760;
            int y = 100 + (i - 1) * 140;
            ops.add(op("create_element", interfaceKey, "type", "InterfaceBlock", "name", def.get("name"),
                    "parent_id", ref("logical_port_package")));
            ops.add(op("add_to_diagram", interfaceKey + "_shape", "diagram_id", ref("logical_port_bdd"),
                    "element_id", ref(interfaceKey), "x", x, "y", y, "width", 220,
                    "height", Math.max(120, 70 + flows.size() * 26)));
            for (int f = 1; f <= flows.size(); f++) {
                String fpKey = interfaceKey + "_flow_property_" + f;
                ops.add(op("create_element", fpKey, "type", "FlowProperty", "name", flows.get(f - 1).get("name"),
                        "parent_id", ref(interfaceKey)));
                // Flow property direction and port type are set through cameo_set_specification after creation.
                ops.add(op("set_specification", fpKey, "element_id", ref(fpKey),
                        "properties", obj("direction", flows.get(f - 1).get("direction"))));
            }
            String portKey = "system_port_" + i;
            int portY = 36 + (i - 1) * 52;
            int portX = i % 2 != 0 ? 12 : 272;
            ops.add(op("create_element", portKey, "type", "Port", "name", def.get("port_name"),
                    "parent_id", ref("logical_port_system")));
            ops.add(op("set_specification", portKey, "element_id", ref(portKey),
                    "properties", obj("type", obj("id", ref(interfaceKey)))));
            ops.add(op("add_to_diagram", portKey + "_shape", "diagram_id", ref("logical_port_bdd"), "element_id", ref(portKey),
                    "container_presentation_id", ref("logical_port_system_shape"), "x", portX, "y", portY,
                    "width", 28, "height", 18));
        }
        return recipe(recipe, arts, List.of(), ops, List.of(new SemanticValidationDefinition("port_boundary_consistency",
                obj("interface_block_ids", refs(interfaceKeys)))));
    }

    /**
     * Context IBD: a context Block containing one typed part per external system, a port pair and a Connector
     * per part, and InformationFlows realized by those connectors. Missing parts or flow names are derived
     * from each other. Ports are typed by the given interface blocks, reused round-robin.
     *
     * @param recipe the recipe entry from the pack JSON
     * @param params materialized recipe parameters
     * @return the runtime recipe
     */
    private static RecipeDefinition logicalIbdTraceability(Map<String, Object> recipe, Map<String, Object> params) {
        String context = J.str(params.get("context_name"));
        String packageName = context + " Context";
        String activityDiagramId = optionalStr(params.get("activity_diagram_id"));
        List<String> interfaceBlockIds = new ArrayList<>();
        for (String s : J.strings(params.get("interface_block_ids"))) {
            if (!s.isEmpty()) {
                interfaceBlockIds.add(s);
            }
        }
        List<String> parts = stringList(params.get("part_names"));
        List<String> flows = stringList(params.get("flow_names"));
        if (parts.isEmpty()) {
            parts = new ArrayList<>();
            if (flows.isEmpty()) {
                parts.add(context + " External System");
            } else {
                for (int i = 1; i <= flows.size(); i++) {
                    parts.add(context + " External System " + i);
                }
            }
        }
        if (flows.isEmpty()) {
            flows = new ArrayList<>();
            for (String p : parts) {
                flows.add(p + " Exchange");
            }
        }
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("logical_context_package", "Package", packageName, "workspace"),
                art("logical_context_block", "Block", context, "logical_context_package"),
                art("logical_context_ibd", "SysML Internal Block Diagram", packageName, "logical_context_block")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("logical_context_package", "Package", packageName, "workspace"),
                createElement("logical_context_block", "Block", context, "logical_context_package"),
                createDiagram("logical_context_ibd", "SysML Internal Block Diagram", packageName, "logical_context_block"),
                op("add_to_diagram", "logical_context_shape", "diagram_id", ref("logical_context_ibd"),
                        "element_id", ref("logical_context_block"), "x", 300, "y", 120, "width", 420,
                        "height", Math.max(260, 150 + parts.size() * 90)),
                op("set_shape_compartments", null, "diagram_id", ref("logical_context_ibd"),
                        "presentation_id", ref("logical_context_shape"),
                        "compartments", obj("properties", true, "ports", true, "operations", false, "attributes", false,
                                "stereotype", false))));
        List<String[]> connectorRefs = new ArrayList<>();
        for (int i = 1; i <= parts.size(); i++) {
            String partName = parts.get(i - 1);
            String externalBlockKey = "external_block_" + i;
            String partKey = "context_part_" + i;
            String partShapeKey = partKey + "_shape";
            String contextPortKey = "context_port_" + i;
            String externalPortKey = "external_port_" + i;
            String contextPortShapeKey = contextPortKey + "_shape";
            String externalPortShapeKey = externalPortKey + "_shape";
            String connectorKey = "context_connector_" + i;
            String interfaceBlockId = interfaceBlockIds.isEmpty() ? null
                    : interfaceBlockIds.get((i - 1) % interfaceBlockIds.size());
            int partY = 30 + (i - 1) * 76;
            int contextPortY = 40 + (i - 1) * 60;
            ops.add(createElement(externalBlockKey, "Block", partName, "logical_context_package"));
            ops.add(createElement(partKey, "Property", partName, "logical_context_block"));
            ops.add(op("set_specification", partKey, "element_id", ref(partKey),
                    "properties", obj("type", obj("id", ref(externalBlockKey)))));
            ops.add(op("add_to_diagram", partShapeKey, "diagram_id", ref("logical_context_ibd"), "element_id", ref(partKey),
                    "container_presentation_id", ref("logical_context_shape"), "x", 24, "y", partY,
                    "width", 180, "height", 56));
            ops.add(createElement(contextPortKey, "Port", defaultPortName(partName) + "_context", "logical_context_block"));
            ops.add(createElement(externalPortKey, "Port", defaultPortName(partName), externalBlockKey));
            if (interfaceBlockId != null) {
                ops.add(op("set_specification", contextPortKey, "element_id", ref(contextPortKey),
                        "properties", obj("type", obj("id", interfaceBlockId))));
                ops.add(op("set_specification", externalPortKey, "element_id", ref(externalPortKey),
                        "properties", obj("type", obj("id", interfaceBlockId))));
            }
            ops.add(op("add_to_diagram", contextPortShapeKey, "diagram_id", ref("logical_context_ibd"),
                    "element_id", ref(contextPortKey), "container_presentation_id", ref("logical_context_shape"),
                    "x", 360, "y", contextPortY, "width", 28, "height", 18));
            ops.add(op("add_to_diagram", externalPortShapeKey, "diagram_id", ref("logical_context_ibd"),
                    "element_id", ref(externalPortKey), "container_presentation_id", ref(partShapeKey),
                    "x", 150, "y", 18, "width", 28, "height", 18));
            // target_part_with_port_id makes the connector end attach to the port as seen through the part (SysML "part.port").
            ops.add(op("create_relationship", connectorKey, "type", "Connector", "name", partName + " Connector",
                    "source_id", ref(contextPortKey), "target_id", ref(externalPortKey),
                    "owner_id", ref("logical_context_block"), "target_part_with_port_id", ref(partKey)));
            ops.add(addPath(connectorKey + "_path", "logical_context_ibd", connectorKey, contextPortShapeKey,
                    externalPortShapeKey));
            ops.add(routePath("logical_context_ibd", connectorKey + "_path"));
            connectorRefs.add(new String[]{connectorKey, contextPortKey, externalPortKey, partKey});
        }
        for (int i = 1; i <= flows.size(); i++) {
            // Flows are assigned to connectors round-robin when there are more flows than parts.
            String[] refsOf = connectorRefs.get((i - 1) % connectorRefs.size());
            String flowKey = "information_flow_" + i;
            ops.add(op("create_relationship", flowKey, "type", "InformationFlow", "name", flows.get(i - 1),
                    "source_id", ref(refsOf[1]), "target_id", ref(refsOf[2]),
                    "owner_id", ref("logical_context_package"), "realizing_connector_id", ref(refsOf[0])));
            ops.add(addPath(flowKey + "_path", "logical_context_ibd", flowKey, refsOf[1] + "_shape", refsOf[2] + "_shape"));
            ops.add(routePath("logical_context_ibd", flowKey + "_path"));
        }
        List<SemanticValidationDefinition> validations = new ArrayList<>();
        // Cross-diagram traceability is only checked when an activity diagram to compare against is provided.
        if (activityDiagramId != null) {
            Map<String, Object> semanticParameters = obj("activity_diagram_id", activityDiagramId,
                    "ibd_diagram_id", ref("logical_context_ibd"));
            if (!interfaceBlockIds.isEmpty()) {
                semanticParameters.put("interface_block_ids", new ArrayList<Object>(interfaceBlockIds));
            }
            validations.add(new SemanticValidationDefinition("cross_diagram_traceability", semanticParameters));
        }
        return recipe(recipe, arts, List.of(), ops, validations);
    }

    /**
     * Allocation BDD: one Block per name, each satisfying the existing requirement at the same position
     * (passed by raw ID); requirement_ref_N artifacts stand for those requirements in conformance checks.
     *
     * @param recipe the recipe entry from the pack JSON
     * @param params materialized recipe parameters
     * @return the runtime recipe
     */
    private static RecipeDefinition requirementsToArchitecture(Map<String, Object> recipe, Map<String, Object> params) {
        String name = J.str(params.get("architecture_name"));
        String packageName = name + " Allocation";
        List<String> requirementIds = stringList(params.get("requirement_ids"));
        List<String> blocks = stringList(params.get("block_names"));
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("allocation_package", "Package", packageName, "workspace"),
                art("allocation_bdd", "SysML Block Definition Diagram", packageName, "allocation_package")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("allocation_package", "Package", packageName, "workspace"),
                createDiagram("allocation_bdd", "SysML Block Definition Diagram", packageName, "allocation_package")));
        List<RelationshipRequirement> rels = new ArrayList<>();
        List<String> requirementRefKeys = new ArrayList<>();
        List<String> blockKeys = new ArrayList<>();
        for (int i = 1; i <= requirementIds.size(); i++) {
            requirementRefKeys.add("requirement_ref_" + i);
            arts.add(art("requirement_ref_" + i, "Requirement", requirementIds.get(i - 1), null));
        }
        for (int i = 1; i <= blocks.size(); i++) {
            String blockKey = "allocated_block_" + i;
            blockKeys.add(blockKey);
            arts.add(art(blockKey, "Block", blocks.get(i - 1), "allocation_package"));
            ops.add(createElement(blockKey, "Block", blocks.get(i - 1), "allocation_package"));
            ops.add(op("add_to_diagram", blockKey + "_shape", "diagram_id", ref("allocation_bdd"), "element_id", ref(blockKey),
                    "x", 160 + (i - 1) * 220, "y", 160));
            if (i - 1 < requirementIds.size()) {
                rels.add(new RelationshipRequirement("Satisfy", blockKey, "requirement_ref_" + i, "satisfies", true));
                ops.add(op("create_relationship", "satisfy_" + i, "type", "Satisfy", "source_id", ref(blockKey),
                        "target_id", requirementIds.get(i - 1), "name", "satisfies"));
            }
        }
        List<SemanticValidationDefinition> validations = new ArrayList<>();
        if (!requirementRefKeys.isEmpty() && !blockKeys.isEmpty()) {
            validations.add(new SemanticValidationDefinition("requirements_to_architecture_trace", obj(
                    "requirement_ids", refs(requirementRefKeys), "architecture_element_ids", refs(blockKeys))));
        }
        return recipe(recipe, arts, rels, ops, validations);
    }

    /** Verification scaffold: one Constraint per requirement ID with a Verify link to it, documenting the method (default "analysis"). */
    private static RecipeDefinition verification(Map<String, Object> recipe, Map<String, Object> params) {
        String name = J.str(params.get("verification_name"));
        String packageName = name + " Verification";
        List<String> requirementIds = stringList(params.get("requirement_ids"));
        List<String> methods = stringList(params.get("verification_methods"));
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("verification_package", "Package", packageName, "workspace")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("verification_package", "Package", packageName, "workspace")));
        List<RelationshipRequirement> rels = new ArrayList<>();
        for (int i = 1; i <= requirementIds.size(); i++) {
            String method = i - 1 < methods.size() ? methods.get(i - 1) : "analysis";
            String key = "verification_case_" + i;
            String caseName = "Verify " + requirementIds.get(i - 1);
            arts.add(art(key, "Constraint", caseName, "verification_package"));
            ops.add(op("create_element", key, "type", "Constraint", "name", caseName,
                    "parent_id", ref("verification_package"), "documentation", "Verification method: " + method));
            rels.add(new RelationshipRequirement("Verify", key, "requirement_ref_" + i, method, true));
            ops.add(op("create_relationship", null, "type", "Verify", "source_id", ref(key),
                    "target_id", requirementIds.get(i - 1), "name", method));
        }
        return recipe(recipe, arts, rels, ops, List.of());
    }

    /** UAF operational starter: an operational package and activity diagram with one Activity per action name placed in a row. */
    private static RecipeDefinition uafOperationalActivity(Map<String, Object> recipe, Map<String, Object> params) {
        String activity = J.str(params.get("activity_name"));
        String packageName = activity + " Operational View";
        List<String> actions = stringList(params.get("action_names"));
        List<ArtifactRequirement> arts = new ArrayList<>(List.of(
                new ArtifactRequirement("workspace", "Package"),
                art("uaf_operational_package", "Package", packageName, "workspace"),
                art("uaf_operational_diagram", "Activity Diagram", packageName, "uaf_operational_package")));
        List<RecipeOperationDefinition> ops = new ArrayList<>(List.of(
                createElement("uaf_operational_package", "Package", packageName, "workspace"),
                createDiagram("uaf_operational_diagram", "Activity Diagram", packageName, "uaf_operational_package")));
        for (int i = 1; i <= actions.size(); i++) {
            String key = "uaf_action_" + i;
            arts.add(art(key, "Activity", actions.get(i - 1), "uaf_operational_package"));
            ops.add(createElement(key, "Activity", actions.get(i - 1), "uaf_operational_package"));
            ops.add(op("add_to_diagram", key + "_shape", "diagram_id", ref("uaf_operational_diagram"), "element_id", ref(key),
                    "x", 120 + (i - 1) * 180, "y", 140));
        }
        return recipe(recipe, arts, List.of(), ops, List.of());
    }

    // ------------------------------------------------------------------ parameter helpers

    /**
     * List-valued parameter as strings; a falsy value gives an empty list and a bare string a one-item list.
     *
     * @param value parameter value
     * @return the strings
     */
    static List<String> stringList(Object value) {
        if (!J.truthy(value)) {
            return new ArrayList<>();
        }
        return J.strings(value);
    }

    /**
     * Identifier-style name ("Ground Station" -> "ground_station"), used for generated port names.
     *
     * @param value a display name
     * @return the identifier, or "port" when nothing remains
     */
    static String defaultPortName(String value) {
        String normalized = J.strip(value.replaceAll("[^a-zA-Z0-9]+", "_"), "_").toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "port" : normalized;
    }

    /**
     * Maps direction synonyms (input, send, provided, both ...) to SysML in/out/inout; unknown values default
     * to out.
     *
     * @param value direction text, or null for the default
     * @return "in", "out" or "inout"
     */
    static String normalizeFlowPropertyDirection(Object value) {
        String direction = defaultPortName(J.str(J.or(value, "out"))).replace("_", "");
        switch (direction) {
            case "in": case "input": case "incoming": case "receive": case "required":
                return "in";
            case "out": case "output": case "outgoing": case "send": case "provided":
                return "out";
            case "inout": case "bidirectional": case "both":
                return "inout";
            default:
                return "out";
        }
    }

    /**
     * Normalizes the interface_definitions parameter. Accepts a single object or string, or a list of them;
     * strings become interfaces without flow properties, and missing names and port names are generated.
     *
     * @param value the parameter value
     * @return interfaces with name, port_name and flow_properties
     */
    static List<Map<String, Object>> interfaceDefinitions(Object value) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (!J.truthy(value)) {
            return normalized;
        }
        List<Object> raw = value instanceof Map || value instanceof String ? J.arr(value) : J.list(value);
        int index = 0;
        for (Object item : raw) {
            index++;
            String name;
            String portName;
            Object rawFlows;
            Map<String, Object> m = J.map(item);
            if (m != null) {
                name = J.str(J.or(optionalStr(m.get("name")), "Interface " + index));
                String pn = optionalStr(J.or(m.get("port_name"), m.get("portName")));
                portName = pn != null ? pn : defaultPortName(name);
                rawFlows = J.or(m.get("flow_properties"), m.get("flowProperties"));
            } else {
                name = J.str(item);
                portName = defaultPortName(name);
                rawFlows = null;
            }
            List<Object> flowItems = rawFlows instanceof Map || rawFlows instanceof String ? J.arr(rawFlows) : J.list(rawFlows);
            List<Object> flows = new ArrayList<>();
            int f = 0;
            for (Object rawFlow : flowItems) {
                f++;
                Map<String, Object> fm = J.map(rawFlow);
                if (fm != null) {
                    String flowName = optionalStr(J.or(fm.get("name"), fm.get("label")));
                    flows.add(obj("name", flowName != null ? flowName : "Flow " + f,
                            "direction", normalizeFlowPropertyDirection(fm.get("direction"))));
                } else {
                    flows.add(obj("name", J.str(rawFlow), "direction", "out"));
                }
            }
            normalized.add(obj("name", name, "port_name", portName, "flow_properties", flows));
        }
        return normalized;
    }

    /**
     * Requirement IDs as given, or REQ-001, REQ-002 ... generated for each requirement text.
     *
     * @param ids requirement IDs given by the caller
     * @param texts requirement texts
     * @return the IDs to use
     */
    static List<String> coalesceRequirementIds(List<String> ids, List<String> texts) {
        if (!ids.isEmpty()) {
            return new ArrayList<>(ids);
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= texts.size(); i++) {
            out.add(String.format(Locale.ROOT, "REQ-%03d", i));
        }
        return out;
    }

    /**
     * Pairs for relationship fan-out: one-to-many, many-to-one, position by position when the lists are the
     * same length, else each right item paired with left items round-robin.
     *
     * @param left left items
     * @param right right items
     * @return {left, right} pairs; empty when either list is empty
     */
    static List<String[]> broadcastPairs(List<String> left, List<String> right) {
        List<String[]> out = new ArrayList<>();
        if (left.isEmpty() || right.isEmpty()) {
            return out;
        }
        if (left.size() == 1) {
            for (String r : right) {
                out.add(new String[]{left.get(0), r});
            }
        } else if (right.size() == 1) {
            for (String l : left) {
                out.add(new String[]{l, right.get(0)});
            }
        } else if (left.size() == right.size()) {
            for (int i = 0; i < left.size(); i++) {
                out.add(new String[]{left.get(i), right.get(i)});
            }
        } else {
            for (int i = 0; i < right.size(); i++) {
                out.add(new String[]{left.get(i % left.size()), right.get(i)});
            }
        }
        return out;
    }

    /**
     * String form of a value, or null for null or the empty string.
     *
     * @param value any value
     * @return the string, or null
     */
    static String optionalStr(Object value) {
        if (value == null) {
            return null;
        }
        String text = J.str(value);
        return text.isEmpty() ? null : text;
    }

    /**
     * Kind to report for a discovered artifact. Generic live types ("Diagram", "Model") are less specific
     * than the expected kind, so the expected kind is kept in those cases; otherwise the live type wins.
     *
     * @param existingKind the kind recorded so far
     * @param liveKind the kind reported by the model, or null
     * @return the kind to keep
     */
    static String preferredLiveKind(String existingKind, Object liveKind) {
        String liveText = optionalStr(liveKind);
        if (liveText == null) {
            liveText = existingKind;
        }
        String live = liveText.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String existing = existingKind.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (live.equals("diagram") && existing.contains("diagram")) {
            return existingKind;
        }
        if (live.equals("model") && existing.equals("package")) {
            return existingKind;
        }
        return liveText;
    }

    /**
     * root_package_id parameter, else the element bound to the "workspace" artifact.
     *
     * @param params recipe parameters
     * @param artifacts known artifacts
     * @return the root package ID, or null
     */
    static String rootPackageId(Map<String, Object> params, List<ArtifactSnapshot> artifacts) {
        String root = optionalStr(params.get("root_package_id"));
        if (root != null) {
            return root;
        }
        for (ArtifactSnapshot a : artifacts) {
            if ("workspace".equals(a.key()) && a.elementId() != null && !a.elementId().isEmpty()) {
                return a.elementId();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ live discovery

    /** Call an optional bridge method; any failure (including "not implemented") yields null. */
    interface BridgeCall {
        /**
         * Calls the bridge.
         *
         * @return the bridge response
         * @throws Exception any bridge failure
         */
        Map<String, Object> call() throws Exception;
    }

    /**
     * Calls an optional bridge method; any failure yields null. UnsupportedOperationException from a Bridge
     * fake lands here too, and LinkageError covers missing Cameo classes.
     *
     * @param call the bridge call
     * @return the response, or null on any failure
     */
    static Map<String, Object> safe(BridgeCall call) {
        try {
            return call.call();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * Binds required artifacts that already exist in the model, so executing a recipe twice reuses them.
     * Bindings are recomputed per pass, so each pass can find artifacts whose parent was found in the
     * previous one; three passes cover the nesting depth of the built-in recipes (package, owner, diagram).
     *
     * @param recipe the recipe
     * @param artifacts known artifacts
     * @param params recipe parameters
     * @param rootPackageId the root package ID, or null to take it from params or the workspace artifact
     * @param bridge model access
     * @return the artifacts with newly bound ones added, hydrated from the model
     */
    static List<ArtifactSnapshot> discoverLiveArtifacts(RecipeDefinition recipe, List<ArtifactSnapshot> artifacts,
                                                        Map<String, Object> params, String rootPackageId, Bridge bridge) {
        Map<String, ArtifactSnapshot> index = new LinkedHashMap<>();
        for (ArtifactSnapshot a : artifacts) {
            index.put(a.key(), a);
        }
        String workspaceId = rootPackageId != null && !rootPackageId.isEmpty() ? rootPackageId : rootPackageId(params, artifacts);
        if (workspaceId != null && !workspaceId.isEmpty() && !index.containsKey("workspace")) {
            index.put("workspace", new ArtifactSnapshot("workspace", "Package", "Workspace", workspaceId));
        }
        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            Map<String, String> bindings = MethodologyRuntime.bindingsOf(new ArrayList<>(index.values()));
            for (ArtifactRequirement req : recipe.requiredArtifacts()) {
                ArtifactSnapshot existing = index.get(req.key());
                if (existing != null && existing.elementId() != null && !existing.elementId().isEmpty()) {
                    continue;
                }
                ArtifactSnapshot found = findLiveArtifact(req, bindings, workspaceId, bridge);
                if (found != null) {
                    index.put(req.key(), found);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return hydrateLiveArtifacts(new ArrayList<>(index.values()), bridge);
    }

    /**
     * Looks up one required artifact by type and exact name under its bound parent (or the workspace).
     * Only direct children of the parent are accepted, even though the query is recursive.
     *
     * @param req the required artifact
     * @param bindings artifact key to element ID
     * @param workspaceId the workspace package ID, or null
     * @param bridge model access
     * @return the found artifact, or null
     */
    static ArtifactSnapshot findLiveArtifact(ArtifactRequirement req, Map<String, String> bindings, String workspaceId,
                                             Bridge bridge) {
        boolean hasWorkspace = workspaceId != null && !workspaceId.isEmpty();
        if ("workspace".equals(req.key()) && hasWorkspace) {
            return new ArtifactSnapshot("workspace", "Package", "Workspace", workspaceId);
        }
        String parentId = req.parentKey() != null ? bindings.get(req.parentKey()) : null;
        if (parentId == null) {
            parentId = workspaceId;
        }
        if (isDiagramKind(req.kind())) {
            return findLiveDiagram(req, parentId, bridge);
        }
        Map<String, Object> query = obj("type", queryType(req.kind()), "name", req.name(), "package", parentId,
                "recursive", true, "limit", 10, "view", "full");
        Map<String, Object> result = safe(() -> bridge.queryElements(query));
        for (Map<String, Object> element : J.maps(result == null ? null : result.get("elements"))) {
            if (req.name() != null && !req.name().equals(optionalStr(element.get("name")))) {
                continue;
            }
            String owner = optionalStr(element.get("ownerId"));
            if (parentId != null && owner != null && !owner.equals(parentId)) {
                continue;
            }
            return new ArtifactSnapshot(req.key(),
                    J.str(J.or(element.get("humanType"), element.get("type"), req.kind())),
                    J.str(J.or(element.get("name"), req.name(), req.key())),
                    optionalStr(element.get("id")), req.parentKey(), J.strings(element.get("stereotypes")), null, null);
        }
        return null;
    }

    /**
     * Diagram counterpart of findLiveArtifact: matches name, owner and diagram type from cameo_list_diagrams.
     *
     * @param req the required diagram artifact
     * @param parentId expected owner ID, or null
     * @param bridge model access
     * @return the found diagram, or null
     */
    static ArtifactSnapshot findLiveDiagram(ArtifactRequirement req, String parentId, Bridge bridge) {
        Map<String, Object> result = safe(bridge::listDiagrams);
        for (Map<String, Object> diagram : J.maps(result == null ? null : result.get("diagrams"))) {
            if (req.name() != null && !req.name().equals(optionalStr(diagram.get("name")))) {
                continue;
            }
            String owner = optionalStr(diagram.get("ownerId"));
            if (parentId != null && owner != null && !owner.equals(parentId)) {
                continue;
            }
            if (!kindMatches(J.text(diagram.get("type")), req.kind())) {
                continue;
            }
            return new ArtifactSnapshot(req.key(), J.str(J.or(diagram.get("type"), req.kind())),
                    J.str(J.or(diagram.get("name"), req.name(), req.key())), optionalStr(diagram.get("id")),
                    req.parentKey(), null, null, null);
        }
        return null;
    }

    /**
     * Refreshes bound artifacts from the model: kind, name, stereotypes, specification properties (plus
     * constraints), owner-derived parent key and relationships to other bound artifacts.
     *
     * @param artifacts artifacts to refresh; unbound ones are kept as they are
     * @param bridge model access
     * @return the refreshed artifacts
     */
    public static List<ArtifactSnapshot> hydrateLiveArtifacts(List<ArtifactSnapshot> artifacts, Bridge bridge) {
        Map<String, String> bindings = MethodologyRuntime.bindingsOf(artifacts);
        Map<String, String> reverse = reverseBindings(bindings);
        List<ArtifactSnapshot> hydrated = new ArrayList<>();
        for (ArtifactSnapshot artifact : artifacts) {
            if (artifact.elementId() == null) {
                hydrated.add(artifact);
                continue;
            }
            String id = artifact.elementId();
            Map<String, Object> element = J.mapOrEmpty(safe(() -> bridge.getElement(id)));
            Map<String, Object> spec = safe(() -> bridge.getSpecification(id));
            Map<String, Object> properties = new LinkedHashMap<>(artifact.properties());
            if (spec != null) {
                properties.putAll(J.mapOrEmpty(spec.get("properties")));
                if (J.truthy(spec.get("constraints"))) {
                    properties.put("constraints", J.copy(spec.get("constraints")));
                }
            }
            // UseCase subjects are a property, not a relationship; they are turned into "Subject" relationship
            // snapshots below so the use case recipe's Subject requirements can be checked.
            List<Object> subjectIds = new ArrayList<>();
            for (Map<String, Object> item : J.maps(properties.get("subject"))) {
                String sid = optionalStr(item.get("id"));
                if (sid != null) {
                    subjectIds.add(sid);
                }
            }
            if (!subjectIds.isEmpty()) {
                properties.put("subject_ids", subjectIds);
            }
            List<RelationshipSnapshot> relationships = uniqueRelationships(MethodologyRuntime.concat(
                    artifact.relationships(), collectLiveRelationships(artifact.key(), id, reverse, properties, bridge)));
            String parentKey = artifact.parentKey();
            if (parentKey == null || parentKey.isEmpty()) {
                String owner = optionalStr(element.get("ownerId"));
                parentKey = reverse.get(owner == null ? "" : owner);
            }
            List<String> stereotypes = element.containsKey("stereotypes")
                    ? J.strings(element.get("stereotypes")) : artifact.stereotypes();
            hydrated.add(new ArtifactSnapshot(artifact.key(),
                    preferredLiveKind(artifact.kind(), J.or(element.get("humanType"), element.get("type"))),
                    J.str(J.or(element.get("name"), artifact.name())), id, parentKey, stereotypes, properties,
                    relationships));
        }
        return hydrated;
    }

    /**
     * Relationships between this artifact and other bound artifacts (by reverse binding lookup), plus subject
     * links.
     *
     * @param artifactKey the artifact's key
     * @param elementId the artifact's element ID
     * @param reverse element ID to artifact key
     * @param properties the artifact's specification properties
     * @param bridge model access
     * @return the relationships, de-duplicated
     */
    static List<RelationshipSnapshot> collectLiveRelationships(String artifactKey, String elementId,
                                                               Map<String, String> reverse, Map<String, Object> properties,
                                                               Bridge bridge) {
        Map<String, Object> response = safe(() -> bridge.getRelationships(elementId));
        List<RelationshipSnapshot> snapshots = new ArrayList<>();
        for (String bucket : new String[]{"outgoing", "incoming", "undirected"}) {
            for (Map<String, Object> rel : J.maps(response == null ? null : response.get(bucket))) {
                String relId = optionalStr(rel.get("relationshipId"));
                String name = optionalStr(rel.get("name"));
                String type = J.text(J.or(rel.get("type"), rel.get("relationshipType"), ""));
                for (Map<String, Object> source : J.maps(rel.get("sources"))) {
                    String sourceKey = reverse.get(J.text(source.get("id")));
                    if (sourceKey == null) {
                        continue;
                    }
                    for (Map<String, Object> target : J.maps(rel.get("targets"))) {
                        String targetKey = reverse.get(J.text(target.get("id")));
                        if (targetKey == null) {
                            continue;
                        }
                        snapshots.add(new RelationshipSnapshot(type, sourceKey, targetKey, relId, name, null));
                    }
                }
            }
        }
        for (Object subjectId : J.list(properties.get("subject_ids"))) {
            String subjectKey = reverse.get(J.str(subjectId));
            if (subjectKey == null) {
                continue;
            }
            snapshots.add(new RelationshipSnapshot("Subject", subjectKey, artifactKey, null, "subject", null));
        }
        return uniqueRelationships(snapshots);
    }

    /**
     * De-duplicates by type, endpoints and name (case-insensitive on type and endpoints); the last occurrence
     * wins.
     *
     * @param relationships relationships, possibly repeated
     * @return the unique relationships
     */
    static List<RelationshipSnapshot> uniqueRelationships(List<RelationshipSnapshot> relationships) {
        Map<List<String>, RelationshipSnapshot> unique = new LinkedHashMap<>();
        for (RelationshipSnapshot r : relationships) {
            unique.put(Arrays.asList(r.relationshipType().toLowerCase(Locale.ROOT), r.sourceKey().toLowerCase(Locale.ROOT),
                    r.targetKey().toLowerCase(Locale.ROOT), r.name() == null ? "" : r.name()), r);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Whether a kind names a diagram (contains "diagram", or is bdd or ibd).
     *
     * @param kind kind name
     * @return whether it is a diagram kind
     */
    static boolean isDiagramKind(String kind) {
        String k = kind.toLowerCase(Locale.ROOT);
        return k.contains("diagram") || k.equals("bdd") || k.equals("ibd");
    }

    /**
     * Metaclass name to query for a kind. Diagram kinds query "Diagram", and Block (a stereotyped UML Class)
     * queries "Class".
     *
     * @param kind expected kind
     * @return the query type
     */
    static String queryType(String kind) {
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "sysml block definition diagram":
            case "sysml internal block diagram":
            case "sysml requirement diagram":
            case "use case diagram":
                return "Diagram";
            case "block":
                return "Class";
            default:
                return kind;
        }
    }

    /**
     * Whether a live type matches an expected kind, ignoring case, underscores and hyphens. A suffix match
     * lets a qualified live type match a shorter expected kind.
     *
     * @param actual the live type
     * @param expected the expected kind
     * @return whether they match
     */
    static boolean kindMatches(String actual, String expected) {
        String a = actual.toLowerCase(Locale.ROOT).replace("_", " ").replace("-", " ");
        String e = expected.toLowerCase(Locale.ROOT).replace("_", " ").replace("-", " ");
        return a.equals(e) || a.endsWith(e);
    }

    // ------------------------------------------------------------------ evidence

    /**
     * Diagram element IDs to capture as evidence. Diagram artifacts are recognized by key naming convention
     * (..._diagram, ..._bdd, ..._ibd).
     *
     * @param bindings artifact key to element ID
     * @return the diagram IDs
     */
    static List<String> diagramIdsForEvidence(Map<String, String> bindings) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (e.getValue() != null && !e.getValue().isEmpty()
                    && (key.contains("diagram") || key.endsWith("_bdd") || key.endsWith("_ibd"))) {
                ids.add(e.getValue());
            }
        }
        return ids;
    }

    /**
     * Shape lists and image metadata of the given diagrams. Images are requested with
     * {@code include_image=false} so evidence stays compact (use cameo_get_diagram_image to view one).
     *
     * @param diagramIds diagram IDs; duplicates and blanks are skipped
     * @param bridge model access
     * @return one snapshot per diagram, sorted by ID
     */
    static List<Map<String, Object>> captureDiagramSnapshots(List<String> diagramIds, Bridge bridge) {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (String diagramId : new TreeSet<>(diagramIds)) {
            if (diagramId.isEmpty()) {
                continue;
            }
            Map<String, Object> image = safe(() -> bridge.getDiagramImage(diagramId, obj("include_image", false)));
            Map<String, Object> shapes = safe(() -> bridge.listDiagramShapes(diagramId));
            if (image == null && shapes == null) {
                continue;
            }
            Map<String, Object> snapshot = obj("diagramId", diagramId);
            if (image != null) {
                snapshot.put("image", image);
            }
            if (shapes != null) {
                snapshot.put("shapes", shapes);
            }
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    /**
     * Runs the recipe's semantic validators with refs resolved against current bindings. An unresolved
     * ref or a validator exception is reported as a failed check instead of aborting the tool call.
     *
     * @param recipe the recipe
     * @param artifacts current artifacts
     * @param bridge model access
     * @return one result per validator
     */
    static List<Map<String, Object>> runSemanticValidations(RecipeDefinition recipe, List<ArtifactSnapshot> artifacts,
                                                            Bridge bridge) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (recipe.semanticValidations().isEmpty()) {
            return results;
        }
        Map<String, String> bindings = MethodologyRuntime.bindingsOf(artifacts);
        for (SemanticValidationDefinition def : recipe.semanticValidations()) {
            String validatorId = def.validatorId();
            Map<String, Object> resolved;
            try {
                resolved = J.mapOrEmpty(MethodologyRuntime.resolveValue(def.parameters(), bindings));
            } catch (MethodologyRuntime.BindingException e) {
                results.add(failure(validatorId, "binding-resolution", e.getMessage()));
                continue;
            }
            Map<String, Object> result;
            try {
                result = invokeSemanticValidator(validatorId, resolved, bridge);
            } catch (Exception | LinkageError e) {
                results.add(failure(validatorId, "execution",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>(result);
            normalized.putIfAbsent("validatorId", validatorId);
            results.add(normalized);
        }
        return results;
    }

    /** A validator result containing one failed check with the given message. */
    private static Map<String, Object> failure(String validatorId, String checkName, String message) {
        return obj("validatorId", validatorId, "ok", false,
                "checks", J.arr(obj("name", checkName, "ok", false, "details", obj("message", message))));
    }

    /**
     * Dispatches a validator ID to the corresponding {@link SemanticValidation} entry point.
     *
     * @param validatorId validator ID from the recipe definition
     * @param p resolved validator parameters
     * @param bridge model access
     * @return the validator result
     * @throws IllegalArgumentException for an unknown validator ID
     * @throws Exception from the validator or the bridge
     */
    static Map<String, Object> invokeSemanticValidator(String validatorId, Map<String, Object> p, Bridge bridge)
            throws Exception {
        switch (validatorId) {
            case "activity_flow_semantics": {
                Integer depth = J.safeInt(p.containsKey("max_partition_depth") ? p.get("max_partition_depth") : 1);
                return SemanticValidation.activityFlowForDiagram(J.str(p.get("diagram_id")), depth == null ? 1 : depth,
                        J.truthy(p.getOrDefault("allow_stereotype_partition_labels", false)), bridge);
            }
            case "port_boundary_consistency":
                return SemanticValidation.portBoundaryForInterfaces(J.strings(p.get("interface_block_ids")),
                        J.strings(p.get("allow_shared_flow_property_names")), bridge);
            case "requirement_quality": {
                Integer minLen = J.safeInt(p.containsKey("min_text_length") ? p.get("min_text_length") : 20);
                return SemanticValidation.requirementQualityForIds(J.strings(p.get("requirement_ids")),
                        J.truthy(p.getOrDefault("require_id", true)), J.truthy(p.getOrDefault("require_measurement", true)),
                        minLen == null ? 20 : minLen, bridge);
            }
            case "cross_diagram_traceability":
                return SemanticValidation.crossDiagramTraceability(optionalStr(p.get("activity_diagram_id")),
                        J.strings(p.get("interface_block_ids")), optionalStr(p.get("ibd_diagram_id")),
                        J.strings(p.get("requirement_ids")), J.strings(p.get("architecture_element_ids")), bridge);
            case "requirements_to_architecture_trace":
                // Requirement-to-architecture trace is the traceability validator restricted to its requirement pairing.
                return SemanticValidation.crossDiagramTraceability(null, List.of(), null,
                        J.strings(p.get("requirement_ids")), J.strings(p.get("architecture_element_ids")), bridge);
            default:
                throw new IllegalArgumentException("Unsupported semantic validator: " + validatorId);
        }
    }

    /**
     * Evidence section of the bundle: IDs created by the run (classified by receipt shape and operation),
     * raw receipts, hydrated artifacts, semantic results and before/after diagram snapshots.
     *
     * @param artifacts current artifacts
     * @param result the execution result
     * @param beforeSnapshots diagram snapshots taken before execution
     * @param afterDiagramIds diagrams to capture now
     * @param semanticValidations semantic results
     * @param bridge model access
     * @return the evidence map
     */
    static Map<String, Object> buildBridgeEvidence(List<ArtifactSnapshot> artifacts, RecipeExecutionResult result,
                                                   List<Map<String, Object>> beforeSnapshots, List<String> afterDiagramIds,
                                                   List<Map<String, Object>> semanticValidations, Bridge bridge) {
        List<Object> elementIds = new ArrayList<>();
        List<Object> relationshipIds = new ArrayList<>();
        List<Object> presentationIds = new ArrayList<>();
        List<Object> receipts = new ArrayList<>();
        for (OperationReceipt receipt : result.receipts()) {
            Map<String, Object> r = receipt.result();
            receipts.add(MethodologyRuntime.plain(r));
            if (r.containsKey("presentationId")) {
                presentationIds.add(J.str(r.get("presentationId")));
            } else if (r.containsKey("diagramId") && "create_diagram".equals(receipt.operationKind())) {
                elementIds.add(J.str(r.get("diagramId")));
            } else if (r.containsKey("id")) {
                if ("create_relationship".equals(receipt.operationKind())) {
                    relationshipIds.add(J.str(r.get("id")));
                } else {
                    elementIds.add(J.str(r.get("id")));
                }
            }
        }
        List<Object> live = new ArrayList<>();
        for (ArtifactSnapshot a : artifacts) {
            live.add(a.toMap());
        }
        return obj(
                "createdElementIds", elementIds,
                "createdRelationshipIds", relationshipIds,
                "createdPresentationIds", presentationIds,
                "receipts", receipts,
                "liveArtifacts", live,
                "semanticValidations", new ArrayList<Object>(semanticValidations),
                "diagramSnapshots", obj("before", new ArrayList<Object>(beforeSnapshots),
                        "after", captureDiagramSnapshots(afterDiagramIds, bridge)));
    }

    /** Detail keys whose values are listed under a failed semantic check in the review packet. */
    private static final List<String> HIGHLIGHT_KEYS = List.of("missing", "missingIdIds", "blankTextIds", "weakTextIds",
            "missingPortTerms", "missingIbdTerms", "missingRequirementTraceIds", "isolatedActionIds", "unreachableActionIds");

    /**
     * Markdown review packet: header, artifact keys, guidance, evidence counts, findings, semantic results,
     * assumptions.
     *
     * @param pack the pack
     * @param bundle the evidence bundle
     * @return the Markdown text
     */
    static String renderReviewPacket(Map<String, Object> pack, EvidenceBundle bundle) {
        ConformanceReport conformance = bundle.conformance();
        List<String> lines = new ArrayList<>();
        lines.add("# " + J.str(pack.get("title")) + " Review Packet");
        lines.add("");
        lines.add("- Pack: `" + bundle.packId() + "`");
        lines.add("- Recipe: `" + bundle.recipeId() + "`");
        lines.add("- Generated: `" + bundle.generatedAt() + "`");
        lines.add("- Conformance: `" + (conformance.passed() ? "PASS" : "FAIL") + "` (" + conformance.summary() + ")");
        lines.add("");
        lines.add("## Artifact Keys");
        for (String key : bundle.artifactKeys().isEmpty() ? List.of("none") : bundle.artifactKeys()) {
            lines.add("- `" + key + "`");
        }
        Map<String, Object> evidence = bundle.bridgeEvidence();
        boolean hasEvidence = evidence != null && !evidence.isEmpty();
        String missing = String.join(", ", bundle.guidance().missingArtifactKeys());
        lines.add("");
        lines.add("## Workflow Guidance");
        lines.add("- Phase: `" + bundle.guidance().phase() + "`");
        lines.add("- Ready To Execute: `" + (bundle.guidance().readyToExecute() ? "True" : "False") + "`");
        lines.add("- Missing Artifacts: " + (missing.isEmpty() ? "none" : missing));
        lines.add("");
        lines.add("## Bridge Evidence");
        lines.add("- Created Elements: " + (hasEvidence ? J.list(evidence.get("createdElementIds")).size() : 0));
        lines.add("- Created Relationships: " + (hasEvidence ? J.list(evidence.get("createdRelationshipIds")).size() : 0));
        lines.add("- Created Presentations: " + (hasEvidence ? J.list(evidence.get("createdPresentationIds")).size() : 0));
        lines.add("- Diagram Snapshots: " + (hasEvidence
                ? J.list(J.get(evidence.get("diagramSnapshots"), "after")).size() : 0));
        lines.add("");
        lines.add("## Findings");
        if (conformance.findings().isEmpty()) {
            lines.add("- No findings.");
        } else {
            for (ConformanceFinding f : conformance.findings()) {
                lines.add("- `" + f.severity() + "` " + f.ruleId() + ": " + f.message());
            }
        }
        List<Map<String, Object>> semantic = hasEvidence ? J.maps(evidence.get("semanticValidations")) : List.of();
        if (!semantic.isEmpty()) {
            int passed = 0;
            for (Map<String, Object> v : semantic) {
                if (J.truthy(v.get("ok"))) {
                    passed++;
                }
            }
            lines.add("");
            lines.add("## Semantic Validation");
            lines.add("- Validators Run: " + semantic.size());
            lines.add("- Passed: " + passed);
            lines.add("- Failed: " + (semantic.size() - passed));
            for (Map<String, Object> v : semantic) {
                String validatorId = J.str(J.or(v.get("validatorId"), v.get("validationId"), "semantic"));
                List<String> failed = new ArrayList<>();
                for (Map<String, Object> check : J.maps(v.get("checks"))) {
                    if (!J.truthy(check.get("ok"))) {
                        failed.add(J.str(check.get("name")));
                    }
                }
                lines.add("- `" + validatorId + "`: `" + (J.truthy(v.get("ok")) ? "PASS" : "FAIL") + "`"
                        + (failed.isEmpty() ? "" : " (" + String.join(", ", failed) + ")"));
                if (failed.isEmpty()) {
                    continue;
                }
                for (Map<String, Object> check : J.maps(v.get("checks"))) {
                    if (J.truthy(check.get("ok"))) {
                        continue;
                    }
                    Map<String, Object> details = J.map(check.get("details"));
                    if (details == null) {
                        continue;
                    }
                    List<String> highlights = new ArrayList<>();
                    for (String key : HIGHLIGHT_KEYS) {
                        Object value = details.get(key);
                        if (J.truthy(value)) {
                            highlights.add(key + "=" + J.repr(value));
                        }
                    }
                    if (!highlights.isEmpty()) {
                        lines.add("  - `" + J.str(check.get("name")) + "` details: " + String.join("; ", highlights));
                    }
                }
            }
        }
        if (!bundle.assumptions().isEmpty()) {
            lines.add("");
            lines.add("## Assumptions");
            for (String a : bundle.assumptions()) {
                lines.add("- " + a);
            }
        }
        return String.join("\n", lines);
    }
}
