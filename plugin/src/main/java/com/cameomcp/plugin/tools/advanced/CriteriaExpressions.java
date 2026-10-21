package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParseException;
import com.cameomcp.json.tree.JsonParser;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Cameo-free criteria expression templates, building and parsing (reference CriteriaHandler,
 * extended with real parsing and validation). Unit-tested without Cameo.
 */
final class CriteriaExpressions {

    /** Canonical direction names, in listing order. */
    static final List<String> DIRECTIONS = Collections.unmodifiableList(
            Arrays.asList("both", "sourceToTarget", "targetToSource"));

    /** Relationship kinds known to Cameo/SysML criteria, in canonical spelling. */
    static final List<String> KNOWN_KINDS = Collections.unmodifiableList(Arrays.asList(
            "Refine", "DeriveReqt", "Satisfy", "Allocate", "Dependency", "Verify", "Trace", "Copy",
            "Abstraction", "Realization", "Usage", "Containment", "Generalization", "Association",
            "InformationFlow", "ItemFlow", "Connector", "Include", "Extend"));

    /** Template ids whose criteria were verified against a UI-created expression via snapshot diff. */
    private static final List<String> UI_VERIFIED = Collections.unmodifiableList(Arrays.asList(
            "refine.targetToSource", "refine.both", "deriveReqt.targetToSource", "deriveReqt.both",
            "satisfy.both", "allocate.both", "allocatedTo"));

    /** Not instantiable; all members are static. */
    private CriteriaExpressions() {
    }

    /**
     * The built-in template table, filtered to templates whose comma-separated targetKinds include target,
     * ignoring case. The table is rebuilt on every call; it is small and keeps the class stateless.
     *
     * @param target relationMap, matrix or table, or null for every template
     * @return the matching template rows
     */
    static JsonArray templates(String target) {
        JsonArray all = new JsonArray();
        all.add(template("refine.targetToSource", "Refine target-to-source", "relationMap", "Refine", "targetToSource", true));
        all.add(template("refine.both", "Refine both directions", "relationMap", "Refine", "both", true));
        all.add(template("deriveReqt.targetToSource", "DeriveReqt target-to-source", "relationMap", "DeriveReqt", "targetToSource", true));
        all.add(template("deriveReqt.both", "DeriveReqt both directions", "relationMap", "DeriveReqt", "both", true));
        all.add(template("satisfy.both", "Satisfy relationships", "relationMap", "Satisfy", "both", true));
        all.add(template("allocate.both", "Allocated to/from", "relationMap", "Allocate", "both", true));
        all.add(template("dependency.direct", "Trace direct dependencies", "relationMap,matrix,table", "Dependency", "both", false));
        all.add(template("satisfy.sourceToTarget", "Satisfy source-to-target", "relationMap", "Satisfy", "sourceToTarget", false));
        all.add(template("allocate.sourceToTarget", "Allocate source-to-target", "relationMap", "Allocate", "sourceToTarget", false));
        if (target == null || target.trim().isEmpty()) {
            return all;
        }
        String t = target.trim().toLowerCase(Locale.ROOT);
        JsonArray filtered = new JsonArray();
        for (JsonElement e : all) {
            for (String kind : e.getAsJsonObject().get("targetKinds").getAsString().split(",")) {
                if (kind.trim().toLowerCase(Locale.ROOT).equals(t)) {
                    filtered.add(e);
                    break;
                }
            }
        }
        return filtered;
    }

    /**
     * One template row. The nested expression's verified flag is recomputed from UI_VERIFIED, matching what
     * build() and parse() report.
     *
     * @param id the template id
     * @param description the human-readable description
     * @param targets comma-separated target kinds
     * @param kind the relationship kind
     * @param direction the canonical direction
     * @param verified the row's verifiedWithUiDiff flag
     * @return the row
     */
    private static JsonObject template(String id, String description, String targets, String kind, String direction,
                                       boolean verified) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("description", description);
        json.addProperty("targetKinds", targets);
        json.addProperty("verifiedWithUiDiff", verified);
        json.addProperty("relationshipKind", kind);
        json.addProperty("direction", direction);
        JsonObject expr = new JsonObject();
        expr.addProperty("mode", "bridge-owned-template");
        expr.addProperty("verifiedWithUiDiff", isUiVerified(id));
        expr.addProperty("relationshipKind", kind);
        expr.addProperty("direction", direction);
        json.add("expression", expr);
        return json;
    }

    /**
     * Whether a template id was confirmed against a criteria made in the Cameo UI.
     *
     * @param id the template id, may be null
     * @return true for the UI-verified ids
     */
    static boolean isUiVerified(String id) {
        return id != null && UI_VERIFIED.contains(id);
    }

    /**
     * The relationship part of a template id, for example "satisfy" from "satisfy.both". Used when build()
     * gets an id that is not in the table.
     *
     * @param id the id, may be null
     * @return the part before the first dot, the whole id when it has no dot, or "" for null or empty
     */
    static String relationshipKindFromId(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        int dot = id.indexOf('.');
        return dot > 0 ? id.substring(0, dot) : id;
    }

    /**
     * Canonical relationship kind (case/spacing-insensitive match against known kinds), or the trimmed input.
     *
     * @param kind the relationship kind, may be null; «guillemets» are removed
     * @return the canonical spelling, the trimmed input when unknown, or null for null
     */
    static String canonicalKind(String kind) {
        if (kind == null) {
            return null;
        }
        // Stereotype-style input such as the guillemet-wrapped name shown in Cameo is accepted.
        String k = kind.trim();
        if (k.startsWith("«") && k.endsWith("»")) {
            k = k.substring(1, k.length() - 1).trim();
        }
        String key = squash(k);
        // Common synonyms that do not squash to a known kind.
        if ("derive".equals(key) || "derivereqt".equals(key) || "derivereq".equals(key)) {
            return "DeriveReqt";
        }
        if ("allocation".equals(key) || "allocated".equals(key) || "allocatedto".equals(key)) {
            return "Allocate";
        }
        for (String known : KNOWN_KINDS) {
            if (squash(known).equals(key)) {
                return known;
            }
        }
        return k;
    }

    /**
     * Whether the kind, after canonicalization, is one of {@link #KNOWN_KINDS}.
     *
     * @param kind the relationship kind, may be null
     * @return true for a known kind
     */
    static boolean isKnownKind(String kind) {
        return kind != null && KNOWN_KINDS.contains(canonicalKind(kind));
    }

    /**
     * Canonical direction, or null when not recognized.
     *
     * @param direction the direction, may be null; blank means both
     * @return both, sourceToTarget or targetToSource, or null
     */
    static String canonicalDirection(String direction) {
        // Blank means "both" (the default); only an unrecognized non-blank value yields null.
        if (direction == null || direction.trim().isEmpty()) {
            return "both";
        }
        switch (squash(direction)) {
            case "both":
            case "bidirectional":
            case "all":
            case "any":
                return "both";
            case "sourcetotarget":
            case "forward":
            case "outgoing":
            case "out":
                return "sourceToTarget";
            case "targettosource":
            case "backward":
            case "reverse":
            case "incoming":
            case "in":
                return "targetToSource";
            default:
                return null;
        }
    }

    /**
     * Lower-cases and drops underscores, hyphens and spaces, so "source_to_target" and "Source To Target"
     * compare equal.
     *
     * @param s the text
     * @return the squashed text
     */
    private static String squash(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    /**
     * Template matching kind + direction, or null.
     *
     * @param kind the relationship kind, may be null
     * @param direction the direction
     * @return the template row, or null
     */
    static JsonObject matchTemplate(String kind, String direction) {
        if (kind == null) {
            return null;
        }
        String k = canonicalKind(kind);
        for (JsonElement e : templates(null)) {
            JsonObject t = e.getAsJsonObject();
            if (t.get("relationshipKind").getAsString().equals(k) && t.get("direction").getAsString().equals(direction)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Looks up a template by id, ignoring case.
     *
     * @param id the template id, may be null
     * @return the template row, or null
     */
    static JsonObject templateById(String id) {
        if (id == null) {
            return null;
        }
        for (JsonElement e : templates(null)) {
            if (e.getAsJsonObject().get("id").getAsString().equalsIgnoreCase(id.trim())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    /**
     * Build an expression. {@code id} (template id) wins when given; otherwise kind + direction,
     * matched against the template table so verified templates are flagged.
     *
     * @param id the template id, or null
     * @param relationshipKind the relationship kind, used when id is null or unknown
     * @param direction the direction, used with the kind
     * @param target the target kind, echoed in the result
     * @return the expression object, flagged verifiedWithUiDiff when it matches a UI-verified template
     * @throws ToolException INVALID_PARAMS for an unknown id with no kind, a missing kind, or a bad direction
     */
    static JsonObject build(String id, String relationshipKind, String direction, String target) {
        // An unknown id is only fatal when no kind was given to fall back on.
        JsonObject tpl = id != null ? templateById(id) : null;
        if (id != null && tpl == null && relationshipKind == null) {
            throw ToolException.invalid("Unknown criteria template '" + id + "'. Use cameo_list_criteria_templates.");
        }
        String kind = relationshipKind != null && !relationshipKind.trim().isEmpty()
                ? canonicalKind(relationshipKind)
                : tpl != null ? tpl.get("relationshipKind").getAsString() : canonicalKind(relationshipKindFromId(id));
        if (kind == null || kind.isEmpty()) {
            throw ToolException.invalid("relationship_kind is required, e.g. Satisfy, Refine, DeriveReqt, Allocate, Verify, Trace.");
        }
        // An explicit direction overrides the template's; otherwise the template's direction is used, then "both".
        String dir = direction == null && tpl != null ? tpl.get("direction").getAsString() : canonicalDirection(direction);
        if (dir == null) {
            throw ToolException.invalid("direction must be one of both, sourceToTarget, targetToSource (got '" + direction + "').");
        }
        if (tpl == null) {
            tpl = matchTemplate(kind, dir);
        }
        JsonObject expression = new JsonObject();
        expression.addProperty("mode", "bridge-owned-template");
        String templateId = tpl != null ? tpl.get("id").getAsString() : null;
        expression.addProperty("verifiedWithUiDiff", isUiVerified(templateId));
        expression.addProperty("relationshipKind", kind);
        expression.addProperty("direction", dir);
        if (templateId != null) {
            expression.addProperty("templateId", templateId);
        }
        if (target != null && !target.trim().isEmpty()) {
            expression.addProperty("target", target.trim());
        }
        JsonArray warnings = new JsonArray();
        if (!isKnownKind(kind)) {
            warnings.add("Relationship kind '" + kind + "' is not a known UML/SysML relationship; check the spelling.");
        }
        // Warn (not fail) when the requested target differs from the diagram kinds the template was captured for.
        if (target != null && tpl != null) {
            boolean ok = false;
            for (String tk : tpl.get("targetKinds").getAsString().split(",")) {
                ok |= tk.trim().equalsIgnoreCase(target.trim());
            }
            if (!ok) {
                warnings.add("Template " + templateId + " was captured for " + tpl.get("targetKinds").getAsString()
                        + ", not " + target.trim() + ".");
            }
        }
        if (warnings.size() > 0) {
            expression.add("warnings", warnings);
        }
        return expression;
    }

    /**
     * Parse an expression given as an object ({relationshipKind, direction} or {id}/{templateId}),
     * a JSON string, a template id ("satisfy.both") or text ("Satisfy:sourceToTarget", "Refine both").
     *
     * @param expression the expression in any of the accepted forms
     * @return the normalized expression with {@code issues}; problems are reported there rather than thrown
     * @throws ToolException INVALID_PARAMS when the expression is missing
     */
    static JsonObject parse(JsonElement expression) {
        JsonObject out = new JsonObject();
        JsonArray issues = new JsonArray();
        if (expression == null || expression.isJsonNull()) {
            throw ToolException.invalid("Missing required parameter 'expression'.");
        }
        String kind = null;
        String direction = null;
        String id = null;
        String target = null;
        String form;
        if (expression.isJsonPrimitive()) {
            String text = expression.getAsString().trim();
            // A JSON object passed as a string is parsed and handled like the object form.
            if (text.startsWith("{")) {
                try {
                    return parse(JsonParser.parseString(text));
                } catch (JsonParseException e) {
                    throw ToolException.invalid("expression looks like JSON but does not parse: " + e.getMessage());
                }
            }
            form = "text";
            if (templateById(text) != null) {
                id = text;
                form = "templateId";
            } else {
                String[] parts = text.split("[:\\s(),/]+");
                if (parts.length == 0 || parts[0].isEmpty()) {
                    throw ToolException.invalid("expression is empty.");
                }
                // Dotted input split only by "/", "," or parentheses (no space or colon) is read as kind.direction on the first dot.
                if (parts.length >= 2 && text.contains(".") && !text.contains(" ") && !text.contains(":")) {
                    String[] dotted = text.split("\\.", 2);
                    kind = dotted[0];
                    direction = dotted[1];
                } else {
                    kind = parts[0];
                    direction = parts.length > 1 ? parts[1] : null;
                    if (parts.length > 2) {
                        issues.add("Ignored trailing text after the direction: '" + text + "'.");
                    }
                }
                // A single token like "Satisfy.both" also splits on the dot.
                if (kind.contains(".") && direction == null) {
                    String[] dotted = kind.split("\\.", 2);
                    kind = dotted[0];
                    direction = dotted[1];
                }
            }
        } else if (expression.isJsonObject()) {
            form = "object";
            JsonObject o = expression.getAsJsonObject();
            // Accept the expression itself, or a wrapper such as a previous build()/parse() result.
            JsonObject inner = o.has("criteriaExpression") && o.get("criteriaExpression").isJsonObject()
                    ? o.getAsJsonObject("criteriaExpression")
                    : o.has("expression") && o.get("expression").isJsonObject() ? o.getAsJsonObject("expression") : o;
            kind = firstString(inner, "relationshipKind", "relationship_kind", "kind", "relationship");
            direction = firstString(inner, "direction");
            id = firstString(inner, "templateId", "template_id", "id");
            target = firstString(inner, "target", "targetKind");
        } else {
            throw ToolException.invalid("expression must be an object or a string.");
        }
        JsonObject tpl = id != null ? templateById(id) : null;
        if (id != null && tpl == null) {
            issues.add("Unknown template id '" + id + "'.");
        }
        if (kind == null && tpl != null) {
            kind = tpl.get("relationshipKind").getAsString();
        }
        if (direction == null && tpl != null) {
            direction = tpl.get("direction").getAsString();
        }
        String dir = canonicalDirection(direction);
        if (dir == null) {
            issues.add("Unrecognized direction '" + direction + "'; expected both, sourceToTarget or targetToSource.");
        }
        String canon = kind == null ? null : canonicalKind(kind);
        if (canon == null || canon.isEmpty()) {
            issues.add("No relationship kind found.");
        } else if (!isKnownKind(canon)) {
            issues.add("Relationship kind '" + canon + "' is not a known UML/SysML relationship.");
        }
        // parsed=true only needs a kind and a direction; an unknown kind is reported in issues but still parses.
        boolean ok = canon != null && !canon.isEmpty() && dir != null;
        if (tpl == null && ok) {
            tpl = matchTemplate(canon, dir);
        }
        out.addProperty("parsed", ok);
        out.addProperty("inputForm", form);
        JsonObject normalized = new JsonObject();
        normalized.addProperty("mode", "bridge-owned-template");
        if (canon != null) {
            normalized.addProperty("relationshipKind", canon);
        }
        if (dir != null) {
            normalized.addProperty("direction", dir);
        }
        if (target != null) {
            normalized.addProperty("target", target);
        }
        String templateId = tpl != null ? tpl.get("id").getAsString() : null;
        if (templateId != null) {
            normalized.addProperty("templateId", templateId);
        }
        normalized.addProperty("verifiedWithUiDiff", isUiVerified(templateId));
        out.add("criteriaExpression", normalized);
        out.addProperty("matchedTemplateId", templateId);
        out.addProperty("verifiedWithUiDiff", isUiVerified(templateId));
        out.add("issues", issues);
        return out;
    }

    /**
     * First non-blank primitive value among the given keys, so callers can send camelCase or snake_case.
     *
     * @param o the object
     * @param keys the keys to try, in order
     * @return the trimmed value, or null
     */
    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                String s = o.get(k).getAsString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }
}
