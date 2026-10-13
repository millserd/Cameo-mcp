package com.cameomcp.plugin.tools.analysis;

import com.cameomcp.plugin.tools.Internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.analysis.J.obj;

/**
 * Production {@link Bridge}: reads and writes through the public tools of the other families
 * ({@link Internal#call}), plus a direct model read for interface flow properties.
 */
public final class InternalBridge implements Bridge {

    /** Shape pages are merged up to this many shapes. */
    static final int MAX_SHAPES = 20000;
    private static final int SHAPE_PAGE = 1000;

    /*
     * Pages through cameo_list_diagram_shapes and merges all pages into one response shaped like a
     * single unpaged page. Stops at MAX_SHAPES, when hasMore is false, or when nextOffset fails to
     * advance (guards against an infinite loop if the tool misreports paging).
     */
    @Override
    public Map<String, Object> listDiagramShapes(String diagramId) throws Exception {
        List<Object> shapes = new ArrayList<>();
        Map<String, Object> first = null;
        int offset = 0;
        while (shapes.size() < MAX_SHAPES) {
            Map<String, Object> page = Internal.call("cameo_list_diagram_shapes",
                    obj("diagram_id", diagramId, "limit", SHAPE_PAGE, "offset", offset));
            if (first == null) {
                first = page;
            }
            List<Object> items = J.list(page.get("shapes"));
            shapes.addAll(items);
            Integer next = J.safeInt(page.get("nextOffset"));
            if (!J.truthy(page.get("hasMore")) || next == null || next <= offset || items.isEmpty()) {
                break;
            }
            offset = next;
        }
        // Reuse the first page's metadata (diagram info etc.) and overwrite the paging fields.
        Map<String, Object> merged = new LinkedHashMap<>(first == null ? obj() : first);
        merged.put("shapes", shapes);
        merged.put("count", shapes.size());
        merged.put("hasMore", false);
        merged.remove("nextOffset");
        merged.put("offset", 0);
        return merged;
    }

    @Override
    public Map<String, Object> getElement(String elementId) throws Exception {
        return Internal.call("cameo_get_element", "element_id", elementId);
    }

    @Override
    public Map<String, Object> getRelationships(String elementId) throws Exception {
        return Internal.call("cameo_get_relationships", "element_id", elementId);
    }

    @Override
    public Map<String, Object> getSpecification(String elementId) throws Exception {
        return Internal.call("cameo_get_specification", "element_id", elementId);
    }

    @Override
    public Map<String, Object> getInterfaceFlowProperties(List<String> interfaceBlockIds) throws Exception {
        return InterfaceFlowReader.read(interfaceBlockIds);
    }

    // cameo_query_elements: null-valued params are dropped so the tool sees them as absent, and the
    // legacy "package" key is renamed to the tool's "package_id" parameter.
    @Override
    public Map<String, Object> queryElements(Map<String, Object> params) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() != null) {
                args.put(e.getKey(), e.getValue());
            }
        }
        if (args.containsKey("package") && !args.containsKey("package_id")) {
            args.put("package_id", args.remove("package"));
        }
        return Internal.call("cameo_query_elements", args);
    }

    @Override
    public Map<String, Object> listDiagrams() throws Exception {
        return Internal.call("cameo_list_diagrams", obj());
    }

    @Override
    public Map<String, Object> modifyElement(String elementId, Map<String, Object> fields) throws Exception {
        Map<String, Object> args = obj("element_id", elementId);
        args.putAll(fields);
        return Internal.call("cameo_modify_element", args);
    }

    @Override
    public Map<String, Object> setSpecification(String elementId, Map<String, Object> properties) throws Exception {
        return Internal.call("cameo_set_specification", obj("element_id", elementId, "properties", properties));
    }

    @Override
    public Map<String, Object> getDiagramImage(String diagramId, Map<String, Object> options) throws Exception {
        Map<String, Object> args = obj("diagram_id", diagramId);
        if (options != null) {
            args.putAll(options);
        }
        return Internal.call("cameo_get_diagram_image", args);
    }

    @Override
    public Map<String, Object> getMatrix(String matrixId) throws Exception {
        return Internal.call("cameo_get_matrix", "matrix_id", matrixId);
    }

    // Methodology recipe operations map 1:1 onto public tools named "cameo_" + operation;
    // null parameters are dropped so optional tool arguments fall back to their defaults.
    @Override
    public Map<String, Object> invoke(String operation, Map<String, Object> params) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() != null) {
                args.put(e.getKey(), e.getValue());
            }
        }
        return Internal.call("cameo_" + operation, args);
    }
}
