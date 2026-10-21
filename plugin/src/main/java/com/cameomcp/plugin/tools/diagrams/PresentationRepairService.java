package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.tools.diagrams.PresentationPresets.RepairDefaults;
import com.cameomcp.plugin.tools.diagrams.PresentationPresets.Selection;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.openapi.uml.PresentationElementsManager;
import com.nomagic.magicdraw.properties.Property;
import com.nomagic.magicdraw.properties.PropertyManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.magicdraw.uml.symbols.paths.PathElement;
import com.nomagic.magicdraw.uml.symbols.shapes.ShapeElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static com.cameomcp.plugin.tools.diagrams.CompartmentAliasResolver.normalizeKey;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.addValue;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.describe;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.diagramType;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.loadedDiagram;
import static com.cameomcp.plugin.tools.diagrams.DiagramSupport.safeMessage;

/** Label/compartment presets, diagram-type-aware repairs and presentation pruning. */
final class PresentationRepairService {

    /** Not instantiable; all members are static. */
    private PresentationRepairService() {
    }

    // ================================================================== presets

    /**
     * Implements cameo_set_transition_label_presentation: label parts of transition paths.
     *
     * @param diagramId the diagram id
     * @param ids presentation ids to change, or null or empty for every transition path on the diagram
     * @param showName show the transition name
     * @param showTriggers show the triggers
     * @param showGuard show the guard
     * @param showEffect show the effect
     * @param resetLabels true to reset label positions after the change
     * @return the result of {@link #preset}
     * @throws Exception as {@link #preset}
     */
    static JsonObject transitionLabels(String diagramId, List<String> ids, boolean showName, boolean showTriggers,
                                       boolean showGuard, boolean showEffect, boolean resetLabels) throws Exception {
        return preset("Configure Transition Label Presentation", "configureTransitionLabelPresentation", diagramId, ids,
                PresentationRepairService::isTransitionPath, "Presentation element is not a transition path",
                PresentationPresets.transitionLabels(showName, showTriggers, showGuard, showEffect), resetLabels);
    }

    /**
     * Implements cameo_set_item_flow_label_presentation: label parts of information-flow and item-flow paths.
     *
     * @param diagramId the diagram id
     * @param ids presentation ids to change, or null or empty for every such path on the diagram
     * @param showName show the flow name
     * @param showConveyed show the conveyed items
     * @param showItemProperty show the item property
     * @param showDirection show the direction
     * @param showStereotype show the stereotype
     * @param resetLabels true to reset label positions after the change
     * @return the result of {@link #preset}
     * @throws Exception as {@link #preset}
     */
    static JsonObject itemFlowLabels(String diagramId, List<String> ids, boolean showName, boolean showConveyed,
                                     boolean showItemProperty, boolean showDirection, boolean showStereotype,
                                     boolean resetLabels) throws Exception {
        return preset("Configure Item Flow Label Presentation", "configureItemFlowLabelPresentation", diagramId, ids,
                PresentationRepairService::isItemFlowPath, "Presentation element is not an information-flow path",
                PresentationPresets.itemFlowLabels(showName, showConveyed, showItemProperty, showDirection,
                        showStereotype), resetLabels);
    }

    /**
     * Implements cameo_set_allocation_compartment_presentation. It never resets labels, because these targets
     * are shapes.
     *
     * @param diagramId the diagram id
     * @param ids presentation ids to change, or null or empty for every shape that supports allocation
     *     compartments
     * @param showAllocatedElements show the allocated elements compartment
     * @param showElementProperties show the element properties compartment
     * @param showPorts show ports
     * @param showFullPorts show full ports
     * @param applyAllocationNaming use the SysML 1.7 allocation compartment naming
     * @return the result of {@link #preset}
     * @throws Exception as {@link #preset}
     */
    static JsonObject allocationCompartments(String diagramId, List<String> ids, boolean showAllocatedElements,
                                             boolean showElementProperties, boolean showPorts, boolean showFullPorts,
                                             boolean applyAllocationNaming) throws Exception {
        return preset("Configure Allocation Compartments", "configureAllocationCompartmentPresentation", diagramId,
                ids, PresentationRepairService::isAllocationCandidate,
                "Presentation element does not support allocation compartments",
                PresentationPresets.allocationCompartments(showAllocatedElements, showElementProperties, showPorts,
                        showFullPorts, applyAllocationNaming), false);
    }

    /**
     * Shared body of the three preset tools: select the targets, apply the selections to each one in a
     * single write session (120 s limit, since a whole diagram may be touched) and collect per-target results.
     * Targets the predicate rejects are only reported when they were named explicitly in ids.
     *
     * @param session the undo label of the write session
     * @param operation the operation name for the receipt
     * @param diagramId the diagram id
     * @param ids presentation ids, or null or empty for every symbol the predicate accepts
     * @param predicate which symbols are targets
     * @param unsupported the error message for a named id the predicate rejects
     * @param selections intent key to selection
     * @param resetLabels true to reset path label positions after the change
     * @return {@code diagramId}, {@code resultCount}, {@code updatedCount}, the per-target {@code results}, a
     * {@code receipt}, and a {@code hint} when nothing matched
     *
     * @throws com.cameomcp.mcp.ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
    private static JsonObject preset(String session, String operation, String diagramId, List<String> ids,
                                     Predicate<PresentationElement> predicate, String unsupported,
                                     Map<String, Selection> selections, boolean resetLabels) throws Exception {
        return Edt.write(session, project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            SelectionResult selection = select(dpe.getPresentationElements(), ids, predicate, unsupported);
            JsonArray results = new JsonArray();
            selection.errors.forEach(results::add);
            for (PresentationElement target : selection.targets) {
                results.add(configure(target, selections, resetLabels, true));
            }
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("resultCount", results.size());
            response.addProperty("updatedCount", countUpdated(results));
            response.add("results", results);
            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", operation);
            receipt.addProperty("diagramId", diagramId);
            receipt.addProperty("status", "applied");
            receipt.addProperty("targetCount", selection.total());
            response.add("receipt", receipt);
            if (results.size() == 0) {
                response.addProperty("hint", "No matching presentation elements on this diagram. Use "
                        + "cameo_list_diagram_shapes to check what is shown.");
            }
            return response;
        }, 120);
    }

    // ================================================================== repairs

    /**
     * Implements cameo_repair_hidden_labels: turns on the diagram type's default shape and path label
     * properties.
     *
     * @param diagramId the diagram id
     * @param ids presentation ids, or null or empty for every symbol with such properties
     * @param dryRun true to only preview the changes
     * @return the result of {@link #repairRun}
     * @throws Exception as {@link #preset}
     */
    static JsonObject repairHiddenLabels(String diagramId, List<String> ids, boolean dryRun) throws Exception {
        return Edt.write("Repair Hidden Labels", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            String type = diagramType(dpe);
            RepairDefaults defaults = PresentationPresets.repairDefaults(type);
            Map<String, Selection> selections = PresentationPresets.selectionMap(true, defaults.hiddenLabelKeys);
            SelectionResult selection = select(dpe.getPresentationElements(), ids,
                    t -> supportsAny(t, selections), "Presentation element does not support hidden-label repair");
            return repairRun(diagramId, type, defaults, "hidden-labels", "repairHiddenLabels", dryRun, false,
                    selection, selections);
        }, 120);
    }

    /**
     * cameo_repair_conveyed_item_labels: sets the diagram type's conveyed-item label properties to true
     * (inverted for "Suppress ..." properties) on paths that have any of them.
     *
     * @param diagramId the diagram id
     * @param ids presentation ids, or null or empty for every path with such properties
     * @param dryRun true to only preview the changes
     * @param resetLabels true to reset label positions after the change
     * @return the result of {@link #repairRun}
     * @throws Exception as {@link #preset}
     */
    static JsonObject repairConveyedItemLabels(String diagramId, List<String> ids, boolean dryRun,
                                               boolean resetLabels) throws Exception {
        return Edt.write("Force Conveyed Item Labels", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            String type = diagramType(dpe);
            RepairDefaults defaults = PresentationPresets.repairDefaults(type);
            Map<String, Selection> selections = PresentationPresets.selectionMap(true, defaults.conveyedItemKeys);
            SelectionResult selection = select(dpe.getPresentationElements(), ids,
                    t -> t instanceof PathElement && supportsAny(t, selections),
                    "Presentation element does not support conveyed-item labels");
            JsonObject response = repairRun(diagramId, type, defaults, "conveyed-item-labels",
                    "repairConveyedItemLabels", dryRun, resetLabels, selection, selections);
            response.addProperty("resetLabels", resetLabels);
            return response;
        }, 120);
    }

    /**
     * Implements cameo_normalize_compartment_presets: turns on the diagram type's default compartments on
     * shapes, skipping comments and notes.
     *
     * @param diagramId the diagram id
     * @param ids presentation ids, or null or empty for every shape with such compartments
     * @param dryRun true to only preview the changes
     * @return the result of {@link #repairRun}
     * @throws Exception as {@link #preset}
     */
    static JsonObject normalizeCompartmentPresets(String diagramId, List<String> ids, boolean dryRun)
            throws Exception {
        return Edt.write("Normalize Compartment Presets", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            String type = diagramType(dpe);
            RepairDefaults defaults = PresentationPresets.repairDefaults(type);
            Map<String, Selection> selections = PresentationPresets.selectionMap(true, defaults.compartmentKeys);
            SelectionResult selection = select(dpe.getPresentationElements(), ids,
                    t -> t instanceof ShapeElement && !isCommentLike(t) && supportsAny(t, selections),
                    "Presentation element does not support compartment normalization");
            return repairRun(diagramId, type, defaults, "compartment-presets", "normalizeCompartmentPresets", dryRun,
                    false, selection, selections);
        }, 120);
    }

    /**
     * Common body of the repair tools: configures (or with dryRun, only previews) each target and wraps the
     * results with the diagram type's repair defaults, so callers can see which keys were used.
     *
     * @param diagramId the diagram id
     * @param type the native diagram type
     * @param defaults the repair defaults for that type
     * @param mode the repair mode name, echoed per entry
     * @param operation the operation name for the receipts
     * @param dryRun true to only preview
     * @param resetLabels true to reset path labels after a change
     * @param selection the targets and selection errors
     * @param selections intent key to selection
     * @return {@code diagramId}, {@code diagramType}, {@code repairMode}, {@code dryRun}, {@code
     *     repairDefaults},
     * {@code resultCount}, {@code updatedCount}, {@code results} and a batch {@code receipt}
     */
    private static JsonObject repairRun(String diagramId, String type, RepairDefaults defaults, String mode,
                                        String operation, boolean dryRun, boolean resetLabels,
                                        SelectionResult selection, Map<String, Selection> selections) {
        JsonArray results = new JsonArray();
        selection.errors.forEach(results::add);
        for (PresentationElement target : selection.targets) {
            JsonObject entry = configure(target, selections, resetLabels, !dryRun);
            entry.addProperty("repairMode", mode);
            entry.add("receipt", repairReceipt(operation, diagramId, type, target.getID(), !dryRun, resetLabels, entry));
            results.add(entry);
        }
        JsonObject response = new JsonObject();
        response.addProperty("diagramId", diagramId);
        response.addProperty("diagramType", type);
        response.addProperty("repairMode", mode);
        response.addProperty("dryRun", dryRun);
        response.add("repairDefaults", defaults.toJson());
        response.addProperty("resultCount", results.size());
        response.addProperty("updatedCount", countUpdated(results));
        response.add("results", results);
        response.add("receipt", batchReceipt(operation, diagramId, type, dryRun, selection.total(),
                countUpdated(results), results.size(), countErrors(results)));
        return response;
    }

    /**
     * cameo_repair_label_positions: resets label positions of paths. With onlyOverlapping, only paths whose
     * bounds (grown by the padding) intersect another selected path are touched.
     *
     * @param diagramId the diagram id
     * @param ids path presentation ids, or null or empty for every path
     * @param dryRun true to only preview
     * @param onlyOverlapping true to touch only paths that overlap another selected path
     * @param overlapPadding pixels to grow each path's bounds by for the overlap test; negative counts as 0
     * @return the per-path results with a batch receipt
     * @throws Exception as {@link #preset}
     */
    static JsonObject repairLabelPositions(String diagramId, List<String> ids, boolean dryRun,
                                           boolean onlyOverlapping, int overlapPadding) throws Exception {
        int padding = Math.max(0, overlapPadding);
        return Edt.write("Repair Label Positions", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            String type = diagramType(dpe);
            SelectionResult selection = select(dpe.getPresentationElements(), ids, pe -> pe instanceof PathElement,
                    "Presentation element is not a path");
            List<PresentationElement> targets = selection.targets;
            if (onlyOverlapping) {
                targets = overlappingPaths(targets, padding);
            }
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            selection.errors.forEach(results::add);
            for (PresentationElement target : targets) {
                JsonObject entry = new JsonObject();
                entry.addProperty("presentationId", target.getID());
                Element el = target.getElement();
                entry.addProperty("elementType", el != null ? el.getHumanType() : "");
                entry.addProperty("overlapCandidate", true);
                entry.addProperty("requestedResetLabels", true);
                entry.addProperty("repairMode", "label-positions");
                if (dryRun) {
                    setStatus(entry, false, true, "preview", false);
                } else {
                    try {
                        pem.resetLabelPositions((PathElement) target);
                        setStatus(entry, true, true, "applied", true);
                    } catch (Exception e) {
                        setStatus(entry, false, false, "error", false);
                        entry.addProperty("error", "Failed to reset label positions: " + safeMessage(e));
                    }
                }
                JsonObject receipt = new JsonObject();
                receipt.addProperty("operation", "repairLabelPositions");
                receipt.addProperty("diagramId", diagramId);
                receipt.addProperty("diagramType", type);
                receipt.addProperty("presentationId", target.getID());
                receipt.addProperty("onlyOverlapping", onlyOverlapping);
                receipt.addProperty("overlapPadding", padding);
                receipt.addProperty("status", entry.get("status").getAsString());
                receipt.addProperty("updated", entry.get("updated").getAsBoolean());
                entry.add("receipt", receipt);
                results.add(entry);
            }
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("diagramType", type);
            response.addProperty("repairMode", "label-positions");
            response.addProperty("dryRun", dryRun);
            response.addProperty("onlyOverlapping", onlyOverlapping);
            response.addProperty("overlapPadding", padding);
            response.addProperty("resultCount", results.size());
            response.addProperty("updatedCount", countUpdated(results));
            response.add("results", results);
            response.add("receipt", batchReceipt("repairLabelPositions", diagramId, type, dryRun,
                    targets.size() + selection.errors.size(), countUpdated(results), results.size(),
                    countErrors(results)));
            return response;
        }, 120);
    }

    /**
     * Sets the status fields of a label-position result entry.
     *
     * @param entry the entry to change
     * @param applied whether the change was applied
     * @param updated whether anything changed
     * @param status the status text
     * @param reset whether labels were reset (written as both resetLabels and labelsReset)
     */
    private static void setStatus(JsonObject entry, boolean applied, boolean updated, String status, boolean reset) {
        entry.addProperty("applied", applied);
        entry.addProperty("updated", updated);
        entry.addProperty("status", status);
        entry.addProperty("resetLabels", reset);
        entry.addProperty("labelsReset", reset);
    }

    /**
     * Keeps the paths whose padded bounds touch another path's bounds, by a pairwise check (O(n squared)).
     * Paths without bounds are dropped.
     *
     * @param targets the candidate paths, may be null
     * @param padding pixels to grow each bounds by
     * @return the overlapping paths; the input itself when it has fewer than two paths
     */
    private static List<PresentationElement> overlappingPaths(List<PresentationElement> targets, int padding) {
        if (targets == null || targets.size() < 2) {
            return targets == null ? Collections.emptyList() : targets;
        }
        Map<String, Rectangle> boundsById = new LinkedHashMap<>();
        for (PresentationElement t : targets) {
            try {
                Rectangle b = t.getBounds();
                if (b != null) {
                    boundsById.put(t.getID(), new Rectangle(b));
                }
            } catch (RuntimeException ignored) {
                // no bounds
            }
        }
        if (boundsById.size() < 2) {
            return Collections.emptyList();
        }
        Set<String> selected = new LinkedHashSet<>();
        List<Map.Entry<String, Rectangle>> entries = new ArrayList<>(boundsById.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Rectangle left = new Rectangle(entries.get(i).getValue());
            left.grow(padding, padding);
            for (int j = i + 1; j < entries.size(); j++) {
                Rectangle right = entries.get(j).getValue();
                if (left.intersects(right) || right.intersects(entries.get(i).getValue())) {
                    selected.add(entries.get(i).getKey());
                    selected.add(entries.get(j).getKey());
                }
            }
        }
        List<PresentationElement> out = new ArrayList<>();
        for (PresentationElement t : targets) {
            if (selected.contains(t.getID())) {
                out.add(t);
            }
        }
        return out;
    }

    // ================================================================== pruning

    /**
     * cameo_prune_diagram_presentations: deletes symbols by rule: not in keep_element_ids, or matching a
     * drop element/shape type. The exclude lists and the diagram's own frame always survive. A symbol whose
     * parent is also being pruned is skipped, since deleting the parent removes it.
     *
     * @param diagramId the diagram id
     * @param keepElementIds element ids whose symbols stay, or null to keep by type rules only
     * @param dropElementTypes element types whose symbols are removed, or null
     * @param dropShapeTypes presentation class names whose symbols are removed, or null
     * @param excludeElementIds element ids that are never pruned, or null
     * @param excludePresentationIds presentation ids that are never pruned, or null
     * @param dryRun true to only list what would be removed
     * @return the removed (or to-be-removed) symbols with counts and a receipt
     * @throws com.cameomcp.mcp.ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
    static JsonObject prunePresentations(String diagramId, List<String> keepElementIds, List<String> dropElementTypes,
                                         List<String> dropShapeTypes, List<String> excludeElementIds,
                                         List<String> excludePresentationIds, boolean dryRun) throws Exception {
        Set<String> keep = set(keepElementIds);
        List<String> dropTypes = dropElementTypes == null ? Collections.emptyList() : dropElementTypes;
        List<String> dropShapes = dropShapeTypes == null ? Collections.emptyList() : dropShapeTypes;
        Set<String> exclElements = set(excludeElementIds);
        Set<String> exclPresentations = set(excludePresentationIds);
        if (keep.isEmpty() && dropTypes.isEmpty() && dropShapes.isEmpty() && exclElements.isEmpty()
                && exclPresentations.isEmpty()) {
            throw ToolException.invalid("Provide at least one prune rule: keep_element_ids, drop_element_types, "
                    + "drop_shape_types, exclude_element_ids or exclude_presentation_ids.");
        }
        return Edt.write("Prune Diagram Presentations", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            List<PresentationElement> flat = new ArrayList<>();
            Map<String, String> parentById = new LinkedHashMap<>();
            DiagramSupport.collect(dpe.getPresentationElements(), flat, parentById, null);

            LinkedHashSet<String> matched = new LinkedHashSet<>();
            for (PresentationElement pe : flat) {
                if (shouldPrune(pe, keep, dropTypes, dropShapes, exclElements, exclPresentations)) {
                    matched.add(pe.getID());
                }
            }
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            int deleted = 0;
            for (PresentationElement pe : flat) {
                String id = pe.getID();
                if (!matched.contains(id)) {
                    continue;
                }
                String parentId = parentById.get(id);
                if (parentId != null && matched.contains(parentId)) {
                    continue; // removed with its parent
                }
                JsonObject entry = describe(pe, parentId);
                if (deleteOrPreview(pem, pe, entry, dryRun)) {
                    deleted++;
                }
                results.add(entry);
            }
            String type = diagramType(dpe);
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("diagramType", type);
            response.addProperty("dryRun", dryRun);
            response.addProperty("matchedCount", matched.size());
            response.addProperty("targetCount", results.size());
            response.addProperty("deletedCount", deleted);
            response.add("results", results);
            response.add("receipt", batchReceipt("prunePresentations", diagramId, type, dryRun, matched.size(),
                    deleted, results.size(), countErrors(results)));
            return response;
        }, 120);
    }

    /**
     * cameo_prune_path_decorations: deletes child symbols of paths whose class name matches one of the
     * drop types (default "RoleView", the association-end role labels).
     *
     * @param diagramId the diagram id
     * @param ids path presentation ids, or null or empty for every path
     * @param dropChildShapeTypes child class names to remove; null or empty means "RoleView"
     * @param dryRun true to only list what would be removed
     * @return the removed (or to-be-removed) child symbols with counts and a receipt
     * @throws com.cameomcp.mcp.ToolException NOT_FOUND for a missing diagram, or a project error
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
    static JsonObject prunePathDecorations(String diagramId, List<String> ids, List<String> dropChildShapeTypes,
                                           boolean dryRun) throws Exception {
        List<String> dropTypes = dropChildShapeTypes == null || dropChildShapeTypes.isEmpty()
                ? Collections.singletonList("RoleView") : new ArrayList<>(dropChildShapeTypes);
        return Edt.write("Prune Path Decorations", project -> {
            DiagramPresentationElement dpe = loadedDiagram(project, diagramId);
            String type = diagramType(dpe);
            SelectionResult selection = select(dpe.getPresentationElements(), ids, pe -> pe instanceof PathElement,
                    "Presentation element is not a path");
            PresentationElementsManager pem = PresentationElementsManager.getInstance();
            JsonArray results = new JsonArray();
            selection.errors.forEach(results::add);
            int deletedTotal = 0;
            for (PresentationElement target : selection.targets) {
                JsonObject entry = describe(target, null);
                entry.addProperty("repairMode", "path-decorations");
                entry.add("dropChildShapeTypes", com.cameomcp.plugin.cameo.Gs.strings(dropTypes));

                List<PresentationElement> descendants = new ArrayList<>();
                Map<String, String> parentById = new LinkedHashMap<>();
                DiagramSupport.collect(target.getPresentationElements(), descendants, parentById, target.getID());
                LinkedHashSet<String> matched = new LinkedHashSet<>();
                for (PresentationElement d : descendants) {
                    if (matchesShapeType(d, dropTypes)) {
                        matched.add(d.getID());
                    }
                }
                JsonArray decorations = new JsonArray();
                int deleted = 0;
                int errors = 0;
                for (PresentationElement d : descendants) {
                    String id = d.getID();
                    if (!matched.contains(id)) {
                        continue;
                    }
                    String parentId = parentById.get(id);
                    if (parentId != null && matched.contains(parentId)) {
                        continue;
                    }
                    // delete the decoration's own children first, deepest last-collected first
                    List<PresentationElement> subtree = new ArrayList<>();
                    Map<String, String> subParents = new LinkedHashMap<>();
                    DiagramSupport.collect(d.getPresentationElements(), subtree, subParents, d.getID());
                    Collections.reverse(subtree);
                    List<PresentationElement> order = new ArrayList<>(subtree);
                    order.add(d);
                    Set<String> seen = new LinkedHashSet<>();
                    for (PresentationElement c : order) {
                        if (!seen.add(c.getID())) {
                            continue;
                        }
                        JsonObject decoration = describe(c, c == d ? parentId : subParents.get(c.getID()));
                        boolean ok = deleteOrPreview(pem, c, decoration, dryRun);
                        if (ok) {
                            deleted++;
                            deletedTotal++;
                        } else if (decoration.has("error")) {
                            errors++;
                        }
                        decorations.add(decoration);
                    }
                }
                // "updated" in a dry run means "would change".
                boolean updated = dryRun ? decorations.size() > 0 : deleted > 0;
                String status;
                if (decorations.size() == 0) {
                    status = "noop";
                } else if (dryRun) {
                    status = "preview";
                } else if (errors > 0 && deleted > 0) {
                    status = "partial";
                } else if (errors > 0) {
                    status = "error";
                } else {
                    status = "applied";
                }
                entry.addProperty("matchedDecorationCount", matched.size());
                entry.addProperty("targetDecorationCount", decorations.size());
                entry.addProperty("deletedCount", deleted);
                entry.addProperty("updated", updated);
                entry.addProperty("applied", !dryRun && updated);
                entry.addProperty("status", status);
                if (errors > 0) {
                    entry.addProperty("error", errors + " path decoration(s) failed to prune");
                }
                entry.add("decorations", decorations);
                entry.add("receipt", repairReceipt("prunePathDecorations", diagramId, type, target.getID(),
                        !dryRun && updated, false, entry));
                results.add(entry);
            }
            JsonObject response = new JsonObject();
            response.addProperty("diagramId", diagramId);
            response.addProperty("diagramType", type);
            response.addProperty("dryRun", dryRun);
            response.add("dropChildShapeTypes", com.cameomcp.plugin.cameo.Gs.strings(dropTypes));
            response.addProperty("resultCount", results.size());
            response.addProperty("updatedCount", countUpdated(results));
            response.addProperty("deletedDecorationCount", deletedTotal);
            response.add("results", results);
            response.add("receipt", batchReceipt("prunePathDecorations", diagramId, type, dryRun, selection.total(),
                    countUpdated(results), results.size(), countErrors(results)));
            return response;
        }, 120);
    }

    /** Deletes (or previews) one presentation; returns true when deleted. */
    private static boolean deleteOrPreview(PresentationElementsManager pem, PresentationElement pe, JsonObject entry,
                                           boolean dryRun) {
        try {
            if (dryRun) {
                entry.addProperty("status", "preview");
                entry.addProperty("applied", false);
                entry.addProperty("deleted", false);
                return false;
            }
            pem.deletePresentationElement(pe);
            entry.addProperty("status", "deleted");
            entry.addProperty("applied", true);
            entry.addProperty("deleted", true);
            return true;
        } catch (Exception e) {
            entry.addProperty("status", "error");
            entry.addProperty("applied", false);
            entry.addProperty("deleted", false);
            entry.addProperty("error", safeMessage(e));
            return false;
        }
    }

    /**
     * Decides whether one symbol is pruned. The diagram frame and excluded ids always stay.
     *
     * @param pe the symbol
     * @param keep element ids to keep; empty means no keep rule
     * @param dropTypes element types to drop
     * @param dropShapes presentation class names to drop
     * @param exclElements element ids never pruned
     * @param exclPresentations presentation ids never pruned
     * @return true when the symbol should be removed
     */
    private static boolean shouldPrune(PresentationElement pe, Set<String> keep, List<String> dropTypes,
                                       List<String> dropShapes, Set<String> exclElements,
                                       Set<String> exclPresentations) {
        String id = pe.getID();
        if (id == null || exclPresentations.contains(id)) {
            return false;
        }
        Element element = null;
        try {
            element = pe.getElement();
        } catch (RuntimeException ignored) {
            // no backing element
        }
        if (element instanceof Diagram) {
            return false;
        }
        String elementId = element != null ? element.getID() : null;
        if (elementId != null && exclElements.contains(elementId)) {
            return false;
        }
        // keep_element_ids prunes everything else that has a model element; symbols without one are kept by this rule.
        boolean matched = !keep.isEmpty() && elementId != null && !keep.contains(elementId);
        if (!dropTypes.isEmpty()) {
            for (String t : dropTypes) {
                if (matchesToken(pe, t)) {
                    matched = true;
                    break;
                }
            }
        }
        if (!dropShapes.isEmpty() && matchesShapeType(pe, dropShapes)) {
            matched = true;
        }
        return matched;
    }

    // ================================================================== configure core

    /**
     * Core of the preset and repair tools: on a property-manager copy, set every property each selection
     * matches, then apply the copy (and optionally reset path labels) when apply is true. Status is
     * "preview" without apply, "partial" when only some requests or steps worked.
     *
     * @param target the symbol
     * @param selections intent key to selection
     * @param resetLabels true to reset path label positions after applying
     * @param apply false to preview only
     * @return the entry with the updates, supported and unsupported keys, status and any error
     */
    private static JsonObject configure(PresentationElement target, Map<String, Selection> selections,
                                        boolean resetLabels, boolean apply) {
        PropertyManager pm = DiagramSupport.propertyManagerCopy(target);
        List<Property> props = DiagramService.properties(pm);
        JsonArray updates = new JsonArray();
        JsonArray supported = new JsonArray();
        JsonArray unsupported = new JsonArray();
        JsonArray errors = new JsonArray();

        for (Map.Entry<String, Selection> s : selections.entrySet()) {
            Set<String> seen = new LinkedHashSet<>();
            for (Property p : props) {
                if (!s.getValue().matches(p.getName())) {
                    continue;
                }
                // Several matching names can normalize alike; set each normalized name only once per request.
                if (!seen.add(normalizeKey(p.getName()))) {
                    continue;
                }
                p.setValue(s.getValue().valueFor(p.getName()));
                JsonObject u = new JsonObject();
                u.addProperty("request", s.getKey());
                u.addProperty("property", p.getName());
                addValue(u, "value", DiagramService.safeValue(p));
                u.addProperty("set", true);
                updates.add(u);
            }
            if (seen.isEmpty()) {
                JsonObject missing = new JsonObject();
                missing.addProperty("request", s.getKey());
                missing.addProperty("error", "No matching presentation property found");
                updates.add(missing);
                unsupported.add(s.getKey());
            } else {
                supported.add(s.getKey());
            }
        }

        boolean requestedReset = resetLabels && target instanceof PathElement;
        boolean propsApplied = false;
        boolean labelsReset = false;
        PresentationElementsManager pem = PresentationElementsManager.getInstance();
        if (apply && supported.size() > 0) {
            try {
                pem.setPresentationElementProperties(target, pm);
                propsApplied = true;
            } catch (Exception e) {
                errors.add("Failed to apply presentation properties: " + safeMessage(e));
            }
        }
        if (apply && requestedReset) {
            try {
                pem.resetLabelPositions((PathElement) target);
                labelsReset = true;
            } catch (Exception e) {
                errors.add("Failed to reset label positions: " + safeMessage(e));
            }
        }
        boolean hasUnsupported = unsupported.size() > 0;
        boolean updated = !apply ? (supported.size() > 0 || requestedReset) : (propsApplied || labelsReset);
        String status;
        if (!apply) {
            status = "preview";
        } else if (errors.size() == 0 && hasUnsupported) {
            status = updated ? "partial" : "error";
        } else if (errors.size() == 0) {
            status = "applied";
        } else {
            status = updated ? "partial" : "error";
        }

        JsonObject r = new JsonObject();
        r.addProperty("presentationId", target.getID());
        Element el = target.getElement();
        if (el != null) {
            r.addProperty("elementId", el.getID());
            r.addProperty("elementType", el.getHumanType());
        }
        r.addProperty("shapeType", target.getClass().getSimpleName());
        r.addProperty("applied", apply && updated);
        r.addProperty("fullyApplied", apply && errors.size() == 0 && !hasUnsupported);
        r.addProperty("status", status);
        r.addProperty("updated", updated);
        r.addProperty("requestedResetLabels", requestedReset);
        r.addProperty("resetLabels", labelsReset);
        r.addProperty("labelsReset", labelsReset);
        r.add("supportedRequests", supported);
        r.add("unsupportedRequests", unsupported);
        r.add("updates", updates);
        if (errors.size() > 0) {
            r.add("errors", errors);
        }
        r.addProperty("resultCount", updates.size());
        return r;
    }

    // ================================================================== selection

    /**
     * Targets to configure, plus error entries for explicitly requested ids that were missing or unsupported.
     */
    static final class SelectionResult {
        /** Symbols to configure. */
        final List<PresentationElement> targets;
        /** Error entries for requested ids that were missing or rejected. */
        final List<JsonObject> errors;

        /**
         * Creates a result.
         *
         * @param targets symbols to configure
         * @param errors error entries
         */
        SelectionResult(List<PresentationElement> targets, List<JsonObject> errors) {
            this.targets = targets;
            this.errors = errors;
        }

        /**
         * Number of targets plus errors, the target count reported in receipts.
         *
         * @return the total
         */
        int total() {
            return targets.size() + errors.size();
        }
    }

    /**
     * Chooses the targets. With no ids, every symbol on the diagram that passes the predicate; with ids,
     * exactly those, or an error entry for each id that is missing or rejected.
     *
     * @param roots the diagram's top-level symbols
     * @param ids presentation ids, or null or empty for all
     * @param predicate which symbols are targets
     * @param unsupported the error message for a rejected id
     * @return the targets and errors
     */
    private static SelectionResult select(List<PresentationElement> roots, List<String> ids,
                                          Predicate<PresentationElement> predicate, String unsupported) {
        List<PresentationElement> flat = DiagramSupport.flatten(roots);
        List<PresentationElement> selected = new ArrayList<>();
        List<JsonObject> errors = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            for (PresentationElement pe : flat) {
                if (safeTest(predicate, pe)) {
                    selected.add(pe);
                }
            }
            return new SelectionResult(selected, errors);
        }
        Map<String, PresentationElement> byId = new LinkedHashMap<>();
        for (PresentationElement pe : flat) {
            byId.put(pe.getID(), pe);
        }
        for (String id : ids) {
            PresentationElement pe = byId.get(id);
            if (pe == null) {
                errors.add(selectionError(id, "Presentation element not found"));
            } else if (!safeTest(predicate, pe)) {
                errors.add(selectionError(id, unsupported));
            } else {
                selected.add(pe);
            }
        }
        return new SelectionResult(selected, errors);
    }

    /**
     * Tests a symbol. A predicate that throws, for example on a symbol in an odd state, just means "not a
     * target".
     *
     * @param p the predicate
     * @param pe the symbol
     * @return the predicate's answer, or false when it throws
     */
    private static boolean safeTest(Predicate<PresentationElement> p, PresentationElement pe) {
        try {
            return p.test(pe);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * An error entry for a requested presentation id.
     *
     * @param id the presentation id
     * @param message the error message
     * @return the entry, with status error and applied and updated false
     */
    private static JsonObject selectionError(String id, String message) {
        JsonObject e = new JsonObject();
        e.addProperty("presentationId", id);
        e.addProperty("status", "error");
        e.addProperty("applied", false);
        e.addProperty("updated", false);
        e.addProperty("error", message);
        return e;
    }

    /**
     * Whether a symbol is a transition path, by class and type-name heuristics (see matchesToken).
     *
     * @param pe the symbol
     * @return true for a path whose names contain "transition"
     */
    private static boolean isTransitionPath(PresentationElement pe) {
        return pe instanceof PathElement && matchesToken(pe, "transition");
    }

    /**
     * Whether a symbol is an information-flow or item-flow path.
     *
     * @param pe the symbol
     * @return true for a path whose names contain "informationflow" or "itemflow"
     */
    private static boolean isItemFlowPath(PresentationElement pe) {
        return pe instanceof PathElement && (matchesToken(pe, "informationflow") || matchesToken(pe, "itemflow"));
    }

    /**
     * Whether a symbol is a shape, other than a comment, whose property manager exposes allocation-related
     * properties.
     *
     * @param pe the symbol
     * @return true for such a shape; false when the properties cannot be read
     */
    private static boolean isAllocationCandidate(PresentationElement pe) {
        if (!(pe instanceof ShapeElement) || isCommentLike(pe)) {
            return false;
        }
        try {
            for (Property p : DiagramService.properties(DiagramSupport.propertyManager(pe))) {
                if (PresentationPresets.isAllocationProperty(p.getName())) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // cannot inspect
        }
        return false;
    }

    /**
     * Whether a symbol shows a comment or note.
     *
     * @param pe the symbol, may be null
     * @return true for comment and note symbols
     */
    private static boolean isCommentLike(PresentationElement pe) {
        return pe != null && (matchesToken(pe, "comment") || matchesToken(pe, "note") || pe.getElement() instanceof Comment);
    }

    /**
     * Whether any property of the target matches any of the selections.
     *
     * @param target the symbol
     * @param selections intent key to selection
     * @return true on a match; false when the properties cannot be read
     */
    private static boolean supportsAny(PresentationElement target, Map<String, Selection> selections) {
        try {
            for (Property p : DiagramService.properties(DiagramSupport.propertyManager(target))) {
                for (Selection s : selections.values()) {
                    if (s.matches(p.getName())) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // cannot inspect
        }
        return false;
    }

    /** Token matched against the presentation class, the element's human type and its metaclass. */
    private static boolean matchesToken(PresentationElement pe, String token) {
        if (pe == null) {
            return false;
        }
        String t = normalizeKey(token);
        if (normalizeKey(pe.getClass().getSimpleName()).contains(t)) {
            return true;
        }
        Element el = pe.getElement();
        if (el == null) {
            return false;
        }
        if (normalizeKey(el.getHumanType()).contains(t)) {
            return true;
        }
        Object classType = el.getClassType();
        return normalizeKey(classType != null ? classType.toString() : "").contains(t);
    }

    /**
     * Substring match of the tokens against the presentation class simple name, for example "RoleView".
     *
     * @param pe the symbol, may be null
     * @param tokens the tokens, may be null
     * @return true when a normalized token occurs in the normalized class name
     */
    private static boolean matchesShapeType(PresentationElement pe, List<String> tokens) {
        if (pe == null || tokens == null) {
            return false;
        }
        String shapeType = normalizeKey(pe.getClass().getSimpleName());
        for (String t : tokens) {
            if (shapeType.contains(normalizeKey(t))) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== receipts

    /**
     * A receipt for a batch repair.
     *
     * @param op the operation name
     * @param diagramId the diagram id
     * @param type the native diagram type
     * @param dryRun whether this was a preview
     * @param targets number of targets
     * @param updated number of results that changed something
     * @param results number of results
     * @param errors number of error results
     * @return the receipt
     */
    private static JsonObject batchReceipt(String op, String diagramId, String type, boolean dryRun, int targets,
                                           int updated, int results, int errors) {
        JsonObject r = new JsonObject();
        r.addProperty("operation", op);
        r.addProperty("diagramId", diagramId);
        r.addProperty("diagramType", type);
        r.addProperty("status", dryRun ? "preview" : "applied");
        r.addProperty("dryRun", dryRun);
        r.addProperty("targetCount", targets);
        r.addProperty("updatedCount", updated);
        r.addProperty("resultCount", results);
        r.addProperty("errorCount", errors);
        return r;
    }

    /**
     * A receipt for one repaired symbol.
     *
     * @param op the operation name
     * @param diagramId the diagram id
     * @param type the native diagram type
     * @param peId the presentation id
     * @param applied whether the change was applied
     * @param resetLabels whether labels were reset
     * @param entry the result entry, for its status and update count
     * @return the receipt
     */
    private static JsonObject repairReceipt(String op, String diagramId, String type, String peId, boolean applied,
                                            boolean resetLabels, JsonObject entry) {
        JsonObject r = new JsonObject();
        r.addProperty("operation", op);
        r.addProperty("diagramId", diagramId);
        r.addProperty("diagramType", type);
        r.addProperty("presentationId", peId);
        r.addProperty("status", entry.has("status") ? entry.get("status").getAsString() : (applied ? "applied" : "preview"));
        r.addProperty("applied", entry.has("applied") ? entry.get("applied").getAsBoolean() : applied);
        r.addProperty("resetLabels", entry.has("resetLabels") ? entry.get("resetLabels").getAsBoolean() : resetLabels);
        r.addProperty("updated", entry.has("updated") && entry.get("updated").getAsBoolean());
        r.addProperty("updateCount", entry.has("updates") ? entry.getAsJsonArray("updates").size() : 0);
        return r;
    }

    /**
     * Counts results that changed something: "updated" true or a non-empty "updates" list, ignoring error
     * entries.
     *
     * @param results the result entries
     * @return the count
     */
    static int countUpdated(JsonArray results) {
        int n = 0;
        for (JsonElement e : results) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (o.has("error")) {
                continue;
            }
            if (o.has("updated") && o.get("updated").isJsonPrimitive() && o.get("updated").getAsBoolean()) {
                n++;
            } else if (o.has("updates") && o.get("updates").isJsonArray() && o.getAsJsonArray("updates").size() > 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * Counts results that carry an "error".
     *
     * @param results the result entries
     * @return the count
     */
    static int countErrors(JsonArray results) {
        int n = 0;
        for (JsonElement e : results) {
            if (e.isJsonObject() && e.getAsJsonObject().has("error")) {
                n++;
            }
        }
        return n;
    }

    /**
     * A set copy of a list.
     *
     * @param l the list, may be null
     * @return a new ordered set, or an empty set for null
     */
    private static Set<String> set(List<String> l) {
        return l == null ? Collections.emptySet() : new LinkedHashSet<>(l);
    }
}
