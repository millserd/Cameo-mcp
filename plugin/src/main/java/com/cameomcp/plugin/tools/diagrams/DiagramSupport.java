package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.ToolException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Shared Cameo helpers for the diagram tools. */
final class DiagramSupport {

    private DiagramSupport() {
    }

    // ------------------------------------------------------------------ lookup

    static DiagramPresentationElement findDiagram(Project project, String diagramId) {
        if (diagramId == null || diagramId.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter 'diagram_id'.");
        }
        Object base = project.getElementByID(diagramId);
        if (base instanceof Diagram) {
            DiagramPresentationElement dpe = project.getDiagram((Diagram) base);
            if (dpe != null) {
                return dpe;
            }
        }
        Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
        if (diagrams != null) {
            for (DiagramPresentationElement dpe : diagrams) {
                Diagram d = dpe.getDiagram();
                if (d != null && diagramId.equals(d.getID())) {
                    return dpe;
                }
            }
        }
        if (base instanceof Element) {
            throw new ToolException("NOT_FOUND", "Element '" + diagramId + "' is a "
                    + ((Element) base).getHumanType() + ", not a diagram. Use cameo_list_diagrams to find diagram IDs.");
        }
        throw new ToolException("NOT_FOUND", "Diagram '" + diagramId
                + "' not found. Use cameo_list_diagrams to find valid diagram IDs.");
    }

    static DiagramPresentationElement loadedDiagram(Project project, String diagramId) {
        DiagramPresentationElement dpe = findDiagram(project, diagramId);
        dpe.ensureLoaded();
        return dpe;
    }

    static Element findElement(Project project, String id, String label) {
        Object o = id == null ? null : project.getElementByID(id);
        if (o instanceof Element) {
            return (Element) o;
        }
        throw new ToolException("NOT_FOUND", label + " '" + id + "' not found. Use cameo_query_elements to find valid IDs.");
    }

    /** Find a presentation element by ID anywhere in the symbol tree. */
    static PresentationElement findPresentation(List<PresentationElement> elements, String peId) {
        if (elements == null || peId == null) {
            return null;
        }
        for (PresentationElement pe : elements) {
            if (peId.equals(pe.getID())) {
                return pe;
            }
            List<PresentationElement> children = pe.getPresentationElements();
            if (children != null && !children.isEmpty()) {
                PresentationElement found = findPresentation(children, peId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static PresentationElement requirePresentation(DiagramPresentationElement dpe, String peId) {
        if (peId == null || peId.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter 'presentation_id'.");
        }
        PresentationElement pe = findPresentation(dpe.getPresentationElements(), peId);
        if (pe == null) {
            throw new ToolException("NOT_FOUND", "Presentation element '" + peId + "' not found on diagram. "
                    + "Use cameo_list_diagram_shapes to get presentationId values (they differ from model element IDs).");
        }
        return pe;
    }

    /** Depth-first flatten; records child -> parent presentation IDs when {@code parentById} is given. */
    static void collect(List<PresentationElement> elements, List<PresentationElement> sink,
                        Map<String, String> parentById, String parentId) {
        if (elements == null) {
            return;
        }
        for (PresentationElement pe : elements) {
            sink.add(pe);
            if (parentById != null && parentId != null) {
                parentById.put(pe.getID(), parentId);
            }
            collect(pe.getPresentationElements(), sink, parentById, pe.getID());
        }
    }

    static List<PresentationElement> flatten(List<PresentationElement> roots) {
        List<PresentationElement> out = new ArrayList<>();
        collect(roots, out, null, null);
        return out;
    }

    // ------------------------------------------------------------------ 2024x / 2026x compatibility

    /**
     * {@code PresentationElement.getPropertyManager()} is public in 2024x but missing from the 2026x OpenAPI
     * javadoc, so it is looked up reflectively (public first, then declared on a superclass).
     */
    static com.nomagic.magicdraw.properties.PropertyManager propertyManager(PresentationElement pe) {
        Object pm = invokeNoArg(pe, "getPropertyManager");
        if (pm instanceof com.nomagic.magicdraw.properties.PropertyManager) {
            return (com.nomagic.magicdraw.properties.PropertyManager) pm;
        }
        throw new ToolException("UNSUPPORTED", "This Cameo version does not expose presentation properties for "
                + pe.getClass().getSimpleName() + " (PresentationElement.getPropertyManager is unavailable).");
    }

    /** Editable copy of a symbol's property manager (Cameo rejects re-applying the live one). */
    static com.nomagic.magicdraw.properties.PropertyManager propertyManagerCopy(PresentationElement pe) {
        return propertyManager(pe).clone();
    }

    /** {@code AbstractDiagramPresentationElement.layout(boolean)} is 2024x API; absent from the 2026x javadoc. */
    static boolean layout(DiagramPresentationElement dpe) {
        java.lang.reflect.Method m = findMethod(dpe.getClass(), "layout", boolean.class);
        if (m == null) {
            throw new ToolException("UNSUPPORTED", "Automatic layout is not available through the API of this "
                    + "Cameo version. Use Layout > Quick Diagram Layout in Cameo, or position shapes with cameo_move_shapes.");
        }
        try {
            Object r = m.invoke(dpe, true);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new ToolException("TOOL_FAILED", "Auto-layout failed: " + safeMessage(c));
        } catch (IllegalAccessException e) {
            throw new ToolException("UNSUPPORTED", "Automatic layout is not accessible in this Cameo version.");
        }
    }

    static Object invokeNoArg(Object target, String name) {
        java.lang.reflect.Method m = findMethod(target.getClass(), name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException | LinkageError ignored) {
            // try declared methods below
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException | LinkageError | RuntimeException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ serialization

    static String diagramType(DiagramPresentationElement dpe) {
        try {
            if (dpe == null || dpe.getDiagramType() == null || dpe.getDiagramType().getType() == null) {
                return "";
            }
            return dpe.getDiagramType().getType();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String name(DiagramPresentationElement dpe) {
        String n = dpe.getName();
        return n != null ? n : "";
    }

    static JsonObject diagramJson(DiagramPresentationElement dpe) {
        JsonObject json = new JsonObject();
        Diagram diagram = dpe.getDiagram();
        json.addProperty("id", diagram != null ? diagram.getID() : null);
        json.addProperty("name", name(dpe));
        json.addProperty("type", diagramType(dpe));
        Element owner = diagram != null ? diagram.getOwner() : null;
        if (owner != null) {
            json.addProperty("ownerId", owner.getID());
            if (owner instanceof NamedElement) {
                json.addProperty("ownerName", ((NamedElement) owner).getName());
            }
        }
        return json;
    }

    static JsonObject bounds(Rectangle r) {
        JsonObject b = new JsonObject();
        b.addProperty("x", r.x);
        b.addProperty("y", r.y);
        b.addProperty("width", r.width);
        b.addProperty("height", r.height);
        return b;
    }

    /** presentationId, shapeType, parentPresentationId, element*, bounds, childCount. */
    static JsonObject describe(PresentationElement pe, String parentId) {
        JsonObject json = new JsonObject();
        json.addProperty("presentationId", pe.getID());
        json.addProperty("shapeType", pe.getClass().getSimpleName());
        if (parentId != null) {
            json.addProperty("parentPresentationId", parentId);
        }
        try {
            Element el = pe.getElement();
            if (el != null) {
                json.addProperty("elementId", el.getID());
                if (el instanceof NamedElement) {
                    json.addProperty("elementName", ((NamedElement) el).getName());
                }
                json.addProperty("elementType", el.getHumanType());
            }
        } catch (RuntimeException ignored) {
            // some presentation elements have no backing element
        }
        try {
            Rectangle r = pe.getBounds();
            if (r != null) {
                json.add("bounds", bounds(r));
            }
        } catch (RuntimeException ignored) {
            // not every presentation element has rectangular bounds
        }
        List<PresentationElement> children = pe.getPresentationElements();
        int childCount = children != null ? children.size() : 0;
        if (childCount > 0) {
            json.addProperty("childCount", childCount);
        }
        return json;
    }

    /** All presentation elements of the diagram, flattened with parent IDs (the reference's collectShapes). */
    static JsonArray collectShapes(DiagramPresentationElement dpe) {
        JsonArray out = new JsonArray();
        collectShapes(dpe.getPresentationElements(), out, null);
        return out;
    }

    private static void collectShapes(List<PresentationElement> elements, JsonArray out, String parentId) {
        if (elements == null) {
            return;
        }
        for (PresentationElement pe : elements) {
            try {
                out.add(describe(pe, parentId));
                List<PresentationElement> children = pe.getPresentationElements();
                if (children != null && !children.isEmpty()) {
                    collectShapes(children, out, pe.getID());
                }
            } catch (RuntimeException ignored) {
                // skip unreadable presentation elements
            }
        }
    }

    // ------------------------------------------------------------------ JSON values

    static void addValue(JsonObject target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean) {
            target.addProperty(key, (Boolean) value);
        } else if (value instanceof Number) {
            target.addProperty(key, (Number) value);
        } else {
            target.addProperty(key, String.valueOf(value));
        }
    }

    static String safeMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Read a string field of a request item, accepting camelCase or snake_case keys. */
    static String itemStr(JsonObject item, String camelKey) {
        JsonElement e = itemGet(item, camelKey);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        String s = e.isJsonPrimitive() ? e.getAsString() : e.toString();
        return s.trim().isEmpty() ? null : s;
    }

    static JsonElement itemGet(JsonObject item, String camelKey) {
        if (item == null) {
            return null;
        }
        JsonElement e = item.get(camelKey);
        if (e == null || e.isJsonNull()) {
            e = item.get(snake(camelKey));
        }
        return e == null || e.isJsonNull() ? null : e;
    }

    static Integer itemInt(JsonObject item, String camelKey) {
        JsonElement e = itemGet(item, camelKey);
        if (e == null) {
            return null;
        }
        try {
            return (int) Math.round(e.getAsDouble());
        } catch (RuntimeException ex) {
            throw ToolException.invalid("'" + camelKey + "' must be a number (got " + e + ").");
        }
    }

    static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Point point(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        if (!o.has("x") || !o.has("y")) {
            return null;
        }
        return new Point((int) Math.round(o.get("x").getAsDouble()), (int) Math.round(o.get("y").getAsDouble()));
    }

    static List<Point> points(JsonElement el) {
        List<Point> out = new ArrayList<>();
        if (el == null || el.isJsonNull() || !el.isJsonArray()) {
            return out;
        }
        for (JsonElement p : el.getAsJsonArray()) {
            Point pt = point(p);
            if (pt != null) {
                out.add(pt);
            }
        }
        return out;
    }

    /** Array of objects from a param; throws INVALID_PARAMS when missing or malformed. */
    static List<JsonObject> objectArray(JsonElement e, String key) {
        if (e == null || e.isJsonNull()) {
            throw ToolException.invalid("Missing required parameter '" + key + "' (an array of objects).");
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                e = com.google.gson.JsonParser.parseString(e.getAsString());
            } catch (RuntimeException ex) {
                throw ToolException.invalid("Parameter '" + key + "' must be an array of objects.");
            }
        }
        if (!e.isJsonArray()) {
            throw ToolException.invalid("Parameter '" + key + "' must be an array of objects.");
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw ToolException.invalid("Every entry of '" + key + "' must be an object.");
            }
            out.add(item.getAsJsonObject());
        }
        return out;
    }

    /** Object param (JSON string accepted); throws INVALID_PARAMS when missing. */
    static JsonObject objectParam(JsonElement e, String key) {
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                e = com.google.gson.JsonParser.parseString(e.getAsString());
            } catch (RuntimeException ex) {
                e = null;
            }
        }
        if (e == null || !e.isJsonObject()) {
            throw ToolException.invalid("Missing required parameter '" + key + "' (an object).");
        }
        return e.getAsJsonObject();
    }
}
