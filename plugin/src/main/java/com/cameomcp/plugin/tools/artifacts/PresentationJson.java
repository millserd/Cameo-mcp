package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.plugin.cameo.ElementJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.properties.PropertyManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Summaries of diagram presentation elements (symbols, paths) for inspection tools. */
public final class PresentationJson {

    private PresentationJson() {
    }

    /** {id, name, type, owner} of a diagram; an empty object for null. */
    public static JsonObject diagramSummary(DiagramPresentationElement dpe) {
        JsonObject json = new JsonObject();
        if (dpe == null) {
            return json;
        }
        Diagram diagram = dpe.getDiagram();
        if (diagram != null) {
            json.addProperty("id", diagram.getID());
        }
        json.addProperty("name", dpe.getName() != null ? dpe.getName() : "");
        json.addProperty("type", ArtifactSupport.diagramType(dpe));
        if (diagram != null && diagram.getOwner() != null) {
            json.add("owner", ElementJson.toJsonCompact(diagram.getOwner()));
        }
        return json;
    }

    /**
     * One symbol/path: id, class name, model element (if any), bounds and child count. The element and
     * bounds reads are guarded separately because some presentation kinds throw for them.
     */
    public static JsonObject presentationSummary(PresentationElement pe, String parentPresentationId) {
        JsonObject json = new JsonObject();
        if (pe == null) {
            return json;
        }
        json.addProperty("presentationId", pe.getID());
        json.addProperty("presentationClassName", pe.getClass().getName());
        json.addProperty("shapeType", pe.getClass().getSimpleName());
        if (parentPresentationId != null) {
            json.addProperty("parentPresentationId", parentPresentationId);
        }
        try {
            Element element = pe.getElement();
            if (element != null) {
                json.add("element", ElementJson.toJsonCompact(element));
                json.addProperty("elementId", element.getID());
                if (element instanceof NamedElement) {
                    json.addProperty("elementName", ((NamedElement) element).getName());
                }
            }
        } catch (Exception e) {
            json.addProperty("elementWarning", e.getMessage());
        }
        try {
            Rectangle bounds = pe.getBounds();
            if (bounds != null) {
                JsonObject b = new JsonObject();
                b.addProperty("x", bounds.x);
                b.addProperty("y", bounds.y);
                b.addProperty("width", bounds.width);
                b.addProperty("height", bounds.height);
                json.add("bounds", b);
            }
        } catch (Exception e) {
            json.addProperty("boundsWarning", e.getMessage());
        }
        List<PresentationElement> children = pe.getPresentationElements();
        json.addProperty("childCount", children != null ? children.size() : 0);
        return json;
    }

    /** presentationSummary plus its serialized property manager and one level of child summaries. */
    public static JsonObject presentationWithProperties(PresentationElement pe, String parentPresentationId,
                                                        boolean includeRaw, boolean summaryOnly) {
        JsonObject json = presentationSummary(pe, parentPresentationId);
        try {
            PropertyManager manager = propertyManager(pe);
            json.add("propertyManager", PropertyJson.serializeManager(manager, includeRaw, summaryOnly));
        } catch (Exception | LinkageError e) {
            JsonArray warnings = new JsonArray();
            warnings.add("Could not read presentation property manager: " + e.getMessage());
            json.add("warnings", warnings);
        }
        JsonArray children = new JsonArray();
        List<PresentationElement> childElements = pe.getPresentationElements();
        if (childElements != null) {
            for (PresentationElement child : childElements) {
                children.add(presentationSummary(child, pe.getID()));
            }
        }
        json.add("children", children);
        return json;
    }

    /**
     * The presentation element's PropertyManager. Called reflectively: getPropertyManager() is not
     * documented in the 2026x OpenAPI (stubs/API-DIFFERENCES.md section 1).
     */
    public static PropertyManager propertyManager(Object pe) throws Exception {
        Object pm = Reflect.invoke(pe, "getPropertyManager");
        if (pm instanceof PropertyManager) {
            return (PropertyManager) pm;
        }
        return null;
    }

    /** Re-layout a diagram; the API differs between 2024x and 2026x (see DiagramCompat). */
    public static void layout(DiagramPresentationElement dpe) {
        com.cameomcp.plugin.cameo.DiagramCompat.layout(dpe);
    }

    /** Depth-first list of all presentation elements; fills child-id to parent-id when a map is given. */
    public static List<PresentationElement> flatten(DiagramPresentationElement dpe, Map<String, String> parentById) {
        List<PresentationElement> out = new ArrayList<>();
        if (dpe != null) {
            collect(dpe.getPresentationElements(), out, parentById, null);
        }
        return out;
    }

    /** presentationSummary for each element, with the parent id looked up in parentById. */
    public static JsonArray summarize(Collection<PresentationElement> elements, Map<String, String> parentById) {
        JsonArray array = new JsonArray();
        if (elements != null) {
            for (PresentationElement pe : elements) {
                array.add(presentationSummary(pe, parentById != null ? parentById.get(pe.getID()) : null));
            }
        }
        return array;
    }

    /** Count of presentation elements per implementation class simple name, in first-seen order. */
    public static JsonObject countByType(Collection<PresentationElement> elements) {
        JsonObject counts = new JsonObject();
        if (elements != null) {
            Map<String, Integer> map = new LinkedHashMap<>();
            for (PresentationElement pe : elements) {
                String type = pe != null ? pe.getClass().getSimpleName() : "null";
                map.merge(type, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : map.entrySet()) {
                counts.addProperty(e.getKey(), e.getValue());
            }
        }
        return counts;
    }

    // Top-level symbols (parentId null) get no entry in parentById.
    private static void collect(List<PresentationElement> elements, List<PresentationElement> sink,
                                Map<String, String> parentById, String parentId) {
        if (elements == null) {
            return;
        }
        for (PresentationElement pe : elements) {
            if (pe == null) {
                continue;
            }
            sink.add(pe);
            if (parentById != null && parentId != null) {
                parentById.put(pe.getID(), parentId);
            }
            collect(pe.getPresentationElements(), sink, parentById, pe.getID());
        }
    }
}
