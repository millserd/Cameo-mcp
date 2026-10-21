package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolContext;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.ClassTypes;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers of the artifacts family (also used by the optional source set):
 * parameter canonicalization, element/diagram lookup, type-token resolution and
 * summary JSON for diagram-based artifacts.
 */
public final class ArtifactSupport {

    /** Native diagram type name of relation maps. */
    public static final String RELATION_MAP_TYPE = "Relation Map Diagram";
    /** Native diagram type name of generic tables. */
    public static final String GENERIC_TABLE_TYPE = "Generic Table";

    /** Optional-module service classes (src/optional/java). */
    public static final String MATRIX_SERVICE = "com.cameomcp.optional.matrix.MatrixService";
    /** Optional-module service class for generic tables. */
    public static final String GENERIC_TABLE_SERVICE = "com.cameomcp.optional.generictable.GenericTableService";
    /** Optional-module service class for relation maps. */
    public static final String RELATION_MAP_SERVICE = "com.cameomcp.optional.relationmap.RelationMapService";

    /** Not instantiable; all members are static. */
    private ArtifactSupport() {
    }

    // ------------------------------------------------------------------ parameters

    /**
     * The tool arguments as a JSON tree object keyed by the spec's snake_case names: a camelCase
     * spelling sent by the client is copied to the canonical key, so implementations only
     * read snake_case. JSON nulls are dropped.
     *
     * @param args the tool arguments
     * @param ctx the calling context, used to find the tool's spec
     * @return a new object keyed by snake_case names
     */
    public static JsonObject params(Args args, ToolContext ctx) {
        JsonObject out = new JsonObject();
        // Copy everything the client sent, under whatever key spelling it used.
        for (Map.Entry<String, Object> e : args.raw().entrySet()) {
            if (e.getValue() != null) {
                out.add(e.getKey(), Gs.toElement(e.getValue()));
            }
        }
        // Then fill each spec parameter that is missing under its snake_case name; args.get(name) is
        // expected to find the value under an alternative spelling (see the javadoc above).
        if (ctx != null && ctx.spec() != null) {
            for (String name : ctx.spec().parameters().keySet()) {
                if (!Gs.has(out, name)) {
                    Object v = args.get(name);
                    if (v != null) {
                        out.add(name, Gs.toElement(v));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Optional string list: null when absent, a list (possibly empty) when present. Accepts a CSV string.
     *
     * @param o the arguments
     * @param key the parameter name
     * @return the strings, trimmed, without blanks; null when absent
     */
    public static List<String> optStrList(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) {
            String s = e.getAsString().trim();
            List<String> out = new ArrayList<>();
            // A string that looks like a JSON array (some clients stringify arrays) is parsed as one.
            if (s.startsWith("[")) {
                try {
                    JsonElement parsed = com.cameomcp.json.tree.JsonParser.parseString(s);
                    if (parsed.isJsonArray()) {
                        for (JsonElement item : parsed.getAsJsonArray()) {
                            if (item != null && !item.isJsonNull()) {
                                out.add(item.isJsonPrimitive() ? item.getAsString() : item.toString());
                            }
                        }
                        return out;
                    }
                } catch (RuntimeException ignored) {
                    // fall through to CSV parsing
                }
            }
            for (String part : s.split(",")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        return Gs.strList(o, key);
    }

    /**
     * Optional trimmed string; null when absent or blank.
     *
     * @param o the arguments
     * @param key the parameter name
     * @return the trimmed value, or null
     */
    public static String optStr(JsonObject o, String key) {
        String s = Gs.str(o, key);
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Optional integer; null when absent.
     *
     * @param o the arguments
     * @param key the parameter name
     * @return the value, or null when absent
     */
    public static Integer optInt(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return null;
        }
        return Gs.integer(o, key, 0);
    }

    /**
     * Optional boolean; null when absent.
     *
     * @param o the arguments
     * @param key the parameter name
     * @return the value, or null when absent
     */
    public static Boolean optBool(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return null;
        }
        return Gs.bool(o, key, false);
    }

    /**
     * Clamps value to [min, max]; null (parameter absent) yields dflt.
     *
     * @param value the value, or null
     * @param min the lowest allowed value
     * @param max the highest allowed value
     * @param dflt the value for null
     * @return the clamped value
     */
    public static int clamp(Integer value, int min, int max, int dflt) {
        if (value == null) {
            return dflt;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Seconds from a float "timeout" parameter, clamped to 1..600 (default 120).
     *
     * @param o the arguments
     * @param key the parameter name
     * @return whole seconds from 1 to 600
     */
    public static int timeoutSeconds(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return 120;
        }
        double d = Gs.number(o, key, 120);
        return (int) Math.max(1, Math.min(600, Math.floor(d)));
    }

    /**
     * Exception class name plus message, or just the class name when the message is blank.
     *
     * @param e the exception
     * @return the description
     */
    public static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getName();
        }
        return e.getClass().getName() + ": " + message;
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Element by id; anything else (missing id, or a non-UML object) becomes the error produced by
     * ProjectKind.notUmlElement, pointing the caller to cameo_query_elements.
     *
     * @param project the active project
     * @param id the element id, may be null
     * @param label what the id is for, used in the message
     * @return the element
     * @throws ToolException NOT_FOUND, or a SysML v2 element error
     */
    public static Element element(Project project, String id, String label) {
        Object o = id == null ? null : project.getElementByID(id);
        if (o instanceof Element) {
            return (Element) o;
        }
        throw com.cameomcp.plugin.cameo.ProjectKind.notUmlElement(o, label + " '" + id + "' not found. Use "
                + "cameo_query_elements to find valid element IDs.");
    }

    /**
     * All elements for the ids, in order; fails on the first unknown id.
     *
     * @param project the active project
     * @param ids the element ids
     * @param label what the ids are for, used in the message
     * @return the elements in the same order
     * @throws ToolException NOT_FOUND for the first unknown id
     */
    public static List<Element> elements(Project project, List<String> ids, String label) {
        List<Element> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(element(project, id, label));
        }
        return out;
    }

    /**
     * Diagram presentation element for a diagram ID, searching the diagram list as a fallback.
     *
     * @param project the active project
     * @param id the diagram id, may be null
     * @param label what the id is for, used in the message
     * @return the diagram
     * @throws ToolException NOT_FOUND when no diagram has that id
     */
    public static DiagramPresentationElement diagram(Project project, String id, String label) {
        Object o = id == null ? null : project.getElementByID(id);
        if (o instanceof Diagram) {
            // Fast path: map the Diagram element to its presentation element.
            DiagramPresentationElement dpe = project.getDiagram((Diagram) o);
            if (dpe != null) {
                return dpe;
            }
        }
        // Fallback: linear scan of the project's diagram list by diagram id.
        Collection<DiagramPresentationElement> all = project.getDiagrams();
        if (all != null && id != null) {
            for (DiagramPresentationElement dpe : all) {
                Diagram d = dpe == null ? null : dpe.getDiagram();
                if (d != null && id.equals(d.getID())) {
                    return dpe;
                }
            }
        }
        throw new ToolException("NOT_FOUND", label + " '" + id + "' not found. Use cameo_list_diagrams to find "
                + "valid diagram IDs.");
    }

    /**
     * Native diagram type name (e.g. "Generic Table"), or "" when unknown; never throws, because
     * getDiagramType() can fail for diagrams whose defining plugin is not loaded.
     *
     * @param dpe the diagram, may be null
     * @return the type name
     */
    public static String diagramType(DiagramPresentationElement dpe) {
        try {
            return dpe != null && dpe.getDiagramType() != null ? dpe.getDiagramType().getType() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * True when the diagram's native type is "Relation Map Diagram".
     *
     * @param dpe the diagram, may be null
     * @return true for relation maps
     */
    public static boolean isRelationMap(DiagramPresentationElement dpe) {
        return RELATION_MAP_TYPE.equals(diagramType(dpe));
    }

    /**
     * Relation Map diagram by ID or NOT_FOUND.
     *
     * @param project the active project
     * @param id the diagram id, may be null
     * @return the relation map's Diagram element
     * @throws ToolException NOT_FOUND when the id is not a relation map
     */
    public static Diagram relationMap(Project project, String id) {
        Object o = id == null ? null : project.getElementByID(id);
        if (o instanceof Diagram) {
            DiagramPresentationElement dpe = project.getDiagram((Diagram) o);
            if (isRelationMap(dpe)) {
                return (Diagram) o;
            }
            throw new ToolException("INVALID_PARAMS", "Element '" + id + "' is a diagram of type '"
                    + diagramType(dpe) + "', not a Relation Map. Use cameo_list_relation_maps.");
        }
        throw new ToolException("NOT_FOUND", "Relation Map '" + id + "' not found. Use cameo_list_relation_maps "
                + "to find valid IDs.");
    }

    /**
     * Summary JSON (id, name, type, ownerId, ownerName) shared by table and relation-map listings.
     *
     * @param dpe the diagram, may be null
     * @param fallbackType the type to report when the native type cannot be read
     * @return the summary
     */
    public static JsonObject diagramSummary(DiagramPresentationElement dpe, String fallbackType) {
        JsonObject json = new JsonObject();
        Diagram diagram = dpe != null ? dpe.getDiagram() : null;
        json.addProperty("id", diagram != null ? diagram.getID() : "");
        json.addProperty("name", dpe != null && dpe.getName() != null ? dpe.getName() : "");
        String type = diagramType(dpe);
        json.addProperty("type", type.isEmpty() ? fallbackType : type);
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
     * Compact JSON for each non-null element.
     *
     * @param elements the elements, may be null
     * @return the compact JSON objects
     */
    public static JsonArray compactArray(Collection<? extends Element> elements) {
        JsonArray array = new JsonArray();
        if (elements != null) {
            for (Element e : elements) {
                if (e != null) {
                    array.add(ElementJson.toJsonCompact(e));
                }
            }
        }
        return array;
    }

    /**
     * String values as a JSON array; null entries are kept as JSON null.
     *
     * @param values the values, may be null
     * @return the string array
     */
    public static JsonArray stringArray(Collection<?> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (Object v : values) {
                array.add(v == null ? null : String.valueOf(v));
            }
        }
        return array;
    }

    // ------------------------------------------------------------------ type tokens

    /**
     * Stereotype or metaclass for a type token such as "Block", "Requirement", "Activity", "value-property".
     *
     * @param project the active project
     * @param typeName the type token
     * @param what the parameter's role, used in error messages
     * @return the stereotype or metaclass element
     * @throws ToolException INVALID_PARAMS when the token is empty or unknown
     */
    public static Element resolveTypeReference(Project project, String typeName, String what) {
        if (typeName == null || typeName.trim().isEmpty()) {
            throw ToolException.invalid(what + " type tokens must not be empty.");
        }
        // Stereotypes take precedence, so "Block" resolves to the SysML stereotype rather than a metaclass.
        Stereotype stereotype = resolveStereotype(project, typeName);
        if (stereotype != null) {
            return stereotype;
        }
        Element metaClass = resolveMetaClass(project, typeName);
        if (metaClass != null) {
            return metaClass;
        }
        throw ToolException.invalid("Unknown " + what + " type: " + typeName + ". Use a UML metaclass such as "
                + "UseCase, Activity or Property, or a stereotype such as Block, Requirement or valueProperty.");
    }

    /**
     * resolveTypeReference for every token, in order; throws on the first unknown token.
     *
     * @param project the active project
     * @param tokens the type tokens
     * @param what the parameter's role, used in error messages
     * @return the stereotype or metaclass elements, in order
     * @throws ToolException INVALID_PARAMS for the first empty or unknown token
     */
    public static List<Object> resolveTypes(Project project, List<String> tokens, String what) {
        List<Object> out = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            out.add(resolveTypeReference(project, t, what));
        }
        return out;
    }

    /**
     * First stereotype in the project whose normalized name equals the normalized input, or null.
     *
     * @param project the active project
     * @param name the stereotype name, in any case or punctuation
     * @return the stereotype, or null
     */
    public static Stereotype resolveStereotype(Project project, String name) {
        String wanted = normalizeTypeToken(name);
        Collection<Stereotype> all = StereotypesHelper.getAllStereotypes(project);
        if (all == null) {
            return null;
        }
        for (Stereotype s : all) {
            if (s != null && wanted.equals(normalizeTypeToken(s.getName()))) {
                return s;
            }
        }
        return null;
    }

    /**
     * Metaclass element (from the UML profile) for a type name. Tries the name as given, then the
     * CamelCase form ("opaque action" to "OpaqueAction"); null when neither is a known class type.
     *
     * @param project the active project
     * @param rawTypeName the type name
     * @return the metaclass element, or null
     */
    public static Element resolveMetaClass(Project project, String rawTypeName) {
        Class<?> metaclass = ClassTypes.getClassType(rawTypeName.trim());
        if (metaclass == null) {
            metaclass = ClassTypes.getClassType(normalizeTypeName(rawTypeName));
        }
        if (metaclass == null) {
            return null;
        }
        String shortName = ClassTypes.getShortName(metaclass);
        if (shortName == null || shortName.isEmpty()) {
            return null;
        }
        return StereotypesHelper.getMetaClassByName(project, shortName);
    }

    /**
     * "value-property" / "opaque action" to "ValueProperty" / "OpaqueAction".
     *
     * @param input the text
     * @return the CamelCase form
     */
    public static String normalizeTypeName(String input) {
        StringBuilder b = new StringBuilder();
        for (String part : input.trim().split("[-_ ]+")) {
            if (part.isEmpty()) {
                continue;
            }
            b.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                b.append(part.substring(1));
            }
        }
        return b.toString();
    }

    /**
     * Lower-case alphanumerics only, for forgiving stereotype matching.
     *
     * @param input the text, may be null
     * @return the token; "" for null
     */
    public static String normalizeTypeToken(String input) {
        return input == null ? "" : input.replaceAll("[^A-Za-z0-9]+", "").toLowerCase(Locale.ROOT);
    }
}
