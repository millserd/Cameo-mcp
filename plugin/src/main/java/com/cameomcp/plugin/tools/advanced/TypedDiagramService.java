package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Typed diagram listing/inspection and gated write previews (reference TypedDiagramHandler). */
final class TypedDiagramService {

    // Write operations that can be previewed; none of them is executed natively yet.
    static final List<String> OPERATIONS = Arrays.asList(
            "sequence-message", "state-transition", "parametric-binding", "legend-apply");

    private TypedDiagramService() {
    }

    // cameo_get_typed_diagram_capabilities: reads use the core diagram API; the class probe is diagnostic only.
    static JsonObject capabilities() {
        JsonObject response = Support.baseCapabilities("typedDiagrams", "native-read-plus-preview");
        response.addProperty("available", true);
        response.addProperty("status", "available");
        response.add("sequenceApiProbe", Support.classProbe(
                "com.nomagic.magicdraw.openapi.uml.PresentationElementsManager",
                "com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement",
                "com.nomagic.uml2.ext.jmi.helpers.InteractionHelper"));
        response.addProperty("writeMode", "diagram-type-gated-preview");
        response.add("previewOperations", Gs.strings(OPERATIONS));
        return response;
    }

    // Cameo's diagram type string (e.g. "SysML Block Definition Diagram"); empty when the type cannot be read.
    private static String typeOf(DiagramPresentationElement dpe) {
        try {
            return dpe.getDiagramType() != null ? dpe.getDiagramType().getType() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /*
     * cameo_list_typed_diagrams: every diagram with its type, optionally filtered by a case-insensitive substring of
     * the type. countsByType is computed over all diagrams, before the filter, so callers see what else exists.
     */
    static JsonObject list(JsonObject p) throws Exception {
        String typeFilter = Gs.str(p, "diagram_type");
        return Edt.read(project -> {
            JsonArray diagrams = new JsonArray();
            Map<String, Integer> byType = new LinkedHashMap<>();
            Collection<DiagramPresentationElement> presentations = project.getDiagrams();
            if (presentations != null) {
                for (DiagramPresentationElement dpe : presentations) {
                    String type = typeOf(dpe);
                    byType.merge(type, 1, Integer::sum);
                    if (typeFilter != null && !type.toLowerCase(Locale.ROOT).contains(typeFilter.trim().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    Diagram diagram = dpe.getDiagram();
                    entry.addProperty("id", diagram != null ? diagram.getID() : "");
                    entry.addProperty("name", dpe.getName());
                    entry.addProperty("diagramType", type);
                    diagrams.add(entry);
                }
            }
            JsonObject response = capabilities();
            response.addProperty("count", diagrams.size());
            JsonObject counts = new JsonObject();
            byType.forEach(counts::addProperty);
            response.add("countsByType", counts);
            response.add("diagrams", diagrams);
            return response;
        });
    }

    /*
     * cameo_inspect_typed_diagram: flattened shape/path list of one diagram (up to limit, 1..5000) with owner links,
     * model element, bounds and per-class counts. Counts and presentationCount always cover the whole diagram.
     */
    static JsonObject inspect(JsonObject p) throws Exception {
        String diagramId = Gs.reqStr(p, "diagram_id").trim();
        int limit = Math.max(1, Math.min(5000, Gs.integer(p, "limit", 500)));
        return Edt.read(project -> {
            DiagramPresentationElement dpe = null;
            Collection<DiagramPresentationElement> all = project.getDiagrams();
            if (all != null) {
                // Diagrams are matched by the model Diagram's ID, which is what other tools return, not the presentation ID.
                for (DiagramPresentationElement candidate : all) {
                    Diagram d = candidate.getDiagram();
                    if (d != null && diagramId.equals(d.getID())) {
                        dpe = candidate;
                        break;
                    }
                }
            }
            if (dpe == null) {
                throw new ToolException("NOT_FOUND", "Diagram '" + diagramId + "' not found. Use cameo_list_typed_diagrams "
                        + "for diagram IDs.");
            }
            // Diagrams not yet opened in this session may have no symbols loaded; ensureLoaded populates them.
            dpe.ensureLoaded();
            List<PresentationElement> flat = new ArrayList<>();
            Map<String, String> parentById = new LinkedHashMap<>();
            collect(dpe.getPresentationElements(), flat, parentById, null);
            JsonArray presentations = new JsonArray();
            Map<String, Integer> byType = new LinkedHashMap<>();
            for (PresentationElement pe : flat) {
                byType.merge(pe.getClass().getSimpleName(), 1, Integer::sum);
                if (presentations.size() < limit) {
                    presentations.add(summary(pe, parentById.get(pe.getID())));
                }
            }
            JsonObject response = capabilities();
            response.addProperty("diagramId", diagramId);
            response.addProperty("name", dpe.getName());
            response.addProperty("diagramType", typeOf(dpe));
            response.addProperty("presentationCount", flat.size());
            JsonObject counts = new JsonObject();
            byType.forEach(counts::addProperty);
            response.add("countsByShapeType", counts);
            response.add("presentations", presentations);
            response.addProperty("truncated", flat.size() > presentations.size());
            return response;
        });
    }

    // Pre-order flatten of nested symbols, recording each child's enclosing symbol ID.
    private static void collect(List<PresentationElement> elements, List<PresentationElement> sink,
                                Map<String, String> parentById, String parentId) {
        if (elements == null) {
            return;
        }
        for (PresentationElement pe : elements) {
            sink.add(pe);
            if (parentId != null) {
                parentById.put(pe.getID(), parentId);
            }
            collect(pe.getPresentationElements(), sink, parentById, pe.getID());
        }
    }

    // One symbol: identity, class, linked model element and bounds. Element and bounds are read defensively per symbol.
    private static JsonObject summary(PresentationElement pe, String parentPresentationId) {
        JsonObject json = new JsonObject();
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

    // cameo_preview_typed_diagram_operation: validates operation and payload, then echoes; no diagram is changed.
    static JsonObject preview(JsonObject p) {
        String operation = Gs.reqStr(p, "operation").trim().toLowerCase(Locale.ROOT);
        if (!OPERATIONS.contains(operation)) {
            throw ToolException.invalid("Unsupported typed diagram operation '" + operation + "'. Use one of "
                    + String.join(", ", OPERATIONS) + ".");
        }
        JsonObject payload = Gs.object(p, "payload");
        if (payload == null) {
            throw ToolException.invalid("Missing required parameter 'payload' (an object describing the operation).");
        }
        JsonObject response = Support.previewAccepted("typedDiagrams", payload,
                "Typed diagram " + operation + " preview recorded. No diagram write was performed.");
        response.addProperty("operation", operation);
        response.addProperty("requiresDiagramTypeGate", true);
        response.add("capabilities", capabilities());
        return response;
    }
}
