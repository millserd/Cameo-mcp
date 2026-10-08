package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolContext;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

    public static final String RELATION_MAP_TYPE = "Relation Map Diagram";
    public static final String GENERIC_TABLE_TYPE = "Generic Table";

    /** Optional-module service classes (src/optional/java). */
    public static final String MATRIX_SERVICE = "com.cameomcp.optional.matrix.MatrixService";
    public static final String GENERIC_TABLE_SERVICE = "com.cameomcp.optional.generictable.GenericTableService";
    public static final String RELATION_MAP_SERVICE = "com.cameomcp.optional.relationmap.RelationMapService";

    private ArtifactSupport() {
    }

    // ------------------------------------------------------------------ parameters

    /**
     * The tool arguments as a Gson object keyed by the spec's snake_case names: a camelCase
     * spelling sent by the client is copied to the canonical key, so implementations only
     * read snake_case. JSON nulls are dropped.
     */
    public static JsonObject params(Args args, ToolContext ctx) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Object> e : args.raw().entrySet()) {
            if (e.getValue() != null) {
                out.add(e.getKey(), Gs.toElement(e.getValue()));
            }
        }
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

    /** Optional string list: null when absent, a list (possibly empty) when present. Accepts a CSV string. */
    public static List<String> optStrList(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) {
            String s = e.getAsString().trim();
            List<String> out = new ArrayList<>();
            if (s.startsWith("[")) {
                try {
                    JsonElement parsed = com.google.gson.JsonParser.parseString(s);
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

    /** Optional trimmed string; null when absent or blank. */
    public static String optStr(JsonObject o, String key) {
        String s = Gs.str(o, key);
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** Optional integer; null when absent. */
    public static Integer optInt(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return null;
        }
        return Gs.integer(o, key, 0);
    }

    /** Optional boolean; null when absent. */
    public static Boolean optBool(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return null;
        }
        return Gs.bool(o, key, false);
    }

    public static int clamp(Integer value, int min, int max, int dflt) {
        if (value == null) {
            return dflt;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Seconds from a float "timeout" parameter, clamped to 1..600 (default 120). */
    public static int timeoutSeconds(JsonObject o, String key) {
        if (!Gs.has(o, key)) {
            return 120;
        }
        double d = Gs.number(o, key, 120);
        return (int) Math.max(1, Math.min(600, Math.floor(d)));
    }

    public static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getName();
        }
        return e.getClass().getName() + ": " + message;
    }

    // ------------------------------------------------------------------ lookup

    public static Element element(Project project, String id, String label) {
        Object o = id == null ? null : project.getElementByID(id);
        if (o instanceof Element) {
            return (Element) o;
        }
        throw new ToolException("NOT_FOUND", label + " '" + id + "' not found. Use cameo_query_elements to find "
                + "valid element IDs.");
    }

    public static List<Element> elements(Project project, List<String> ids, String label) {
        List<Element> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(element(project, id, label));
        }
        return out;
    }

    /** Diagram presentation element for a diagram ID, searching the diagram list as a fallback. */
    public static DiagramPresentationElement diagram(Project project, String id, String label) {
        Object o = id == null ? null : project.getElementByID(id);
        if (o instanceof Diagram) {
            DiagramPresentationElement dpe = project.getDiagram((Diagram) o);
            if (dpe != null) {
                return dpe;
            }
        }
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

    public static String diagramType(DiagramPresentationElement dpe) {
        try {
            return dpe != null && dpe.getDiagramType() != null ? dpe.getDiagramType().getType() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static boolean isRelationMap(DiagramPresentationElement dpe) {
        return RELATION_MAP_TYPE.equals(diagramType(dpe));
    }

    /** Relation Map diagram by ID or NOT_FOUND. */
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

    /** Summary JSON (id, name, type, ownerId, ownerName) shared by table and relation-map listings. */
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

    /** Stereotype or metaclass for a type token such as "Block", "Requirement", "Activity", "value-property". */
    public static Element resolveTypeReference(Project project, String typeName, String what) {
        if (typeName == null || typeName.trim().isEmpty()) {
            throw ToolException.invalid(what + " type tokens must not be empty.");
        }
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

    public static List<Object> resolveTypes(Project project, List<String> tokens, String what) {
        List<Object> out = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            out.add(resolveTypeReference(project, t, what));
        }
        return out;
    }

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

    /** "value-property" / "opaque action" to "ValueProperty" / "OpaqueAction". */
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

    /** Lower-case alphanumerics only, for forgiving stereotype matching. */
    public static String normalizeTypeToken(String input) {
        return input == null ? "" : input.replaceAll("[^A-Za-z0-9]+", "").toLowerCase(Locale.ROOT);
    }
}
