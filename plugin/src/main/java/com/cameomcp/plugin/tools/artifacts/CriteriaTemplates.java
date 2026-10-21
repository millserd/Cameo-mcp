package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in Relation Map criteria templates. Raw templates carry the expression text; DSL templates
 * are turned into a native relation expression for the stereotype at apply time (optional module).
 */
public final class CriteriaTemplates {

    /** One template. */
    public static final class Template {
        /** Template key, "relationship.direction", for example "refine.both". */
        public final String key;
        /** Raw expression for raw templates; null for DSL templates. */
        public final String expression;
        /** Relationship type the template follows, for example "Refine". */
        public final String relationshipType;
        /** Direction as reported to agents: sourceToTarget, targetToSource or both. */
        public final String direction;
        /**
         * True when the template's effect was confirmed by diffing settings against one made in the Cameo UI.
         */
        public final boolean verifiedWithUiDiff;
        /** Stereotype for DSL templates; null for raw templates. */
        public final String stereotypeName;
        /** DIRECT, OPPOSITE or BOTH (native Direction enum constant) for DSL templates. */
        public final String dslDirection;
        /** Human-readable name for DSL templates; null for raw templates. */
        public final String displayName;

        /**
         * Creates a template.
         *
         * @param key the template key
         * @param expression the raw expression, or null for DSL templates
         * @param relationshipType the relationship type
         * @param direction the reported direction
         * @param verified whether the template was checked against the Cameo UI
         * @param stereotypeName the DSL stereotype, or null for raw templates
         * @param dslDirection the native Direction constant, or null for raw templates
         * @param displayName the display name, or null
         */
        private Template(String key, String expression, String relationshipType, String direction, boolean verified,
                         String stereotypeName, String dslDirection, String displayName) {
            this.key = key;
            this.expression = expression;
            this.relationshipType = relationshipType;
            this.direction = direction;
            this.verifiedWithUiDiff = verified;
            this.stereotypeName = stereotypeName;
            this.dslDirection = dslDirection;
            this.displayName = displayName;
        }

        /**
         * Whether this is a DSL template (built from a stereotype) rather than a raw expression.
         *
         * @return true for DSL templates
         */
        public boolean isDsl() {
            return stereotypeName != null;
        }

        /**
         * The template as JSON. DSL templates have no stored expression, so "rawExpression" reports
         * "Type:direction" for
         * display only.
         *
         * @return the template JSON
         */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("key", key);
            if (!isDsl()) {
                json.addProperty("rawExpression", expression);
                json.addProperty("expressionKind", "raw");
            } else {
                json.addProperty("rawExpression", relationshipType + ":" + direction);
                json.addProperty("expressionKind", "dslRelation");
                json.addProperty("stereotypeName", stereotypeName);
                json.addProperty("dslDirection", dslDirection);
                json.addProperty("displayName", displayName);
            }
            json.addProperty("relationshipType", relationshipType);
            json.addProperty("direction", direction);
            json.addProperty("verifiedWithUiDiff", verifiedWithUiDiff);
            return json;
        }
    }

    /** All templates by key, in listing order. */
    private static final Map<String, Template> TEMPLATES = build();

    /** Not instantiable; all members are static. */
    private CriteriaTemplates() {
    }

    /**
     * Template by key (surrounding whitespace ignored), or null.
     *
     * @param key the template key, may be null
     * @return the template, or null
     */
    public static Template get(String key) {
        return key == null ? null : TEMPLATES.get(key.trim());
    }

    /**
     * Every template, in listing order.
     *
     * @return an unmodifiable view
     */
    public static Collection<Template> all() {
        return Collections.unmodifiableCollection(TEMPLATES.values());
    }

    /**
     * The cameo_list_relation_map_criteria_templates result.
     *
     * @return {@code count} and {@code templates}
     */
    public static JsonObject listing() {
        JsonArray templates = new JsonArray();
        for (Template t : TEMPLATES.values()) {
            templates.add(t.toJson());
        }
        JsonObject response = new JsonObject();
        response.addProperty("count", templates.size());
        response.add("templates", templates);
        return response;
    }

    /**
     * Builds the template table. Keys are "relationship.direction"; "allocatedTo" is an alias with the same
     * settings as allocate.both.
     *
     * @return key to template, in listing order
     */
    private static Map<String, Template> build() {
        Map<String, Template> m = new LinkedHashMap<>();
        raw(m, "dependency.direct", "Dependency", "Dependency", "both", false);
        raw(m, "abstraction.direct", "Abstraction", "Abstraction", "both", false);
        dsl(m, "refine.sourceToTarget", "Refine", "DIRECT", "Refine source-to-target", "sourceToTarget", false);
        dsl(m, "refine.targetToSource", "Refine", "OPPOSITE", "Refine target-to-source", "targetToSource", true);
        dsl(m, "refine.both", "Refine", "BOTH", "Refine both directions", "both", true);
        dsl(m, "deriveReqt.sourceToTarget", "DeriveReqt", "DIRECT", "DeriveReqt source-to-target", "sourceToTarget", false);
        dsl(m, "deriveReqt.targetToSource", "DeriveReqt", "OPPOSITE", "DeriveReqt target-to-source", "targetToSource", true);
        dsl(m, "deriveReqt.both", "DeriveReqt", "BOTH", "DeriveReqt both directions", "both", true);
        dsl(m, "satisfy.sourceToTarget", "Satisfy", "DIRECT", "Satisfy source-to-target", "sourceToTarget", false);
        dsl(m, "satisfy.targetToSource", "Satisfy", "OPPOSITE", "Satisfy target-to-source", "targetToSource", false);
        dsl(m, "satisfy.both", "Satisfy", "BOTH", "Satisfy relationships", "both", true);
        dsl(m, "allocate.sourceToTarget", "Allocate", "DIRECT", "Allocate source-to-target", "sourceToTarget", false);
        dsl(m, "allocate.targetToSource", "Allocate", "OPPOSITE", "Allocate target-to-source", "targetToSource", false);
        dsl(m, "allocate.both", "Allocate", "BOTH", "Allocated to/from", "both", true);
        dsl(m, "allocatedTo", "Allocate", "BOTH", "Allocated to/from", "both", true);
        return Collections.unmodifiableMap(m);
    }

    /** Registers a raw template whose criterion is the literal expression text. */
    private static void raw(Map<String, Template> m, String key, String expression, String type, String direction,
                            boolean verified) {
        m.put(key, new Template(key, expression, type, direction, verified, null, null, null));
    }

    /** Registers a DSL template; relationshipType is the stereotype name itself. */
    private static void dsl(Map<String, Template> m, String key, String stereotype, String dslDirection,
                            String displayName, String direction, boolean verified) {
        m.put(key, new Template(key, null, stereotype, direction, verified, stereotype, dslDirection, displayName));
    }
}
