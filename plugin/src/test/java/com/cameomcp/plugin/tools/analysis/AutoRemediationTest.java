// family: analysis
package com.cameomcp.plugin.tools.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.analysis.Fixtures.rel;
import static com.cameomcp.plugin.tools.analysis.Fixtures.strs;
import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AutoRemediation}: remediation plans with previewable receipts, clean and missing inputs,
 * live detection through the semantic validators, and {@link SequenceMatcher} parity with Python difflib.
 */
class AutoRemediationTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    static FakeBridge remediationBridge() {
        FakeBridge b = new FakeBridge();
        b.elements.put("a1", obj("id", "a1", "type", "OpaqueAction", "name", "Display Service Options"));
        b.elements.put("a2", obj("id", "a2", "type", "OpaqueAction", "name", "Send Confirmation"));
        b.elements.put("n1", obj("id", "n1", "type", "InitialNode", "name", "Start"));
        b.elements.put("f1", obj("id", "f1", "type", "ActivityFinalNode", "name", "Done"));
        b.elements.put("if-1", obj("id", "if-1", "humanType", "Interface Block", "name", "Customer UI Port Type"));
        b.elements.put("if-2", obj("id", "if-2", "humanType", "Interface Block", "name", "Scheduling System Port Type"));
        b.elements.put("iflow-1", obj("id", "iflow-1", "type", "InformationFlow", "name", "Availability Query"));
        b.elements.put("req-1", obj("id", "req-1", "humanType", "Requirement", "name", "Appointment Response Time"));
        b.elements.put("req-2", obj("id", "req-2", "humanType", "Requirement", "name", "System Availability"));
        b.elements.put("blk-1", obj("id", "blk-1", "humanType", "Block", "name", "Scheduling System"));
        b.shapes.put("act-1", obj("shapes", arr(
                obj("presentationId", "pe-n1", "elementId", "n1", "elementType", "InitialNode"),
                obj("presentationId", "pe-a1", "elementId", "a1", "elementType", "OpaqueAction"),
                obj("presentationId", "pe-a2", "elementId", "a2", "elementType", "OpaqueAction"),
                obj("presentationId", "pe-f1", "elementId", "f1", "elementType", "ActivityFinalNode"))));
        b.shapes.put("ibd-1", obj("shapes", arr(
                obj("presentationId", "pe-port", "elementId", "if-1", "elementType", "Port"),
                obj("presentationId", "pe-flow", "elementId", "iflow-1", "elementType", "InformationFlow"))));
        b.relationships.put("a1", obj("outgoing", arr(rel("cf-1", "ControlFlow", "a1", "f1")),
                "incoming", arr(rel("cf-0", "ControlFlow", "n1", "a1")), "undirected", arr()));
        b.relationships.put("if-1", obj("outgoing", arr(), "incoming", arr(), "undirected", arr(obj(
                "relationshipId", "iflow-1", "type", "InformationFlow", "name", "Availability Query",
                "conveyed", arr(obj("id", "c-1", "name", "Available Slots")),
                "relatedElements", arr(obj("id", "blk-1", "name", "Scheduling System"))))));
        b.interfaceFlowPayload = obj(
                "interfaceBlocks", arr(obj("id", "if-1", "name", "Customer UI Port Type"),
                        obj("id", "if-2", "name", "Scheduling System Port Type")),
                "flowProperties", arr(
                        obj("id", "fp-1", "name", "Available Slots", "ownerId", "if-1", "direction", "out"),
                        obj("id", "fp-2", "name", "Available Slots", "ownerId", "if-2", "direction", "in")));
        b.specifications.put("req-1", obj("elementId", "req-1",
                "appliedStereotypes", arr(obj("stereotype", "Requirement", "taggedValues", obj("id", "", "text", "")))));
        b.specifications.put("req-2", obj("elementId", "req-2", "documentation", "The system shall be easy to use.",
                "appliedStereotypes", arr(obj("stereotype", "Requirement", "taggedValues",
                        obj("id", "", "text", "The system shall be easy to use.")))));
        return b;
    }

    @Test
    void buildRemediationPlanCreatesPreviewableReceipts() {
        Map<String, Object> result = AutoRemediation.buildRemediationPlan(
                obj("ok", false, "diagramId", "act-1",
                        "checks", arr(obj("name", "actions-connected", "ok", false,
                                "details", obj("isolatedActionIds", arr("a2")))),
                        "metrics", obj("initialNodeIds", arr("n1"), "actionIds", arr("a1", "a2"),
                                "isolatedActionIds", arr("a2"), "unreachableActionIds", arr("a2")),
                        "elements", arr(obj("id", "n1", "type", "InitialNode", "name", "Start"),
                                obj("id", "a1", "type", "OpaqueAction", "name", "Display Service Options"),
                                obj("id", "a2", "type", "OpaqueAction", "name", "Send Confirmation"))),
                obj("ok", false,
                        "checks", arr(obj("name", "duplicate-flow-properties", "ok", false, "details", obj())),
                        "metrics", obj("duplicateFlowProperties", obj("Available Slots", arr("if-1", "if-2")),
                                "directionConflicts", obj(), "unnamedPropertyIds", arr("fp-9"),
                                "orphanPropertyIds", arr(), "missingDirectionIds", arr()),
                        "interfaceBlocks", arr(obj("id", "if-1", "name", "Customer UI Port Type"),
                                obj("id", "if-2", "name", "Scheduling System Port Type")),
                        "flowProperties", arr(
                                obj("id", "fp-1", "name", "Available Slots", "ownerId", "if-1", "direction", "out"),
                                obj("id", "fp-2", "name", "Available Slots", "ownerId", "if-2", "direction", "in"))),
                obj("ok", false,
                        "checks", arr(obj("name", "requirement-text-present", "ok", false, "details", obj())),
                        "metrics", obj("missingIdIds", arr("req-1"), "blankTextIds", arr("req-1"),
                                "weakTextIds", arr("req-2"),
                                "evaluatedRequirements", arr(
                                        obj("elementId", "req-1", "name", "Appointment Response Time",
                                                "requirementId", "", "text", ""),
                                        obj("elementId", "req-2", "name", "System Availability",
                                                "requirementId", "", "text", "The system shall be easy to use."))),
                        "requirements", arr(obj("id", "req-1", "name", "Appointment Response Time"),
                                obj("id", "req-2", "name", "System Availability"))),
                obj("ok", false,
                        "checks", arr(obj("name", "activity-to-port-coverage", "ok", false, "details", obj())),
                        "metrics", obj("activityTermCount", 2, "portTermCount", 1, "ibdTermCount", 1,
                                "missingPortTerms", arr("Available Slots"), "missingIbdTerms", arr("Availability Query"),
                                "missingRequirementTraceIds", arr("req-1")),
                        "activityTerms", arr("Display Service Options", "Send Confirmation"),
                        "portTerms", arr("Available Slots"),
                        "ibdTerms", arr("Availability Query"),
                        "requirementLinks", obj("req-1", arr(), "req-2", arr())),
                Fixtures.maps(obj("id", "blk-1", "name", "Scheduling System", "humanType", "Block")));

        assertEquals(false, result.get("ok"));
        assertTrue(((Number) m(result.get("summary")).get("issueCount")).intValue() > 0);
        assertEquals("preview", m(result.get("patchPlan")).get("mode"));
        List<Map<String, Object>> receipts = J.maps(result.get("receipts"));
        for (Map<String, Object> r : receipts) {
            assertEquals("preview", r.get("status"));
        }
        Map<String, Object> trace = null;
        Map<String, Object> port = null;
        for (Map<String, Object> r : receipts) {
            if ("cross-diagram-traceability".equals(r.get("category")) && "req-1".equals(m(r.get("target")).get("elementId"))) {
                trace = r;
            }
            if ("port-boundary".equals(r.get("category"))
                    && "Available Slots".equals(m(r.get("target")).get("flowPropertyName")) && port == null) {
                port = r;
            }
        }
        List<Map<String, Object>> targets = J.maps(m(m(trace.get("preview")).get("after")).get("traceTargets"));
        assertEquals("blk-1", targets.get(0).get("elementId"));
        assertTrue(m(m(port.get("preview")).get("after")).containsKey("candidateFixes"));
    }

    @Test
    void buildRemediationPlanIsEmptyWhenClean() {
        Map<String, Object> result = AutoRemediation.buildRemediationPlan(
                obj("ok", true, "checks", arr(), "metrics", obj(), "elements", arr()),
                obj("ok", true, "checks", arr(), "metrics", obj(), "interfaceBlocks", arr(), "flowProperties", arr()),
                obj("ok", true, "checks", arr(), "metrics", obj(), "requirements", arr()),
                obj("ok", true, "checks", arr(), "metrics", obj(), "activityTerms", arr(), "portTerms", arr(),
                        "ibdTerms", arr(), "requirementLinks", obj()),
                Fixtures.maps());
        assertEquals(true, result.get("ok"));
        assertEquals(0, ((Number) m(result.get("summary")).get("issueCount")).intValue());
        assertEquals(List.of(), result.get("receipts"));
    }

    @Test
    void buildRemediationPlanAcceptsMissingPayloads() {
        Map<String, Object> result = AutoRemediation.buildRemediationPlan(null, null, null, null, null);
        assertEquals(true, result.get("ok"));
    }

    @Test
    void detectUsesSemanticValidation() throws Exception {
        FakeBridge bridge = remediationBridge();
        AutoRemediation.DetectOptions o = new AutoRemediation.DetectOptions();
        o.activityDiagramId = "act-1";
        o.interfaceBlockIds = strs("if-1", "if-2");
        o.ibdDiagramId = "ibd-1";
        o.requirementIds = strs("req-1", "req-2");
        o.architectureElementIds = strs("blk-1");
        Map<String, Object> result = AutoRemediation.detect(o, bridge);

        assertEquals(false, result.get("ok"));
        assertTrue(((Number) m(result.get("summary")).get("issueCount")).intValue() > 0);
        assertTrue(result.containsKey("findings"));
        assertEquals(List.of(List.of("if-1", "if-2")), bridge.interfaceFlowCalls);
        Map<String, Object> summary = m(m(result.get("patchPlan")).get("summary"));
        assertTrue(((Number) summary.get("activityTermCount")).intValue() > 0);
        boolean found = false;
        for (Map<String, Object> r : J.maps(result.get("receipts"))) {
            if ("cross-diagram-traceability".equals(r.get("category")) && "req-1".equals(m(r.get("target")).get("elementId"))) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void sequenceMatcherMatchesDifflib() {
        // difflib.SequenceMatcher(None, "requirment", "requirement").ratio() == 0.9523809523809523
        assertEquals(0.9523809523809523, SequenceMatcher.ratio("requirment", "requirement"), 1e-12);
        // difflib.get_close_matches("requirment", [...], n=2, cutoff=0.88)
        assertEquals(List.of("requirement"), SequenceMatcher.closeMatches("requirment",
                List.of("requirement", "retirement", "require"), 2, 0.88));
    }
}
