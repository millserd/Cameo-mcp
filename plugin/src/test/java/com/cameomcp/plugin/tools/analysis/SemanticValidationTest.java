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

class SemanticValidationTest {

    /** The reference test bridge: an activity, an IBD, two requirements and two interface blocks. */
    static FakeBridge semanticBridge() {
        FakeBridge b = new FakeBridge();
        b.shapes.put("act-1", obj("shapes", arr(
                obj("presentationId", "pe-start", "elementId", "n1", "elementType", "InitialNode"),
                obj("presentationId", "pe-a1", "elementId", "a1", "elementType", "OpaqueAction"),
                obj("presentationId", "pe-f1", "elementId", "f1", "elementType", "ActivityFinalNode"))));
        b.shapes.put("ibd-1", obj("shapes", arr(
                obj("presentationId", "pe-port", "elementId", "p1", "elementType", "Port"),
                obj("presentationId", "pe-flow", "elementId", "iflow-1", "elementType", "InformationFlow"))));
        b.elements.put("n1", obj("id", "n1", "type", "InitialNode", "name", "Start"));
        b.elements.put("a1", obj("id", "a1", "type", "OpaqueAction", "name", "Display Available Slots"));
        b.elements.put("f1", obj("id", "f1", "type", "ActivityFinalNode", "name", "Done"));
        b.elements.put("p1", obj("id", "p1", "type", "Port", "name", "Customer UI"));
        b.elements.put("iflow-1", obj("id", "iflow-1", "type", "InformationFlow", "name", "Availability Query"));
        b.elements.put("req-1", obj("id", "req-1", "type", "Class", "name", "Appointment Response Time"));
        b.elements.put("req-2", obj("id", "req-2", "type", "Class", "name", "System Availability"));
        b.specifications.put("req-1", obj("elementId", "req-1",
                "documentation", "The system shall respond within 5 seconds.",
                "appliedStereotypes", arr(obj("stereotype", "Requirement", "taggedValues",
                        obj("id", "REQ-1", "text", "The system shall respond within 5 seconds.")))));
        b.specifications.put("req-2", obj("elementId", "req-2", "documentation", "",
                "appliedStereotypes", arr(obj("stereotype", "Requirement", "taggedValues", obj("id", "", "text", "")))));
        b.relationships.put("n1", obj("outgoing", arr(rel("r1", "ControlFlow", "n1", "a1")), "incoming", arr()));
        b.relationships.put("a1", obj("outgoing", arr(rel("r2", "ControlFlow", "a1", "f1")),
                "incoming", arr(rel("r1", "ControlFlow", "n1", "a1"))));
        b.relationships.put("f1", obj("outgoing", arr(), "incoming", arr(rel("r2", "ControlFlow", "a1", "f1"))));
        b.relationships.put("p1", obj("outgoing", arr(), "incoming", arr(), "undirected", arr(obj(
                "relationshipId", "iflow-1", "type", "InformationFlow", "name", "Availability Query",
                "conveyed", arr(obj("id", "blk-1", "name", "Available Slots")),
                "relatedElements", arr(obj("id", "blk-2", "name", "Scheduling System"))))));
        b.relationships.put("req-1", obj("outgoing", arr(rel("trace-1", "Trace", "req-1", "blk-1")), "incoming", arr()));
        b.relationships.put("req-2", obj("outgoing", arr(), "incoming", arr()));
        b.interfaceFlowPayload = obj(
                "interfaceBlocks", arr(obj("id", "if-1", "name", "Customer UI Port Type"),
                        obj("id", "if-2", "name", "Scheduling System Port Type")),
                "flowProperties", arr(
                        obj("id", "fp-1", "name", "Available Slots", "ownerId", "if-1", "direction", "out"),
                        obj("id", "fp-2", "name", "Available Slots", "ownerId", "if-2", "direction", "in")));
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void activityFlowForDiagramReadsSnapshot() throws Exception {
        Map<String, Object> result = SemanticValidation.activityFlowForDiagram("act-1", 1, false, semanticBridge());
        assertEquals(true, result.get("ok"));
        assertEquals("act-1", result.get("diagramId"));
        assertEquals(2, ((Number) m(result.get("metrics")).get("flowRelationshipCount")).intValue());
        assertEquals(2, J.list(result.get("relationships")).size());
    }

    @Test
    void portBoundaryForInterfacesUsesNativeReadback() throws Exception {
        FakeBridge bridge = semanticBridge();
        Map<String, Object> result = SemanticValidation.portBoundaryForInterfaces(strs("if-1", "if-2"), null, bridge);
        assertEquals(false, result.get("ok"));
        assertEquals(2, J.list(result.get("interfaceBlocks")).size());
        assertTrue(m(m(result.get("metrics")).get("duplicateFlowProperties")).containsKey("available slots"));
        assertEquals(List.of("if-1", "if-2"),
                m(m(result.get("metrics")).get("duplicateFlowPropertyOwnerIds")).get("available slots"));
        assertEquals(List.of(List.of("if-1", "if-2")), bridge.interfaceFlowCalls);
    }

    @Test
    void requirementQualityForIdsMergesElementAndSpecification() throws Exception {
        Map<String, Object> result = SemanticValidation.requirementQualityForIds(strs("req-1", "req-2"), true, true, 20,
                semanticBridge());
        assertEquals(false, result.get("ok"));
        assertEquals(2, J.list(result.get("requirements")).size());
        assertTrue(J.strings(m(result.get("metrics")).get("blankTextIds")).contains("req-2"));
    }

    @Test
    void crossDiagramTraceabilityReadsAllArtifactSets() throws Exception {
        FakeBridge bridge = semanticBridge();
        Map<String, Object> result = SemanticValidation.crossDiagramTraceability("act-1", strs("if-1", "if-2"), "ibd-1",
                strs("req-1", "req-2"), strs("blk-1"), bridge);
        assertEquals(false, result.get("ok"));
        assertTrue(J.strings(result.get("portTerms")).contains("Available Slots"));
        assertTrue(J.strings(result.get("ibdTerms")).contains("Availability Query"));
        assertTrue(J.strings(m(result.get("metrics")).get("missingRequirementTraceIds")).contains("req-2"));
        assertEquals(List.of(List.of("if-1", "if-2")), bridge.interfaceFlowCalls);
    }

    @Test
    void flattenRelationshipResponsesDeduplicatesAcrossBuckets() {
        List<Map<String, Object>> flat = SemanticValidation.flattenRelationshipResponses(List.of(
                obj("outgoing", arr(rel("r1", "ControlFlow", "n1", "a1"))),
                obj("incoming", arr(rel("r1", "ControlFlow", "n1", "a1")))));
        assertEquals(1, flat.size());
    }
}
