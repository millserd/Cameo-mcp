package com.cameomcp.plugin.tools.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cameomcp.plugin.tools.analysis.J.normalizedKey;
import static com.cameomcp.plugin.tools.analysis.J.normalizedText;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static com.cameomcp.plugin.tools.analysis.J.truthy;

/**
 * Turns semantic validation findings into previewable remediation receipts and a patch plan.
 * Never mutates the model: it reads through the {@link Bridge} and proposes follow-up actions.
 */
public final class AutoRemediation {

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private AutoRemediation() {
    }

    // ------------------------------------------------------------------ helpers

    static Set<String> tokenize(Object value) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(normalizedKey(value));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    static String slugify(Object value) {
        String slug = J.strip(NON_SLUG.matcher(normalizedKey(value)).replaceAll("-"), "-");
        return slug.isEmpty() ? "item" : slug;
    }

    static List<String> uniqueValues(List<?> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (Object v : values) {
            String t = normalizedText(v);
            if (!t.isEmpty()) {
                seen.add(t);
            }
        }
        return new ArrayList<>(seen);
    }

    static int[] termSimilarity(String left, String right) {
        Set<String> l = tokenize(left);
        Set<String> r = tokenize(right);
        int overlap = 0;
        for (String t : l) {
            if (r.contains(t)) {
                overlap++;
            }
        }
        int sharedPrefix = !l.isEmpty() && !r.isEmpty() && l.iterator().next().equals(r.iterator().next()) ? 1 : 0;
        Set<String> union = new LinkedHashSet<>(l);
        union.addAll(r);
        return new int[]{overlap, sharedPrefix, -union.size()};
    }

    static String bestTermMatch(String term, List<String> candidates) {
        List<Object[]> scored = new ArrayList<>();
        for (String c : candidates) {
            if (!normalizedText(c).isEmpty()) {
                scored.add(new Object[]{c, termSimilarity(term, c)});
            }
        }
        if (scored.isEmpty()) {
            return null;
        }
        Comparator<Object[]> cmp = (a, b) -> {
            int[] x = (int[]) a[1];
            int[] y = (int[]) b[1];
            for (int i = 0; i < 3; i++) {
                if (x[i] != y[i]) {
                    return Integer.compare(x[i], y[i]);
                }
            }
            return 0;
        };
        scored.sort(cmp.reversed());
        return (String) scored.get(0)[0];
    }

    static Map<String, Map<String, Object>> elementLookup(List<Map<String, Object>> elements) {
        Map<String, Map<String, Object>> lookup = new LinkedHashMap<>();
        for (Map<String, Object> e : elements) {
            String id = J.text(J.or(e.get("id"), e.get("elementId")));
            if (!id.isEmpty()) {
                lookup.put(id, new LinkedHashMap<>(e));
            }
        }
        return lookup;
    }

    static String targetName(Map<String, Object> payload) {
        return normalizedText(J.or(payload.get("name"), payload.get("elementName"), payload.get("humanType"),
                payload.get("type"), payload.get("id"), payload.get("elementId"), ""));
    }

    static String receiptId(String prefix, Object... parts) {
        List<String> slugs = new ArrayList<>();
        for (Object p : parts) {
            if (!normalizedText(p).isEmpty()) {
                slugs.add(slugify(p));
            }
        }
        return slugs.isEmpty() ? prefix : prefix + "-" + String.join("-", slugs);
    }

    static Map<String, Object> preview(Map<String, Object> before, Map<String, Object> after, String note) {
        Map<String, Object> p = obj("before", new LinkedHashMap<>(before), "after", new LinkedHashMap<>(after));
        if (note != null && !note.isEmpty()) {
            p.put("note", note);
        }
        return p;
    }

    private static Map<String, Object> step(String receiptId, String kind, Object target, Object preview,
                                            Object applyHint, double confidence) {
        return obj("stepId", receiptId, "kind", kind, "target", target, "preview", preview,
                "applyHint", applyHint, "confidence", confidence, "receiptId", receiptId);
    }

    private static Map<String, Object> metricsOf(Map<String, Object> validation) {
        return J.mapOrEmpty(validation == null ? null : validation.get("metrics"));
    }

    // ------------------------------------------------------------------ receipt builders

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>>[] activityReceipts(Map<String, Object> validation) {
        List<Map<String, Object>> receipts = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        if (!truthy(validation)) {
            return new List[]{receipts, steps};
        }
        Map<String, Object> metrics = metricsOf(validation);
        Map<String, Map<String, Object>> elements = elementLookup(J.maps(validation.get("elements")));
        List<String> initialIds = J.strings(metrics.get("initialNodeIds"));
        List<String> actionTerms = new ArrayList<>();
        for (Map<String, Object> e : elements.values()) {
            if (normalizedKey(J.or(e.get("type"), e.get("humanType"), e.get("elementType"))).contains("action")) {
                actionTerms.add(normalizedText(J.or(e.get("name"), e.get("elementName"), e.get("id"), "")));
            }
        }
        List<String> anchorIds = uniqueValues(initialIds);
        for (String actionId : uniqueValues(J.list(metrics.get("isolatedActionIds")))) {
            Map<String, Object> element = elements.getOrDefault(actionId, obj("id", actionId));
            String name = targetName(element);
            String label = name.isEmpty() ? actionId : name;
            String rid = receiptId("activity", "isolated", label);
            List<Object> anchors = new ArrayList<>();
            for (String anchorId : anchorIds) {
                anchors.add(obj("elementId", anchorId,
                        "name", targetName(elements.getOrDefault(anchorId, obj("id", anchorId)))));
            }
            if (anchors.isEmpty() && !actionTerms.isEmpty()) {
                anchors.add(obj("name", actionTerms.get(0)));
            }
            Map<String, Object> pv = preview(
                    obj("action", label, "state", "isolated"),
                    obj("action", label, "state", "connected", "candidateAnchors", anchors),
                    "Add a control-flow path into or out of the action before applying.");
            Map<String, Object> target = obj("diagramId", validation.get("diagramId"), "elementId", actionId, "elementName", name);
            Map<String, Object> hint = obj("operation", "add_control_flow", "preferredAnchors", anchors);
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "activity-flow",
                    "title", "Connect isolated action '" + label + "'",
                    "reason", "The activity graph reports an isolated action with no control-flow path.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("isolatedActionIds", J.arr(actionId),
                            "activityTerms", new ArrayList<>(J.list(metrics.get("actionIds"))))));
            steps.add(step(rid, "add_control_flow", target, pv, hint, 0.78));
        }
        for (String actionId : uniqueValues(J.list(metrics.get("unreachableActionIds")))) {
            boolean already = false;
            for (Map<String, Object> s : steps) {
                Map<String, Object> t = J.map(s.get("target"));
                if (t != null && actionId.equals(t.get("elementId"))) {
                    already = true;
                    break;
                }
            }
            if (already) {
                continue;
            }
            Map<String, Object> element = elements.getOrDefault(actionId, obj("id", actionId));
            String name = targetName(element);
            String label = name.isEmpty() ? actionId : name;
            String rid = receiptId("activity", "unreachable", label);
            Map<String, Object> pv = preview(
                    obj("action", label, "state", "unreachable"),
                    obj("action", label, "state", "reachable",
                            "candidateFix", "connect this action to the existing flow or add an entry path"),
                    "The action is not reachable from the current initial nodes.");
            Map<String, Object> target = obj("diagramId", validation.get("diagramId"), "elementId", actionId, "elementName", name);
            Map<String, Object> hint = obj("operation", "rewire_activity_flow",
                    "suggestion", "Connect the action to an initial path or remove the dead branch.");
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "activity-flow",
                    "title", "Make action '" + label + "' reachable",
                    "reason", "The activity graph reports an unreachable action.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("unreachableActionIds", J.arr(actionId))));
            steps.add(step(rid, "rewire_activity_flow", target, pv, hint, 0.71));
        }
        return new List[]{receipts, steps};
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>>[] portReceipts(Map<String, Object> portValidation,
                                                    Map<String, Object> traceValidation,
                                                    List<String> activityTerms) {
        List<Map<String, Object>> receipts = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        if (!truthy(portValidation)) {
            return new List[]{receipts, steps};
        }
        Map<String, Object> metrics = metricsOf(portValidation);
        List<Map<String, Object>> blocks = J.maps(portValidation.get("interfaceBlocks"));

        Map<String, Object> duplicates = J.mapOrEmpty(metrics.get("duplicateFlowProperties"));
        for (Map.Entry<String, Object> e : duplicates.entrySet()) {
            String name = e.getKey();
            List<Object> owners = J.list(e.getValue());
            List<Object> ownerNames = new ArrayList<>();
            for (Object owner : owners) {
                Map<String, Object> match = null;
                for (Map<String, Object> b : blocks) {
                    if (J.text(b.get("id")).equals(J.str(owner))) {
                        match = b;
                        break;
                    }
                }
                ownerNames.add(targetName(match != null ? match : obj("id", owner)));
            }
            String rid = receiptId("port", "duplicate", name);
            Map<String, Object> target = obj("flowPropertyName", name, "ownerIds", new ArrayList<>(owners), "ownerNames", ownerNames);
            Map<String, Object> pv = preview(
                    obj("name", name, "owners", ownerNames),
                    obj("name", name, "owners", ownerNames, "candidateFixes", J.arr(
                            "keep the shared name and explicitly whitelist it",
                            "rename the interface-side flow property to a distinct term")),
                    "Preview-only. Pick one policy before applying a rename or allow-list update.");
            Map<String, Object> hint = obj("operation", "normalize_shared_flow_property_name",
                    "candidatePolicy", "allow-list or rename");
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "port-boundary",
                    "title", "Normalize duplicate flow property '" + name + "'",
                    "reason", "The same flow-property name is defined on multiple interface blocks.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("duplicateFlowProperties", obj(name, ownerNames))));
            steps.add(step(rid, "normalize_shared_flow_property_name", target, pv, hint, 0.82));
        }

        Map<String, Object> conflicts = J.mapOrEmpty(metrics.get("directionConflicts"));
        for (Map.Entry<String, Object> se : conflicts.entrySet()) {
            String scopeName = se.getKey();
            for (Map.Entry<String, Object> ce : J.mapOrEmpty(se.getValue()).entrySet()) {
                String name = ce.getKey();
                List<Object> directions = new ArrayList<>(J.list(ce.getValue()));
                String rid = receiptId("port", "direction", truthy(scopeName) ? scopeName : "scope", name);
                Map<String, Object> target = obj("scopeName", scopeName, "flowPropertyName", name, "directions", directions);
                Map<String, Object> pv = preview(
                        obj("scope", scopeName, "name", name, "directions", directions),
                        obj("scope", scopeName, "name", name,
                                "directions", directions.isEmpty() ? J.arr() : J.arr(directions.get(0)),
                                "candidateFix", "keep one direction per scope and align the opposite side"),
                        "Preview-only. Standardize the port-side direction before applying.");
                Map<String, Object> hint = obj("operation", "normalize_flow_property_direction",
                        "candidateDirections", directions);
                receipts.add(obj(
                        "receiptId", rid,
                        "status", "preview",
                        "category", "port-boundary",
                        "title", "Normalize direction for '" + name + "' in '"
                                + (truthy(scopeName) ? scopeName : "unnamed scope") + "'",
                        "reason", "The same flow property is exposed with conflicting directions in one scope.",
                        "target", target,
                        "preview", pv,
                        "applyHint", hint,
                        "evidence", obj("directionConflicts", obj(scopeName, obj(name, directions)))));
                steps.add(step(rid, "normalize_flow_property_direction", target, pv, hint, 0.8));
            }
        }

        for (String fpId : uniqueValues(J.list(metrics.get("unnamedPropertyIds")))) {
            String rid = receiptId("port", "unnamed", fpId);
            Map<String, Object> target = obj("flowPropertyId", fpId);
            Map<String, Object> pv = preview(
                    obj("flowPropertyId", fpId, "name", ""),
                    obj("flowPropertyId", fpId, "name", "Suggested Name"),
                    "Choose a domain-accurate label before applying.");
            Map<String, Object> hint = obj("operation", "rename_flow_property",
                    "suggestion", "Give the property a meaningful name that matches the activity wording.");
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "port-boundary",
                    "title", "Name the flow property '" + fpId + "'",
                    "reason", "The flow-property list contains an unnamed entry.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("unnamedPropertyIds", J.arr(fpId))));
            steps.add(step(rid, "rename_flow_property", target, pv, hint, 0.66));
        }

        List<Object> tracePortTerms = traceValidation == null ? new ArrayList<>()
                : new ArrayList<>(J.list(traceValidation.get("portTerms")));
        for (String missing : uniqueValues(J.list(metricsOf(traceValidation).get("missingPortTerms")))) {
            String best = bestTermMatch(missing, activityTerms);
            String preferred = best != null ? best : missing;
            String rid = receiptId("trace", "port", missing);
            Map<String, Object> target = obj("diagramSide", "ports", "missingTerm", missing);
            Map<String, Object> pv = preview(
                    obj("missingTerm", missing, "currentPortTerms", tracePortTerms),
                    obj("missingTerm", missing, "suggestedPortTerm", preferred, "candidateFixes", J.arr(
                            "rename a flow property to the matching activity term",
                            "if the name is already correct, mark the term as intentionally shared")),
                    "Preview-only. This is a term-alignment suggestion, not a live rename.");
            Map<String, Object> hint = obj("operation", "align_port_term", "preferredTerm", preferred);
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "cross-diagram-traceability",
                    "title", "Align port trace term '" + missing + "'",
                    "reason", "The activity term is not represented by the current port-side wording.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("missingPortTerms", J.arr(missing),
                            "activityTerms", new ArrayList<>(activityTerms),
                            "portTerms", tracePortTerms)));
            steps.add(step(rid, "align_port_term", target, pv, hint, best != null ? 0.74 : 0.58));
        }
        return new List[]{receipts, steps};
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>>[] requirementReceipts(Map<String, Object> requirementValidation,
                                                           Map<String, Object> traceValidation,
                                                           List<Map<String, Object>> architectureElements) {
        List<Map<String, Object>> receipts = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        if (!truthy(requirementValidation)) {
            return new List[]{receipts, steps};
        }
        Map<String, Object> metrics = metricsOf(requirementValidation);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : J.maps(requirementValidation.get("requirements"))) {
            Object id = J.or(r.get("id"), r.get("elementId"));
            if (truthy(id)) {
                byId.put(J.str(id), new LinkedHashMap<>(r));
            }
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (architectureElements != null) {
            for (Map<String, Object> e : architectureElements) {
                String id = J.text(J.or(e.get("id"), e.get("elementId")));
                if (id.isEmpty()) {
                    continue;
                }
                candidates.add(obj("elementId", id, "name", targetName(e),
                        "type", normalizedText(J.or(e.get("type"), e.get("humanType"), e.get("elementType"), ""))));
            }
        }

        for (String reqId : uniqueValues(J.list(metrics.get("missingIdIds")))) {
            Map<String, Object> req = byId.getOrDefault(reqId, obj("id", reqId));
            String name = targetName(req);
            String candidateId = "REQ-" + slugify(name.isEmpty() ? reqId : name).toUpperCase(java.util.Locale.ROOT);
            String rid = receiptId("requirement", "missing-id", reqId);
            Map<String, Object> target = obj("elementId", reqId, "name", name);
            Map<String, Object> pv = preview(
                    obj("elementId", reqId, "requirementId", ""),
                    obj("elementId", reqId, "requirementId", candidateId),
                    "Preview-only. Verify the identifier convention before applying.");
            Map<String, Object> hint = obj("operation", "set_requirement_id", "candidateRequirementId", candidateId);
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "requirement-quality",
                    "title", "Assign requirement id to '" + (name.isEmpty() ? reqId : name) + "'",
                    "reason", "The requirement is missing a stable identifier.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("missingIdIds", J.arr(reqId))));
            steps.add(step(rid, "set_requirement_id", target, pv, hint, 0.67));
        }

        for (String reqId : uniqueValues(J.list(metrics.get("blankTextIds")))) {
            Map<String, Object> req = byId.getOrDefault(reqId, obj("id", reqId));
            String name = targetName(req);
            String rid = receiptId("requirement", "blank-text", reqId);
            Map<String, Object> target = obj("elementId", reqId, "name", name);
            Map<String, Object> pv = preview(
                    obj("elementId", reqId, "text", ""),
                    obj("elementId", reqId,
                            "textTemplate", "The system shall <observable behavior> within <measurable threshold>."),
                    "Preview-only. Replace the template with domain-specific language before applying.");
            Map<String, Object> hint = obj("operation", "rewrite_requirement_text",
                    "suggestion", "State the observable behavior and a measurable constraint.");
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "requirement-quality",
                    "title", "Add requirement text for '" + (name.isEmpty() ? reqId : name) + "'",
                    "reason", "The requirement text is blank.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("blankTextIds", J.arr(reqId))));
            steps.add(step(rid, "rewrite_requirement_text", target, pv, hint, 0.63));
        }

        for (String reqId : uniqueValues(J.list(metrics.get("weakTextIds")))) {
            Map<String, Object> req = byId.getOrDefault(reqId, obj("id", reqId));
            String name = targetName(req);
            String rid = receiptId("requirement", "weak-text", reqId);
            Map<String, Object> target = obj("elementId", reqId, "name", name);
            Map<String, Object> pv = preview(
                    obj("elementId", reqId, "textState", "weak"),
                    obj("elementId", reqId,
                            "textTemplate", "The system shall <verb> <object> within <specific threshold>."),
                    "Preview-only. Use a measurable threshold and an observable outcome.");
            Map<String, Object> hint = obj("operation", "rewrite_requirement_text",
                    "suggestion", "Add a measurable threshold and an observable outcome.");
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "requirement-quality",
                    "title", "Strengthen requirement text for '" + (name.isEmpty() ? reqId : name) + "'",
                    "reason", "The requirement text is not sufficiently measurable.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("weakTextIds", J.arr(reqId))));
            steps.add(step(rid, "rewrite_requirement_text", target, pv, hint, 0.65));
        }

        for (String reqId : uniqueValues(J.list(metricsOf(traceValidation).get("missingRequirementTraceIds")))) {
            Map<String, Object> req = byId.getOrDefault(reqId, obj("id", reqId));
            String name = targetName(req);
            Map<String, Object> candidate = null;
            if (!candidates.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Map<String, Object> c : candidates) {
                    names.add((String) c.get("name"));
                }
                String best = bestTermMatch(name.isEmpty() ? reqId : name, names);
                candidate = candidates.get(0);
                for (Map<String, Object> c : candidates) {
                    if (c.get("name").equals(best)) {
                        candidate = c;
                        break;
                    }
                }
            }
            List<Object> targets = candidate != null ? J.arr(candidate)
                    : new ArrayList<>(candidates.subList(0, Math.min(3, candidates.size())));
            String rid = receiptId("trace", "requirement", reqId);
            Map<String, Object> target = obj("elementId", reqId, "name", name);
            Map<String, Object> pv = preview(
                    obj("requirementId", reqId, "traceTargets", J.arr()),
                    obj("requirementId", reqId, "traceTargets", targets),
                    "Preview-only. Choose the final trace target before applying a relationship.");
            Map<String, Object> hint = obj("operation", "add_trace_relationship", "candidateTargets", targets);
            List<Object> archIds = new ArrayList<>();
            for (Map<String, Object> c : candidates) {
                archIds.add(c.get("elementId"));
            }
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "cross-diagram-traceability",
                    "title", "Trace requirement '" + (name.isEmpty() ? reqId : name) + "' to architecture",
                    "reason", "The requirement is not linked to any known architecture element.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("missingRequirementTraceIds", J.arr(reqId), "architectureElementIds", archIds)));
            steps.add(step(rid, "add_trace_relationship", target, pv, hint, candidate != null ? 0.72 : 0.52));
        }
        return new List[]{receipts, steps};
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>>[] ibdReceipts(Map<String, Object> traceValidation, List<String> activityTerms) {
        List<Map<String, Object>> receipts = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        if (!truthy(traceValidation)) {
            return new List[]{receipts, steps};
        }
        List<Object> ibdTerms = new ArrayList<>(J.list(traceValidation.get("ibdTerms")));
        for (String missing : uniqueValues(J.list(metricsOf(traceValidation).get("missingIbdTerms")))) {
            String best = bestTermMatch(missing, activityTerms);
            String preferred = best != null ? best : missing;
            String rid = receiptId("trace", "ibd", missing);
            Map<String, Object> target = obj("diagramSide", "ibd", "missingTerm", missing);
            Map<String, Object> pv = preview(
                    obj("missingTerm", missing, "currentIbdTerms", ibdTerms),
                    obj("missingTerm", missing, "suggestedIbdTerm", preferred, "candidateFixes", J.arr(
                            "rename the item-flow label to the matching activity term",
                            "if the label is deliberate, mark it as intentionally different")),
                    "Preview-only. This is a term-alignment suggestion, not a live rename.");
            Map<String, Object> hint = obj("operation", "align_ibd_term", "preferredTerm", preferred);
            receipts.add(obj(
                    "receiptId", rid,
                    "status", "preview",
                    "category", "cross-diagram-traceability",
                    "title", "Align IBD trace term '" + missing + "'",
                    "reason", "The activity term is not represented by the current IBD wording.",
                    "target", target,
                    "preview", pv,
                    "applyHint", hint,
                    "evidence", obj("missingIbdTerms", J.arr(missing),
                            "activityTerms", new ArrayList<>(activityTerms),
                            "ibdTerms", ibdTerms)));
            steps.add(step(rid, "align_ibd_term", target, pv, hint, best != null ? 0.74 : 0.58));
        }
        return new List[]{receipts, steps};
    }

    // ------------------------------------------------------------------ plan

    public static Map<String, Object> buildRemediationPlan(Map<String, Object> activityValidation,
                                                           Map<String, Object> portValidation,
                                                           Map<String, Object> requirementValidation,
                                                           Map<String, Object> traceValidation,
                                                           List<Map<String, Object>> architectureElements) {
        Map<String, Object> activity = J.copy(activityValidation);
        Map<String, Object> port = J.copy(portValidation);
        Map<String, Object> requirement = J.copy(requirementValidation);
        Map<String, Object> trace = J.copy(traceValidation);

        List<String> activityTerms = J.strings(trace.get("activityTerms"));
        List<Object> portTerms = J.list(trace.get("portTerms"));
        List<Object> ibdTerms = J.list(trace.get("ibdTerms"));

        List<Map<String, Object>>[] a = activityReceipts(activity);
        List<Map<String, Object>>[] p = portReceipts(port, trace, activityTerms);
        List<Map<String, Object>>[] r = requirementReceipts(requirement, trace, architectureElements);
        List<Map<String, Object>>[] i = ibdReceipts(trace, activityTerms);

        List<Object> receipts = new ArrayList<>();
        List<Object> steps = new ArrayList<>();
        for (List<Map<String, Object>>[] bucket : List.of(a, p, r, i)) {
            receipts.addAll(bucket[0]);
        }
        for (List<Map<String, Object>>[] bucket : List.of(a, p, r, i)) {
            steps.addAll(bucket[1]);
        }
        List<Object> checks = new ArrayList<>();
        for (Map<String, Object> v : List.of(activity, port, requirement, trace)) {
            checks.addAll(J.list(v.get("checks")));
        }
        Map<String, Object> summary = obj(
                "issueCount", receipts.size(),
                "receiptCount", receipts.size(),
                "stepCount", steps.size(),
                "activityIssueCount", a[0].size(),
                "portIssueCount", p[0].size(),
                "requirementIssueCount", r[0].size(),
                "ibdIssueCount", i[0].size(),
                "activityTermCount", activityTerms.size(),
                "portTermCount", portTerms.size(),
                "ibdTermCount", ibdTerms.size(),
                "missingRequirementTraceCount", J.list(metricsOf(trace).get("missingRequirementTraceIds")).size());
        Map<String, Object> patchPlan = obj(
                "planId", "cross-diagram-auto-remediation",
                "mode", "preview",
                "summary", summary,
                "steps", steps);
        boolean ok;
        if (!checks.isEmpty()) {
            boolean allOk = true;
            for (Object c : checks) {
                if (!truthy(J.get(c, "ok"))) {
                    allOk = false;
                    break;
                }
            }
            ok = receipts.isEmpty() && allOk;
        } else {
            ok = receipts.isEmpty();
        }
        List<Object> arch = new ArrayList<>();
        if (architectureElements != null) {
            for (Map<String, Object> e : architectureElements) {
                arch.add(new LinkedHashMap<>(e));
            }
        }
        return obj(
                "ok", ok,
                "checks", checks,
                "summary", summary,
                "findings", obj("activity", activity, "ports", port, "requirements", requirement, "traceability", trace),
                "receipts", receipts,
                "patchPlan", patchPlan,
                "architectureElements", arch);
    }

    // ------------------------------------------------------------------ live detection

    /** Diagram snapshot without relationship de-duplication (as the remediation planner reads it). */
    static Map<String, Object> gatherDiagramSnapshot(String diagramId, Bridge bridge) throws Exception {
        Map<String, Object> shapes = SemanticValidation.nonNull(bridge.listDiagramShapes(diagramId));
        List<String> ids = SemanticValidation.shapeElementIds(shapes);
        List<Object> elements = new ArrayList<>();
        for (String id : ids) {
            elements.add(bridge.getElement(id));
        }
        List<Object> relationships = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> response = bridge.getRelationships(id);
            if (response == null) {
                continue;
            }
            for (String bucket : new String[]{"outgoing", "incoming", "undirected"}) {
                for (Map<String, Object> rel : J.maps(response.get(bucket))) {
                    relationships.add(new LinkedHashMap<>(rel));
                }
            }
        }
        return obj("diagramId", diagramId, "diagramShapes", shapes, "elements", elements, "relationships", relationships);
    }

    /** Options for {@link #detect}. */
    public static final class DetectOptions {
        public String activityDiagramId;
        public List<String> interfaceBlockIds = new ArrayList<>();
        public String ibdDiagramId;
        public List<String> requirementIds = new ArrayList<>();
        public List<String> architectureElementIds = new ArrayList<>();
        public List<String> allowSharedFlowPropertyNames;
        public boolean requireId = true;
        public boolean requireMeasurement = true;
        public int minRequirementTextLength = 20;
        public int maxPartitionDepth = 1;
        public boolean allowStereotypePartitionLabels = false;
    }

    public static Map<String, Object> detect(DetectOptions o, Bridge bridge) throws Exception {
        Map<String, Object> activity = new LinkedHashMap<>();
        Map<String, Object> port = new LinkedHashMap<>();
        Map<String, Object> requirement = new LinkedHashMap<>();
        Map<String, Object> trace = new LinkedHashMap<>();
        if (truthy(o.activityDiagramId)) {
            activity = SemanticValidation.activityFlowForDiagram(o.activityDiagramId, o.maxPartitionDepth,
                    o.allowStereotypePartitionLabels, bridge);
        }
        if (!o.interfaceBlockIds.isEmpty()) {
            port = SemanticValidation.portBoundaryForInterfaces(o.interfaceBlockIds, o.allowSharedFlowPropertyNames, bridge);
        }
        if (!o.requirementIds.isEmpty()) {
            requirement = SemanticValidation.requirementQualityForIds(o.requirementIds, o.requireId,
                    o.requireMeasurement, o.minRequirementTextLength, bridge);
        }
        List<String> activityTerms = new ArrayList<>();
        if (!activity.isEmpty()) {
            activityTerms = Verification.extractActivityTraceTerms(J.maps(activity.get("elements")),
                    J.maps(activity.get("relationships")));
        }
        List<String> ibdTerms = new ArrayList<>();
        if (truthy(o.ibdDiagramId)) {
            Map<String, Object> snap = gatherDiagramSnapshot(o.ibdDiagramId, bridge);
            ibdTerms = Verification.extractIbdTraceTerms(J.maps(snap.get("elements")), J.maps(snap.get("relationships")));
        }
        Map<String, Object> links = new LinkedHashMap<>();
        if (!o.requirementIds.isEmpty()) {
            List<Map<String, Object>> responses = new ArrayList<>();
            for (String id : o.requirementIds) {
                responses.add(bridge.getRelationships(id));
            }
            for (int k = 0; k < o.requirementIds.size(); k++) {
                links.put(o.requirementIds.get(k),
                        SemanticValidation.relatedRequirementArchitectureIds(o.requirementIds.get(k), responses.get(k)));
            }
        }
        if (!activityTerms.isEmpty() || !port.isEmpty() || !ibdTerms.isEmpty() || !links.isEmpty()
                || !o.architectureElementIds.isEmpty()) {
            List<String> portTerms = new ArrayList<>();
            for (Map<String, Object> fp : J.maps(port.get("flowProperties"))) {
                String name = J.text(fp.get("name")).trim();
                if (!name.isEmpty()) {
                    portTerms.add(name);
                }
            }
            trace = Verification.verifyCrossDiagramTraceability(activityTerms, portTerms, ibdTerms, links,
                    o.requirementIds, o.architectureElementIds);
        }
        List<Map<String, Object>> arch = new ArrayList<>();
        for (String id : o.architectureElementIds) {
            arch.add(SemanticValidation.nonNull(bridge.getElement(id)));
        }
        return buildRemediationPlan(activity, port, requirement, trace, arch);
    }
}
