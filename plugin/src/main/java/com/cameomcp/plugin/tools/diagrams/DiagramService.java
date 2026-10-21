package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.Internal;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
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

    /** Not instantiable; all members are static. */
    private DiagramService() {
    }

    // ================================================================== diagrams

    /**
     * Implements cameo_list_diagrams: every diagram in the project (id, name, type, owner). A diagram that
     * throws while being described is skipped so one broken diagram does not fail the whole listing.
     *
     * @return {@code count} and {@code diagrams}
     * @throws Exception when no project is open or the hand-off to the EDT fails
     */
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
        /** Element the relation map is centered on, or null. */
        String contextId;
        /** Elements whose contents the relation map covers, or null. */
        List<String> scopeIds;
        /** Element types (metaclass or stereotype names) the relation map shows, or null. */
        List<String> elementTypes;
        /** Dependency criteria expressions the relation map follows, or null. */
        List<String> dependencyCriteria;
        /** Relation map depth: -1 for indefinite, 0 to 100, or null for Cameo's default. */
        Integer depth;

        /**
         * Whether any relation map option was given.
         *
         * @return true when at least one field is set
         */
        boolean hasConfiguration() {
            return contextId != null || scopeIds != null || elementTypes != null || dependencyCriteria != null
                    || depth != null;
        }
    }

    /**
     * Implements cameo_create_diagram: resolves the type token to native Cameo type names and creates the
     * diagram under parent_id (default: the primary model). Each native name is tried in order until one
     * works. For a Relation Map with relation_map_* options, the settings are checked inside the create
     * session and applied after it closes.
     *
     * @param type the diagram type token or alias, see DiagramTypes
     * @param name the diagram name
     * @param parentId the owning namespace id, or null or blank for the primary model
     * @param rm the relation map options; ignored for other diagram types
     * @return {@code id}, {@code name}, {@code type} (the native type used), {@code parentId}, and {@code
     *     relationMap}
     * with the applied settings or the configuration error
     *
     * @throws ToolException INVALID_PARAMS for a missing type, bad depth, non-namespace parent, unknown
     *     relation map
     * type, or a type Cameo cannot create; NOT_FOUND for missing ids
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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
                parent = com.cameomcp.plugin.cameo.ProjectKind.primaryModel(project);
            }
            // Validating relation-map ids here, before createDiagram, means a bad id aborts the session and no
            // half-configured diagram is left behind.
            boolean relationMap = DiagramTypes.isRelationMap(candidates.get(0));
            if (relationMap && rm.hasConfiguration()) {
                planRelationMap(project, rm, relationMapPlan);
            }

            ModelElementsManager mem = ModelElementsManager.getInstance();
            Diagram diagram = null;
            String resolvedType = null;
            List<String> failures = new ArrayList<>();
            // Try each native name in order: an unknown name can throw or return null depending on the version,
            // so both count as "try the next one", and every failure is reported if none works.
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

        // Configured outside the create session: cameo_configure_relation_map runs its own Edt.write, which
        // cannot start while this one holds the single write permit. Failure is reported, not thrown, since
        // the diagram itself was created.
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

    /**
     * Resolves a relation-map element type. A stereotype whose normalized name matches wins (Block,
     * Requirement);
     * otherwise the token is tried as a UML metaclass, raw and then normalized.
     *
     * @param project the active project
     * @param typeName the type token
     * @return the stereotype or metaclass element
     * @throws ToolException INVALID_PARAMS when nothing matches
     */
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

    /**
     * Lower-case alphanumerics only, for comparing type tokens.
     *
     * @param input the token, may be null
     * @return the normalized token; "" for null
     */
    private static String normalizeTypeToken(String input) {
        return input == null ? "" : input.replaceAll("[^A-Za-z0-9]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Implements cameo_delete_diagram: removes the diagram element only; the model elements it showed remain.
     *
     * @param diagramId the diagram id
     * @return the diagram's summary from before the removal, {@code deleted}, {@code diagramId} and a {@code
     *     note}
     * @throws ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
    static JsonObject deleteDiagram(String diagramId) throws Exception {
        return Edt.write("Delete Diagram", project -> {
            DiagramPresentationElement dpe = findDiagram(project, diagramId);
            Diagram diagram = dpe.getDiagram();
            // Described before removal, while the diagram can still be read.
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

    /**
     * Implements cameo_rename_diagram: renames the diagram and reports the previous name.
     *
     * @param diagramId the diagram id
     * @param name the new name; must not be blank
     * @return {@code diagramId}, {@code previousName}, {@code renamed} and the diagram summary
     * @throws ToolException INVALID_PARAMS for a blank name, NOT_FOUND for a missing diagram
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_auto_layout: Cameo's layout through DiagramCompat, because the 2024x and 2026x APIs
     * differ. Uses a 180 second time limit instead of the default because layout of large diagrams can be
     * slow.
     *
     * @param diagramId the diagram id
     * @return {@code diagramId}, {@code name} and {@code layoutApplied}
     * @throws ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * cameo_add_to_diagram: shows an existing model element on a diagram, optionally inside a container
     * symbol. ActivityPartitions take a separate native swimlane path; everything else uses
     * createShapeElement. The receipt echoes the bounds Cameo actually applied.
     *
     * @param diagramId the diagram id
     * @param elementId the model element to show
     * @param x left edge, or null for Cameo's placement
     * @param y top edge, or null for Cameo's placement
     * @param width width, or null or negative for Cameo's default size
     * @param height height, or null or negative for Cameo's default size
     * @param containerPeId the presentation id of the container symbol, or null for the diagram itself
     * @return a {@code receipt} with the new presentation id, the applied bounds and a status
     * @throws ToolException INVALID_PARAMS for a missing element id or only one of width and height,
     *     NOT_FOUND for
     * missing ids, TOOL_FAILED when Cameo does not create the symbol
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
    static JsonObject addToDiagram(String diagramId, String elementId, Integer x, Integer y, Integer width,
                                   Integer height, String containerPeId) throws Exception {
        if (elementId == null || elementId.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter 'element_id'.");
        }
        // Negative width/height means "Cameo's default size", same as omitting them; only both-or-neither is valid.
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
            // Default drop point when x/y are omitted.
            int px = x != null ? x : 100;
            int py = y != null ? y : 100;
            ShapeElement shape;
            String status;
            boolean partitionNative = false;
            // Partitions are not dropped as ordinary shapes: they are drawn as lanes of a swimlane container.
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
                // createShapeElement takes only a point, so the size is applied in a second step.
                if (w != null) {
                    pem.reshapeShapeElement(shape, new Rectangle(px, py, w, h));
                }
                status = "created";
            }

            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("elementId", elementId);
            Rectangle bounds = null;
            // Prefer the bounds Cameo really used; fall back to the requested point when bounds are unavailable.
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
        // Only a fresh swimlane is built here; inserting a lane into an existing swimlane is left to the user
        // rather than tearing down and rebuilding the container.
        if (findFirstSwimlane(dpe.getPresentationElements()) != null) {
            throw new ToolException("CONFLICT", "The diagram already has a swimlane but no presentation for partition "
                    + partition.getID() + ". Refusing to rebuild the whole swimlane container automatically; add the "
                    + "partition to the existing swimlane in Cameo or delete the swimlane first.");
        }
        // One lane per partition of the same owner, so sibling lanes appear together. Defaults: 220 wide per
        // lane, 280 high overall.
        List<ActivityPartition> siblings = siblingPartitions(partition);
        int laneCount = Math.max(siblings.size(), 1);
        int laneWidth = width != null ? width : 220;
        int totalHeight = height != null ? height : 280;
        // createSwimlane is declared with raw List parameters, hence the raw ArrayList and the suppression.
        @SuppressWarnings({"rawtypes", "unchecked"})
        ShapeElement swimlane = pem.createSwimlane(Collections.emptyList(), new ArrayList(siblings), dpe);
        if (swimlane == null) {
            throw new ToolException("TOOL_FAILED", "Failed to create swimlane for activity partition: " + partition.getID());
        }
        pem.reshapeShapeElement(swimlane, new Rectangle(x != null ? x : 100, y != null ? y : 100,
                laneWidth * laneCount, totalHeight));
        // createSwimlane does not hand back the lane for this partition, so look it up in the symbol tree.
        PresentationElement created = findPartitionPresentation(dpe.getPresentationElements(), partition.getID());
        if (created == null) {
            throw new ToolException("TOOL_FAILED", "Failed to locate the created swimlane presentation for activity "
                    + "partition: " + partition.getID());
        }
        return new Object[]{requireShape(created, "Created activity partition presentation is not a shape"), "created"};
    }

    /**
     * All partitions owned by the partition's owner (normally the activity), always including this one.
     *
     * @param partition the partition
     * @return the sibling partitions in model order
     */
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

    /**
     * Finds the symbol showing a partition. A SwimlaneHeaderView wins at once; another view whose class
     * name contains Swimlane/Partition is kept as a fallback. Matched by class simple name, so no internal
     * view classes are referenced.
     *
     * @param elements the symbols to search, recursively; may be null
     * @param id the partition's element id, may be null
     * @return the best matching symbol, or null
     */
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

    /**
     * Depth-first search for an existing swimlane container symbol (SwimlaneView).
     *
     * @param elements the symbols to search, may be null
     * @return the first swimlane, or null
     */
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

    /**
     * Casts a symbol to a shape.
     *
     * @param pe the symbol
     * @param message the error message when it is not a shape
     * @return the shape
     * @throws ToolException TOOL_FAILED when pe is not a ShapeElement
     */
    private static ShapeElement requireShape(PresentationElement pe, String message) {
        if (pe instanceof ShapeElement) {
            return (ShapeElement) pe;
        }
        throw new ToolException("TOOL_FAILED", message);
    }

    // ================================================================== shapes

    /**
     * Implements cameo_list_diagram_shapes: flattens all symbols on the EDT, then filters and pages off the
     * EDT in ShapeFilter.
     *
     * @param diagramId the diagram id
     * @param options the filter and paging options
     * @return the result of {@link ShapeFilter#filter}
     * @throws ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any failure while handing the work to the EDT
     */
    static JsonObject listShapes(String diagramId, ShapeFilter.Options options) throws Exception {
        JsonArray shapes = Edt.onEdt("List Diagram Shapes", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            return DiagramSupport.collectShapes(dpe);
        });
        return ShapeFilter.filter(diagramId, shapes, options);
    }

    /**
     * cameo_get_shape_properties: every presentation property of one symbol, returned three ways: a
     * name->value map, a list with ids and class types, and well-known compartments under short keys.
     *
     * @param diagramId the diagram id
     * @param peId the symbol's presentation id
     * @return {@code diagramId}, {@code presentationId}, {@code shapeType}, {@code elementId}, the {@code
     *     properties}
     * map, the {@code propertyList}, the {@code compartments} and {@code resultCount}
     *
     * @throws ToolException NOT_FOUND for a missing diagram or symbol, UNSUPPORTED when the property manager
     *     is not
     * available
     *
     * @throws Exception any failure while handing the work to the EDT
     */
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

    /**
     * The properties of a manager. PropertyManager.getProperties() is untyped and may return null; callers
     * always get a list.
     *
     * @param pm the property manager
     * @return the properties, never null
     */
    @SuppressWarnings("unchecked")
    static List<Property> properties(PropertyManager pm) {
        List<Property> l = pm.getProperties();
        return l != null ? l : Collections.emptyList();
    }

    /**
     * Reads a property value. A getter can throw; such properties are reported without a value instead of
     * failing the call.
     *
     * @param p the property
     * @return the value, or null when the getter throws
     */
    static Object safeValue(Property p) {
        try {
            return p.getValue();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * cameo_move_shapes: reshapes each symbol to its x/y/width/height (all four required). Errors are
     * recorded per item and the loop continues, so one bad item does not roll back the others.
     *
     * @param diagramId the diagram id
     * @param shapes items with presentationId, x, y, width and height
     * @return the per-item results in the {@link #batch} envelope
     * @throws ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_delete_shapes: removes symbols only, so the model elements stay. Errors are recorded
     * per id.
     *
     * @param diagramId the diagram id
     * @param ids the presentation ids; must not be empty
     * @return the per-id results in the {@link #batch} envelope
     * @throws ToolException INVALID_PARAMS for an empty list, NOT_FOUND for a missing diagram
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_reparent_shapes: moves symbols under another symbol on the same diagram, with per-item
     * errors. {@code PresentationElement.setParent} is called by reflection; if this Cameo lacks it, the
     * whole call fails before any symbol is touched.
     *
     * @param diagramId the diagram id
     * @param reparentings items with presentationId and parentPresentationId
     * @return the per-item results in the {@link #batch} envelope
     * @throws NoSuchMethodException when this Cameo has no setParent
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
    static JsonObject reparentShapes(String diagramId, List<JsonObject> reparentings) throws Exception {
        return Edt.write("Reparent Shapes", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            List<PresentationElement> all = dpe.getPresentationElements();
            // setParent is called reflectively; if this Cameo version lacks it, getMethod fails the whole call
            // up front, before any symbol is touched.
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

    /**
     * cameo_add_diagram_paths: draws existing relationships between two symbols already on the diagram.
     * Source/target are presentation ids (from cameo_list_diagram_shapes), not model element ids.
     *
     * @param diagramId the diagram id
     * @param paths items with relationshipId, sourceShapeId and targetShapeId (presentation ids)
     * @return the per-item results in the {@link #batch} envelope
     * @throws ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * cameo_route_paths: sets a path's break points, and its end points when sourcePoint/targetPoint are
     * given. Label positions are reset afterwards unless resetLabels is false, since old label offsets
     * usually end up far from the new route.
     *
     * @param diagramId the diagram id
     * @param routes items with presentationId, breakPoints, optional sourcePoint and targetPoint, and resetLabels
     * @return the per-item results in the {@link #batch} envelope
     * @throws ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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
                    // changePathPoints also moves the end points; changePathBreakPoints keeps the current ones.
                    if (sourcePoint != null || targetPoint != null) {
                        pem.changePathPoints(path, sourcePoint, targetPoint, breakPoints);
                    } else {
                        pem.changePathBreakPoints(path, breakPoints);
                    }
                    if (resetLabels) {
                        // A failed label reset makes this route's result an error, even though the points were changed.
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

    /**
     * cameo_set_shape_properties: sets presentation properties by exact native name (as listed by
     * cameo_get_shape_properties) on a copy of the property manager, then applies the copy once.
     * Unknown names are reported per entry; the others are still applied.
     *
     * @param diagramId the diagram id
     * @param peId the symbol's presentation id
     * @param input native property name to new value; must not be empty
     * @return {@code diagramId}, {@code presentationId}, the per-name {@code properties} results, {@code
     *     resultCount}
     * and a {@code receipt} with status applied or noop
     *
     * @throws ToolException INVALID_PARAMS for an empty input, NOT_FOUND for a missing diagram or symbol,
     * UNSUPPORTED or TOOL_FAILED when the properties cannot be read or applied
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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
                    // JSON -> property value: null -> "", numbers -> int (fraction dropped), objects/arrays -> JSON text.
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
            // Apply only when something matched, so an all-unknown request is a "noop" rather than an empty write.
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

    /**
     * cameo_set_shape_compartments: like cameo_set_shape_properties, but keys are compartment names
     * ("showAttributes", "ports", ...) resolved through CompartmentAliasResolver.
     *
     * @param diagramId the diagram id
     * @param peId the symbol's presentation id
     * @param input compartment key to boolean; must not be empty
     * @return {@code diagramId}, {@code presentationId}, the per-key {@code results}, {@code resultCount} and
     *     a
     * {@code receipt} with status applied or noop
     *
     * @throws ToolException INVALID_PARAMS for an empty input, NOT_FOUND for a missing diagram or symbol,
     * UNSUPPORTED or TOOL_FAILED when the properties cannot be read or applied
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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
                // A boolean means "show": valueFor inverts it when the resolved property is a "Suppress ..." one.
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

    /**
     * Finds the property a compartment key controls. An exact normalized name match wins; otherwise the alias
     * table's native candidates are tried.
     *
     * @param byName the symbol's properties by native name
     * @param requestedKey the compartment key the agent sent
     * @return the property, or null when nothing matches
     */
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

    /**
     * Writes an edited property-manager copy back to the symbol.
     *
     * @param target the symbol
     * @param pm the edited copy
     * @param message the start of the error message
     * @throws ToolException TOOL_FAILED when Cameo rejects the change
     */
    static void applyOrThrow(PresentationElement target, PropertyManager pm, String message) {
        try {
            PresentationElementsManager.getInstance().setPresentationElementProperties(target, pm);
        } catch (Exception e) {
            throw new ToolException("TOOL_FAILED", message + ": " + safeMessage(e));
        }
    }

    // ================================================================== small builders

    /**
     * A per-item error entry.
     *
     * @param peId the presentation id the error is about
     * @param message the error message
     * @return {@code presentationId} and {@code error}
     */
    private static JsonObject error(String peId, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("presentationId", peId);
        err.addProperty("error", message);
        return err;
    }

    /**
     * Common envelope for per-item tools: the results plus how many of them carry an "error".
     *
     * @param diagramId the diagram id
     * @param results the per-item results
     * @return {@code diagramId}, {@code resultCount}, the error count and {@code results}
     */
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

    /**
     * A receipt describing one presentation change.
     *
     * @param op the operation name
     * @param diagramId the diagram id
     * @param peId the presentation id
     * @param status the outcome, such as applied or noop
     * @return the receipt object
     */
    private static JsonObject receipt(String op, String diagramId, String peId, String status) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("operation", op);
        receipt.addProperty("diagramId", diagramId);
        receipt.addProperty("presentationId", peId);
        receipt.addProperty("status", status);
        return receipt;
    }
}
