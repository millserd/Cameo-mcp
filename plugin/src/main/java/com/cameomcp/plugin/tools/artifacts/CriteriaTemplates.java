package com.cameomcp.plugin.tools.artifacts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        public final String key;
        /** Raw expression for raw templates; null for DSL templates. */
        public final String expression;
        public final String relationshipType;
        public final String direction;
        public final boolean verifiedWithUiDiff;
        /** Stereotype for DSL templates; null for raw templates. */
        public final String stereotypeName;
        /** DIRECT, OPPOSITE or BOTH (native Direction enum constant) for DSL templates. */
        public final String dslDirection;
        public final String displayName;

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

        public boolean isDsl() {
            return stereotypeName != null;
        }

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

    private static final Map<String, Template> TEMPLATES = build();

    private CriteriaTemplates() {
    }

    public static Template get(String key) {
        return key == null ? null : TEMPLATES.get(key.trim());
    }

    public static Collection<Template> all() {
        return Collections.unmodifiableCollection(TEMPLATES.values());
    }

    /** The cameo_list_relation_map_criteria_templates result. */
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

    private static void raw(Map<String, Template> m, String key, String expression, String type, String direction,
                            boolean verified) {
        m.put(key, new Template(key, expression, type, direction, verified, null, null, null));
    }

    private static void dsl(Map<String, Template> m, String key, String stereotype, String dslDirection,
                            String displayName, String direction, boolean verified) {
        m.put(key, new Template(key, null, stereotype, direction, verified, stereotype, dslDirection, displayName));
    }
}
