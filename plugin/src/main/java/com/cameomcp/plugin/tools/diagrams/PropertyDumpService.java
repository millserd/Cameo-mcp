package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonNull;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonPrimitive;
import com.nomagic.magicdraw.properties.Property;
import com.nomagic.magicdraw.properties.PropertyManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostic dumps of diagram and presentation property managers (cameo_dump_*_properties). */
final class PropertyDumpService {

    // Cap on items serialized per collection or map value, to keep dumps of large values bounded.
    private static final int MAX_ITEMS = 250;

    private PropertyDumpService() {
    }

    /*
     * cameo_dump_diagram_properties: the diagram's own presentation properties, counts of symbols by
     * class, and a page (offset/limit) of symbols, optionally each with its full property manager.
     * Read-only, so it runs on the EDT without a session; 120 s limit for big diagrams.
     */
    static JsonObject dumpDiagram(String diagramId, boolean includeRaw, boolean includePresentationProperties,
                                  boolean summaryOnly, int limit, int offset) throws Exception {
        return Edt.onEdt("Dump Diagram Properties", project -> {
            DiagramPresentationElement dpe = DiagramSupport.findDiagram(project, diagramId);
            JsonArray warnings = new JsonArray();
            // Unlike loadedDiagram, a failing ensureLoaded is reported as a warning so the dump still returns
            // whatever is readable.
            try {
                dpe.ensureLoaded();
                warnings.add("Diagram ensureLoaded() was called before presentation inspection");
            } catch (Exception e) {
                warnings.add("ensureLoaded failed: " + e.getMessage());
            }
            JsonObject response = new JsonObject();
            response.add("diagram", diagramSummary(dpe));
            // The Diagram is an Element, so this yields its compact element JSON.
            response.add("diagramProperties", serializeValue(dpe.getDiagram(), includeRaw, true));
            try {
                response.add("diagramPresentationProperties",
                        serializeManager(DiagramSupport.propertyManager(dpe), includeRaw, summaryOnly));
            } catch (Exception e) {
                warnings.add("Could not read diagram presentation property manager: " + e.getMessage());
            }
            Map<String, String> parentById = new LinkedHashMap<>();
            List<PresentationElement> all = new ArrayList<>();
            DiagramSupport.collect(dpe.getPresentationElements(), all, parentById, null);
            response.addProperty("presentationCount", all.size());
            response.add("presentationCountsByType", countByType(all));

            int start = Math.max(0, offset);
            int end = Math.min(all.size(), start + Math.max(0, limit));
            JsonArray page = new JsonArray();
            for (int i = start; i < end; i++) {
                PresentationElement pe = all.get(i);
                page.add(includePresentationProperties
                        ? withProperties(pe, parentById.get(pe.getID()), includeRaw, summaryOnly)
                        : summary(pe, parentById.get(pe.getID())));
            }
            response.addProperty("limit", limit);
            response.addProperty("offset", offset);
            response.addProperty("returnedPresentationCount", page.size());
            response.addProperty("hasMore", end < all.size());
            if (end < all.size()) {
                response.addProperty("nextOffset", end);
            }
            response.add("presentations", page);
            response.add("warnings", warnings);
            return response;
        }, 120);
    }

    // cameo_dump_presentation_properties: one symbol with its full property manager and direct children.
    static JsonObject dumpPresentation(String diagramId, String presentationId, boolean includeRaw,
                                       boolean summaryOnly) throws Exception {
        return Edt.onEdt("Dump Presentation Properties", project -> {
            DiagramPresentationElement dpe = DiagramSupport.loadedDiagram(project, diagramId);
            // Only the parent map is needed, to report the target's parentPresentationId.
            Map<String, String> parentById = new LinkedHashMap<>();
            DiagramSupport.collect(dpe.getPresentationElements(), new ArrayList<>(), parentById, null);
            PresentationElement target = DiagramSupport.requirePresentation(dpe, presentationId);
            JsonObject response = new JsonObject();
            response.add("diagram", diagramSummary(dpe));
            response.add("presentation", withProperties(target, parentById.get(target.getID()), includeRaw, summaryOnly));
            return response;
        });
    }

    // ------------------------------------------------------------------ presentation serializer

    static JsonObject diagramSummary(DiagramPresentationElement dpe) {
        JsonObject json = new JsonObject();
        Diagram diagram = dpe.getDiagram();
        if (diagram != null) {
            json.addProperty("id", diagram.getID());
        }
        json.addProperty("name", DiagramSupport.name(dpe));
        json.addProperty("type", DiagramSupport.diagramType(dpe));
        if (diagram != null && diagram.getOwner() != null) {
            json.add("owner", ElementJson.toJsonCompact(diagram.getOwner()));
        }
        return json;
    }

    // Identity of one symbol (full and simple class name, element, bounds, child count); read failures
    // become *Warning fields instead of errors.
    static JsonObject summary(PresentationElement pe, String parentId) {
        JsonObject json = new JsonObject();
        json.addProperty("presentationId", pe.getID());
        json.addProperty("presentationClassName", pe.getClass().getName());
        json.addProperty("shapeType", pe.getClass().getSimpleName());
        if (parentId != null) {
            json.addProperty("parentPresentationId", parentId);
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
            Rectangle b = pe.getBounds();
            if (b != null) {
                json.add("bounds", DiagramSupport.bounds(b));
            }
        } catch (Exception e) {
            json.addProperty("boundsWarning", e.getMessage());
        }
        List<PresentationElement> children = pe.getPresentationElements();
        json.addProperty("childCount", children != null ? children.size() : 0);
        return json;
    }

    // summary() plus the serialized property manager and one-level summaries of the direct children.
    static JsonObject withProperties(PresentationElement pe, String parentId, boolean includeRaw, boolean summaryOnly) {
        JsonObject json = summary(pe, parentId);
        try {
            json.add("propertyManager", serializeManager(DiagramSupport.propertyManager(pe), includeRaw, summaryOnly));
        } catch (Exception e) {
            JsonArray warnings = new JsonArray();
            warnings.add("Could not read presentation property manager: " + e.getMessage());
            json.add("warnings", warnings);
        }
        JsonArray children = new JsonArray();
        List<PresentationElement> kids = pe.getPresentationElements();
        if (kids != null) {
            for (PresentationElement c : kids) {
                children.add(summary(c, pe.getID()));
            }
        }
        json.add("children", children);
        return json;
    }

    // Symbol counts keyed by presentation class simple name, in first-seen order.
    static JsonObject countByType(Collection<PresentationElement> elements) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (PresentationElement pe : elements) {
            map.merge(pe != null ? pe.getClass().getSimpleName() : "null", 1, Integer::sum);
        }
        JsonObject counts = new JsonObject();
        map.forEach(counts::addProperty);
        return counts;
    }

    // ------------------------------------------------------------------ property serializer

    // Every property of a manager; a failure while listing becomes a warning on the result.
    static JsonObject serializeManager(PropertyManager manager, boolean includeRaw, boolean summaryOnly) {
        JsonObject json = new JsonObject();
        JsonArray properties = new JsonArray();
        JsonArray warnings = new JsonArray();
        if (manager == null) {
            json.addProperty("propertyCount", 0);
            json.add("properties", properties);
            warnings.add("PropertyManager was null");
            json.add("warnings", warnings);
            return json;
        }
        json.addProperty("className", manager.getClass().getName());
        try {
            for (Property p : DiagramService.properties(manager)) {
                properties.add(serializeProperty(p, includeRaw, summaryOnly));
            }
        } catch (Exception e) {
            warnings.add("Could not read properties: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        json.addProperty("propertyCount", properties.size());
        json.add("properties", properties);
        if (warnings.size() > 0) {
            json.add("warnings", warnings);
        }
        return json;
    }

    // One property: name, id, class type, value (with its Java type and string form) and, with includeRaw,
    // the raw class/string of the value. Each read is guarded so one bad getter does not hide the rest.
    static JsonObject serializeProperty(Property property, boolean includeRaw, boolean summaryOnly) {
        JsonObject json = new JsonObject();
        JsonArray warnings = new JsonArray();
        if (property == null) {
            json.add("value", JsonNull.INSTANCE);
            warnings.add("Property was null");
            json.add("warnings", warnings);
            return json;
        }
        safeAdd(json, "name", property::getName, warnings);
        safeAdd(json, "id", property::getID, warnings);
        safeAdd(json, "classType", property::getClassType, warnings);
        json.addProperty("propertyClassName", property.getClass().getName());
        Object value = null;
        try {
            value = property.getValue();
            json.add("value", serializeValue(value, includeRaw, summaryOnly));
            json.addProperty("valueType", value != null ? value.getClass().getName() : "null");
            json.addProperty("valueString", value != null ? String.valueOf(value) : null);
        } catch (Exception e) {
            warnings.add("Could not read value: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            json.add("value", JsonNull.INSTANCE);
        }
        if (includeRaw && value != null) {
            JsonObject raw = new JsonObject();
            raw.addProperty("className", value.getClass().getName());
            raw.addProperty("string", String.valueOf(value));
            json.add("raw", raw);
        }
        if (warnings.size() > 0) {
            json.add("warnings", warnings);
        }
        return json;
    }

    /*
     * Best-effort JSON for an arbitrary property value: primitives as-is, Elements as compact JSON,
     * collections/maps as a count plus up to MAX_ITEMS entries (items omitted with summaryOnly). Anything
     * else becomes class name + toString, and with includeRaw also the results of its no-arg getters.
     */
    static JsonElement serializeValue(Object value, boolean includeRaw, boolean summaryOnly) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Character) {
            return new JsonPrimitive((Character) value);
        }
        if (value instanceof Element) {
            return ElementJson.toJsonCompact((Element) value);
        }
        if (value instanceof Collection<?>) {
            JsonArray array = new JsonArray();
            if (!summaryOnly) {
                int n = 0;
                // Nested values are serialized in summary mode so a dump does not expand recursively.
                for (Object item : (Collection<?>) value) {
                    if (n++ >= MAX_ITEMS) {
                        break;
                    }
                    array.add(serializeValue(item, includeRaw, true));
                }
            }
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("collectionType", value.getClass().getName());
            wrapper.addProperty("count", ((Collection<?>) value).size());
            wrapper.add("items", array);
            return wrapper;
        }
        if (value instanceof Map<?, ?>) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("mapType", value.getClass().getName());
            wrapper.addProperty("count", ((Map<?, ?>) value).size());
            if (!summaryOnly) {
                JsonObject entries = new JsonObject();
                int n = 0;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    if (n++ >= MAX_ITEMS) {
                        break;
                    }
                    entries.add(String.valueOf(e.getKey()), serializeValue(e.getValue(), includeRaw, true));
                }
                wrapper.add("entries", entries);
            }
            return wrapper;
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("className", value.getClass().getName());
        fallback.addProperty("string", String.valueOf(value));
        if (includeRaw) {
            // Reflective getter sweep: diagnostic only, used to discover how an unfamiliar value type is structured.
            JsonObject getters = new JsonObject();
            for (Method m : value.getClass().getMethods()) {
                String n = m.getName();
                if (m.getParameterCount() != 0 || m.getReturnType() == Void.TYPE || "getClass".equals(n)
                        || !(n.startsWith("get") || n.startsWith("is"))) {
                    continue;
                }
                try {
                    getters.add(n, serializeValue(m.invoke(value), false, true));
                } catch (Exception | LinkageError ignored) {
                    // diagnostic only
                }
            }
            if (getters.size() > 0) {
                fallback.add("getterValues", getters);
            }
        }
        return fallback;
    }

    // A Supplier that is allowed to throw from Cameo getters, used by safeAdd.
    private interface StringSupplier {
        String get();
    }

    private static void safeAdd(JsonObject json, String key, StringSupplier s, JsonArray warnings) {
        try {
            String v = s.get();
            if (v != null) {
                json.addProperty(key, v);
            }
        } catch (Exception e) {
            warnings.add("Could not read " + key + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
