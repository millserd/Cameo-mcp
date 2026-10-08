package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.Internal;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.PresentationElementsManager;
import com.nomagic.magicdraw.properties.Property;
import com.nomagic.magicdraw.properties.PropertyManager;
import com.nomagic.magicdraw.uml.ClassTypes;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.magicdraw.uml.symbols.paths.PathElement;
import com.nomagic.magicdraw.uml.symbols.shapes.ShapeElement;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.activities.mdintermediateactivities.ActivityPartition;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.addValue;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.findDiagram;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.findElement;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.findPresentation;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.itemGet;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.itemInt;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.itemStr;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.loadedDiagram;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.requirePresentation;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.safeMessage;

/** Diagram lifecycle, shapes, paths and per-shape properties. */
final class DiagramService {

    private DiagramService() {
    }

    // ================================================================== diagrams

    static JsonObject listDiagrams() throws Exception {
        return Edt.read(project -> {
            JsonArray array = new JsonArray();
            Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
            if (diagrams != null) {
                for (DiagramPresentationElement dpe : diagrams) {
                    try {
                        array.add(DiagramSupport.diagramJson(dpe));
                    } catch (RuntimeException ignored) {
                        // skip diagrams that cannot be read
                    }
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("count", array.size());
            response.add("diagrams", array);
            return response;
        });
    }

    /** Relation-map options of cameo_create_diagram. */
    static final class RelationMapOptions {
        String contextId;
        List<String> scopeIds;
        List<String> elementTypes;
        List<String> dependencyCriteria;
        Integer depth;

        boolean hasConfiguration() {
            return contextId != null || scopeIds != null || elementTypes != null || dependencyCriteria != null
                    || depth != null;
        }
    }

    static JsonObject createDiagram(String type, String name, String parentId, RelationMapOptions rm)
            throws Exception {
        if (type == null || type.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter 'type'. Call cameo_list_diagram_types for valid values.");
        }
        if (rm.depth != null && (rm.depth < -1 || rm.depth > 100)) {
            throw ToolException.invalid("relation_map_depth must be -1 for indefinite depth, or between 0 and 100.");
        }
        List<String> candidates = DiagramTypes.nativeCandidates(type);
        JsonObject relationMapPlan = new JsonObject();
        JsonObject created = Edt.write("Create Diagram", project -> {
            Namespace parent;
            if (parentId != null && !parentId.trim().isEmpty()) {
                Element p = findElement(project, parentId, "Parent element");
                if (!(p instanceof Namespace)) {
                    throw ToolException.invalid("Parent element '" + parentId + "' is a " + p.getHumanType()
                            + ", not a Namespace. Use a Package, Block/Class or other namespace as parent_id.");
                }
                parent = (Namespace) p;
            } else {
                parent = project.getPrimaryModel();
                if (parent == null) {
                    throw new ToolException("PRECONDITION_FAILED", "The project has no primary model.");
                }
            }
            boolean relationMap = DiagramTypes.isRelationMap(candidates.get(0));
            if (relationMap && rm.hasConfiguration()) {
                planRelationMap(project, rm, relationMapPlan);
            }

            ModelElementsManager mem = ModelElementsManager.getInstance();
            Diagram diagram = null;
            String resolvedType = null;
            List<String> failures = new ArrayList<>();
            for (String candidate : candidates) {
                try {
                    diagram = mem.createDiagram(candidate, parent);
                    if (diagram == null) {
                        failures.add(candidate + ": not created");
                    }
                } catch (Exception e) {
                    failures.add(candidate + ": " + safeMessage(e));
                    diagram = null;
                }
                if (diagram != null) {
                    resolvedType = candidate;
                    break;
                }
            }
            if (diagram == null) {
                throw new ToolException("INVALID_PARAMS", "Cameo could not create a diagram of type '" + type + "' ("
                        + String.join("; ", failures) + "). Call cameo_list_diagram_types for validated types; SysML "
                        + "types need a project with the SysML profile.");
            }
            if (name != null && !name.isEmpty()) {
                diagram.setName(name);
            }
            JsonObject response = new JsonObject();
            response.addProperty("id", diagram.getID());
            response.addProperty("name", diagram.getName() != null ? diagram.getName() : "");
            response.addProperty("type", resolvedType);
            response.addProperty("parentId", parent.getID());
            return response;
        });

        if (relationMapPlan.size() > 0) {
            created.add("relationMap", configureRelationMap(created.get("id").getAsString(), rm, relationMapPlan));
        }
        return created;
    }

    /** Validate relation-map references inside the create session and collect what to configure. */
    private static void planRelationMap(Project project, RelationMapOptions rm, JsonObject plan) {
        if (rm.contextId != null) {
            plan.add("context", ElementJson.toJsonCompact(findElement(project, rm.contextId, "Relation map context element")));
        }
        if (rm.scopeIds != null) {
            JsonArray scope = new JsonArray();
            for (String id : rm.scopeIds) {
                scope.add(ElementJson.toJsonCompact(findElement(project, id, "Relation map scope element")));
            }
            plan.add("scope", scope);
            plan.addProperty("scopeCount", scope.size());
        }
        if (rm.elementTypes != null) {
            JsonArray types = new JsonArray();
            for (String typeName : rm.elementTypes) {
                types.add(ElementJson.toJsonCompact(resolveTypeElement(project, typeName)));
            }
            plan.add("elementTypes", types);
            plan.addProperty("elementTypeCount", types.size());
        }
        if (rm.dependencyCriteria != null) {
            plan.addProperty("dependencyCriteriaCount", rm.dependencyCriteria.size());
        }
        if (rm.depth != null) {
            plan.addProperty("depth", rm.depth);
        }
        plan.addProperty("planned", true);
    }

    /** Apply relation-map settings through the artifacts family's public tool, after the diagram exists. */
    private static JsonObject configureRelationMap(String diagramId, RelationMapOptions rm, JsonObject plan) {
        JsonObject out = plan.deepCopy();
        out.remove("planned");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("relation_map_id", diagramId);
        if (rm.contextId != null) {
            args.put("context_element_id", rm.contextId);
            args.put("make_element_as_context", true);
        }
        if (rm.scopeIds != null) {
            args.put("scope_ids", rm.scopeIds);
        }
        if (plan.has("elementTypes")) {
            List<String> ids = new ArrayList<>();
            for (JsonElement t : plan.getAsJsonArray("elementTypes")) {
                ids.add(t.getAsJsonObject().get("id").getAsString());
            }
            args.put("element_type_ids", ids);
            args.put("types_include_subtypes", true);
            args.put("types_include_custom_types", true);
        }
        if (rm.dependencyCriteria != null) {
            args.put("dependency_criteria", rm.dependencyCriteria);
        }
        if (rm.depth != null) {
            args.put("depth", rm.depth);
        }
        try {
            Map<String, Object> result = Internal.call("cameo_configure_relation_map", args);
            out.addProperty("configured", true);
            out.addProperty("refreshed", true);
            out.add("settings", Gs.toElement(result));
        } catch (Exception e) {
            out.addProperty("configured", false);
            out.addProperty("refreshed", false);
            out.addProperty("error", (e instanceof ToolException ? ((ToolException) e).code() + ": " : "")
                    + safeMessage(e));
            out.addProperty("hint", "The Relation Map diagram was created but its settings were not applied. "
                    + "Retry with cameo_configure_relation_map(relation_map_id='" + diagramId + "', ...), or use "
                    + "cameo_create_relation_map, which needs the Relation Map plugin.");
        }
        return out;
    }

    private static Element resolveTypeElement(Project project, String typeName) {
        String normalized = normalizeTypeToken(typeName);
        Collection<Stereotype> stereotypes = StereotypesHelper.getAllStereotypes(project);
        if (stereotypes != null) {
            for (Stereotype s : stereotypes) {
                if (normalized.equals(normalizeTypeToken(s.getName()))) {
                    return s;
                }
            }
        }
        java.lang.Class<?> metaclass = ClassTypes.getClassType(typeName);
        if (metaclass == null) {
            metaclass = ClassTypes.getClassType(normalized);
        }
        if (metaclass != null) {
            String shortName = ClassTypes.getShortName(metaclass);
            if (shortName != null && !shortName.isEmpty()) {
                Element mc = StereotypesHelper.getMetaClassByName(project, shortName);
                if (mc != null) {
                    return mc;
                }
            }
        }
        throw ToolException.invalid("Unknown relation map element type: '" + typeName
                + "'. Use a UML metaclass (Class, Activity) or stereotype name (Block, Requirement).");
    }

    private static String normalizeTypeToken(String input) {
        return input == null ? "" : input.replaceAll("[^A-Za-z0-9]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    static JsonObject deleteDiagram(String diagramId) throws Exception {
        return Edt.write("Delete Diagram", project -> {
            DiagramPresentationElement dpe = findDiagram(project, diagramId);
            Diagram diagram = dpe.getDiagram();
            JsonObject response = DiagramSupport.diagramJson(dpe);
            if (diagram == null) {
                throw new ToolException("NOT_FOUND", "Diagram '" + diagramId + "' has no model element.");
            }
            if (!diagram.isEditable()) {
                throw new ToolException("PRECONDITION_FAILED", "Diagram '" + diagramId + "' is read-only (locked "
                        + "in Teamwork Cloud or part of a used project).");
            }
            ModelElementsManager.getInstance().removeElement(diagram);
            response.addProperty("deleted", true);
            response.addProperty("diagramId", diagramId);
            response.addProperty("note", "Only the diagram was removed; the model elements it showed remain.");
            return response;
        });
    }

    static JsonObject renameDiagram(String diagramId, String name) throws Exception {
        if (name == null || name.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter 'name'.");
        }
        return Edt.write("Rename Diagram", project -> {
            DiagramPresentationElement dpe = findDiagram(project, diagramId);
            Diagram diagram = dpe.getDiagram();
            if (!diagram.isEditable()) {
                throw new ToolException("PRECONDITION_FAILED", "Diagram '" + diagramId + "' is read-only.");
            }
            String old = diagram.getName();
            diagram.setName(name);
            JsonObject response = DiagramSupport.diagramJson(dpe);
            response.addProperty("diagramId", diagramId);
            response.addProperty("previousName", old != null ? old : "");
            response.addProperty("renamed", true);
            return response;
        });
    }

    static JsonObject autoLayout(String diagramId) throws Exception {
        return Edt.write("Auto-Layout Diagram", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            boolean success = DiagramSupport.layout(dpe);
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("name", DiagramSupport.name(dpe));
            response.addProperty("layoutApplied", success);
            return response;
        }, 180);
    }

    // ================================================================== add to diagram

    static JsonObject addToDiagram(String diagramId, String elementId, Integer x, Integer y, Integer width,
                                   Integer height, String containerPeId) throws Exception {
        if (elementId == null || elementId.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter 'element_id'.");
        }
        Integer w = width != null && width >= 0 ? width : null;
        Integer h = height != null && height >= 0 ? height : null;
        if ((w == null) != (h == null)) {
            throw ToolException.invalid("width and height must both be non-negative, or both be omitted/negative.");
        }
        return Edt.write("Add Element to Diagram", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            Element element = findElement(project, elementId, "Element");
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            PresentationElement shapeParent = dpe;
            if (containerPeId != null) {
                shapeParent = findPresentation(dpe.getPresentationElements(), containerPeId);
                if (shapeParent == null) {
                    throw new ToolException("NOT_FOUND", "Container shape '" + containerPeId + "' not found. Use "
                            + "cameo_list_diagram_shapes to get container presentation IDs.");
                }
            }
            int px = x != null ? x : 100;
            int py = y != null ? y : 100;
            ShapeElement shape;
            String status;
            boolean partitionNative = false;
            if (element instanceof ActivityPartition) {
                if (shapeParent != dpe) {
                    throw new ToolException("UNSUPPORTED", "ActivityPartition insertion through "
                            + "container_presentation_id is not supported; omit it and the swimlane container is "
                            + "created or resolved natively.");
                }
                Object[] r = addActivityPartition(dpe, (ActivityPartition) element, pem, x, y, w, h);
                shape = (ShapeElement) r[0];
                status = (String) r[1];
                partitionNative = true;
            } else {
                shape = pem.createShapeElement(element, shapeParent, true, new Point(px, py));
                if (shape == null) {
                    throw new ToolException("TOOL_FAILED", "Cameo could not create a shape for element '" + elementId
                            + "' on this diagram (the element type may not be allowed on a "
                            + DiagramSupport.diagramType(dpe) + ").");
                }
                if (w != null) {
                    pem.reshapeShapeElement(shape, new Rectangle(px, py, w, h));
                }
                status = "created";
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("elementId", elementId);
            Rectangle bounds = null;
            try {
                bounds = shape.getBounds();
            } catch (RuntimeException ignored) {
                // bounds not available for all shape types
            }
            if (bounds != null) {
                response.addProperty("x", bounds.x);
                response.addProperty("y", bounds.y);
                response.addProperty("width", bounds.width);
                response.addProperty("height", bounds.height);
            } else {
                response.addProperty("x", px);
                response.addProperty("y", py);
            }
            response.addProperty("added", true);
            response.addProperty("presentationId", shape.getID());

            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "addShape");
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("elementId", elementId);
            receipt.addProperty("presentationId", shape.getID());
            if (containerPeId != null) {
                receipt.addProperty("containerPresentationId", containerPeId);
            }
            if (bounds != null) {
                receipt.addProperty("x", bounds.x);
                receipt.addProperty("y", bounds.y);
                receipt.addProperty("width", bounds.width);
                receipt.addProperty("height", bounds.height);
            } else {
                receipt.addProperty("x", px);
                receipt.addProperty("y", py);
                if (w != null) {
                    receipt.addProperty("width", w);
                    receipt.addProperty("height", h);
                }
            }
            receipt.addProperty("status", status);
            if (partitionNative) {
                receipt.addProperty("activityPartitionNative", true);
            }
            response.add("receipt", receipt);
            return response;
        });
    }

    /** Returns {shape, status}. */
    private static Object[] addActivityPartition(DiagramPresentationElement dpe, ActivityPartition partition,
                                                 PresentationElementsManager pem, Integer x, Integer y,
                                                 Integer width, Integer height) throws Exception {
        PresentationElement existing = findPartitionPresentation(dpe.getPresentationElements(), partition.getID());
        if (existing != null) {
            return new Object[]{requireShape(existing, "Existing activity partition presentation is not a shape"),
                    "existing"};
        }
        if (findFirstSwimlane(dpe.getPresentationElements()) != null) {
            throw new ToolException("CONFLICT", "The diagram already has a swimlane but no presentation for partition "
                    + partition.getID() + ". Refusing to rebuild the whole swimlane container automatically; add the "
                    + "partition to the existing swimlane in Cameo or delete the swimlane first.");
        }
        List<ActivityPartition> siblings = siblingPartitions(partition);
        int laneCount = Math.max(siblings.size(), 1);
        int laneWidth = width != null ? width : 220;
        int totalHeight = height != null ? height : 280;
        @SuppressWarnings({"rawtypes", "unchecked"})
        ShapeElement swimlane = pem.createSwimlane(Collections.emptyList(), new ArrayList(siblings), dpe);
        if (swimlane == null) {
            throw new ToolException("TOOL_FAILED", "Failed to create swimlane for activity partition: " + partition.getID());
        }
        pem.reshapeShapeElement(swimlane, new Rectangle(x != null ? x : 100, y != null ? y : 100,
                laneWidth * laneCount, totalHeight));
        PresentationElement created = findPartitionPresentation(dpe.getPresentationElements(), partition.getID());
        if (created == null) {
            throw new ToolException("TOOL_FAILED", "Failed to locate the created swimlane presentation for activity "
                    + "partition: " + partition.getID());
        }
        return new Object[]{requireShape(created, "Created activity partition presentation is not a shape"), "created"};
    }

    private static List<ActivityPartition> siblingPartitions(ActivityPartition partition) {
        Element owner = partition.getOwner();
        if (owner == null) {
            return Collections.singletonList(partition);
        }
        List<ActivityPartition> partitions = new ArrayList<>();
        for (Element owned : owner.getOwnedElement()) {
            if (owned instanceof ActivityPartition) {
                partitions.add((ActivityPartition) owned);
            }
        }
        if (!partitions.contains(partition)) {
            partitions.add(partition);
        }
        return partitions;
    }

    private static PresentationElement findPartitionPresentation(List<PresentationElement> elements, String id) {
        if (elements == null || id == null) {
            return null;
        }
        PresentationElement fallback = null;
        for (PresentationElement pe : elements) {
            Element el = null;
            try {
                el = pe.getElement();
            } catch (RuntimeException ignored) {
                // no backing element
            }
            if (el != null && id.equals(el.getID())) {
                String cls = pe.getClass().getSimpleName();
                if ("SwimlaneHeaderView".equals(cls)) {
                    return pe;
                }
                if (fallback == null && (cls.contains("Swimlane") || cls.contains("Partition"))) {
                    fallback = pe;
                }
            }
            PresentationElement nested = findPartitionPresentation(pe.getPresentationElements(), id);
            if (nested != null) {
                return nested;
            }
        }
        return fallback;
    }

    private static ShapeElement findFirstSwimlane(List<PresentationElement> elements) {
        if (elements == null) {
            return null;
        }
        for (PresentationElement pe : elements) {
            if ("SwimlaneView".equals(pe.getClass().getSimpleName())) {
                return requireShape(pe, "Swimlane presentation is not a shape");
            }
            ShapeElement nested = findFirstSwimlane(pe.getPresentationElements());
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static ShapeElement requireShape(PresentationElement pe, String message) {
        if (pe instanceof ShapeElement) {
            return (ShapeElement) pe;
        }
        throw new ToolException("TOOL_FAILED", message);
    }

    // ================================================================== shapes

    static JsonObject listShapes(String diagramId, ShapeFilter.Options options) throws Exception {
        JsonArray shapes = Edt.onEdt("List Diagram Shapes", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            return DiagramSupport.collectShapes(dpe);
        });
        return ShapeFilter.filter(diagramId, shapes, options);
    }

    static JsonObject getShapeProperties(String diagramId, String peId) throws Exception {
        return Edt.onEdt("Get Shape Properties", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            PresentationElement target = requirePresentation(dpe, peId);
            PropertyManager pm = DiagramSupport.propertyManager(target);
            JsonArray list = new JsonArray();
            JsonObject props = new JsonObject();
            JsonObject compartments = new JsonObject();
            if (pm != null) {
                for (Property p : properties(pm)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", p.getName());
                    if (p.getID() != null) {
                        entry.addProperty("id", p.getID());
                    }
                    if (p.getClassType() != null) {
                        entry.addProperty("classType", p.getClassType());
                    }
                    Object value = safeValue(p);
                    addValue(entry, "value", value);
                    list.add(entry);
                    addValue(props, p.getName(), value);
                    String key = PresentationPresets.canonicalCompartmentKey(p.getName());
                    if (key != null) {
                        addValue(compartments, key, value);
                    }
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("presentationId", peId);
            JsonObject shape = DiagramSupport.describe(target, null);
            if (shape.has("shapeType")) {
                response.add("shapeType", shape.get("shapeType"));
            }
            if (shape.has("elementId")) {
                response.add("elementId", shape.get("elementId"));
            }
            response.add("properties", props);
            response.add("propertyList", list);
            response.add("compartments", compartments);
            response.addProperty("resultCount", list.size());
            return response;
        });
    }

    @SuppressWarnings("unchecked")
    static List<Property> properties(PropertyManager pm) {
        List<Property> l = pm.getProperties();
        return l != null ? l : Collections.emptyList();
    }

    static Object safeValue(Property p) {
        try {
            return p.getValue();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static JsonObject moveShapes(String diagramId, List<JsonObject> shapes) throws Exception {
        return Edt.write("Move/Resize Shapes", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            List<PresentationElement> all = dpe.getPresentationElements();
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            for (JsonObject req : shapes) {
                String peId = itemStr(req, "presentationId");
                Integer x = itemInt(req, "x");
                Integer y = itemInt(req, "y");
                Integer w = itemInt(req, "width");
                Integer h = itemInt(req, "height");
                if (peId == null || x == null || y == null || w == null || h == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("presentationId", peId);
                    err.addProperty("error", "Request item must include presentationId, x, y, width, and height");
                    results.add(err);
                    continue;
                }
                PresentationElement target = findPresentation(all, peId);
                if (target == null) {
                    results.add(error(peId, "Presentation element not found"));
                    continue;
                }
                if (!(target instanceof ShapeElement)) {
                    results.add(error(peId, "Element is not a shape (cannot reshape paths)"));
                    continue;
                }
                try {
                    pem.reshapeShapeElement((ShapeElement) target, new Rectangle(x, y, w, h));
                } catch (Exception e) {
                    results.add(error(peId, "Reshape failed: " + safeMessage(e)));
                    continue;
                }
                JsonObject ok = new JsonObject();
                ok.addProperty("presentationId", peId);
                ok.addProperty("reshaped", true);
                JsonObject receipt = new JsonObject();
                receipt.addProperty("operation", "reshapeShape");
                receipt.addProperty("diagramId", diagramId);
                receipt.addProperty("presentationId", peId);
                receipt.addProperty("x", x);
                receipt.addProperty("y", y);
                receipt.addProperty("width", w);
                receipt.addProperty("height", h);
                receipt.addProperty("status", "applied");
                ok.add("receipt", receipt);
                results.add(ok);
            }
            return batch(diagramId, results);
        });
    }

    static JsonObject deleteShapes(String diagramId, List<String> ids) throws Exception {
        if (ids.isEmpty()) {
            throw ToolException.invalid("Parameter 'presentation_ids' must be a non-empty list.");
        }
        return Edt.write("Delete Presentation Elements", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            List<PresentationElement> all = dpe.getPresentationElements();
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            for (String peId : ids) {
                PresentationElement target = findPresentation(all, peId);
                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", peId);
                if (target == null) {
                    entry.addProperty("error", "Presentation element not found");
                } else {
                    try {
                        pem.deletePresentationElement(target);
                        entry.addProperty("deleted", true);
                    } catch (Exception e) {
                        entry.addProperty("error", "Delete failed: " + safeMessage(e));
                    }
                }
                results.add(entry);
            }
            return batch(diagramId, results);
        });
    }

    static JsonObject reparentShapes(String diagramId, List<JsonObject> reparentings) throws Exception {
        return Edt.write("Reparent Shapes", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            List<PresentationElement> all = dpe.getPresentationElements();
            Method setParent = PresentationElement.class.getMethod("setParent", PresentationElement.class);
            JsonArray results = new JsonArray();
            for (JsonObject req : reparentings) {
                String peId = itemStr(req, "presentationId");
                String parentId = itemStr(req, "parentPresentationId");
                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", peId);
                entry.addProperty("parentPresentationId", parentId);
                if (peId == null || parentId == null) {
                    entry.addProperty("error", "Each reparenting must include presentationId and parentPresentationId");
                    results.add(entry);
                    continue;
                }
                PresentationElement child = findPresentation(all, peId);
                PresentationElement parent = findPresentation(all, parentId);
                if (child == null) {
                    entry.addProperty("error", "Presentation element not found: " + peId);
                } else if (parent == null) {
                    entry.addProperty("error", "Parent presentation element not found: " + parentId);
                } else {
                    try {
                        setParent.invoke(child, parent);
                        entry.addProperty("reparented", true);
                        JsonObject receipt = new JsonObject();
                        receipt.addProperty("operation", "reparentShape");
                        receipt.addProperty("diagramId", diagramId);
                        receipt.addProperty("presentationId", peId);
                        receipt.addProperty("parentPresentationId", parentId);
                        receipt.addProperty("status", "applied");
                        entry.add("receipt", receipt);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        entry.addProperty("error", "Reparent failed: " + safeMessage(e.getCause() != null ? e.getCause() : e));
                    }
                }
                results.add(entry);
            }
            return batch(diagramId, results);
        });
    }

    static JsonObject addPaths(String diagramId, List<JsonObject> paths) throws Exception {
        return Edt.write("Add Relationship Paths", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            List<PresentationElement> all = dpe.getPresentationElements();
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            for (JsonObject req : paths) {
                String relId = itemStr(req, "relationshipId");
                String srcId = itemStr(req, "sourceShapeId");
                String tgtId = itemStr(req, "targetShapeId");
                if (relId == null || srcId == null || tgtId == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Request item must include relationshipId, sourceShapeId, and targetShapeId");
                    results.add(err);
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("relationshipId", relId);
                Object rel = project.getElementByID(relId);
                PresentationElement src = findPresentation(all, srcId);
                PresentationElement tgt = findPresentation(all, tgtId);
                if (!(rel instanceof Element)) {
                    entry.addProperty("error", "Relationship model element not found: " + relId);
                } else if (src == null) {
                    entry.addProperty("error", "Source presentation element not found: " + srcId
                            + " (use presentationId values from cameo_list_diagram_shapes, not model element IDs)");
                } else if (tgt == null) {
                    entry.addProperty("error", "Target presentation element not found: " + tgtId
                            + " (use presentationId values from cameo_list_diagram_shapes, not model element IDs)");
                } else {
                    try {
                        PathElement path = pem.createPathElement((Element) rel, src, tgt);
                        if (path != null) {
                            entry.addProperty("presentationId", path.getID());
                            entry.addProperty("created", true);
                        } else {
                            entry.addProperty("error", "Failed to create path element");
                        }
                    } catch (Exception e) {
                        entry.addProperty("error", "Failed to create path element: " + safeMessage(e));
                    }
                }
                results.add(entry);
            }
            return batch(diagramId, results);
        });
    }

    static JsonObject routePaths(String diagramId, List<JsonObject> routes) throws Exception {
        return Edt.write("Route Paths", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            List<PresentationElement> all = dpe.getPresentationElements();
            JsonArray results = new JsonArray();
            for (JsonObject req : routes) {
                String peId = itemStr(req, "presentationId");
                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", peId);
                if (peId == null) {
                    entry.addProperty("error", "Each route must include presentationId");
                    results.add(entry);
                    continue;
                }
                PresentationElement target = findPresentation(all, peId);
                if (!(target instanceof PathElement)) {
                    entry.addProperty("error", target == null ? "Presentation element not found"
                            : "Presentation element is not a path");
                    results.add(entry);
                    continue;
                }
                PathElement path = (PathElement) target;
                List<Point> breakPoints = DiagramSupport.points(itemGet(req, "breakPoints"));
                Point sourcePoint = DiagramSupport.point(itemGet(req, "sourcePoint"));
                Point targetPoint = DiagramSupport.point(itemGet(req, "targetPoint"));
                JsonElement reset = itemGet(req, "resetLabels");
                boolean resetLabels = reset == null || reset.getAsBoolean();
                try {
                    if (sourcePoint != null || targetPoint != null) {
                        pem.changePathPoints(path, sourcePoint, targetPoint, breakPoints);
                    } else {
                        pem.changePathBreakPoints(path, breakPoints);
                    }
                    if (resetLabels) {
                        try {
                            pem.resetLabelPositions(path);
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to reset label positions for presentation "
                                    + peId + ": " + safeMessage(e), e);
                        }
                    }
                } catch (Exception e) {
                    entry.addProperty("status", "error");
                    entry.addProperty("updated", false);
                    entry.addProperty("error", safeMessage(e));
                    results.add(entry);
                    continue;
                }
                entry.addProperty("routed", true);
                entry.addProperty("breakPointCount", breakPoints.size());
                JsonObject receipt = new JsonObject();
                receipt.addProperty("operation", "routePath");
                receipt.addProperty("diagramId", diagramId);
                receipt.addProperty("presentationId", peId);
                receipt.addProperty("resetLabels", resetLabels);
                receipt.addProperty("status", "applied");
                entry.add("receipt", receipt);
                results.add(entry);
            }
            return batch(diagramId, results);
        });
    }

    // ================================================================== shape properties

    static JsonObject setShapeProperties(String diagramId, String peId, JsonObject input) throws Exception {
        if (input.size() == 0) {
            throw ToolException.invalid("'properties' must contain at least one property name. Call "
                    + "cameo_get_shape_properties to see the names this shape supports.");
        }
        return Edt.write("Set Shape Properties", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            PresentationElement target = requirePresentation(dpe, peId);
            // The property manager must be cloned: Cameo rejects re-applying the live one.
            PropertyManager pm = DiagramSupport.propertyManagerCopy(target);
            List<Property> props = properties(pm);
            JsonArray updated = new JsonArray();
            int setCount = 0;
            for (Map.Entry<String, JsonElement> e : input.entrySet()) {
                Property match = null;
                for (Property p : props) {
                    if (e.getKey().equals(p.getName())) {
                        match = p;
                        break;
                    }
                }
                JsonObject u = new JsonObject();
                u.addProperty("name", e.getKey());
                if (match == null) {
                    u.addProperty("error", "Property not found on this shape");
                } else {
                    JsonElement val = e.getValue();
                    if (val == null || val.isJsonNull()) {
                        match.setValue("");
                    } else if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isBoolean()) {
                        match.setValue(val.getAsBoolean());
                    } else if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isNumber()) {
                        match.setValue(val.getAsInt());
                    } else if (val.isJsonPrimitive()) {
                        match.setValue(val.getAsString());
                    } else {
                        match.setValue(val.toString());
                    }
                    u.addProperty("set", true);
                    setCount++;
                }
                updated.add(u);
            }
            if (setCount > 0) {
                applyOrThrow(target, pm, "Failed to apply shape properties to presentation " + peId);
            }
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("presentationId", peId);
            response.add("properties", updated);
            response.addProperty("resultCount", updated.size());
            response.add("receipt", receipt("setShapeProperties", diagramId, peId, setCount > 0 ? "applied" : "noop"));
            return response;
        });
    }

    static JsonObject setShapeCompartments(String diagramId, String peId, JsonObject input) throws Exception {
        if (input.size() == 0) {
            throw ToolException.invalid("'compartments' must contain at least one key, e.g. {\"showAttributes\": false}.");
        }
        return Edt.write("Set Shape Compartments", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            PresentationElement target = requirePresentation(dpe, peId);
            PropertyManager pm = DiagramSupport.propertyManagerCopy(target);
            Map<String, Property> byName = new LinkedHashMap<>();
            for (Property p : properties(pm)) {
                byName.put(p.getName(), p);
            }
            JsonArray updated = new JsonArray();
            int setCount = 0;
            for (Map.Entry<String, JsonElement> e : input.entrySet()) {
                String key = e.getKey();
                Property property = resolveCompartmentProperty(byName, key);
                JsonObject entry = new JsonObject();
                entry.addProperty("compartment", key);
                if (property == null) {
                    entry.addProperty("error", "No matching compartment property found on this shape");
                    updated.add(entry);
                    continue;
                }
                JsonElement value = e.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                    property.setValue(PresentationPresets.sel(value.getAsBoolean(), Collections.emptyList(),
                            Collections.emptyList()).valueFor(property.getName()));
                } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    property.setValue(value.getAsInt());
                } else if (value.isJsonPrimitive()) {
                    property.setValue(value.getAsString());
                } else {
                    property.setValue(value.toString());
                }
                entry.addProperty("property", property.getName());
                addValue(entry, "value", safeValue(property));
                entry.addProperty("set", true);
                setCount++;
                updated.add(entry);
            }
            if (setCount > 0) {
                applyOrThrow(target, pm, "Failed to apply shape compartments to presentation " + peId);
            }
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("presentationId", peId);
            response.add("results", updated);
            response.addProperty("resultCount", updated.size());
            response.add("receipt", receipt("setShapeCompartments", diagramId, peId, setCount > 0 ? "applied" : "noop"));
            return response;
        });
    }

    private static Property resolveCompartmentProperty(Map<String, Property> byName, String requestedKey) {
        String normalized = CompartmentAliasResolver.normalizeKey(requestedKey);
        for (Map.Entry<String, Property> e : byName.entrySet()) {
            if (CompartmentAliasResolver.normalizeKey(e.getKey()).equals(normalized)) {
                return e.getValue();
            }
        }
        for (String candidate : CompartmentAliasResolver.candidatePropertyNames(requestedKey)) {
            Property p = byName.get(candidate);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    static void applyOrThrow(PresentationElement target, PropertyManager pm, String message) {
        try {
            PresentationElementsManager.getInstance().setPresentationElementProperties(target, pm);
        } catch (Exception e) {
            throw new ToolException("TOOL_FAILED", message + ": " + safeMessage(e));
        }
    }

    // ================================================================== small builders

    private static JsonObject error(String peId, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("presentationId", peId);
        err.addProperty("error", message);
        return err;
    }

    private static JsonObject batch(String diagramId, JsonArray results) {
        JsonObject response = new JsonObject();
        response.addProperty("diagramId", diagramId);
        response.addProperty("resultCount", results.size());
        int errors = 0;
        for (JsonElement r : results) {
            if (r.isJsonObject() && r.getAsJsonObject().has("error")) {
                errors++;
            }
        }
        response.addProperty("errorCount", errors);
        response.add("results", results);
        return response;
    }

    private static JsonObject receipt(String op, String diagramId, String peId, String status) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("operation", op);
        receipt.addProperty("diagramId", diagramId);
        receipt.addProperty("presentationId", peId);
        receipt.addProperty("status", status);
        return receipt;
    }
}
