package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.plugin.tools.analysis.Bridge;
import com.cameomcp.plugin.tools.analysis.J;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.cameomcp.plugin.tools.analysis.J.obj;

/**
 * Methodology runtime: executable recipe definitions, workflow guidance, execution planning,
 * recipe execution through a {@link Bridge}, conformance checks and evidence bundles.
 * Value types serialize to snake_case maps ({@code toMap()}), matching the reference output.
 */
public final class MethodologyRuntime {

    /** Static methods only. */
    private MethodologyRuntime() {
    }

    /** Raised when an operation parameter references an artifact key that has no element binding. */
    public static final class BindingException extends RuntimeException {
        /**
         * Creates the exception.
         *
         * @param message what was not bound
         */
        public BindingException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ value types

    /**
     * An artifact a recipe needs: key, kind, optional exact name/parent, required stereotypes and property
     * values.
     *
     * @param key artifact key, unique within a recipe
     * @param kind expected element or diagram kind
     * @param name exact expected name, or null for any
     * @param parentKey key of the expected owner artifact, or null for any
     * @param stereotypes stereotypes that must be applied; null means none
     * @param properties specification values that must match; null means none
     */
    public record ArtifactRequirement(String key, String kind, String name, String parentKey,
                                      List<String> stereotypes, Map<String, Object> properties) {
        /** Copies the lists so the record is not affected by later changes to the arguments. */
        public ArtifactRequirement {
            stereotypes = stereotypes == null ? List.of() : List.copyOf(stereotypes);
            properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        }

        /**
         * A requirement with only a key and a kind.
         *
         * @param key artifact key
         * @param kind expected kind
         */
        public ArtifactRequirement(String key, String kind) {
            this(key, kind, null, null, null, null);
        }

        /**
         * A requirement without stereotype or property constraints.
         *
         * @param key artifact key
         * @param kind expected kind
         * @param name exact expected name, or null
         * @param parentKey expected owner key, or null
         */
        public ArtifactRequirement(String key, String kind, String name, String parentKey) {
            this(key, kind, name, parentKey, null, null);
        }

        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("key", key, "kind", kind, "name", name, "parent_key", parentKey,
                    "stereotypes", new ArrayList<>(stereotypes), "properties", plain(properties));
        }
    }

    /**
     * A relationship a recipe expects between two artifact keys; optional ones never block.
     *
     * @param relationshipType relationship type, such as "satisfy"
     * @param sourceKey source artifact key
     * @param targetKey target artifact key
     * @param name expected name, or null for any
     * @param required whether a missing relationship blocks the recipe
     */
    public record RelationshipRequirement(String relationshipType, String sourceKey, String targetKey,
                                          String name, boolean required) {
        /**
         * A required relationship.
         *
         * @param relationshipType relationship type
         * @param sourceKey source artifact key
         * @param targetKey target artifact key
         * @param name expected name, or null
         */
        public RelationshipRequirement(String relationshipType, String sourceKey, String targetKey, String name) {
            this(relationshipType, sourceKey, targetKey, name, true);
        }

        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("relationship_type", relationshipType, "source_key", sourceKey, "target_key", targetKey,
                    "name", name, "required", required);
        }
    }

    /**
     * An existing relationship between two artifacts, as reported by the caller or discovered in the model.
     *
     * @param relationshipType relationship type
     * @param sourceKey source artifact key
     * @param targetKey target artifact key
     * @param relationshipId model ID of the relationship, or null
     * @param name relationship name, or null
     * @param guard transition guard, or null
     */
    public record RelationshipSnapshot(String relationshipType, String sourceKey, String targetKey,
                                       String relationshipId, String name, String guard) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("relationship_type", relationshipType, "source_key", sourceKey, "target_key", targetKey,
                    "relationship_id", relationshipId, "name", name, "guard", guard);
        }
    }

    /**
     * An artifact that exists (or is claimed to exist), bound to a model element ID when known.
     *
     * @param key artifact key
     * @param kind element or diagram kind
     * @param name element name
     * @param elementId bound model element ID, or null when not yet created
     * @param parentKey owner artifact key, or null
     * @param stereotypes applied stereotypes; null means none
     * @param properties specification values; null means none
     * @param relationships relationships to other artifacts; null means none
     */
    public record ArtifactSnapshot(String key, String kind, String name, String elementId, String parentKey,
                                   List<String> stereotypes, Map<String, Object> properties,
                                   List<RelationshipSnapshot> relationships) {
        /** Copies the lists so the record is not affected by later changes to the arguments. */
        public ArtifactSnapshot {
            stereotypes = stereotypes == null ? List.of() : List.copyOf(stereotypes);
            properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
            relationships = relationships == null ? List.of() : List.copyOf(relationships);
        }

        /**
         * A snapshot with no parent, stereotypes, properties or relationships.
         *
         * @param key artifact key
         * @param kind element or diagram kind
         * @param name element name
         * @param elementId bound model element ID, or null
         */
        public ArtifactSnapshot(String key, String kind, String name, String elementId) {
            this(key, kind, name, elementId, null, null, null, null);
        }

        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            List<Object> rels = new ArrayList<>();
            for (RelationshipSnapshot r : relationships) {
                rels.add(r.toMap());
            }
            return obj("key", key, "kind", kind, "name", name, "element_id", elementId, "parent_key", parentKey,
                    "stereotypes", new ArrayList<>(stereotypes), "properties", plain(properties), "relationships", rels);
        }
    }

    /**
     * One recipe step: an operation kind (a cameo_ tool) plus parameters that may hold {"ref": key}
     * placeholders.
     *
     * @param kind operation kind, one of {@link #OPERATION_KINDS}
     * @param artifactKey artifact the operation creates or updates, or null
     * @param targetKey secondary artifact key, or null
     * @param parameters tool parameters; values may be {"ref": key} placeholders
     */
    public record RecipeOperationDefinition(String kind, String artifactKey, String targetKey,
                                            Map<String, Object> parameters) {
        /** Replaces null parameters with an empty map. The map is not copied. */
        public RecipeOperationDefinition {
            parameters = parameters == null ? new LinkedHashMap<>() : parameters;
        }

        /**
         * An operation without a target key.
         *
         * @param kind operation kind
         * @param artifactKey artifact the operation creates or updates, or null
         * @param parameters tool parameters
         */
        public RecipeOperationDefinition(String kind, String artifactKey, Map<String, Object> parameters) {
            this(kind, artifactKey, null, parameters);
        }

        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("kind", kind, "artifact_key", artifactKey, "target_key", targetKey, "parameters", plain(parameters));
        }
    }

    /**
     * A semantic validator to run after the recipe (e.g. activity flow, port boundary).
     *
     * @param validatorId validator ID, such as "activity_flow_semantics"
     * @param parameters validator parameters; values may be {"ref": key} placeholders
     */
    public record SemanticValidationDefinition(String validatorId, Map<String, Object> parameters) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("validator_id", validatorId, "parameters", plain(parameters));
        }
    }

    /**
     * Runtime view of a pack recipe: required artifacts/relationships, operations and review material.
     *
     * @param recipeId recipe ID
     * @param name display name
     * @param phase method phase ID
     * @param description recipe goal; null becomes ""
     * @param requiredArtifacts artifacts the recipe needs
     * @param requiredRelationships relationships the recipe needs
     * @param operations steps that create the artifacts
     * @param reviewChecklist review checklist items
     * @param evidenceSections evidence section names
     * @param layoutRecipe layout profile name, or null
     * @param requiredProfiles profiles the recipe needs
     * @param semanticValidations validators run after the recipe
     */
    public record RecipeDefinition(String recipeId, String name, String phase, String description,
                                   List<ArtifactRequirement> requiredArtifacts,
                                   List<RelationshipRequirement> requiredRelationships,
                                   List<RecipeOperationDefinition> operations,
                                   List<String> reviewChecklist, List<String> evidenceSections,
                                   String layoutRecipe, List<String> requiredProfiles,
                                   List<SemanticValidationDefinition> semanticValidations) {
        /** Replaces null lists with empty ones and copies the rest. */
        public RecipeDefinition {
            description = description == null ? "" : description;
            requiredArtifacts = requiredArtifacts == null ? List.of() : List.copyOf(requiredArtifacts);
            requiredRelationships = requiredRelationships == null ? List.of() : List.copyOf(requiredRelationships);
            operations = operations == null ? List.of() : List.copyOf(operations);
            reviewChecklist = reviewChecklist == null ? List.of() : List.copyOf(reviewChecklist);
            evidenceSections = evidenceSections == null ? List.of() : List.copyOf(evidenceSections);
            requiredProfiles = requiredProfiles == null ? List.of() : List.copyOf(requiredProfiles);
            semanticValidations = semanticValidations == null ? List.of() : List.copyOf(semanticValidations);
        }

        /**
         * Returns the keys of the required artifacts, in order.
         *
         * @return the keys of the required artifacts, in order
         */
        public List<String> requiredArtifactKeys() {
            List<String> keys = new ArrayList<>();
            for (ArtifactRequirement r : requiredArtifacts) {
                keys.add(r.key());
            }
            return keys;
        }

        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("recipe_id", recipeId, "name", name, "phase", phase, "description", description,
                    "required_artifacts", maps(requiredArtifacts, ArtifactRequirement::toMap),
                    "required_relationships", maps(requiredRelationships, RelationshipRequirement::toMap),
                    "operations", maps(operations, RecipeOperationDefinition::toMap),
                    "review_checklist", new ArrayList<>(reviewChecklist),
                    "evidence_sections", new ArrayList<>(evidenceSections),
                    "layout_recipe", layoutRecipe,
                    "required_profiles", new ArrayList<>(requiredProfiles),
                    "semantic_validations", maps(semanticValidations, SemanticValidationDefinition::toMap));
        }
    }

    /**
     * Runtime view of a methodology pack: its phases and recipes in order.
     *
     * @param packId pack ID
     * @param name display name
     * @param description pack description; null becomes ""
     * @param phases method phase IDs in order
     * @param recipes recipes in pack order
     * @param reviewChecklist pack-level review checklist
     * @param requiredProfiles profiles the pack needs
     */
    public record PackDefinition(String packId, String name, String description, List<String> phases,
                                 List<RecipeDefinition> recipes, List<String> reviewChecklist,
                                 List<String> requiredProfiles) {
        /** Replaces null lists with empty ones and copies the rest. */
        public PackDefinition {
            description = description == null ? "" : description;
            phases = phases == null ? List.of() : List.copyOf(phases);
            recipes = recipes == null ? List.of() : List.copyOf(recipes);
            reviewChecklist = reviewChecklist == null ? List.of() : List.copyOf(reviewChecklist);
            requiredProfiles = requiredProfiles == null ? List.of() : List.copyOf(requiredProfiles);
        }

        /**
         * Recipe by exact ID.
         *
         * @param recipeId recipe ID
         * @return the recipe
         * @throws IllegalArgumentException for an unknown ID
         */
        public RecipeDefinition recipe(String recipeId) {
            for (RecipeDefinition r : recipes) {
                if (r.recipeId().equals(recipeId)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("Unknown recipe: " + recipeId);
        }

        /**
         * The first recipe whose required artifacts are not all present. When every recipe is satisfied the
         * last one is returned, so guidance always has a recipe to report on.
         *
         * @param completed artifacts that exist
         * @return the recipe, or null for a pack without recipes
         */
        public RecipeDefinition firstPendingRecipe(List<ArtifactSnapshot> completed) {
            for (RecipeDefinition r : recipes) {
                if (!recipeSatisfied(r, completed)) {
                    return r;
                }
            }
            return recipes.isEmpty() ? null : recipes.get(recipes.size() - 1);
        }
    }

    /**
     * What is done and what is still missing for one recipe, with blockers and next actions.
     *
     * @param packId pack ID
     * @param packName pack name
     * @param recipeId recipe ID
     * @param recipeName recipe name
     * @param phase method phase ID
     * @param readyToExecute whether every required artifact is present
     * @param completionRatio share of required artifacts present, 0.0 to 1.0
     * @param completedArtifactKeys keys present
     * @param missingArtifactKeys keys missing or not matching
     * @param missingRelationships descriptions of missing relationships
     * @param blockers what stops the recipe
     * @param recommendedActions suggested next steps
     */
    public record WorkflowGuidance(String packId, String packName, String recipeId, String recipeName, String phase,
                                   boolean readyToExecute, double completionRatio,
                                   List<String> completedArtifactKeys, List<String> missingArtifactKeys,
                                   List<String> missingRelationships, List<String> blockers,
                                   List<String> recommendedActions) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("pack_id", packId, "pack_name", packName, "recipe_id", recipeId, "recipe_name", recipeName,
                    "phase", phase, "ready_to_execute", readyToExecute, "completion_ratio", completionRatio,
                    "completed_artifact_keys", new ArrayList<>(completedArtifactKeys),
                    "missing_artifact_keys", new ArrayList<>(missingArtifactKeys),
                    "missing_relationships", new ArrayList<>(missingRelationships),
                    "blockers", new ArrayList<>(blockers),
                    "recommended_actions", new ArrayList<>(recommendedActions));
        }
    }

    /**
     * A recipe step in a plan: status "pending" or "skipped" (artifact already bound) with an optional
     * reason.
     *
     * @param stepId step ID
     * @param operation the operation
     * @param status "pending" or "skipped"
     * @param reason why the step is skipped, or null
     * @param resolvedParameters parameters with refs resolved where possible
     */
    public record PlannedOperation(String stepId, RecipeOperationDefinition operation, String status, String reason,
                                   Map<String, Object> resolvedParameters) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("step_id", stepId, "operation", operation.toMap(), "status", status, "reason", reason,
                    "resolved_parameters", plain(resolvedParameters));
        }
    }

    /**
     * Ordered steps for executing a recipe, plus the artifact bindings known before execution.
     *
     * @param packId pack ID
     * @param recipeId recipe ID
     * @param recipeName recipe name
     * @param phase method phase ID
     * @param artifactBindings artifact key to element ID before execution
     * @param plannedOperations steps in order
     * @param requiredArtifactKeys keys the recipe needs
     * @param missingArtifactKeys keys not yet present
     * @param requiredRelationships relationships the recipe needs
     * @param readyToExecute whether every required artifact is present
     * @param notes notes for the reviewer
     */
    public record RecipeExecutionPlan(String packId, String recipeId, String recipeName, String phase,
                                      Map<String, String> artifactBindings, List<PlannedOperation> plannedOperations,
                                      List<String> requiredArtifactKeys, List<String> missingArtifactKeys,
                                      List<RelationshipRequirement> requiredRelationships, boolean readyToExecute,
                                      List<String> notes) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("pack_id", packId, "recipe_id", recipeId, "recipe_name", recipeName, "phase", phase,
                    "artifact_bindings", new LinkedHashMap<String, Object>(artifactBindings),
                    "planned_operations", maps(plannedOperations, PlannedOperation::toMap),
                    "required_artifact_keys", new ArrayList<>(requiredArtifactKeys),
                    "missing_artifact_keys", new ArrayList<>(missingArtifactKeys),
                    "required_relationships", maps(requiredRelationships, RelationshipRequirement::toMap),
                    "ready_to_execute", readyToExecute,
                    "notes", new ArrayList<>(notes));
        }
    }

    /**
     * Outcome of one executed or skipped step; the full tool result is kept for the caller.
     *
     * @param stepId step ID
     * @param operationKind operation kind
     * @param status "applied" or "skipped"
     * @param result the tool result, or the skip reason
     */
    public record OperationReceipt(String stepId, String operationKind, String status, Map<String, Object> result) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("step_id", stepId, "operation_kind", operationKind, "status", status, "result", plain(result));
        }
    }

    /**
     * Receipts, final bindings and the artifacts created or updated by a recipe execution.
     *
     * @param packId pack ID
     * @param recipeId recipe ID
     * @param recipeName recipe name
     * @param phase method phase ID
     * @param receipts one receipt per step
     * @param artifactBindings artifact key to element ID after execution
     * @param createdArtifacts artifacts created by the run
     * @param updatedArtifacts artifacts whose specification was set
     * @param notes notes for the reviewer
     */
    public record RecipeExecutionResult(String packId, String recipeId, String recipeName, String phase,
                                        List<OperationReceipt> receipts, Map<String, String> artifactBindings,
                                        List<ArtifactSnapshot> createdArtifacts, List<ArtifactSnapshot> updatedArtifacts,
                                        List<String> notes) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("pack_id", packId, "recipe_id", recipeId, "recipe_name", recipeName, "phase", phase,
                    "receipts", maps(receipts, OperationReceipt::toMap),
                    "artifact_bindings", new LinkedHashMap<String, Object>(artifactBindings),
                    "created_artifacts", maps(createdArtifacts, ArtifactSnapshot::toMap),
                    "updated_artifacts", maps(updatedArtifacts, ArtifactSnapshot::toMap),
                    "notes", new ArrayList<>(notes));
        }
    }

    /**
     * One conformance problem; rule IDs are prefixed by type (artifact:, kind:, relationship:, semantic:
     * ...).
     *
     * @param ruleId rule ID, such as "artifact:requirements_package"
     * @param severity "error" or "warning"
     * @param message human-readable explanation
     * @param artifactKey affected artifact, or null
     * @param relationshipType affected relationship type, or null
     */
    public record ConformanceFinding(String ruleId, String severity, String message, String artifactKey,
                                     String relationshipType) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("rule_id", ruleId, "severity", severity, "message", message, "artifact_key", artifactKey,
                    "relationship_type", relationshipType);
        }
    }

    /**
     * Conformance verdict for a recipe; passed only when there are no findings of any severity.
     *
     * @param packId pack ID
     * @param recipeId recipe ID
     * @param passed true when there are no findings
     * @param findings the findings
     * @param summary one-line summary
     */
    public record ConformanceReport(String packId, String recipeId, boolean passed, List<ConformanceFinding> findings,
                                    String summary) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("pack_id", packId, "recipe_id", recipeId, "passed", passed,
                    "findings", maps(findings, ConformanceFinding::toMap), "summary", summary);
        }
    }

    /**
     * Everything a reviewer needs for one recipe run, serialized into the review packet.
     *
     * @param packId pack ID
     * @param packName pack name
     * @param recipeId recipe ID
     * @param recipeName recipe name
     * @param generatedAt UTC timestamp, ISO 8601 with microseconds
     * @param artifactKeys sorted keys of the final bindings
     * @param guidance workflow guidance after the run
     * @param executionPlan the plan
     * @param executionResult the result
     * @param conformance the conformance report
     * @param checklist review checklist
     * @param assumptions assumptions recorded by the caller
     * @param notes reviewer notes
     * @param bridgeEvidence model evidence (created IDs, receipts, snapshots)
     */
    public record EvidenceBundle(String packId, String packName, String recipeId, String recipeName,
                                 String generatedAt, List<String> artifactKeys, WorkflowGuidance guidance,
                                 RecipeExecutionPlan executionPlan, RecipeExecutionResult executionResult,
                                 ConformanceReport conformance, List<String> checklist, List<String> assumptions,
                                 List<String> notes, Map<String, Object> bridgeEvidence) {
        /**
         * Serializes with snake_case keys.
         *
         * @return a new map
         */
        public Map<String, Object> toMap() {
            return obj("pack_id", packId, "pack_name", packName, "recipe_id", recipeId, "recipe_name", recipeName,
                    "generated_at", generatedAt, "artifact_keys", new ArrayList<>(artifactKeys),
                    "guidance", guidance.toMap(), "execution_plan", executionPlan.toMap(),
                    "execution_result", executionResult.toMap(), "conformance", conformance.toMap(),
                    "checklist", new ArrayList<>(checklist), "assumptions", new ArrayList<>(assumptions),
                    "notes", new ArrayList<>(notes), "bridge_evidence", plain(bridgeEvidence));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Serializer for a value type; used by {@link #maps} to turn record lists into lists of plain maps.
     *
     * @param <T> the value type
     */
    private interface ToMap<T> {
        /**
         * Serializes one value.
         *
         * @param value the value
         * @return its map form
         */
        Map<String, Object> apply(T value);
    }

    /**
     * Serializes each value.
     *
     * @param <T> the value type
     * @param values the values
     * @param fn the serializer
     * @return the maps, in order
     */
    private static <T> List<Object> maps(Collection<T> values, ToMap<T> fn) {
        List<Object> out = new ArrayList<>();
        for (T v : values) {
            out.add(fn.apply(v));
        }
        return out;
    }

    /**
     * Deep copy of map/list values (so serialized output never aliases runtime state).
     *
     * @param value any value
     * @return a deep copy of maps and lists; other values unchanged
     */
    @SuppressWarnings("unchecked")
    static Object plainValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(e.getKey()), plainValue(e.getValue()));
            }
            return out;
        }
        if (value instanceof Collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Collection<Object>) value) {
                out.add(plainValue(item));
            }
            return out;
        }
        return value;
    }

    /**
     * Deep copy of a parameter/property map; null becomes an empty map.
     *
     * @param value a map, or null
     * @return the deep copy
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> plain(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : (Map<String, Object>) plainValue(value);
    }

    /**
     * Case- and whitespace-insensitive key for comparing kinds, names and relationship types.
     *
     * @param value text, or null
     * @return the stripped lower-case text, or ""
     */
    static String normalizeName(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Replace every {@code {"ref": key}} (single-key map) with the bound element id.
     *
     * <p>Maps and lists are copied recursively.
     *
     * @param value a parameter value
     * @param bindings artifact key to element ID
     * @return the resolved value
     * @throws BindingException for a ref with no binding
     */
    @SuppressWarnings("unchecked")
    public static Object resolveValue(Object value, Map<String, String> bindings) {
        if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            if (m.size() == 1 && m.containsKey("ref")) {
                String ref = J.str(m.get("ref"));
                if (!bindings.containsKey(ref)) {
                    throw new BindingException("Unknown artifact binding: " + ref);
                }
                return bindings.get(ref);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                out.put(e.getKey(), resolveValue(e.getValue(), bindings));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                out.add(resolveValue(item, bindings));
            }
            return out;
        }
        return value;
    }

    /** Artifacts by key; a later snapshot with the same key replaces an earlier one. */
    private static Map<String, ArtifactSnapshot> matchingArtifacts(List<ArtifactSnapshot> artifacts) {
        Map<String, ArtifactSnapshot> index = new LinkedHashMap<>();
        for (ArtifactSnapshot a : artifacts) {
            index.put(a.key(), a);
        }
        return index;
    }

    /**
     * The id a bridge operation result refers to (element, relationship, presentation or diagram).
     *
     * @param result a tool result
     * @return the ID, or null when none is found
     */
    public static String resultPrimaryId(Map<String, Object> result) {
        // Tool results differ in shape: a top-level id or presentationId, a nested element/relationship
        // object, a results list of placed shapes, or only a diagramId; checked in that order.
        Object direct = J.or(result.get("id"), result.get("presentationId"));
        if (direct != null) {
            return J.str(direct);
        }
        Map<String, Object> element = J.map(result.get("element"));
        if (element != null && element.get("id") != null) {
            return J.str(element.get("id"));
        }
        Map<String, Object> relationship = J.map(result.get("relationship"));
        if (relationship != null && relationship.get("id") != null) {
            return J.str(relationship.get("id"));
        }
        for (Map<String, Object> entry : J.maps(result.get("results"))) {
            if (entry.get("presentationId") != null) {
                return J.str(entry.get("presentationId"));
            }
        }
        if (result.get("diagramId") != null) {
            return J.str(result.get("diagramId"));
        }
        return null;
    }

    /** Normalized identity of a relationship requirement or snapshot for set membership. */
    private static List<String> relationshipKey(String type, String source, String target, String name) {
        return Arrays.asList(normalizeName(type), normalizeName(source), normalizeName(target), normalizeName(name));
    }

    /**
     * True when every required artifact of the recipe is present and matches.
     *
     * @param recipe the recipe
     * @param completed artifacts that exist
     * @return whether the recipe is satisfied
     */
    static boolean recipeSatisfied(RecipeDefinition recipe, List<ArtifactSnapshot> completed) {
        return missingArtifactRequirements(recipe.requiredArtifacts(), matchingArtifacts(completed)).isEmpty();
    }

    /**
     * Required artifacts not satisfied by the index: missing, wrong kind, wrong name or parent, a
     * required stereotype absent, or a required property value different (compared with Python equality).
     *
     * @param requirements required artifacts
     * @param index artifacts by key
     * @return the unsatisfied requirements
     */
    static List<ArtifactRequirement> missingArtifactRequirements(List<ArtifactRequirement> requirements,
                                                                 Map<String, ArtifactSnapshot> index) {
        List<ArtifactRequirement> missing = new ArrayList<>();
        for (ArtifactRequirement req : requirements) {
            ArtifactSnapshot artifact = index.get(req.key());
            if (artifact == null
                    || !normalizeName(artifact.kind()).equals(normalizeName(req.kind()))
                    || (req.name() != null && !req.name().equals(artifact.name()))
                    || (req.parentKey() != null && !req.parentKey().equals(artifact.parentKey()))
                    || (!req.stereotypes().isEmpty() && !new HashSet<>(artifact.stereotypes()).containsAll(req.stereotypes()))) {
                missing.add(req);
                continue;
            }
            for (Map.Entry<String, Object> e : req.properties().entrySet()) {
                if (!J.pyEquals(artifact.properties().get(e.getKey()), e.getValue())) {
                    missing.add(req);
                    break;
                }
            }
        }
        return missing;
    }

    /**
     * Required relationships with no matching snapshot on any artifact (non-required ones are ignored).
     *
     * @param requirements required relationships
     * @param artifacts artifacts with their relationships
     * @return the missing required relationships
     */
    static List<RelationshipRequirement> missingRelationshipRequirements(List<RelationshipRequirement> requirements,
                                                                         List<ArtifactSnapshot> artifacts) {
        Set<List<String>> all = new HashSet<>();
        for (ArtifactSnapshot a : artifacts) {
            for (RelationshipSnapshot r : a.relationships()) {
                all.add(relationshipKey(r.relationshipType(), r.sourceKey(), r.targetKey(), r.name()));
            }
        }
        List<RelationshipRequirement> missing = new ArrayList<>();
        for (RelationshipRequirement req : requirements) {
            if (!all.contains(relationshipKey(req.relationshipType(), req.sourceKey(), req.targetKey(), req.name()))
                    && req.required()) {
                missing.add(req);
            }
        }
        return missing;
    }

    // ------------------------------------------------------------------ guidance and planning

    /**
     * Guidance for a recipe (or, without recipeId, the first pending one): completion ratio over the
     * required artifacts, blockers for missing artifacts and relationships, and recommended next actions.
     *
     * @param pack the runtime pack
     * @param completed artifacts that exist
     * @param recipeId recipe ID, or null for the first pending recipe
     * @return the guidance
     * @throws IllegalArgumentException for an unknown recipe ID
     */
    public static WorkflowGuidance buildWorkflowGuidance(PackDefinition pack, List<ArtifactSnapshot> completed,
                                                         String recipeId) {
        Map<String, ArtifactSnapshot> index = matchingArtifacts(completed);
        RecipeDefinition recipe = recipeId != null ? pack.recipe(recipeId) : pack.firstPendingRecipe(completed);
        if (recipe == null) {
            throw new IllegalArgumentException("pack must contain at least one recipe");
        }
        List<ArtifactRequirement> missingArtifacts = missingArtifactRequirements(recipe.requiredArtifacts(), index);
        List<RelationshipRequirement> missingRelationships =
                missingRelationshipRequirements(recipe.requiredRelationships(), completed);
        List<String> completedKeys = new ArrayList<>(new TreeSet<>(index.keySet()));
        List<String> requiredKeys = recipe.requiredArtifactKeys();
        List<String> missingKeys = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (ArtifactRequirement r : missingArtifacts) {
            missingKeys.add(r.key());
            blockers.add("missing artifact: " + r.key());
        }
        List<String> missingRelationshipLabels = new ArrayList<>();
        for (RelationshipRequirement r : missingRelationships) {
            blockers.add("missing relationship: " + r.relationshipType() + " " + r.sourceKey() + "->" + r.targetKey());
            missingRelationshipLabels.add(r.relationshipType() + ":" + r.sourceKey() + "->" + r.targetKey());
        }
        double ratio = requiredKeys.isEmpty() ? 1.0
                : J.round((double) (requiredKeys.size() - missingKeys.size()) / requiredKeys.size(), 3);
        return new WorkflowGuidance(pack.packId(), pack.name(), recipe.recipeId(), recipe.name(), recipe.phase(),
                blockers.isEmpty(), ratio, completedKeys, missingKeys, missingRelationshipLabels, blockers,
                recommendedActions(recipe, missingArtifacts, missingRelationships));
    }

    /**
     * Recommended next actions. Without anything missing, the recipe's review checklist becomes the
     * recommended next steps.
     *
     * @param recipe the recipe
     * @param missingArtifacts unsatisfied artifact requirements
     * @param missingRelationships missing relationships
     * @return the actions
     */
    private static List<String> recommendedActions(RecipeDefinition recipe, List<ArtifactRequirement> missingArtifacts,
                                                   List<RelationshipRequirement> missingRelationships) {
        List<String> actions = new ArrayList<>();
        for (ArtifactRequirement r : missingArtifacts) {
            String action = "create " + r.kind() + " '" + (r.name() != null && !r.name().isEmpty() ? r.name() : r.key()) + "'";
            if (r.parentKey() != null) {
                action += " under '" + r.parentKey() + "'";
            }
            actions.add(action);
        }
        for (RelationshipRequirement r : missingRelationships) {
            actions.add("create " + r.relationshipType() + " from '" + r.sourceKey() + "' to '" + r.targetKey() + "'");
        }
        if (actions.isEmpty()) {
            if (!recipe.reviewChecklist().isEmpty()) {
                actions.addAll(recipe.reviewChecklist());
            } else {
                actions.add("Execute recipe '" + recipe.name() + "'");
            }
        }
        return actions;
    }

    /**
     * Plans a recipe run against the artifacts that already exist. create_* steps whose artifact is
     * already bound are skipped, which makes re-running a recipe after a partial failure safe.
     *
     * @param pack the runtime pack
     * @param recipeId recipe ID
     * @param completed artifacts that exist
     * @return the plan
     * @throws IllegalArgumentException for an unknown recipe ID
     */
    public static RecipeExecutionPlan buildRecipeExecutionPlan(PackDefinition pack, String recipeId,
                                                               List<ArtifactSnapshot> completed) {
        RecipeDefinition recipe = pack.recipe(recipeId);
        Map<String, ArtifactSnapshot> index = matchingArtifacts(completed);
        List<ArtifactRequirement> missingArtifacts = missingArtifactRequirements(recipe.requiredArtifacts(), index);
        Set<String> missingKeys = new HashSet<>();
        List<String> missingKeyList = new ArrayList<>();
        for (ArtifactRequirement r : missingArtifacts) {
            missingKeys.add(r.key());
            missingKeyList.add(r.key());
        }
        Map<String, String> bindings = bindingsOf(completed);

        List<PlannedOperation> planned = new ArrayList<>();
        int index1 = 0;
        for (RecipeOperationDefinition op : recipe.operations()) {
            index1++;
            String stepId = recipe.recipeId() + ":" + index1;
            String targetKey = op.artifactKey() != null && !op.artifactKey().isEmpty() ? op.artifactKey() : op.targetKey();
            Map<String, Object> params = new LinkedHashMap<>(op.parameters());
            if (targetKey != null && bindings.containsKey(targetKey) && op.kind().startsWith("create_")) {
                planned.add(new PlannedOperation(stepId, op, "skipped", "artifact '" + targetKey + "' already exists", params));
            // add_* steps for an artifact that does not yet satisfy its requirement stay pending, with a note.
            } else if (targetKey != null && missingKeys.contains(targetKey) && op.kind().startsWith("add_")) {
                planned.add(new PlannedOperation(stepId, op, "pending", "artifact '" + targetKey + "' not yet satisfied", params));
            } else {
                planned.add(new PlannedOperation(stepId, op, "pending", null, params));
            }
        }
        return new RecipeExecutionPlan(pack.packId(), recipe.recipeId(), recipe.name(), recipe.phase(), bindings,
                planned, recipe.requiredArtifactKeys(), missingKeyList, recipe.requiredRelationships(),
                missingArtifacts.isEmpty(), recipe.reviewChecklist());
    }

    /**
     * {key: element_id} for artifacts that have an element id, in artifact order.
     *
     * @param artifacts artifacts
     * @return the bindings
     */
    public static Map<String, String> bindingsOf(List<ArtifactSnapshot> artifacts) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (ArtifactSnapshot a : artifacts) {
            if (a.elementId() != null && !a.elementId().isEmpty()) {
                bindings.put(a.key(), a.elementId());
            }
        }
        return bindings;
    }

    // ------------------------------------------------------------------ execution

    /** Supported recipe operation kinds (each maps to the cameo_&lt;kind&gt; tool). */
    public static final List<String> OPERATION_KINDS = List.of(
            "create_element", "create_relationship", "create_diagram", "add_to_diagram", "add_diagram_paths",
            "set_specification", "set_shape_properties", "set_shape_compartments", "reparent_shapes",
            "route_paths", "apply_profile", "apply_stereotype", "set_usecase_subject");

    /** Raised when a recipe step fails; carries the receipts of the steps that already ran. */
    public static final class StepFailedException extends RuntimeException {
        /** ID of the failed step. */
        private final String stepId;
        /** Kind of the failed operation. */
        private final String operationKind;
        /** Receipts of the steps that ran before the failure. */
        private final List<OperationReceipt> receipts;

        /**
         * Creates the exception; the message names the step and the cause.
         *
         * @param stepId failed step ID
         * @param operationKind failed operation kind
         * @param receipts receipts of earlier steps (copied)
         * @param cause the underlying failure
         */
        public StepFailedException(String stepId, String operationKind, List<OperationReceipt> receipts, Throwable cause) {
            super("Recipe step " + stepId + " (" + operationKind + ") failed: "
                    + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
            this.stepId = stepId;
            this.operationKind = operationKind;
            this.receipts = List.copyOf(receipts);
        }

        /**
         * Returns the failed step ID.
         *
         * @return the failed step ID
         */
        public String stepId() {
            return stepId;
        }

        /**
         * Returns the failed operation kind.
         *
         * @return the failed operation kind
         */
        public String operationKind() {
            return operationKind;
        }

        /**
         * Returns the receipts of the steps that ran before the failure.
         *
         * @return the receipts of the steps that ran before the failure
         */
        public List<OperationReceipt> receipts() {
            return receipts;
        }
    }

    /**
     * Executes the planned steps in order through the bridge. {"ref": key} parameters are resolved
     * against the bindings accumulated so far, so later steps can use elements created earlier.
     * The first failure aborts the run with a StepFailedException carrying the receipts of applied steps;
     * nothing is rolled back automatically.
     *
     * <p>Skipped steps get a "skipped" receipt. After each applied step, the operation's artifact key is
     * bound to the ID the result refers to, so later refs resolve to it.
     *
     * @param plan the plan
     * @param bridge model access; each operation is one tool call
     * @return receipts, final bindings and created and updated artifacts
     * @throws StepFailedException on the first failing step
     */
    @SuppressWarnings("unchecked")
    public static RecipeExecutionResult executeRecipe(RecipeExecutionPlan plan, Bridge bridge) {
        Map<String, String> bindings = new LinkedHashMap<>(plan.artifactBindings());
        List<OperationReceipt> receipts = new ArrayList<>();
        List<ArtifactSnapshot> created = new ArrayList<>();
        List<ArtifactSnapshot> updated = new ArrayList<>();

        for (PlannedOperation step : plan.plannedOperations()) {
            RecipeOperationDefinition op = step.operation();
            if ("skipped".equals(step.status())) {
                receipts.add(new OperationReceipt(step.stepId(), op.kind(), "skipped", obj("reason", step.reason())));
                continue;
            }
            Map<String, Object> resolved;
            Map<String, Object> result;
            try {
                resolved = (Map<String, Object>) resolveValue(step.resolvedParameters(), bindings);
                if (!OPERATION_KINDS.contains(op.kind())) {
                    throw new IllegalArgumentException("Unsupported recipe operation: " + op.kind());
                }
                result = bridge.invoke(op.kind(), resolved);
                if (result == null) {
                    throw new IllegalStateException(op.kind() + "() must return a mapping, got None");
                }
            } catch (RuntimeException e) {
                throw new StepFailedException(step.stepId(), op.kind(), receipts, e);
            } catch (Exception e) {
                throw new StepFailedException(step.stepId(), op.kind(), receipts, e);
            }
            receipts.add(new OperationReceipt(step.stepId(), op.kind(), "applied", new LinkedHashMap<>(result)));

            // Bind the artifact key to the new element (or presentation element) so later refs resolve to it.
            String createdId = resultPrimaryId(result);
            if (op.artifactKey() != null && !op.artifactKey().isEmpty() && createdId != null) {
                bindings.put(op.artifactKey(), createdId);
                Map<String, Object> params = step.resolvedParameters();
                // The snapshot kind is the created element type, PresentationElement for diagram placements, else the operation kind.
                Object kind = params.containsKey("type") ? params.get("type")
                        : ("add_to_diagram".equals(op.kind()) ? "PresentationElement" : op.kind());
                Object name = params.containsKey("name") ? params.get("name") : op.artifactKey();
                created.add(new ArtifactSnapshot(op.artifactKey(), J.str(kind), J.str(name), createdId));
            }
            if ("set_specification".equals(op.kind()) && op.artifactKey() != null && !op.artifactKey().isEmpty()) {
                Object props = resolved.containsKey("properties") ? resolved.get("properties") : new LinkedHashMap<>();
                updated.add(new ArtifactSnapshot(op.artifactKey(), "specification", op.artifactKey(),
                        bindings.get(op.artifactKey()), null, null, J.mapOrEmpty(props), null));
            }
        }
        return new RecipeExecutionResult(plan.packId(), plan.recipeId(), plan.recipeName(), plan.phase(), receipts,
                bindings, created, updated, plan.notes());
    }

    // ------------------------------------------------------------------ conformance

    /**
     * Structural conformance of the current artifacts against a recipe. Missing artifacts, wrong kinds,
     * missing stereotypes, wrong property values and missing relationships are errors; a name mismatch
     * is only a warning.
     *
     * @param recipe the recipe
     * @param current current artifacts
     * @param packId pack ID for the report
     * @return the report
     */
    public static ConformanceReport runConformanceChecks(RecipeDefinition recipe, List<ArtifactSnapshot> current,
                                                         String packId) {
        Map<String, ArtifactSnapshot> index = matchingArtifacts(current);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ArtifactRequirement req : recipe.requiredArtifacts()) {
            ArtifactSnapshot artifact = index.get(req.key());
            if (artifact == null) {
                findings.add(new ConformanceFinding("artifact:" + req.key(), "error",
                        "Missing required artifact '" + req.key() + "'", req.key(), null));
                continue;
            }
            if (!normalizeName(artifact.kind()).equals(normalizeName(req.kind()))) {
                findings.add(new ConformanceFinding("kind:" + req.key(), "error",
                        "Artifact '" + req.key() + "' must be kind '" + req.kind() + "'", req.key(), null));
            }
            if (req.name() != null && !req.name().equals(artifact.name())) {
                findings.add(new ConformanceFinding("name:" + req.key(), "warning",
                        "Artifact '" + req.key() + "' should be named '" + req.name() + "'", req.key(), null));
            }
            Set<String> missingStereotypes = new TreeSet<>(req.stereotypes());
            missingStereotypes.removeAll(artifact.stereotypes());
            for (String stereotype : missingStereotypes) {
                findings.add(new ConformanceFinding("stereotype:" + req.key() + ":" + stereotype, "error",
                        "Artifact '" + req.key() + "' is missing stereotype '" + stereotype + "'", req.key(), null));
            }
            for (Map.Entry<String, Object> e : req.properties().entrySet()) {
                if (!J.pyEquals(artifact.properties().get(e.getKey()), e.getValue())) {
                    findings.add(new ConformanceFinding("property:" + req.key() + ":" + e.getKey(), "error",
                            "Artifact '" + req.key() + "' must set '" + e.getKey() + "' to " + J.repr(e.getValue()),
                            req.key(), null));
                }
            }
        }
        for (RelationshipRequirement req : missingRelationshipRequirements(recipe.requiredRelationships(), current)) {
            findings.add(new ConformanceFinding(
                    "relationship:" + req.relationshipType() + ":" + req.sourceKey() + "->" + req.targetKey(), "error",
                    "Missing relationship '" + req.relationshipType() + "' from '" + req.sourceKey() + "' to '"
                            + req.targetKey() + "'", null, req.relationshipType()));
        }
        boolean passed = findings.isEmpty();
        return new ConformanceReport(packId == null ? "" : packId, recipe.recipeId(), passed, findings,
                passed ? "passed" : findings.size() + " issue(s) found");
    }

    /**
     * One error finding per failed check of each semantic validation result.
     *
     * @param validationResults semantic validation results
     * @return the findings
     */
    public static List<ConformanceFinding> semanticValidationFindings(List<Map<String, Object>> validationResults) {
        List<ConformanceFinding> findings = new ArrayList<>();
        for (Map<String, Object> validation : validationResults) {
            String validatorId = J.str(J.or(validation.get("validatorId"), validation.get("validationId"), "semantic"));
            for (Object c : J.list(validation.get("checks"))) {
                Map<String, Object> check = J.map(c);
                if (check == null || J.truthy(check.get("ok"))) {
                    continue;
                }
                String checkName = J.str(J.or(check.get("name"), "check"));
                findings.add(new ConformanceFinding("semantic:" + validatorId + ":" + checkName, "error",
                        semanticFailureMessage(validatorId, checkName, check.get("details")), null, null));
            }
        }
        return findings;
    }

    /**
     * The report with extra findings appended and passed/summary recomputed.
     *
     * @param report the original report
     * @param extra findings to add
     * @return a new report
     */
    public static ConformanceReport extendConformanceReport(ConformanceReport report, List<ConformanceFinding> extra) {
        List<ConformanceFinding> combined = new ArrayList<>(report.findings());
        combined.addAll(extra);
        boolean passed = combined.isEmpty();
        return new ConformanceReport(report.packId(), report.recipeId(), passed, combined,
                passed ? "passed" : combined.size() + " issue(s) found");
    }

    /**
     * Metric keys that hold the offending items of a failed check, in the order they are tried for messages.
     */
    private static final List<String> DETAIL_KEYS = List.of(
            "isolatedActionIds", "unreachableActionIds", "containerOnlyPartitions", "stereotypeStyleNames",
            "duplicateFlowProperties", "directionConflicts", "orphanPropertyIds", "missingDirectionIds",
            "missingIdIds", "blankTextIds", "weakTextIds", "missingPortTerms", "missingIbdTerms",
            "missingRequirementTraceIds", "missing");

    /**
     * Short message for a failed semantic check: the check's own message if present, else the first
     * three offending items from the first non-empty detail key, else a generic line.
     *
     * @param validatorId validator ID
     * @param checkName failed check name
     * @param details the check's details
     * @return the message
     */
    static String semanticFailureMessage(String validatorId, String checkName, Object details) {
        Map<String, Object> detailMap = J.map(details);
        if (detailMap != null) {
            Object message = detailMap.get("message");
            if (message instanceof String && !((String) message).strip().isEmpty()) {
                return ((String) message).strip();
            }
        } else {
            detailMap = Collections.emptyMap();
        }
        for (String key : DETAIL_KEYS) {
            Object value = detailMap.get(key);
            if (value instanceof Map && !((Map<?, ?>) value).isEmpty()) {
                List<String> preview = new ArrayList<>();
                for (Object k : ((Map<?, ?>) value).keySet()) {
                    if (preview.size() == 3) {
                        break;
                    }
                    preview.add(J.str(k));
                }
                return validatorId + " check '" + checkName + "' failed: " + String.join(", ", preview);
            }
            if (value instanceof List && !((List<?>) value).isEmpty()) {
                List<?> list = (List<?>) value;
                List<String> preview = new ArrayList<>();
                for (int i = 0; i < Math.min(3, list.size()); i++) {
                    preview.add(J.str(list.get(i)));
                }
                return validatorId + " check '" + checkName + "' failed: " + String.join(", ", preview)
                        + (list.size() > 3 ? "..." : "");
            }
        }
        return validatorId + " check '" + checkName.replace("-", " ") + "' failed";
    }

    // ------------------------------------------------------------------ evidence

    /**
     * Timestamp format with a fixed six-digit fraction; Python's isoformat would omit the fraction when it is
     * exactly zero.
     */
    private static final DateTimeFormatter ISO_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    /**
     * {@code datetime.now(timezone.utc).isoformat()}.
     *
     * @return the current UTC time, ISO 8601 with microseconds
     */
    static String utcNowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).format(ISO_MICROS);
    }

    /**
     * Assembles the evidence bundle; artifact keys are the sorted keys of the final bindings.
     *
     * @param pack the runtime pack
     * @param recipe the recipe
     * @param guidance workflow guidance
     * @param plan the plan
     * @param result the execution result
     * @param conformance the conformance report
     * @param assumptions assumptions, or null
     * @param notes notes, or null
     * @param bridgeEvidence model evidence, or null
     * @return the bundle
     */
    public static EvidenceBundle buildEvidenceBundle(PackDefinition pack, RecipeDefinition recipe,
                                                     WorkflowGuidance guidance, RecipeExecutionPlan plan,
                                                     RecipeExecutionResult result, ConformanceReport conformance,
                                                     List<String> assumptions, List<String> notes,
                                                     Map<String, Object> bridgeEvidence) {
        List<String> keys = new ArrayList<>(new TreeSet<>(result.artifactBindings().keySet()));
        return new EvidenceBundle(pack.packId(), pack.name(), recipe.recipeId(), recipe.name(), utcNowIso(), keys,
                guidance, plan, result, conformance, recipe.reviewChecklist(),
                assumptions == null ? List.of() : List.copyOf(assumptions),
                notes == null ? List.of() : List.copyOf(notes),
                bridgeEvidence == null ? new LinkedHashMap<>() : new LinkedHashMap<>(bridgeEvidence));
    }

    /**
     * New list with the elements of a followed by those of b.
     *
     * @param <T> element type
     * @param a first list
     * @param b second list
     * @return the new list
     */
    static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * Insertion-ordered set copy.
     *
     * @param values the values
     * @return a new LinkedHashSet
     */
    static Set<String> orderedSet(Collection<String> values) {
        return new LinkedHashSet<>(values);
    }
}
