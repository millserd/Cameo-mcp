package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.OptionalModules;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** cameo_create_snapshot / list / get / delete / diff. */
public final class Snapshots {

    private Snapshots() {
    }

    public static JsonObject create(JsonObject p) throws Exception {
        String targetType = Gs.reqStr(p, "target_type").trim();
        String targetId = ArtifactSupport.optStr(p, "target_id");
        String name = Gs.str(p, "name");
        boolean includeRaw = Gs.bool(p, "include_raw", false);
        boolean includePresentations = Gs.bool(p, "include_presentations", true);
        boolean includeProperties = Gs.bool(p, "include_properties", true);
        String canonical = canonicalTarget(targetType);
        if (!"project".equals(canonical) && !"ui".equals(canonical) && targetId == null) {
            throw ToolException.invalid("target_id is required for target_type '" + canonical + "'.");
        }
        JsonObject relationMapSettings = null;
        if ("relationMap".equals(canonical)) {
            relationMapSettings = relationMapSettings(targetId);
        }
        JsonObject rmSettings = relationMapSettings;
        return Edt.read(project -> {
            JsonObject snapshot = new JsonObject();
            if (name != null) {
                snapshot.addProperty("name", name);
            }
            JsonObject target = new JsonObject();
            target.addProperty("targetType", targetType);
            if (targetId != null) {
                target.addProperty("targetId", targetId);
            }
            snapshot.add("target", target);
            snapshot.add("payload", capture(project, canonical, targetId, includeRaw, includePresentations,
                    includeProperties, rmSettings));
            JsonObject summary = new JsonObject();
            summary.addProperty("includeRaw", includeRaw);
            summary.addProperty("includePresentations", includePresentations);
            summary.addProperty("includeProperties", includeProperties);
            snapshot.add("summary", summary);
            return SnapshotStore.create(snapshot);
        });
    }

    static String canonicalTarget(String targetType) {
        for (String t : new String[]{"project", "element", "diagram", "relationMap", "ui"}) {
            if (t.equalsIgnoreCase(targetType) || t.equalsIgnoreCase(targetType.replace("_", "").replace("-", ""))) {
                return t;
            }
        }
        throw ToolException.invalid("Unsupported snapshot target_type: " + targetType
                + ". Use project, element, diagram, relationMap or ui.");
    }

    private static JsonObject relationMapSettings(String relationMapId) throws Exception {
        try {
            return OptionalModules.call(ArtifactSupport.RELATION_MAP_SERVICE, "snapshotSettings",
                    Gs.obj("relation_map_id", relationMapId));
        } catch (ToolException e) {
            if ("UNSUPPORTED".equals(e.code())) {
                JsonObject o = new JsonObject();
                o.addProperty("available", false);
                o.addProperty("reason", e.getMessage());
                return o;
            }
            throw e;
        }
    }

    private static JsonObject capture(Project project, String targetType, String targetId, boolean includeRaw,
                                      boolean includePresentations, boolean includeProperties,
                                      JsonObject relationMapSettings) {
        switch (targetType) {
            case "project": {
                JsonObject json = new JsonObject();
                json.addProperty("name", project.getName());
                Element primary = project.getPrimaryModel();
                if (primary != null) {
                    json.add("primaryModel", ElementJson.toJsonCompact(primary));
                }
                json.addProperty("diagramCount", project.getDiagrams() != null ? project.getDiagrams().size() : 0);
                return json;
            }
            case "element":
                return ElementJson.toJson(ArtifactSupport.element(project, targetId, "Element"));
            case "diagram":
            case "relationMap": {
                DiagramPresentationElement dpe = ArtifactSupport.diagram(project, targetId, "Diagram");
                if ("relationMap".equals(targetType) && !ArtifactSupport.isRelationMap(dpe)) {
                    throw ToolException.invalid("Diagram '" + targetId + "' is not a Relation Map; use "
                            + "target_type='diagram'.");
                }
                JsonObject json = new JsonObject();
                json.add("diagram", PresentationJson.diagramSummary(dpe));
                if (relationMapSettings != null) {
                    json.add("relationMapSettings", relationMapSettings);
                }
                if (includeProperties) {
                    try {
                        json.add("diagramPresentationProperties",
                                PropertyJson.serializeManager(PresentationJson.propertyManager(dpe), includeRaw, true));
                    } catch (Exception | LinkageError e) {
                        JsonArray warnings = new JsonArray();
                        warnings.add("Could not read diagram property manager: " + e.getMessage());
                        json.add("warnings", warnings);
                    }
                }
                if (includePresentations) {
                    dpe.ensureLoaded();
                    Map<String, String> parentById = new LinkedHashMap<>();
                    List<PresentationElement> presentations = PresentationJson.flatten(dpe, parentById);
                    json.addProperty("presentationCount", presentations.size());
                    json.add("presentationCountsByType", PresentationJson.countByType(presentations));
                    json.add("presentations", PresentationJson.summarize(presentations, parentById));
                }
                return json;
            }
            case "ui": {
                JsonObject json = new JsonObject();
                json.addProperty("note", "Use cameo_get_ui_state for live UI details; this snapshot captured "
                        + "project identity only.");
                json.addProperty("projectName", project.getName());
                return json;
            }
            default:
                throw ToolException.invalid("Unsupported snapshot target_type: " + targetType);
        }
    }

    public static JsonObject list() {
        JsonArray snapshots = new JsonArray();
        for (JsonObject s : SnapshotStore.list()) {
            snapshots.add(s);
        }
        JsonObject response = new JsonObject();
        response.addProperty("count", snapshots.size());
        response.add("snapshots", snapshots);
        return response;
    }

    public static JsonObject get(JsonObject p) {
        String id = Gs.reqStr(p, "snapshot_id").trim();
        JsonObject s = SnapshotStore.get(id);
        if (s == null) {
            throw new ToolException("NOT_FOUND", "Snapshot '" + id + "' not found. Snapshots are kept in memory "
                    + "until Cameo restarts; use cameo_list_snapshots.");
        }
        return s;
    }

    public static JsonObject delete(JsonObject p) {
        String id = Gs.reqStr(p, "snapshot_id").trim();
        JsonObject response = new JsonObject();
        response.addProperty("deleted", SnapshotStore.delete(id));
        response.addProperty("snapshotId", id);
        return response;
    }

    public static JsonObject diff(JsonObject p) {
        String beforeId = Gs.reqStr(p, "before_snapshot_id").trim();
        String afterId = Gs.reqStr(p, "after_snapshot_id").trim();
        JsonObject before = SnapshotStore.get(beforeId);
        JsonObject after = SnapshotStore.get(afterId);
        if (before == null || after == null) {
            throw new ToolException("NOT_FOUND", "Both snapshots must exist (missing: "
                    + (before == null ? beforeId : "") + (before == null && after == null ? ", " : "")
                    + (after == null ? afterId : "") + "). Use cameo_list_snapshots.");
        }
        int maxChanges = Gs.integer(p, "max_changes", 500);
        boolean includeDetails = Gs.bool(p, "include_details", true);
        List<String> ignore = ArtifactSupport.optStrList(p, "ignore_paths");
        JsonObject response = JsonDiff.diff(before.get("payload"), after.get("payload"),
                JsonDiff.ignoreSet(ignore), maxChanges, includeDetails);
        response.addProperty("beforeSnapshotId", beforeId);
        response.addProperty("afterSnapshotId", afterId);
        return response;
    }
}
