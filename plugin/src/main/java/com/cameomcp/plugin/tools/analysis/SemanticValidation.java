package com.cameomcp.plugin.tools.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.cameomcp.plugin.tools.analysis.J.obj;
import static com.cameomcp.plugin.tools.analysis.J.truthy;

/**
 * Semantic validation over live model data: gathers diagram snapshots, interface flow properties
 * and requirement specifications through a {@link Bridge}, then applies the {@link Verification}
 * heuristics.
 */
public final class SemanticValidation {

    /** Static methods only. */
    private SemanticValidation() {
    }

    /**
     * Interface blocks and flow properties via the bridge, copied so callers can add fields safely.
     *
     * @param ids interface block IDs
     * @param bridge model access
     * @return a mutable copy of the payload
     * @throws IllegalStateException when the bridge returns null
     * @throws Exception from the bridge
     */
    static Map<String, Object> readInterfaceFlowProperties(List<String> ids, Bridge bridge) throws Exception {
        Map<String, Object> payload = bridge.getInterfaceFlowProperties(ids);
        if (payload == null) {
            throw new IllegalStateException("Bridge returned a non-object payload from get_interface_flow_properties.");
        }
        return new LinkedHashMap<>(payload);
    }

    /** IDs of a relationship endpoint list ({@code [{id, ...}]}); missing IDs become "". */
    private static List<String> endpointIdList(Object endpoints) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : J.maps(endpoints)) {
            out.add(J.text(item.get("id")));
        }
        return out;
    }

    /**
     * Identity key for a relationship as reported by cameo_get_relationships. The same relationship
     * appears in the responses of both its ends (outgoing for one, incoming for the other), so it
     * is keyed by ID, type and endpoints rather than by object identity.
     *
     * @param rel one relationship entry
     * @return [relationshipId, type, source IDs, target IDs, related element IDs]
     */
    static List<Object> relationshipSignature(Map<String, Object> rel) {
        return Arrays.asList(
                rel.get("relationshipId"),
                J.or(rel.get("type"), rel.get("relationshipType")),
                endpointIdList(rel.get("sources")),
                endpointIdList(rel.get("targets")),
                endpointIdList(rel.get("relatedElements")));
    }

    /**
     * Merges get_relationships responses for many elements into one list, first occurrence wins.
     *
     * @param responses get_relationships responses; null entries are skipped
     * @return the unique relationships, in first-seen order
     */
    static List<Map<String, Object>> flattenRelationshipResponses(List<Map<String, Object>> responses) {
        Map<List<Object>, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> response : responses) {
            if (response == null) {
                continue;
            }
            for (String bucket : new String[]{"outgoing", "incoming", "undirected"}) {
                for (Map<String, Object> rel : J.maps(response.get(bucket))) {
                    deduped.putIfAbsent(relationshipSignature(rel), new LinkedHashMap<>(rel));
                }
            }
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * Sorted unique IDs of the model elements shown on a diagram (shapes without an element are skipped).
     *
     * @param diagramShapes a cameo_list_diagram_shapes response
     * @return the element IDs
     */
    static List<String> shapeElementIds(Map<String, Object> diagramShapes) {
        Set<String> ids = new TreeSet<>();
        for (Map<String, Object> shape : J.maps(diagramShapes.get("shapes"))) {
            if (truthy(shape.get("elementId"))) {
                ids.add(J.str(shape.get("elementId")));
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Diagram shapes, the elements behind them and their relationships (deduplicated).
     *
     * <p>Calls get_element and get_relationships once per shown element.
     *
     * @param diagramId diagram element ID
     * @param bridge model access
     * @return diagramId, diagramShapes, elements and relationships
     * @throws Exception from the bridge
     */
    static Map<String, Object> gatherDiagramSnapshot(String diagramId, Bridge bridge) throws Exception {
        Map<String, Object> shapes = nonNull(bridge.listDiagramShapes(diagramId));
        List<String> ids = shapeElementIds(shapes);
        List<Object> elements = new ArrayList<>();
        List<Map<String, Object>> responses = new ArrayList<>();
        for (String id : ids) {
            elements.add(bridge.getElement(id));
        }
        for (String id : ids) {
            responses.add(bridge.getRelationships(id));
        }
        return obj(
                "diagramId", diagramId,
                "diagramShapes", shapes,
                "elements", elements,
                "relationships", flattenRelationshipResponses(responses));
    }

    /**
     * Treats a null bridge response as an empty object.
     *
     * @param m a bridge response, or null
     * @return m, or a new empty map
     */
    static Map<String, Object> nonNull(Map<String, Object> m) {
        return m == null ? new LinkedHashMap<>() : m;
    }

    /**
     * Combines get_element and get_specification for one requirement: the element JSON plus the full
     * specification under "specification", with the specification's richer fields (documentation,
     * properties, stereotypes, constraints) overriding the compact element fields.
     *
     * @param element get_element response
     * @param spec get_specification response
     * @return a new merged map
     */
    static Map<String, Object> mergeRequirementPayload(Map<String, Object> element, Map<String, Object> spec) {
        Map<String, Object> merged = J.copy(element);
        Map<String, Object> specification = J.copy(spec);
        merged.put("specification", specification);
        for (String key : new String[]{"name", "type", "documentation", "properties", "appliedStereotypes", "constraints"}) {
            if (specification.containsKey(key)) {
                merged.put(key, specification.get(key));
            }
        }
        return merged;
    }

    /**
     * Sorted IDs of every element related to a requirement in any direction (the requirement itself
     * excluded).
     *
     * @param requirementId the requirement's ID
     * @param response its get_relationships response, or null
     * @return the related IDs; empty for null
     */
    static List<String> relatedRequirementArchitectureIds(String requirementId, Map<String, Object> response) {
        Set<String> related = new TreeSet<>();
        if (response == null) {
            return new ArrayList<>();
        }
        for (String bucket : new String[]{"outgoing", "incoming"}) {
            for (Map<String, Object> rel : J.maps(response.get(bucket))) {
                for (String endpointKey : new String[]{"sources", "targets"}) {
                    for (Map<String, Object> endpoint : J.maps(rel.get(endpointKey))) {
                        Object id = endpoint.get("id");
                        if (truthy(id) && !J.str(id).equals(requirementId)) {
                            related.add(J.str(id));
                        }
                    }
                }
            }
        }
        for (Map<String, Object> rel : J.maps(response.get("undirected"))) {
            for (Map<String, Object> endpoint : J.maps(rel.get("relatedElements"))) {
                Object id = endpoint.get("id");
                if (truthy(id) && !J.str(id).equals(requirementId)) {
                    related.add(J.str(id));
                }
            }
        }
        return new ArrayList<>(related);
    }

    // ------------------------------------------------------------------ entry points

    /**
     * cameo_verify_activity_flow_semantics: snapshots the activity diagram and checks flow and partition
     * semantics.
     *
     * @param diagramId activity diagram ID
     * @param maxPartitionDepth deepest allowed partition nesting
     * @param allowStereotypePartitionLabels accept partition labels that look like stereotype notation, such as
     *     «allocate»
     * @param bridge model access
     * @return the verification result plus the snapshot it was based on
     * @throws Exception from the bridge
     */
    public static Map<String, Object> activityFlowForDiagram(String diagramId, int maxPartitionDepth,
                                                             boolean allowStereotypePartitionLabels,
                                                             Bridge bridge) throws Exception {
        Map<String, Object> snapshot = gatherDiagramSnapshot(diagramId, bridge);
        Map<String, Object> shapesPayload = J.map(snapshot.get("diagramShapes"));
        Map<String, Object> result = Verification.verifyActivityFlowSemantics(
                J.maps(snapshot.get("elements")),
                J.maps(snapshot.get("relationships")),
                J.maps(shapesPayload.get("shapes")),
                maxPartitionDepth,
                allowStereotypePartitionLabels);
        result.put("diagramId", diagramId);
        result.put("elements", snapshot.get("elements"));
        result.put("relationships", snapshot.get("relationships"));
        result.put("diagramShapes", shapesPayload);
        return result;
    }

    /**
     * cameo_verify_port_boundary_consistency: checks flow-property names and directions across interface
     * blocks.
     *
     * @param interfaceBlockIds interface block IDs
     * @param allowSharedFlowPropertyNames flow property names allowed on more than one block, or null
     * @param bridge model access
     * @return the verification result plus interfaceBlocks and flowProperties
     * @throws Exception from the bridge
     */
    public static Map<String, Object> portBoundaryForInterfaces(List<String> interfaceBlockIds,
                                                                List<String> allowSharedFlowPropertyNames,
                                                                Bridge bridge) throws Exception {
        Map<String, Object> payload = readInterfaceFlowProperties(interfaceBlockIds, bridge);
        List<Map<String, Object>> blocks = J.maps(payload.get("interfaceBlocks"));
        List<Map<String, Object>> flows = J.maps(payload.get("flowProperties"));
        Map<String, Object> result = Verification.verifyPortBoundaryConsistency(blocks, flows, allowSharedFlowPropertyNames);
        result.put("interfaceBlocks", blocks);
        result.put("flowProperties", flows);
        return result;
    }

    /**
     * cameo_verify_requirement_quality: loads each requirement's element and specification and grades its
     * text.
     *
     * @param requirementIds requirement IDs
     * @param requireId fail requirements without an ID
     * @param requireMeasurement fail requirements that do not look directive (shall, must, will) and measurable
     * @param minTextLength minimum requirement text length
     * @param bridge model access
     * @return the verification result plus the merged requirements
     * @throws Exception from the bridge
     */
    public static Map<String, Object> requirementQualityForIds(List<String> requirementIds, boolean requireId,
                                                               boolean requireMeasurement, int minTextLength,
                                                               Bridge bridge) throws Exception {
        List<Map<String, Object>> elements = new ArrayList<>();
        for (String id : requirementIds) {
            elements.add(bridge.getElement(id));
        }
        List<Map<String, Object>> specs = new ArrayList<>();
        for (String id : requirementIds) {
            specs.add(bridge.getSpecification(id));
        }
        List<Map<String, Object>> requirements = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            requirements.add(mergeRequirementPayload(elements.get(i), specs.get(i)));
        }
        Map<String, Object> result = Verification.verifyRequirementQuality(requirements, requireId, requireMeasurement, minTextLength);
        result.put("requirements", requirements);
        return result;
    }

    /**
     * cameo_verify_cross_diagram_traceability: collects naming terms from the activity diagram, the
     * interface flow properties and the IBD, plus each requirement's related elements, then checks
     * that they line up. Every input is optional; omitted sources contribute empty term lists.
     *
     * @param activityDiagramId activity diagram ID, or null to skip
     * @param interfaceBlockIds interface block IDs, or null or empty to skip
     * @param ibdDiagramId internal block diagram ID, or null to skip
     * @param requirementIds requirement IDs, or null or empty to skip
     * @param architectureElementIds architecture element IDs the requirements should trace to
     * @param bridge model access
     * @return the verification result plus the collected terms and requirement links
     * @throws Exception from the bridge
     */
    public static Map<String, Object> crossDiagramTraceability(String activityDiagramId,
                                                               List<String> interfaceBlockIds,
                                                               String ibdDiagramId,
                                                               List<String> requirementIds,
                                                               List<String> architectureElementIds,
                                                               Bridge bridge) throws Exception {
        List<String> activityTerms = new ArrayList<>();
        List<String> portTerms = new ArrayList<>();
        List<String> ibdTerms = new ArrayList<>();
        Map<String, Object> requirementLinks = new LinkedHashMap<>();
        if (truthy(activityDiagramId)) {
            Map<String, Object> snap = gatherDiagramSnapshot(activityDiagramId, bridge);
            activityTerms = Verification.extractActivityTraceTerms(J.maps(snap.get("elements")), J.maps(snap.get("relationships")));
        }
        if (interfaceBlockIds != null && !interfaceBlockIds.isEmpty()) {
            Map<String, Object> payload = readInterfaceFlowProperties(interfaceBlockIds, bridge);
            portTerms = flowPropertyNames(payload);
        }
        if (truthy(ibdDiagramId)) {
            Map<String, Object> snap = gatherDiagramSnapshot(ibdDiagramId, bridge);
            ibdTerms = Verification.extractIbdTraceTerms(J.maps(snap.get("elements")), J.maps(snap.get("relationships")));
        }
        if (requirementIds != null && !requirementIds.isEmpty()) {
            List<Map<String, Object>> responses = new ArrayList<>();
            for (String id : requirementIds) {
                responses.add(bridge.getRelationships(id));
            }
            for (int i = 0; i < requirementIds.size(); i++) {
                String id = requirementIds.get(i);
                requirementLinks.put(id, relatedRequirementArchitectureIds(id, responses.get(i)));
            }
        }
        Map<String, Object> result = Verification.verifyCrossDiagramTraceability(
                activityTerms, portTerms, ibdTerms, requirementLinks, requirementIds, architectureElementIds);
        result.put("activityTerms", activityTerms);
        result.put("portTerms", portTerms);
        result.put("ibdTerms", ibdTerms);
        result.put("requirementLinks", requirementLinks);
        return result;
    }

    /**
     * Sorted unique non-blank flow property names (trimmed).
     *
     * @param payload interface flow property payload
     * @return the names
     */
    static List<String> flowPropertyNames(Map<String, Object> payload) {
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> fp : J.maps(payload.get("flowProperties"))) {
            String name = J.text(fp.get("name")).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }
}
