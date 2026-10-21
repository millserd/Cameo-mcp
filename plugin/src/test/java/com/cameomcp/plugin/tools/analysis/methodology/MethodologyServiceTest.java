// family: analysis
package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.tools.analysis.FakeBridge;
import com.cameomcp.plugin.tools.analysis.J;
import com.cameomcp.plugin.tools.analysis.methodology.MethodologyRuntime.ArtifactSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cameomcp.plugin.tools.analysis.Fixtures.rel;
import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link MethodologyService} against a fake bridge: pack listing, guidance, recipe
 * execution with evidence, required parameters, non-mutating validation and review packets, and the semantic
 * checks of each built-in recipe.
 */
class MethodologyServiceTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> el(String id, String name, String humanType, String ownerId) {
        Map<String, Object> e = obj("id", id, "name", name, "humanType", humanType);
        if (ownerId != null) {
            e.put("ownerId", ownerId);
        }
        return e;
    }

    /** Port of the reference service-test bridge. */
    static FakeBridge serviceBridge() {
        FakeBridge b = new FakeBridge();
        b.elements.put("root-1", el("root-1", "Workspace", "Package", null));
        b.elements.put("pkg-req", el("pkg-req", "ATM Requirements", "Package", "root-1"));
        b.elements.put("dia-req", el("dia-req", "ATM Requirements", "SysML Requirement Diagram", "pkg-req"));
        b.elements.put("req-blank", el("req-blank", "REQ-1", "Requirement", "pkg-req"));
        b.elements.put("pkg-act", el("pkg-act", "Schedule Appointment Activity", "Package", "root-1"));
        b.elements.put("activity-1", el("activity-1", "Schedule Appointment", "Activity", "pkg-act"));
        b.elements.put("act-diagram-1", el("act-diagram-1", "Schedule Appointment Activity", "Activity Diagram", "activity-1"));
        b.elements.put("act-start", obj("id", "act-start", "name", "Start", "type", "InitialNode"));
        b.elements.put("act-a1", obj("id", "act-a1", "name", "Display Service Options", "type", "OpaqueAction"));
        b.elements.put("act-a2", obj("id", "act-a2", "name", "Send Confirmation", "type", "OpaqueAction"));
        b.elements.put("act-final", obj("id", "act-final", "name", "Done", "type", "ActivityFinalNode"));
        b.elements.put("act-partition", obj("id", "act-partition", "name", "Scheduling System", "type", "ActivityPartition"));
        b.elements.put("pkg-port", el("pkg-port", "AAS Ports", "Package", "root-1"));
        b.elements.put("sys-port", el("sys-port", "AAS", "Block", "pkg-port"));
        b.elements.put("bdd-port", el("bdd-port", "AAS Ports", "SysML Block Definition Diagram", "pkg-port"));
        b.elements.put("if-1", el("if-1", "Customer UI Port Type", "Interface Block", "pkg-port"));
        b.elements.put("if-2", el("if-2", "Scheduling System Port Type", "Interface Block", "pkg-port"));
        b.elements.put("pkg-ibd", el("pkg-ibd", "AAS Context", "Package", "root-1"));
        b.elements.put("blk-ibd", el("blk-ibd", "AAS", "Block", "pkg-ibd"));
        b.elements.put("ibd-1", el("ibd-1", "AAS Context", "SysML Internal Block Diagram", "blk-ibd"));
        b.elements.put("ibd-port", obj("id", "ibd-port", "name", "Customer UI", "type", "Port"));
        b.elements.put("ibd-flow", obj("id", "ibd-flow", "name", "Availability Query", "type", "InformationFlow"));
        b.elementFallback = id -> obj("id", id, "name", id, "humanType", "Class", "ownerId", "root-1");
        b.specifications.put("req-blank", obj("elementId", "req-blank", "properties", obj(),
                "appliedStereotypes", arr(obj("stereotype", "Requirement", "taggedValues", obj("id", "", "text", "")))));
        b.specificationFallback = id -> obj("elementId", id, "properties", obj());
        b.shapes.put("act-diagram-1", obj("shapes", arr(
                obj("presentationId", "pe-partition", "elementId", "act-partition", "elementType", "ActivityPartition"),
                obj("presentationId", "pe-start", "parentPresentationId", "pe-partition", "elementId", "act-start",
                        "elementType", "InitialNode"),
                obj("presentationId", "pe-a1", "parentPresentationId", "pe-partition", "elementId", "act-a1",
                        "elementType", "OpaqueAction"),
                obj("presentationId", "pe-a2", "parentPresentationId", "pe-partition", "elementId", "act-a2",
                        "elementType", "OpaqueAction"),
                obj("presentationId", "pe-final", "parentPresentationId", "pe-partition", "elementId", "act-final",
                        "elementType", "ActivityFinalNode"))));
        b.shapes.put("ibd-1", obj("shapes", arr(
                obj("presentationId", "pe-ibd-port", "elementId", "ibd-port", "elementType", "Port"),
                obj("presentationId", "pe-ibd-flow", "elementId", "ibd-flow", "elementType", "InformationFlow"))));
        b.shapesFallback = id -> obj("shapes", arr());
        b.relationships.put("act-start", obj("outgoing", arr(rel("cf-1", "ControlFlow", "act-start", "act-a1")),
                "incoming", arr(), "undirected", arr()));
        b.relationships.put("act-a1", obj("outgoing", arr(rel("cf-2", "ControlFlow", "act-a1", "act-final")),
                "incoming", arr(rel("cf-1", "ControlFlow", "act-start", "act-a1")), "undirected", arr()));
        b.relationships.put("act-final", obj("outgoing", arr(),
                "incoming", arr(rel("cf-2", "ControlFlow", "act-a1", "act-final")), "undirected", arr()));
        b.relationships.put("ibd-port", obj("outgoing", arr(), "incoming", arr(), "undirected", arr(obj(
                "relationshipId", "ibd-flow", "type", "InformationFlow", "name", "Availability Query",
                "conveyed", arr(obj("id", "conveyed-1", "name", "Available Slots")),
                "relatedElements", arr(obj("id", "rel-1", "name", "Scheduling System"))))));
        b.interfaceFlowPayload = obj(
                "interfaceBlocks", arr(obj("id", "if-1", "name", "Customer UI Port Type"),
                        obj("id", "if-2", "name", "Scheduling System Port Type")),
                "flowProperties", arr(
                        obj("id", "fp-1", "name", "Available Slots", "ownerId", "if-1", "direction", "out"),
                        obj("id", "fp-2", "name", "Available Slots", "ownerId", "if-2", "direction", "in")));
        b.diagramsPayload = obj("diagrams", arr());
        b.queryPayload = obj("elements", arr());
        b.imageFn = id -> obj("id", id, "format", "png", "width", 10, "height", 10, "image", "stub");
        return b;
    }

    private static Map<String, Object> art(String key, String kind, String name, String elementId, String parentKey) {
        Map<String, Object> a = obj("key", key, "kind", kind, "name", name, "element_id", elementId);
        if (parentKey != null) {
            a.put("parent_key", parentKey);
        }
        return a;
    }

    private static boolean anyRule(Map<String, Object> validation, String prefix) {
        for (Map<String, Object> f : J.maps(m(validation.get("conformance")).get("findings"))) {
            if (J.str(f.get("rule_id")).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void listMethodologyPacksReturnsOosem() {
        Map<String, Object> result = MethodologyService.listMethodologyPacks();
        assertTrue(((Number) result.get("count")).intValue() >= 2);
        assertEquals("oosem", J.maps(result.get("packs")).get(0).get("id"));
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> p : J.maps(result.get("packs"))) {
            ids.add(p.get("id"));
        }
        assertTrue(ids.contains("uaf"));
    }

    @Test
    void guidanceReportsMissingArtifacts() {
        Map<String, Object> result = MethodologyService.getWorkflowGuidance("oosem", "stakeholder_needs_package",
                obj("subject_name", "ATM", "root_package_id", "root-1"),
                List.of(art("workspace", "Package", "Workspace", "root-1", null)));
        Map<String, Object> guidance = m(result.get("guidance"));
        assertEquals("stakeholder_needs_package", guidance.get("recipe_id"));
        assertTrue(J.strings(guidance.get("missing_artifact_keys")).contains("stakeholder_needs_package"));
        assertEquals("oosem", m(result.get("pack")).get("id"));
    }

    @Test
    void guidanceWithoutRecipePicksFirstPendingRecipe() {
        Map<String, Object> result = MethodologyService.getWorkflowGuidance("oosem", null, null, null);
        assertEquals("stakeholder_needs_package", m(result.get("guidance")).get("recipe_id"));
    }

    @Test
    void executeMethodologyRecipeReturnsEvidenceBundle() {
        Map<String, Object> result = MethodologyService.executeMethodologyRecipe("oosem", "use_case_model",
                obj("root_package_id", "root-1", "subject_name", "ATM", "actor_names", arr("Customer"),
                        "use_case_names", arr("Withdraw Cash")),
                "root-1", null, null, serviceBridge());
        assertEquals("oosem", m(result.get("pack")).get("id"));
        assertTrue(result.containsKey("executionResult"));
        assertTrue(result.containsKey("evidenceBundle"));
        assertTrue(J.str(result.get("reviewPacketMarkdown")).contains("# OOSEM Viewpoint Pack Review Packet"));
        assertEquals("use_case_model", m(result.get("recipe")).get("id"));
    }

    @Test
    void executeRequiresRequiredParameters() {
        ToolException e = assertThrows(ToolException.class, () -> MethodologyService.executeMethodologyRecipe(
                "oosem", "use_case_model", obj("root_package_id", "root-1"), "root-1", null, null, serviceBridge()));
        assertEquals("INVALID_PARAMS", e.code());
        assertTrue(e.getMessage().contains("subject_name"));
    }

    @Test
    void validateAndReviewPacketAreNonMutating() {
        List<Map<String, Object>> artifacts = List.of(art("workspace", "Package", "Workspace", "root-1", null));
        FakeBridge bridge = serviceBridge();
        Map<String, Object> validation = MethodologyService.validateMethodologyRecipe("oosem", "system_requirements_package",
                obj("root_package_id", "root-1", "system_name", "ATM"), artifacts, bridge);
        Map<String, Object> packet = MethodologyService.generateReviewPacket("oosem", "system_requirements_package",
                obj("root_package_id", "root-1", "system_name", "ATM"), artifacts,
                List.of("Requirements IDs are still provisional."), null, bridge);
        assertTrue(validation.containsKey("conformance"));
        assertTrue(packet.containsKey("evidenceBundle"));
        assertTrue(J.str(packet.get("reviewPacketMarkdown")).contains("Requirements IDs are still provisional."));
        assertTrue(bridge.callsOf("create_element").isEmpty());
        assertTrue(bridge.callsOf("set_specification").isEmpty());
    }

    @Test
    void requirementSemanticsAreMergedIntoMethodologyConformance() {
        List<Map<String, Object>> artifacts = List.of(
                art("workspace", "Package", "Workspace", "root-1", null),
                art("requirements_package", "Package", "ATM Requirements", "pkg-req", "workspace"),
                art("requirements_diagram", "SysML Requirement Diagram", "ATM Requirements", "dia-req", "requirements_package"),
                art("requirement_1", "Requirement", "REQ-1", "req-blank", "requirements_package"));
        Map<String, Object> params = obj("root_package_id", "root-1", "system_name", "ATM",
                "requirement_ids", arr("REQ-1"), "requirement_texts", arr(""));
        Map<String, Object> validation = MethodologyService.validateMethodologyRecipe("oosem",
                "system_requirements_package", params, artifacts, serviceBridge());
        Map<String, Object> packet = MethodologyService.generateReviewPacket("oosem", "system_requirements_package",
                params, artifacts, null, null, serviceBridge());
        assertTrue(validation.containsKey("semanticValidations"));
        assertEquals(false, m(validation.get("conformance")).get("passed"));
        assertTrue(anyRule(validation, "semantic:requirement_quality"));
        assertTrue(J.str(packet.get("reviewPacketMarkdown")).contains("## Semantic Validation"));
    }

    @Test
    void logicalActivityRecipeSurfacesActivitySemanticFindings() {
        List<Map<String, Object>> artifacts = List.of(
                art("workspace", "Package", "Workspace", "root-1", null),
                art("logical_activity_package", "Package", "Schedule Appointment Activity", "pkg-act", "workspace"),
                art("logical_activity", "Activity", "Schedule Appointment", "activity-1", "logical_activity_package"),
                art("logical_activity_diagram", "Activity Diagram", "Schedule Appointment Activity", "act-diagram-1",
                        "logical_activity"));
        Map<String, Object> validation = MethodologyService.validateMethodologyRecipe("oosem", "logical_activity_flow",
                obj("root_package_id", "root-1", "activity_name", "Schedule Appointment"), artifacts, serviceBridge());
        assertEquals(false, m(validation.get("conformance")).get("passed"));
        assertTrue(anyRule(validation, "semantic:activity_flow_semantics"));
    }

    @Test
    void logicalActivityRecipeCreatesPartitionsBeforeVisualsAndUsesAbsolutePositions() {
        FakeBridge bridge = serviceBridge();
        MethodologyService.executeMethodologyRecipe("oosem", "logical_activity_flow",
                obj("root_package_id", "root-1", "activity_name", "Schedule Appointment",
                        "performer_names", arr("Customer", "Scheduling System"),
                        "action_names", arr("Capture Need", "Schedule Slot")),
                "root-1", null, null, bridge);
        int lastPartitionCreate = -1;
        int firstAdd = -1;
        int controlFlows = 0;
        for (int i = 0; i < bridge.calls.size(); i++) {
            Object[] call = bridge.calls.get(i);
            Map<String, Object> kwargs = m(call[1]);
            if ("create_element".equals(call[0]) && "ActivityPartition".equals(kwargs.get("type"))) {
                lastPartitionCreate = i;
            }
            if ("add_to_diagram".equals(call[0])) {
                if (firstAdd < 0) {
                    firstAdd = i;
                }
                assertFalse(kwargs.containsKey("container_presentation_id"));
            }
            if ("create_relationship".equals(call[0]) && "ControlFlow".equals(kwargs.get("type"))) {
                controlFlows++;
            }
        }
        assertTrue(lastPartitionCreate >= 0 && lastPartitionCreate < firstAdd);
        assertEquals(3, controlFlows);
    }

    @Test
    void logicalPortRecipeSurfacesPortBoundaryFindings() {
        List<Map<String, Object>> artifacts = List.of(
                art("workspace", "Package", "Workspace", "root-1", null),
                art("logical_port_package", "Package", "AAS Ports", "pkg-port", "workspace"),
                art("logical_port_system", "Block", "AAS", "sys-port", "logical_port_package"),
                art("logical_port_bdd", "SysML Block Definition Diagram", "AAS Ports", "bdd-port", "logical_port_package"),
                art("interface_block_1", "Interface Block", "Customer UI Port Type", "if-1", "logical_port_package"),
                art("interface_block_2", "Interface Block", "Scheduling System Port Type", "if-2", "logical_port_package"));
        Map<String, Object> validation = MethodologyService.validateMethodologyRecipe("oosem", "logical_port_bdd",
                obj("root_package_id", "root-1", "system_name", "AAS", "interface_definitions", arr(
                        obj("name", "Customer UI Port Type", "flow_properties",
                                arr(obj("name", "Available Slots", "direction", "out"))),
                        obj("name", "Scheduling System Port Type", "flow_properties",
                                arr(obj("name", "Available Slots", "direction", "in"))))),
                artifacts, serviceBridge());
        assertEquals(false, m(validation.get("conformance")).get("passed"));
        assertTrue(anyRule(validation, "semantic:port_boundary_consistency"));
    }

    @Test
    void executeLogicalPortRecipeKeepsCreatedBindingsForSemanticValidation() {
        Map<String, Object> result = MethodologyService.executeMethodologyRecipe("oosem", "logical_port_bdd",
                obj("root_package_id", "root-1", "system_name", "AAS", "interface_definitions", arr(
                        obj("name", "Customer UI Port Type", "flow_properties",
                                arr(obj("name", "Available Slots", "direction", "out"))),
                        obj("name", "Scheduling System Port Type", "flow_properties",
                                arr(obj("name", "Available Slots", "direction", "in"))))),
                "root-1", null, null, serviceBridge());
        assertTrue(result.containsKey("semanticValidations"));
        Map<String, Object> port = null;
        for (Map<String, Object> v : J.maps(result.get("semanticValidations"))) {
            if ("port_boundary_consistency".equals(v.get("validatorId"))) {
                port = v;
            }
        }
        for (Map<String, Object> c : J.maps(port.get("checks"))) {
            assertFalse("binding-resolution".equals(c.get("name")));
        }
    }

    @Test
    void hydrateLiveArtifactsKeepsSpecificKindsWhenLiveKindIsGeneric() {
        FakeBridge bridge = serviceBridge();
        bridge.elements.get("root-1").put("humanType", "Model");
        bridge.elements.get("bdd-port").put("humanType", "Diagram");
        List<ArtifactSnapshot> hydrated = MethodologyService.hydrateLiveArtifacts(List.of(
                new ArtifactSnapshot("workspace", "Package", "Workspace", "root-1"),
                new ArtifactSnapshot("logical_port_bdd", "SysML Block Definition Diagram", "AAS Ports", "bdd-port")), bridge);
        assertEquals("Package", hydrated.get(0).kind());
        assertEquals("SysML Block Definition Diagram", hydrated.get(1).kind());
    }

    @Test
    void logicalIbdRecipeSurfacesTraceabilityFindings() {
        List<Map<String, Object>> artifacts = List.of(
                art("workspace", "Package", "Workspace", "root-1", null),
                art("logical_context_package", "Package", "AAS Context", "pkg-ibd", "workspace"),
                art("logical_context_block", "Block", "AAS", "blk-ibd", "logical_context_package"),
                art("logical_context_ibd", "SysML Internal Block Diagram", "AAS Context", "ibd-1", "logical_context_block"));
        Map<String, Object> validation = MethodologyService.validateMethodologyRecipe("oosem", "logical_ibd_traceability",
                obj("root_package_id", "root-1", "context_name", "AAS", "activity_diagram_id", "act-diagram-1",
                        "flow_names", arr("Availability Query")),
                artifacts, serviceBridge());
        assertEquals(false, m(validation.get("conformance")).get("passed"));
        assertTrue(anyRule(validation, "semantic:cross_diagram_traceability"));
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void helpersMatchReferenceBehaviour() {
        assertEquals("customer_ui", MethodologyService.defaultPortName("  Customer UI! "));
        assertEquals("port", MethodologyService.defaultPortName("!!!"));
        assertEquals("in", MethodologyService.normalizeFlowPropertyDirection("Incoming"));
        assertEquals("inout", MethodologyService.normalizeFlowPropertyDirection("in-out"));
        assertEquals("out", MethodologyService.normalizeFlowPropertyDirection(null));
        assertEquals(List.of("REQ-001", "REQ-002"), MethodologyService.coalesceRequirementIds(List.of(), List.of("a", "b")));
        List<String[]> pairs = MethodologyService.broadcastPairs(List.of("a", "b"), List.of("x", "y", "z"));
        assertEquals(3, pairs.size());
        assertEquals("a", pairs.get(2)[0]);
        assertTrue(MethodologyService.kindMatches("SysML Block Definition Diagram", "Block Definition Diagram"));
        assertEquals("Class", MethodologyService.queryType("Block"));
        List<Map<String, Object>> defs = MethodologyService.interfaceDefinitions(arr("Sensor Bus",
                obj("name", "Power", "portName", "pwr", "flowProperties", obj("label", "Voltage", "direction", "provided"))));
        assertEquals("sensor_bus", defs.get(0).get("port_name"));
        assertEquals("pwr", defs.get(1).get("port_name"));
        assertEquals(List.of(obj("name", "Voltage", "direction", "out")), defs.get(1).get("flow_properties"));
    }

    @Test
    void reviewPacketMarkdownHasReferenceSections() {
        Map<String, Object> packet = MethodologyService.generateReviewPacket("oosem", "use_case_model",
                obj("subject_name", "ATM"), List.of(art("workspace", "Package", "Workspace", "root-1", null)),
                List.of("Assume one ATM."), List.of("note"), serviceBridge());
        String md = J.str(packet.get("reviewPacketMarkdown"));
        assertTrue(md.contains("## Artifact Keys"));
        assertTrue(md.contains("- Ready To Execute: `False`"));
        assertTrue(md.contains("## Bridge Evidence"));
        assertTrue(md.contains("## Findings"));
        assertTrue(md.contains("## Assumptions\n- Assume one ATM."));
        assertEquals(List.of("note"), m(packet.get("evidenceBundle")).get("notes"));
    }
}
