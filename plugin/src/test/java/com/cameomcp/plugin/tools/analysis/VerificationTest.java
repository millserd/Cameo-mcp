// family: analysis
package com.cameomcp.plugin.tools.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.analysis.Fixtures.check;
import static com.cameomcp.plugin.tools.analysis.Fixtures.maps;
import static com.cameomcp.plugin.tools.analysis.Fixtures.pngPayload;
import static com.cameomcp.plugin.tools.analysis.Fixtures.rel;
import static com.cameomcp.plugin.tools.analysis.Fixtures.strs;
import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    // ------------------------------------------------------------------ diagram visual

    @Test
    void analyzeDiagramImageHandlesInvalidBase64() {
        Map<String, Object> metrics = Verification.analyzeDiagramImage(obj("image", "%%%not-base64%%%", "width", 10, "height", 10));
        assertEquals(0, ((Number) metrics.get("byteCount")).intValue());
        assertEquals(false, metrics.get("pngSignatureOk"));
        assertEquals(0, ((Number) metrics.get("imageWidth")).intValue());
        assertEquals(0, ((Number) metrics.get("imageHeight")).intValue());
    }

    @Test
    void verifyDiagramVisualRequiresRealRelationshipPaths() {
        Verification.VisualOptions o = new Verification.VisualOptions();
        o.expectedRelationshipIds = strs("rel-1");
        Map<String, Object> result = Verification.verifyDiagramVisual(pngPayload(100, 80),
                obj("shapes", arr(obj("presentationId", "shape-1", "elementId", "rel-1",
                        "shapeType", "PathLabelElement", "elementType", "InformationFlow"))), o);
        assertEquals(false, result.get("ok"));
        Map<String, Object> c = check(result, "expected-relationships-present");
        assertEquals(false, c.get("ok"));
        assertEquals(List.of("rel-1"), m(c.get("details")).get("missing"));
    }

    @Test
    void verifyDiagramVisualReportsOverlapAndContentMetrics() {
        Verification.VisualOptions o = new Verification.VisualOptions();
        o.expectedElementIds = strs("part-a", "part-b");
        o.expectedRelationshipIds = strs("connector-1");
        o.minShapeCount = 3;
        o.minRelationshipShapeCount = 1;
        o.minWidth = 100;
        o.minHeight = 80;
        o.minImageBytes = 100;
        o.minContentCoverageRatio = 0.05;
        o.maxOverlapRatio = 0.2;
        Map<String, Object> result = Verification.verifyDiagramVisual(pngPayload(100, 80), obj("shapes", arr(
                obj("presentationId", "shape-a", "elementId", "part-a", "shapeType", "ShapeElement",
                        "elementType", "Property", "parentPresentationId", null,
                        "bounds", obj("x", 10, "y", 10, "width", 80, "height", 60)),
                obj("presentationId", "shape-b", "elementId", "part-b", "shapeType", "ShapeElement",
                        "elementType", "Property", "parentPresentationId", null,
                        "bounds", obj("x", 20, "y", 15, "width", 80, "height", 60)),
                obj("presentationId", "path-1", "elementId", "connector-1", "shapeType", "ConnectorPathElement",
                        "elementType", "Connector"))), o);
        assertEquals(false, result.get("ok"));
        assertTrue(((Number) m(result.get("image")).get("contentCoverageRatio")).doubleValue() > 0.05);
        assertEquals(List.of("connector-1"), m(result.get("shapes")).get("relationshipElementIds"));
        assertEquals(false, check(result, "shape-overlap").get("ok"));
        assertEquals(true, check(result, "png-signature").get("ok"));
        assertEquals(true, check(result, "image-dimensions").get("ok"));
    }

    @Test
    void verifyDiagramVisualChecksReportedDimensions() {
        Map<String, Object> payload = pngPayload(100, 80);
        payload.put("width", 90);
        Map<String, Object> result = Verification.verifyDiagramVisual(payload, obj("shapes", arr()),
                new Verification.VisualOptions());
        assertEquals(false, result.get("ok"));
        assertEquals(false, check(result, "reported-image-dimensions").get("ok"));
    }

    // ------------------------------------------------------------------ matrix

    @Test
    void verifyMatrixConsistencyReadsDependenciesAndDensity() {
        Map<String, Object> result = Verification.verifyMatrixConsistency(obj(
                        "rowCount", 2, "columnCount", 2,
                        "rows", arr(obj("id", "row-1"), obj("id", "row-2")),
                        "columns", arr(obj("id", "col-1"), obj("id", "col-2")),
                        "populatedCellCount", 1,
                        "populatedCells", arr(obj("rowId", "row-1", "columnId", "col-1",
                                "dependencies", arr(obj("name", "Refine"))))),
                strs("row-1"), strs("col-1"), strs("Refine"), 1, 0.25);
        assertEquals(true, result.get("ok"));
        assertEquals(0.25, ((Number) m(result.get("metrics")).get("density")).doubleValue());
        assertEquals(List.of("Refine"), m(result.get("metrics")).get("dependencyNames"));
    }

    @Test
    void verifyMatrixConsistencyAcceptsLegacyDependencyNameField() {
        Map<String, Object> result = Verification.verifyMatrixConsistency(obj(
                        "rows", arr(obj("id", "row-1")),
                        "columns", arr(obj("id", "col-1")),
                        "populatedCells", arr(obj("dependencies", arr(obj("dependencyName", "DeriveReqt"))))),
                strs(), strs(), strs("DeriveReqt"), 1, 1.0);
        assertEquals(true, result.get("ok"));
        assertEquals(List.of("DeriveReqt"), m(result.get("metrics")).get("dependencyNames"));
    }

    @Test
    void verifyMatrixConsistencyFlagsPayloadCountMismatch() {
        Map<String, Object> result = Verification.verifyMatrixConsistency(obj(
                        "rowCount", 3, "columnCount", 1, "populatedCellCount", 2,
                        "rows", arr(obj("id", "row-1")),
                        "columns", arr(obj("id", "col-1")),
                        "populatedCells", arr(obj("dependencies", arr()))),
                strs(), strs(), strs(), 0, 0.0);
        assertEquals(false, result.get("ok"));
        assertEquals(false, check(result, "payload-counts-consistent").get("ok"));
    }

    // ------------------------------------------------------------------ activity flow

    @Test
    void verifyActivityFlowSemanticsFlagsIslandsAndPartitionSmells() {
        Map<String, Object> result = Verification.verifyActivityFlowSemantics(
                maps(obj("id", "n1", "type", "InitialNode", "name", "Start"),
                        obj("id", "a1", "type", "OpaqueAction", "name", "Query Available Slots"),
                        obj("id", "a2", "type", "OpaqueAction", "name", "Display Available Slots"),
                        obj("id", "f1", "type", "ActivityFinalNode", "name", "Done"),
                        obj("id", "a3", "type", "OpaqueAction", "name", "Send Confirmation"),
                        obj("id", "p1", "type", "ActivityPartition", "name", "«allocate»"),
                        obj("id", "p2", "type", "ActivityPartition", "name", "Scheduling System")),
                maps(rel("r1", "ControlFlow", "n1", "a1"), rel("r2", "ControlFlow", "a1", "a2"),
                        rel("r3", "ControlFlow", "a2", "f1")),
                maps(obj("presentationId", "pe-p1", "elementId", "p1", "elementType", "ActivityPartition"),
                        obj("presentationId", "pe-p2", "parentPresentationId", "pe-p1", "elementId", "p2",
                                "elementType", "ActivityPartition"),
                        obj("presentationId", "pe-a1", "parentPresentationId", "pe-p2", "elementId", "a1",
                                "elementType", "OpaqueAction"),
                        obj("presentationId", "pe-a2", "parentPresentationId", "pe-p2", "elementId", "a2",
                                "elementType", "OpaqueAction"),
                        obj("presentationId", "pe-a3", "parentPresentationId", "pe-p2", "elementId", "a3",
                                "elementType", "OpaqueAction")),
                1, false);
        assertEquals(false, result.get("ok"));
        assertTrue(J.strings(m(result.get("metrics")).get("isolatedActionIds")).contains("a3"));
        assertTrue(J.strings(m(result.get("metrics")).get("unreachableActionIds")).contains("a3"));
        assertEquals(false, check(result, "partition-sanity").get("ok"));
    }

    @Test
    void verifyActivityFlowSemanticsAcceptsConnectedFlow() {
        Map<String, Object> result = Verification.verifyActivityFlowSemantics(
                maps(obj("id", "n1", "type", "InitialNode"),
                        obj("id", "a1", "type", "OpaqueAction", "name", "Select Provider and Service"),
                        obj("id", "a2", "type", "OpaqueAction", "name", "Select Time Slot"),
                        obj("id", "f1", "type", "ActivityFinalNode")),
                maps(rel("r1", "ControlFlow", "n1", "a1"), rel("r2", "ControlFlow", "a1", "a2"),
                        rel("r3", "ControlFlow", "a2", "f1")),
                maps(), 1, false);
        assertEquals(true, result.get("ok"));
    }

    // ------------------------------------------------------------------ ports

    @Test
    void verifyPortBoundaryConsistencyScopesDirectionConflictsToOwner() {
        Map<String, Object> result = Verification.verifyPortBoundaryConsistency(
                maps(obj("id", "if-1", "name", "Customer UI Port Type"),
                        obj("id", "if-2", "name", "Scheduling System Port Type")),
                maps(obj("id", "fp-1", "name", "Available Slots", "ownerId", "if-1", "direction", "out"),
                        obj("id", "fp-2", "name", "Available Slots", "ownerId", "if-1", "direction", "in"),
                        obj("id", "fp-3", "name", "Available Slots", "ownerId", "if-2", "direction", "in")),
                strs("Available Slots"));
        assertEquals(false, result.get("ok"));
        Map<String, Object> metrics = m(result.get("metrics"));
        assertFalse(m(metrics.get("duplicateFlowProperties")).containsKey("available slots"));
        Map<String, Object> conflicts = m(metrics.get("directionConflicts"));
        assertTrue(conflicts.containsKey("Customer UI Port Type"));
        assertTrue(m(conflicts.get("Customer UI Port Type")).containsKey("available slots"));
        assertFalse(conflicts.containsKey("Scheduling System Port Type"));
    }

    // ------------------------------------------------------------------ requirements

    @Test
    void verifyRequirementQualityFlagsBlankAndWeakRequirements() {
        Map<String, Object> result = Verification.verifyRequirementQuality(maps(
                obj("id", "req-1", "name", "Appointment Response Time",
                        "appliedStereotypes", arr(obj("stereotype", "Requirement",
                                "taggedValues", obj("id", "REQ-1", "text", "")))),
                obj("id", "req-2", "name", "System Availability",
                        "appliedStereotypes", arr(obj("stereotype", "Requirement",
                                "taggedValues", obj("id", "", "text", "The system shall be easy to use.")))),
                obj("id", "req-3", "name", "Confirmation",
                        "documentation", "The system shall send confirmation within 5 seconds of booking.")),
                true, true, 20);
        assertEquals(false, result.get("ok"));
        Map<String, Object> metrics = m(result.get("metrics"));
        assertTrue(J.strings(metrics.get("blankTextIds")).contains("req-1"));
        assertTrue(J.strings(metrics.get("missingIdIds")).contains("req-2"));
        assertTrue(J.strings(metrics.get("weakTextIds")).contains("req-2"));
        Map<String, Object> req3 = null;
        for (Map<String, Object> item : J.maps(metrics.get("evaluatedRequirements"))) {
            if ("req-3".equals(item.get("elementId"))) {
                req3 = item;
            }
        }
        assertEquals(true, req3.get("isMeasurable"));
    }

    // ------------------------------------------------------------------ traceability

    @Test
    void verifyCrossDiagramTraceabilityFlagsMissingMatches() {
        Map<String, Object> result = Verification.verifyCrossDiagramTraceability(
                strs("Query Available Slots", "Display Available Slots", "Select Provider and Service"),
                strs("Available Slots", "Appointment Record"),
                strs("Available Slots", "Provider Selection"),
                obj("req-1", arr("blk-1"), "req-2", arr()),
                strs("req-1", "req-2"), strs("blk-1"));
        assertEquals(false, result.get("ok"));
        Map<String, Object> metrics = m(result.get("metrics"));
        assertTrue(J.strings(metrics.get("missingPortTerms")).contains("Appointment Record"));
        assertTrue(J.strings(metrics.get("missingRequirementTraceIds")).contains("req-2"));
    }

    @Test
    void verifyCrossDiagramTraceabilityRejectsSingleTokenOverlapFalsePositive() {
        Map<String, Object> result = Verification.verifyCrossDiagramTraceability(
                strs("AAS System Status"), strs("System"), strs("System"), obj(), strs(), strs());
        assertEquals(false, result.get("ok"));
        Map<String, Object> metrics = m(result.get("metrics"));
        assertTrue(J.strings(metrics.get("missingPortTerms")).contains("System"));
        assertTrue(J.strings(metrics.get("missingIbdTerms")).contains("System"));
    }

    @Test
    void extractTraceTermsIncludeFlowLabels() {
        List<String> activityTerms = Verification.extractActivityTraceTerms(
                maps(obj("id", "a1", "type", "OpaqueAction", "name", "Display Available Slots"),
                        obj("id", "o1", "type", "ObjectNode", "name", "Available Slots")),
                maps(obj("relationshipId", "r1", "type", "ObjectFlow",
                        "itemProperty", obj("id", "p1", "name", "Available Slots"),
                        "conveyed", arr(obj("id", "blk-1", "name", "Appointment Record")))));
        List<String> ibdTerms = Verification.extractIbdTraceTerms(
                maps(obj("id", "p1", "type", "Port", "name", "Customer UI")),
                maps(obj("relationshipId", "r2", "type", "ItemFlow", "name", "Availability Query",
                        "conveyed", arr(obj("id", "blk-2", "name", "Available Slots")))));
        assertTrue(activityTerms.contains("Available Slots"));
        assertTrue(activityTerms.contains("Appointment Record"));
        assertTrue(ibdTerms.contains("Availability Query"));
    }
}
