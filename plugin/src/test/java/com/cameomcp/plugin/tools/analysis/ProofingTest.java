// family: analysis
package com.cameomcp.plugin.tools.analysis;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cameomcp.plugin.tools.analysis.Fixtures.maps;
import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Proofing}: requirement, comment, state, transition and diagram text proofing, combined
 * reports, patch plans, determinism, target collection from a package, and applying plans through a fake
 * bridge.
 */
class ProofingTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<Map<String, Object>> operations(Map<String, Object> report) {
        return J.maps(m(report.get("patch_plan")).get("operations"));
    }

    @Test
    void proofRequirementsReturnsSemanticBaselineAndPatchPlan() {
        Map<String, Object> report = Proofing.proofRequirements(maps(obj("id", "req-1", "type", "Requirement",
                "name", "apointment response time", "text", "the system shall respond within 5 seconds")), false);
        assertEquals(false, report.get("ok"));
        assertTrue(m(report.get("sections")).containsKey("semanticBaseline"));
        assertEquals(true, m(report.get("semanticBaseline")).get("ok"));
        assertTrue(((Number) m(report.get("patch_plan")).get("operationCount")).intValue() >= 1);
        Set<Object> actions = new HashSet<>();
        for (Map<String, Object> op : operations(report)) {
            actions.add(op.get("action"));
        }
        assertTrue(actions.contains("rename_element"));
        assertTrue(actions.contains("replace_text"));
    }

    @Test
    void proofCommentsFlagsTodoPlaceholders() {
        Map<String, Object> report = Proofing.proofComments(maps(obj("id", "comment-1", "body", "  TODO: tighten this label  ")), false);
        assertEquals(false, report.get("ok"));
        assertEquals(1, ((Number) m(report.get("patch_plan")).get("operationCount")).intValue());
        Map<String, Object> finding = J.maps(report.get("findings")).get(0);
        assertTrue(J.str(finding.get("message")).contains("placeholder"));
        assertEquals("Tighten this label.", operations(report).get(0).get("suggestedText"));
    }

    @Test
    void stateTransitionProofingDistinguishesTitleAndPascalCase() {
        Map<String, Object> report = Proofing.proofStateTransitionNames(
                maps(obj("id", "state-1", "name", "query pending")),
                maps(obj("id", "transition-1", "name", "booking requested")), false);
        assertEquals(false, report.get("ok"));
        Set<Object> suggested = new HashSet<>();
        for (Map<String, Object> op : operations(report)) {
            suggested.add(op.get("suggestedText"));
        }
        assertTrue(suggested.contains("Query Pending"));
        assertTrue(suggested.contains("BookingRequested"));
    }

    @Test
    void diagramTextProofingSupportsAutoApplyPatchPlans() {
        Map<String, Object> report = Proofing.proofDiagramText(maps(obj("id", "diagram-text-1", "label", "  verified selection  ")), true);
        assertEquals(false, report.get("ok"));
        Map<String, Object> plan = m(report.get("patch_plan"));
        assertEquals("auto_apply", plan.get("mode"));
        assertEquals(true, plan.get("previewOnly"));
        assertEquals("Verified Selection", operations(report).get(0).get("suggestedText"));
    }

    @Test
    void combinedTextProofingAggregatesSections() {
        Map<String, Object> report = Proofing.proofTexts(
                maps(obj("id", "req-1", "name", "response latency", "text", "the system shall respond within 5 seconds")),
                maps(obj("id", "comment-1", "body", "  fix this later  ")),
                maps(obj("id", "state-1", "name", "idle mode")),
                maps(obj("id", "transition-1", "name", "query requested")),
                maps(obj("id", "diagram-text-1", "text", "show verification result")),
                false);
        assertEquals(false, report.get("ok"));
        assertEquals(6, ((Number) m(report.get("patch_plan")).get("operationCount")).intValue());
        assertEquals(4, ((Number) m(report.get("metrics")).get("sectionCount")).intValue());
        assertTrue(m(report.get("sections")).containsKey("stateTransitions"));
        assertTrue(m(report.get("sections")).containsKey("diagramText"));
    }

    @Test
    void buildPatchPlanAcceptsMapFindings() {
        Map<String, Object> plan = Proofing.buildPatchPlan(maps(obj(
                "category", "requirements", "severity", "medium", "artifactType", "Requirement",
                "artifactId", "req-99", "field", "text", "message", "normalize casing",
                "currentText", "hello world", "suggestedText", "Hello world.", "suggestions", "Hello",
                "confidence", 0.9)), false);
        assertEquals(1, ((Number) plan.get("operationCount")).intValue());
        assertEquals("Hello world.", J.maps(plan.get("operations")).get(0).get("suggestedText"));
    }

    @Test
    void analyzersAreDeterministic() {
        assertEquals(Proofing.proofComments(maps(obj("id", "c-1", "body", "hello")), false),
                Proofing.proofComments(maps(obj("id", "c-1", "body", "hello")), false));
        assertEquals(Proofing.proofDiagramText(maps(obj("id", "d-1", "label", "hello")), false),
                Proofing.proofDiagramText(maps(obj("id", "d-1", "label", "hello")), false));
        assertEquals(Proofing.proofStateTransitionNames(maps(obj("id", "s-1", "name", "idle")), maps(obj("id", "t-1", "name", "go")), false),
                Proofing.proofStateTransitionNames(maps(obj("id", "s-1", "name", "idle")), maps(obj("id", "t-1", "name", "go")), false));
    }

    // ------------------------------------------------------------------ bridge-backed

    static FakeBridge proofingBridge() {
        FakeBridge b = new FakeBridge();
        b.queryPayload = obj("elements", arr(
                obj("id", "req-1", "type", "Requirement", "name", "apointment response time"),
                obj("id", "comment-1", "type", "Comment", "name", "TODO note"),
                obj("id", "state-1", "type", "State", "name", "query pending"),
                obj("id", "transition-1", "type", "Transition", "name", "booking requested")));
        b.specifications.put("req-1", obj("elementId", "req-1", "appliedStereotypes", arr(obj("stereotype", "Requirement",
                "taggedValues", obj("text", "the system shall respond within 5 seconds")))));
        b.specifications.put("comment-1", obj("elementId", "comment-1", "properties", obj("body", "TODO: tighten this label")));
        b.specificationFallback = id -> obj("elementId", id, "properties", obj());
        b.diagramsPayload = obj("diagrams", arr(obj("id", "dia-1", "ownerId", "req-1")));
        b.shapesFallback = id -> obj("shapes", arr(obj("presentationId", "pe-1", "elementId", "req-1",
                "elementType", "Requirement", "shapeType", "ClassView", "elementName", "verified selection")));
        return b;
    }

    @Test
    void collectProofingTargetsHydratesRootPackageContent() throws Exception {
        Map<String, Object> result = Proofing.collectProofingTargets("pkg-1", null, null, null, null, null, proofingBridge());
        List<Map<String, Object>> requirements = J.maps(result.get("requirements"));
        assertEquals(1, requirements.size());
        assertEquals("the system shall respond within 5 seconds", requirements.get(0).get("text"));
        List<Map<String, Object>> comments = J.maps(result.get("comments"));
        assertEquals(1, comments.size());
        assertEquals("TODO: tighten this label", comments.get(0).get("body"));
        assertEquals(1, J.list(result.get("diagramText")).size());
    }

    @Test
    void applyPatchPlanRoutesNameAndTextUpdates() {
        FakeBridge bridge = proofingBridge();
        Map<String, Object> result = Proofing.applyPatchPlan(obj("operations", arr(
                obj("target", obj("artifactId", "state-1", "field", "name"), "suggestedText", "Query Pending"),
                obj("target", obj("artifactId", "req-1", "field", "text"),
                        "suggestedText", "The system shall respond within 5 seconds."))), bridge);
        assertEquals(true, result.get("ok"));
        assertEquals(List.of(obj("element_id", "state-1", "name", "Query Pending")), bridge.callsOf("modify_element"));
        assertEquals(List.of(obj("element_id", "req-1",
                        "properties", obj("text", "The system shall respond within 5 seconds."))),
                bridge.callsOf("set_specification"));
    }

    @Test
    void proofModelTextCanAutoApply() throws Exception {
        Map<String, Object> result = Proofing.proofModelText("pkg-1", null, null, null, null, null, true, proofingBridge());
        assertTrue(result.containsKey("applyReceipts"));
        assertEquals(true, result.get("autoApplyCompleted"));
        assertTrue(((Number) m(result.get("applyReceipts")).get("receiptCount")).intValue() > 0);
    }
}
