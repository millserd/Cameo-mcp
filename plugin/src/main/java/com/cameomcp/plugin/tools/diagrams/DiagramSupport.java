package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
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

    /** Not instantiable; all members are static. */
    private DiagramSupport() {
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Resolves a diagram id to its DiagramPresentationElement: direct id lookup first, then a scan of all
     * diagrams. When the id names a non-diagram element the error says what it is, to help a caller that
     * passed an element id by mistake.
     *
     * <p>Threading: call on the EDT.
     *
     * @param project the active project
     * @param diagramId the diagram id
     * @return the diagram, possibly not yet loaded
     * @throws ToolException INVALID_PARAMS when the id is blank, NOT_FOUND when no diagram has it
     */
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

    /**
     * Same as {@link #findDiagram}, but makes sure the diagram's symbols are loaded. Diagrams can be loaded
     * lazily and would otherwise show no presentation elements.
     *
     * @param project the active project
     * @param diagramId the diagram id
     * @return the loaded diagram
     * @throws ToolException as {@link #findDiagram}
     */
    static DiagramPresentationElement loadedDiagram(Project project, String diagramId) {
        DiagramPresentationElement dpe = findDiagram(project, diagramId);
        dpe.ensureLoaded();
        return dpe;
    }

    /**
     * Looks up a model element by id. A missing id or a non-UML object becomes a tool error naming the label.
     *
     * @param project the active project
     * @param id the element id, may be null
     * @param label what the id is for, such as "Element", used in the message
     * @return the element
     * @throws ToolException NOT_FOUND, or a SysML v2 element error
     */
    static Element findElement(Project project, String id, String label) {
        Object o = id == null ? null : project.getElementByID(id);
        if (o instanceof Element) {
            return (Element) o;
        }
        throw com.cameomcp.plugin.cameo.ProjectKind.notUmlElement(o, label + " '" + id + "' not found. Use "
                + "cameo_query_elements to find valid IDs.");
    }

    /**
     * Find a presentation element by ID anywhere in the symbol tree.
     *
     * @param elements the symbols to search, recursively; may be null
     * @param peId the presentation id, may be null
     * @return the symbol, or null
     */
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

    /**
     * Like {@link #findPresentation}, but fails with a message reminding callers that presentation ids differ
     * from element ids.
     *
     * @param dpe the diagram
     * @param peId the presentation id
     * @return the symbol
     * @throws ToolException INVALID_PARAMS when the id is blank, NOT_FOUND when no symbol has it
     */
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

    /**
     * Depth-first flatten; records child -> parent presentation IDs when {@code parentById} is given.
     *
     * @param elements the symbols to walk, may be null
     * @param sink receives every symbol in depth-first order
     * @param parentById receives child id to parent id, or null to skip
     * @param parentId the parent of {@code elements}, or null at the top level
     */
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

    /**
     * Every presentation element under the given roots, depth-first, without parent tracking.
     *
     * @param roots the top-level symbols, may be null
     * @return the flat list
     */
    static List<PresentationElement> flatten(List<PresentationElement> roots) {
        List<PresentationElement> out = new ArrayList<>();
        collect(roots, out, null, null);
        return out;
    }

    // ------------------------------------------------------------------ 2024x / 2026x compatibility

    /**
     * {@code PresentationElement.getPropertyManager()} is public in 2024x but missing from the 2026x OpenAPI
     * javadoc, so it is looked up reflectively (public first, then declared on a superclass).
     *
     * @param pe the symbol
     * @return its live property manager
     * @throws ToolException UNSUPPORTED when this Cameo does not expose it
     */
    static com.nomagic.magicdraw.properties.PropertyManager propertyManager(PresentationElement pe) {
        Object pm = invokeNoArg(pe, "getPropertyManager");
        if (pm instanceof com.nomagic.magicdraw.properties.PropertyManager) {
            return (com.nomagic.magicdraw.properties.PropertyManager) pm;
        }
        throw new ToolException("UNSUPPORTED", "This Cameo version does not expose presentation properties for "
                + pe.getClass().getSimpleName() + " (PresentationElement.getPropertyManager is unavailable).");
    }

    /**
     * Editable copy of a symbol's property manager (Cameo rejects re-applying the live one).
     *
     * @param pe the symbol
     * @return a clone of its property manager, to change and apply
     * @throws ToolException UNSUPPORTED when this Cameo does not expose it
     */
    static com.nomagic.magicdraw.properties.PropertyManager propertyManagerCopy(PresentationElement pe) {
        return propertyManager(pe).clone();
    }

    /**
     * Auto-layout; the API differs between 2024x and 2026x, see {@link
     * com.cameomcp.plugin.cameo.DiagramCompat}.
     *
     * @param dpe the diagram
     * @return true when a layout ran
     */
    static boolean layout(DiagramPresentationElement dpe) {
        return com.cameomcp.plugin.cameo.DiagramCompat.layout(dpe);
    }

    /**
     * Calls a no-arg method by reflection. A RuntimeException thrown by the method itself is rethrown
     * unwrapped so callers see the real error.
     *
     * @param target the object to call on
     * @param name the method name
     * @return the result, or null when the method is missing or inaccessible
     */
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

    /**
     * Finds a method: a public one (including inherited) first, otherwise one declared on the class or a
     * superclass, made accessible. LinkageError is caught because a method signature may refer to classes
     * missing in this Cameo version.
     *
     * @param type the class to search
     * @param name the method name
     * @param params the parameter types
     * @return the method, or null when none is found
     */
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

    /**
     * Native diagram type name, for example "SysML Block Definition Diagram".
     *
     * @param dpe the diagram, may be null
     * @return the type, or "" when it cannot be read
     */
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

    /**
     * Diagram name, never null.
     *
     * @param dpe the diagram
     * @return the name, or ""
     */
    static String name(DiagramPresentationElement dpe) {
        String n = dpe.getName();
        return n != null ? n : "";
    }

    /**
     * The id, name, type and owner summary used by cameo_list_diagrams and as the base of other diagram
     * responses.
     *
     * @param dpe the diagram
     * @return the summary
     */
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

    /**
     * Rectangle as JSON.
     *
     * @param r the rectangle
     * @return {@code x}, {@code y}, {@code width} and {@code height}
     */
    static JsonObject bounds(Rectangle r) {
        JsonObject b = new JsonObject();
        b.addProperty("x", r.x);
        b.addProperty("y", r.y);
        b.addProperty("width", r.width);
        b.addProperty("height", r.height);
        return b;
    }

    /**
     * presentationId, shapeType, parentPresentationId, element*, bounds, childCount.
     *
     * @param pe the symbol
     * @param parentId the parent symbol's id, or null for a top-level symbol
     * @return the description; parts that cannot be read are left out
     */
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

    /**
     * All presentation elements of the diagram, flattened with parent IDs (the reference's collectShapes).
     *
     * @param dpe the diagram, already loaded
     * @return one {@link #describe} object per symbol, depth-first
     */
    static JsonArray collectShapes(DiagramPresentationElement dpe) {
        JsonArray out = new JsonArray();
        collectShapes(dpe.getPresentationElements(), out, null);
        return out;
    }

    /**
     * Walks the symbol tree into a JSON array. Unreadable symbols, and their subtrees, are skipped rather
     * than failing the listing.
     *
     * @param elements the symbols at this level, may be null
     * @param out receives the descriptions
     * @param parentId the parent of {@code elements}, or null at the top level
     */
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

    /**
     * Adds a property value as a JSON boolean or number when possible, else as its string form. Null is
     * omitted.
     *
     * @param target the object to add to
     * @param key the field name
     * @param value the value, may be null
     */
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

    /**
     * A non-null message for an exception.
     *
     * @param e the exception
     * @return its message, or its class simple name when it has none
     */
    static String safeMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Read a string field of a request item, accepting camelCase or snake_case keys.
     *
     * @param item the request item
     * @param camelKey the camelCase key; the snake_case form is tried too
     * @return the trimmed-to-null string, or null when absent or blank
     */
    static String itemStr(JsonObject item, String camelKey) {
        JsonElement e = itemGet(item, camelKey);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        String s = e.isJsonPrimitive() ? e.getAsString() : e.toString();
        return s.trim().isEmpty() ? null : s;
    }

    /**
     * Raw value of a request item by camelCase key, falling back to the snake_case form.
     *
     * @param item the request item, may be null
     * @param camelKey the camelCase key
     * @return the value, or null when absent or JSON null
     */
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

    /**
     * Integer field of a request item. Decimals are rounded.
     *
     * @param item the request item
     * @param camelKey the camelCase key
     * @return the value, or null when absent
     * @throws ToolException INVALID_PARAMS when the value is not a number
     */
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

    /**
     * Converts camelCase to snake_case, for example "parentPresentationId" to "parent_presentation_id".
     *
     * @param camel the camelCase name
     * @return the snake_case name
     */
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

    /**
     * Reads an {x, y} object as a Point, rounding each coordinate.
     *
     * @param el the value, may be null
     * @return the point, or null when the value is not an object with both coordinates
     */
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

    /**
     * Reads an array of {x, y} objects as points. Malformed entries are skipped.
     *
     * @param el the value, may be null
     * @return the points; empty when the value is not an array
     */
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

    /**
     * Array of objects from a param; throws INVALID_PARAMS when missing or malformed.
     *
     * @param e the parameter value; a JSON string holding an array is parsed first
     * @param key the parameter name, for error messages
     * @return the objects
     * @throws ToolException INVALID_PARAMS when missing, not an array, or holding a non-object
     */
    static List<JsonObject> objectArray(JsonElement e, String key) {
        if (e == null || e.isJsonNull()) {
            throw ToolException.invalid("Missing required parameter '" + key + "' (an array of objects).");
        }
        // Some MCP clients send nested arrays as a JSON string; parse it before validating.
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                e = com.cameomcp.json.tree.JsonParser.parseString(e.getAsString());
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

    /**
     * Object param (JSON string accepted); throws INVALID_PARAMS when missing.
     *
     * @param e the parameter value; a JSON string holding an object is parsed first
     * @param key the parameter name, for error messages
     * @return the object
     * @throws ToolException INVALID_PARAMS when missing or not an object
     */
    static JsonObject objectParam(JsonElement e, String key) {
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                e = com.cameomcp.json.tree.JsonParser.parseString(e.getAsString());
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
