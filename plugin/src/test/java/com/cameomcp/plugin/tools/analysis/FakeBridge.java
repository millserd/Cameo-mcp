package com.cameomcp.plugin.tools.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;

/** In-memory bridge for analysis tests: canned reads plus a recorder for write operations. */
public class FakeBridge implements Bridge {

    public final Map<String, Map<String, Object>> shapes = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> elements = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> specifications = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> relationships = new LinkedHashMap<>();
    public Map<String, Object> interfaceFlowPayload = obj("interfaceBlocks", arr(), "flowProperties", arr());
    public final List<List<String>> interfaceFlowCalls = new ArrayList<>();
    /** (operation, params) of every write / invoke call, in order. */
    public final List<Object[]> calls = new ArrayList<>();

    /** Fallbacks for ids missing from the maps; null means "throw". */
    public Function<String, Map<String, Object>> elementFallback;
    public Function<String, Map<String, Object>> specificationFallback;
    public Function<String, Map<String, Object>> shapesFallback;
    public boolean relationshipsDefaultEmpty = true;

    public Map<String, Object> diagramsPayload;
    public Map<String, Object> queryPayload;
    public Function<String, Map<String, Object>> imageFn;
    public final List<Map<String, Object>> imageOptions = new ArrayList<>();
    public Function<String, Map<String, Object>> matrixFn;
    public int counter;

    private static Map<String, Object> lookup(Map<String, Map<String, Object>> map, String id,
                                              Function<String, Map<String, Object>> fallback, String what) {
        Map<String, Object> v = map.get(id);
        if (v != null) {
            return v;
        }
        if (fallback != null) {
            return fallback.apply(id);
        }
        throw new NoSuchElementException(what + " not found: " + id);
    }

    @Override
    public Map<String, Object> listDiagramShapes(String diagramId) {
        return lookup(shapes, diagramId, shapesFallback, "diagram");
    }

    @Override
    public Map<String, Object> getElement(String elementId) {
        return lookup(elements, elementId, elementFallback, "element");
    }

    @Override
    public Map<String, Object> getRelationships(String elementId) {
        Map<String, Object> r = relationships.get(elementId);
        if (r == null) {
            if (!relationshipsDefaultEmpty) {
                throw new NoSuchElementException("relationships not found: " + elementId);
            }
            r = obj("outgoing", arr(), "incoming", arr(), "undirected", arr());
        }
        return r;
    }

    @Override
    public Map<String, Object> getSpecification(String elementId) {
        return lookup(specifications, elementId, specificationFallback, "specification");
    }

    @Override
    public Map<String, Object> getInterfaceFlowProperties(List<String> ids) {
        interfaceFlowCalls.add(new ArrayList<>(ids));
        return interfaceFlowPayload;
    }

    @Override
    public Map<String, Object> queryElements(Map<String, Object> params) throws Exception {
        calls.add(new Object[]{"query_elements", params});
        if (queryPayload == null) {
            return Bridge.super.queryElements(params);
        }
        return queryPayload;
    }

    @Override
    public Map<String, Object> listDiagrams() throws Exception {
        if (diagramsPayload == null) {
            return Bridge.super.listDiagrams();
        }
        return diagramsPayload;
    }

    @Override
    public Map<String, Object> modifyElement(String elementId, Map<String, Object> fields) {
        Map<String, Object> params = obj("element_id", elementId);
        params.putAll(fields);
        calls.add(new Object[]{"modify_element", params});
        return obj("id", elementId, "updated", true);
    }

    @Override
    public Map<String, Object> setSpecification(String elementId, Map<String, Object> properties) {
        calls.add(new Object[]{"set_specification", obj("element_id", elementId, "properties", properties)});
        return obj("elementId", elementId, "updated", true);
    }

    @Override
    public Map<String, Object> getDiagramImage(String diagramId, Map<String, Object> options) throws Exception {
        imageOptions.add(options == null ? new LinkedHashMap<>() : options);
        if (imageFn == null) {
            return Bridge.super.getDiagramImage(diagramId, options);
        }
        Map<String, Object> image = new LinkedHashMap<>(imageFn.apply(diagramId));
        if (options != null && options.get("format") != null) {
            image.put("format", options.get("format"));
        }
        return image;
    }

    @Override
    public Map<String, Object> getMatrix(String matrixId) throws Exception {
        if (matrixFn == null) {
            return Bridge.super.getMatrix(matrixId);
        }
        return matrixFn.apply(matrixId);
    }

    /** Recipe operations: create_* return ids, add_to_diagram a presentationId, paths a results list. */
    @Override
    public Map<String, Object> invoke(String operation, Map<String, Object> params) {
        switch (operation) {
            case "create_element":
                calls.add(new Object[]{operation, params});
                counter++;
                return obj("id", "el-" + counter, "name", params.get("name"));
            case "create_relationship":
                calls.add(new Object[]{operation, params});
                counter++;
                return obj("id", "rel-" + counter, "name", params.get("name"));
            case "create_diagram":
                calls.add(new Object[]{operation, params});
                counter++;
                return obj("id", "dia-" + counter, "name", params.get("name"));
            case "add_to_diagram":
                calls.add(new Object[]{operation, params});
                counter++;
                return obj("presentationId", "pe-" + counter, "diagramId", params.get("diagram_id"));
            case "add_diagram_paths":
                counter++;
                return obj("results", arr(obj("presentationId", "path-" + counter)));
            case "set_specification":
                calls.add(new Object[]{operation, params});
                return obj("updated", true);
            case "set_shape_compartments":
            case "route_paths":
            case "set_usecase_subject":
                return obj("updated", true);
            default:
                throw new UnsupportedOperationException("Bridge client does not implement " + operation + "()");
        }
    }

    public Map<String, Object> lastImageOptions() {
        return imageOptions.get(imageOptions.size() - 1);
    }

    public List<Map<String, Object>> callsOf(String operation) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] c : calls) {
            if (operation.equals(c[0])) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) c[1];
                out.add(p);
            }
        }
        return out;
    }
}
