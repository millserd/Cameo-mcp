package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonPrimitive;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Enumeration;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Type-aware coercion of JSON values into stereotype tagged values: enumeration tags get a real
 * EnumerationLiteral (by id, name or case-insensitive name), {"id": ...} objects become element
 * references, arrays become lists. Also serializes tag values back to JSON.
 */
final class TaggedValues {

    /** Not instantiable; all members are static. */
    private TaggedValues() {
    }

    /**
     * The stereotype attribute that defines the named tag, or null; base_* extension ends are not tags.
     *
     * @param stereotype the stereotype to search; inherited attributes are not searched
     * @param tagName the exact tag name
     * @return the defining attribute, or null when the stereotype has no such tag
     */
    static Property tagDefinition(Stereotype stereotype, String tagName) {
        List<Property> attributes = stereotype.getAttribute();
        if (attributes == null) {
            return null;
        }
        for (Property attribute : attributes) {
            if (attribute == null || StereotypesHelper.isExtensionProperty(attribute)) {
                continue;
            }
            if (tagName.equals(attribute.getName())) {
                return attribute;
            }
        }
        return null;
    }

    /**
     * Names of the settable tags (non-extension attributes) of a stereotype.
     *
     * @param stereotype the stereotype to list; inherited attributes are not included
     * @return the tag names in declaration order, without empty names
     */
    static List<String> tagNames(Stereotype stereotype) {
        List<String> out = new ArrayList<>();
        List<Property> attributes = stereotype.getAttribute();
        if (attributes != null) {
            for (Property a : attributes) {
                if (a != null && !StereotypesHelper.isExtensionProperty(a) && a.getName() != null
                        && !a.getName().isEmpty()) {
                    out.add(a.getName());
                }
            }
        }
        return out;
    }

    /**
     * Converts a JSON value to what the tag's type needs; unknown tags get generic coercion.
     *
     * @param project the open project, used to resolve element references
     * @param stereotype the stereotype that owns the tag
     * @param tagName the tag name
     * @param value the JSON value; null or JSON null clears the tag
     * @return the value to pass to {@code StereotypesHelper.setStereotypePropertyValue}
     * @throws ToolException INVALID_PARAMS when an enumeration tag gets a value that names none of its
     *     literals
     */
    static Object coerceForTag(Project project, Stereotype stereotype, String tagName, JsonElement value) {
        Property def = tagDefinition(stereotype, tagName);
        if (def == null) {
            return coerceGeneric(project, value);
        }
        return coerceForType(project, def.getType(), value, stereotype, tagName);
    }

    /**
     * Coerces a value for a tag of a known type: arrays item by item, enumeration types to a real literal,
     * and anything else generically.
     *
     * @param project the open project
     * @param expected the tag's declared type, may be null
     * @param value the JSON value
     * @param stereotype the stereotype, for error messages
     * @param tagName the tag name, for error messages
     * @return the coerced value, a list for arrays, or null for JSON null
     * @throws ToolException INVALID_PARAMS for an unknown enumeration literal
     */
    private static Object coerceForType(Project project, Type expected, JsonElement value, Stereotype stereotype,
                                        String tagName) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                out.add(coerceForType(project, expected, item, stereotype, tagName));
            }
            return out;
        }
        if (expected instanceof Enumeration) {
            return literal(project, (Enumeration) expected, value, stereotype, tagName);
        }
        return coerceGeneric(project, value);
    }

    /**
     * Finds the enumeration literal a value names. Tries an {"id"} object first, then an exact id, an exact
     * name, and finally a name ignoring case.
     *
     * @param project the open project, used to resolve an id
     * @param enumeration the tag's enumeration type
     * @param value the JSON value naming the literal
     * @param stereotype the stereotype, for the error message
     * @param tagName the tag name, for the error message
     * @return the matching literal owned by the enumeration
     * @throws ToolException INVALID_PARAMS listing the valid literals when nothing matches
     */
    private static EnumerationLiteral literal(Project project, Enumeration enumeration, JsonElement value,
                                              Stereotype stereotype, String tagName) {
        Collection<EnumerationLiteral> owned = enumeration.getOwnedLiteral();
        if (owned == null) {
            owned = Collections.emptyList();
        }
        if (value.isJsonObject() && value.getAsJsonObject().has("id")
                && value.getAsJsonObject().get("id").isJsonPrimitive()) {
            String id = value.getAsJsonObject().get("id").getAsString();
            BaseElement ref = project.getElementByID(id);
            if (ref instanceof EnumerationLiteral) {
                for (EnumerationLiteral l : owned) {
                    if (l != null && id.equals(l.getID())) {
                        return (EnumerationLiteral) ref;
                    }
                }
            }
        }
        String candidate = literalCandidate(value);
        if (candidate != null) {
            for (EnumerationLiteral l : owned) {
                if (l != null && candidate.equals(l.getID())) {
                    return l;
                }
            }
            for (EnumerationLiteral l : owned) {
                if (l != null && candidate.equals(l.getName())) {
                    return l;
                }
            }
            for (EnumerationLiteral l : owned) {
                if (l != null && l.getName() != null && candidate.equalsIgnoreCase(l.getName())) {
                    return l;
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (EnumerationLiteral l : owned) {
            if (l != null) {
                names.add(l.getName());
            }
        }
        throw ToolException.invalid("Enumeration \"" + stereotype.getQualifiedName() + "::" + tagName
                + "\" does not own literal " + value + ". Valid literals: " + names + ".");
    }

    /**
     * Extracts the literal name or id from a value: a plain string, or the name, literal or value field of an
     * object.
     *
     * @param value the JSON value
     * @return the first non-empty candidate, or null
     */
    private static String literalCandidate(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String s = value.getAsString();
            return s == null || s.isEmpty() ? null : s;
        }
        if (!value.isJsonObject()) {
            return null;
        }
        JsonObject o = value.getAsJsonObject();
        for (String key : new String[]{"name", "literal", "value"}) {
            if (o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isString()) {
                String s = o.get(key).getAsString();
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Primitive JSON -> Java value; {"id": x} -> referenced element when it exists.
     *
     * @param project the open project, used to resolve {"id"} references
     * @param value the JSON value
     * @return a Boolean, Number or String for primitives, a List for arrays, the referenced Element for an
     * {"id"} object whose id exists, otherwise the object's JSON text; null for JSON null
     */
    static Object coerceGeneric(Project project, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                out.add(coerceGeneric(project, item));
            }
            return out;
        }
        if (value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            if (o.has("id") && o.get("id").isJsonPrimitive()) {
                BaseElement ref = project.getElementByID(o.get("id").getAsString());
                if (ref instanceof Element) {
                    return ref;
                }
            }
            return o.toString();
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            return p.getAsNumber();
        }
        return p.getAsString();
    }

    /**
     * Tag value -> JSON (elements as compact references).
     *
     * @param value a tag value from Cameo: a primitive, Element, Collection or anything else
     * @return the JSON form; any other object becomes its {@code String.valueOf} text, and null stays null
     */
    static JsonElement serialize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof Character) {
            return new JsonPrimitive((Character) value);
        }
        if (value instanceof Element) {
            return ElementJson.toJsonCompact((Element) value);
        }
        if (value instanceof Collection<?>) {
            JsonArray arr = new JsonArray();
            for (Object item : (Collection<?>) value) {
                JsonElement s = serialize(item);
                if (s != null) {
                    arr.add(s);
                }
            }
            return arr;
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
