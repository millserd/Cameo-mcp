package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.Internal;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/** Generic criteria expressions (reference CriteriaHandler). Writes stay gated. */
final class CriteriaService {

    private CriteriaService() {
    }

    /*
     * cameo_get_criteria_capabilities: criteria are bridge-owned templates, always available, but no template
     * write is verified. The native expression classes are probed only for diagnostics; nothing here calls them.
     */
    static JsonObject capabilities() {
        JsonObject response = Support.baseCapabilities("criteria", "bridge-owned-plus-ui-diff");
        response.addProperty("available", true);
        response.addProperty("status", "available");
        response.addProperty("verifiedTemplateWrites", false);
        response.addProperty("sourceOfTruth", "UI snapshot/diff evidence");
        response.addProperty("relationMapRefreshDefault", false);
        response.add("nativeExpressionClasses", Support.classProbe(
                "com.nomagic.magicdraw.expressions.specification.ExpressionSpecification",
                "com.nomagic.magicdraw.expressions.specification.ExpressionSpecificationUtil",
                "com.nomagic.magicdraw.expressions.specification.DSLRelationExpressionSpecification"));
        response.add("directions", Gs.strings(CriteriaExpressions.DIRECTIONS));
        response.add("knownRelationshipKinds", Gs.strings(CriteriaExpressions.KNOWN_KINDS));
        return response;
    }

    // cameo_list_criteria_templates: the template table, optionally filtered by target kind (relationMap, matrix, table).
    static JsonObject templates(JsonObject p) {
        String target = Gs.str(p, "target");
        JsonArray templates = CriteriaExpressions.templates(target);
        JsonObject response = capabilities();
        if (target != null) {
            response.addProperty("target", target);
        }
        response.addProperty("count", templates.size());
        response.add("templates", templates);
        return response;
    }

    // cameo_build_criteria_expression: normalizes template id / kind / direction into an expression object; no model write.
    static JsonObject build(JsonObject p) {
        String id = Gs.str(p, "template_id");
        JsonObject expression = CriteriaExpressions.build(id, Gs.str(p, "relationship_kind"), Gs.str(p, "direction"),
                Gs.str(p, "target"));
        JsonObject response = new JsonObject();
        response.addProperty("built", true);
        response.addProperty("verifiedWithUiDiff", expression.get("verifiedWithUiDiff").getAsBoolean());
        response.addProperty("writePerformed", false);
        response.add("request", Support.request(p));
        response.add("criteriaExpression", expression);
        response.add("capabilities", capabilities());
        return response;
    }

    // cameo_parse_criteria_expression: accepts object, JSON-string or free-text forms and reports issues instead of failing.
    static JsonObject parse(JsonObject p) {
        JsonObject response = CriteriaExpressions.parse(p.get("expression"));
        response.add("request", Support.request(p));
        response.add("capabilities", capabilities());
        return response;
    }

    /*
     * cameo_apply_criteria_template: always refused. target_id is still required first so a malformed call reports
     * the missing argument rather than the gate. Relation Map criteria have their own dedicated tool.
     */
    static JsonObject apply(JsonObject p) {
        Gs.reqStr(p, "target_id");
        JsonObject response = Support.unsupported("criteria",
                "Criteria writes are gated until UI-created native expressions are captured and replayed by snapshot diff.",
                "Create the expression in the Cameo UI, then run cameo_capture_criteria_template_from_diff. For Relation Maps "
                        + "use cameo_set_relation_map_criteria.");
        response.add("request", Support.request(p));
        response.add("capabilities", capabilities());
        throw Support.refusal("UNSUPPORTED", response);
    }

    /*
     * cameo_capture_criteria_template_from_diff: records a before/after snapshot pair for a criteria edit made in the UI.
     * The diff is attached when the snapshot tools can compute it; failures become diffEvidenceError, not a tool error.
     */
    static JsonObject capture(JsonObject p) {
        String before = Gs.reqStr(p, "before_snapshot_id");
        String after = Gs.reqStr(p, "after_snapshot_id");
        JsonObject response = Support.previewAccepted("criteria", Support.request(p),
                "Criteria capture request recorded. Use snapshot IDs and diff evidence to promote this into a verified template.");
        response.addProperty("verifiedWithUiDiff", false);
        response.addProperty("requiresBeforeSnapshotId", true);
        response.addProperty("requiresAfterSnapshotId", true);
        // Attach the snapshot diff as evidence when the snapshot tools are available.
        try {
            Map<String, Object> diff = Internal.call("cameo_diff_snapshots",
                    "before_snapshot_id", before, "after_snapshot_id", after, "include_details", true, "max_changes", 200);
            response.add("diffEvidence", Gs.toElement(diff));
        } catch (ToolException e) {
            response.addProperty("diffEvidenceError", e.code() + ": " + e.getMessage());
        } catch (Exception e) {
            response.addProperty("diffEvidenceError", e.toString());
        }
        response.add("capabilities", capabilities());
        return response;
    }
}
