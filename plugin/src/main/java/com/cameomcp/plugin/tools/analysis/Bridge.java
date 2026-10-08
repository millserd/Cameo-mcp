package com.cameomcp.plugin.tools.analysis;

import java.util.List;
import java.util.Map;

/**
 * Model data access used by the analysis family. The production implementation
 * ({@link InternalBridge}) calls the public tools of the other families in-process; tests use
 * in-memory fakes. Every method returns the JSON object of the corresponding tool contract.
 * Methods a fake does not need may be left unimplemented: they throw
 * {@link UnsupportedOperationException}, which callers that probe optional data treat as "no data".
 */
public interface Bridge {

    /** All shapes of a diagram ({@code cameo_list_diagram_shapes}, every page merged into {@code shapes}). */
    default Map<String, Object> listDiagramShapes(String diagramId) throws Exception {
        throw new UnsupportedOperationException("list_diagram_shapes");
    }

    /** {@code cameo_get_element}. */
    default Map<String, Object> getElement(String elementId) throws Exception {
        throw new UnsupportedOperationException("get_element");
    }

    /** {@code cameo_get_relationships} (outgoing / incoming / undirected buckets). */
    default Map<String, Object> getRelationships(String elementId) throws Exception {
        throw new UnsupportedOperationException("get_relationships");
    }

    /** {@code cameo_get_specification}. */
    default Map<String, Object> getSpecification(String elementId) throws Exception {
        throw new UnsupportedOperationException("get_specification");
    }

    /** Interface blocks plus their owned flow properties: {@code {interfaceBlocks:[...], flowProperties:[...]}}. */
    default Map<String, Object> getInterfaceFlowProperties(List<String> interfaceBlockIds) throws Exception {
        throw new UnsupportedOperationException("get_interface_flow_properties");
    }

    /** {@code cameo_query_elements} with the given parameters (type, name, package_id, recursive, limit, view). */
    default Map<String, Object> queryElements(Map<String, Object> params) throws Exception {
        throw new UnsupportedOperationException("query_elements");
    }

    /** {@code cameo_list_diagrams}. */
    default Map<String, Object> listDiagrams() throws Exception {
        throw new UnsupportedOperationException("list_diagrams");
    }

    /** {@code cameo_modify_element} with {@code name} and/or {@code documentation}. */
    default Map<String, Object> modifyElement(String elementId, Map<String, Object> fields) throws Exception {
        throw new UnsupportedOperationException("modify_element");
    }

    /** {@code cameo_set_specification} with a {@code properties} map. */
    default Map<String, Object> setSpecification(String elementId, Map<String, Object> properties) throws Exception {
        throw new UnsupportedOperationException("set_specification");
    }

    /** {@code cameo_get_diagram_image}; {@code options} are extra tool parameters (format, include_image, ...). */
    default Map<String, Object> getDiagramImage(String diagramId, Map<String, Object> options) throws Exception {
        throw new UnsupportedOperationException("get_diagram_image");
    }

    /** {@code cameo_get_matrix}. */
    default Map<String, Object> getMatrix(String matrixId) throws Exception {
        throw new UnsupportedOperationException("get_matrix");
    }

    /**
     * A methodology recipe operation (create_element, create_relationship, create_diagram, add_to_diagram,
     * add_diagram_paths, set_specification, set_shape_properties, set_shape_compartments, reparent_shapes,
     * route_paths, apply_profile, apply_stereotype, set_usecase_subject) with its snake_case tool parameters.
     */
    default Map<String, Object> invoke(String operation, Map<String, Object> params) throws Exception {
        throw new UnsupportedOperationException(operation);
    }
}
