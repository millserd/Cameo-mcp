package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.impl.ElementsFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Requirements CSV/JSON import/export plus the ReqIF probe (reference ImportExportHandler). */
final class RequirementsService {

    /** Native ReqIF utility class; only probed. */
    private static final String REQIF_UTILS = "com.nomagic.requirements.reqif.ReqIFUtils";
    /** Native ReqIF mapping manager class; only probed. */
    private static final String REQIF_MAPPING_MANAGER = "com.nomagic.requirements.reqif.mapping.ReqIFMappingManager";
    /** Upper bound on elements visited while scanning for requirements. */
    private static final int SCAN_LIMIT = 250_000;

    /** Static helpers only. */
    private RequirementsService() {
    }

    /**
     * cameo_get_requirements_capabilities: the generic {@link FeatureProbes} answer for the ReqIF product
     * classes.
     *
     * @return the capability object
     */
    static JsonObject requirementsCapabilities() {
        return FeatureProbes.capability("requirements");
    }

    /**
     * cameo_get_import_export_capabilities. CSV and JSON import and export are implemented here and always
     * available; native ReqIF is only probed, never driven.
     *
     * @return the capability object, including the ReqIF class probe and supported import sources
     */
    static JsonObject capabilities() {
        JsonObject response = Support.baseCapabilities("importExport", "bridge-owned-plus-native-probe");
        response.addProperty("available", true);
        response.addProperty("status", "available");
        response.addProperty("csvJsonBridgeOwned", true);
        response.addProperty("xlsxBridgeOwned", false);
        JsonObject reqifProbe = Support.classProbe(REQIF_UTILS, REQIF_MAPPING_MANAGER);
        response.add("reqifClassProbe", reqifProbe);
        response.addProperty("nativeReqifAvailable", reqifProbe.get("allFound").getAsBoolean());
        response.add("pluginDirectoriesFound", Support.pluginDirectories("requirements", "reqif", "import"));
        response.addProperty("nativeImportMode", "preview-required");
        response.add("exportFormats", Gs.strings(Arrays.asList("json", "csv")));
        response.add("importSources", Gs.strings(Arrays.asList("requirements", "rows", "source_rows", "csv_text", "source_path (.csv/.json)")));
        return response;
    }

    // ------------------------------------------------------------------ export

    /** Collect requirement-like elements under the roots (depth-first, bounded). */
    private static JsonObject collect(Project project, List<String> rootIds, int limit) {
        List<Element> roots = new ArrayList<>();
        for (String id : rootIds) {
            roots.add(Support.requireElement(project, id, "Scope element"));
        }
        if (roots.isEmpty() && project.getPrimaryModel() != null) {
            roots.add(project.getPrimaryModel());
        }
        JsonArray requirements = new JsonArray();
        int visited = 0;
        boolean truncated = false;
        // Overlapping roots (one scope inside another) would otherwise list the same requirement twice.
        java.util.Set<String> seen = new java.util.HashSet<>();
        outer:
        for (Element root : roots) {
            Deque<Element> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Element element = stack.pop();
                if (++visited > SCAN_LIMIT) {
                    truncated = true;
                    break outer;
                }
                if (isRequirementLike(element) && seen.add(element.getID())) {
                    if (requirements.size() >= limit) {
                        truncated = true;
                        break outer;
                    }
                    requirements.add(ElementJson.toJson(element));
                }
                Collection<Element> children = element.getOwnedElement();
                if (children != null) {
                    List<Element> list = new ArrayList<>(children);
                    for (int i = list.size() - 1; i >= 0; i--) {
                        stack.push(list.get(i));
                    }
                }
            }
        }
        JsonObject out = new JsonObject();
        out.add("requirements", requirements);
        out.addProperty("rootId", roots.isEmpty() ? "" : roots.get(0).getID());
        JsonArray rootArray = new JsonArray();
        for (Element r : roots) {
            rootArray.add(r.getID());
        }
        out.add("rootIds", rootArray);
        out.addProperty("visitedElements", visited);
        out.addProperty("truncated", truncated);
        return out;
    }

    /**
     * SysML requirement detection without depending on the SysML profile API: true when the element's class
     * name or any applied stereotype name contains "requirement". This also matches, for example,
     * extendedRequirement or stereotypes from custom profiles. Stereotype lookup failures are ignored.
     *
     * @param element element to test
     * @return true for requirement-like elements
     */
    static boolean isRequirementLike(Element element) {
        String className = element.getClass().getSimpleName();
        if (className != null && className.toLowerCase(Locale.ROOT).contains("requirement")) {
            return true;
        }
        try {
            List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(element);
            if (stereotypes != null) {
                for (Stereotype stereotype : stereotypes) {
                    String n = stereotype.getName();
                    if (n != null && n.toLowerCase(Locale.ROOT).contains("requirement")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Some element kinds do not expose stereotype state cleanly.
        }
        return false;
    }

    /**
     * Scope IDs for export: scope_ids plus root_id (or the legacy package_id) placed first, so the first root
     * reported is the one named.
     *
     * @param p tool arguments
     * @return trimmed, de-duplicated IDs; empty means the primary model
     */
    private static List<String> scopeIds(JsonObject p) {
        List<String> ids = new ArrayList<>(Gs.strList(p, "scope_ids"));
        String rootId = Gs.str(p, "root_id");
        if (rootId == null) {
            rootId = Gs.str(p, "package_id");
        }
        if (rootId != null && !rootId.trim().isEmpty() && !ids.contains(rootId.trim())) {
            ids.add(0, rootId.trim());
        }
        return ids;
    }

    /**
     * cameo_export_requirements. Collects requirements under the scope (default primary model) and returns
     * them as JSON or CSV, optionally written to output_path. "reqif" is accepted but only returns JSON and
     * writes no file. limit is clamped to 1..5000, default 1000. The output path is checked before and again
     * after the model read.
     *
     * @param p tool arguments
     * @return the requirements, count, truncated flag, and csvText or file details
     * @throws ToolException INVALID_PARAMS for a bad format or output path, CONFLICT for an existing file
     *     without overwrite, NOT_FOUND for a bad scope ID, TOOL_FAILED when the file cannot be written
     * @throws Exception NO_PROJECT from the model read, or any read failure
     */
    static JsonObject exportRequirements(JsonObject p) throws Exception {
        String format = Gs.str(p, "format", "json").trim().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("json", "csv", "reqif").contains(format)) {
            throw ToolException.invalid("format must be json, csv or reqif (got '" + format + "').");
        }
        int limit = Math.max(1, Math.min(5000, Gs.integer(p, "limit", 1000)));
        String outputPath = Gs.str(p, "output_path");
        boolean overwrite = Gs.bool(p, "overwrite", false);
        if (outputPath != null && !"reqif".equals(format)) {
            // Validate before touching the model so a bad path fails fast.
            RequirementsCodec.checkOutputPath(outputPath, overwrite, "overwrite");
        }
        JsonObject collected = Edt.read(project -> collect(project, scopeIds(p), limit));
        JsonArray requirements = collected.getAsJsonArray("requirements");

        JsonObject response = new JsonObject();
        response.addProperty("format", format);
        response.addProperty("rootId", Gs.str(collected, "rootId", ""));
        response.add("rootIds", collected.get("rootIds"));
        response.addProperty("nativeReqifRequested", "reqif".equals(format));
        response.addProperty("nativeReqifWritten", false);
        response.addProperty("count", requirements.size());
        response.addProperty("limit", limit);
        response.addProperty("truncated", collected.get("truncated").getAsBoolean());
        response.add("requirements", requirements);

        String content = null;
        if ("csv".equals(format)) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (JsonElement e : requirements) {
                rows.add(RequirementsCodec.exportRow(e.getAsJsonObject()));
            }
            content = RequirementsCodec.toCsv(RequirementsCodec.EXPORT_COLUMNS, rows);
            response.add("columns", Gs.strings(RequirementsCodec.EXPORT_COLUMNS));
            response.addProperty("csvText", content);
        } else if ("json".equals(format)) {
            JsonObject doc = new JsonObject();
            doc.addProperty("format", "cameo-mcp-requirements");
            doc.addProperty("count", requirements.size());
            doc.add("requirements", requirements);
            content = doc.toPrettyString();
        } else {
            response.addProperty("message", "Native ReqIF export is not enabled; the requirements are returned as JSON. "
                    + "Use Cameo's File > Export To > ReqIF for a native ReqIF file.");
        }
        // Re-check to obtain the File, and in case the file appeared while the model was being read.
        if (outputPath != null) {
            if (content == null) {
                response.addProperty("fileWritten", false);
            } else {
                File file = RequirementsCodec.checkOutputPath(outputPath, overwrite, "overwrite");
                boolean existed = file.exists();
                try {
                    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new ToolException("TOOL_FAILED", "Could not write '" + file + "': " + e.getMessage());
                }
                response.addProperty("fileWritten", true);
                response.addProperty("outputPath", file.getAbsolutePath());
                response.addProperty("outputBytes", file.length());
                response.addProperty("overwroteExisting", existed);
            }
        }
        response.add("capabilities", capabilities());
        response.add("request", Support.request(p));
        return response;
    }

    /**
     * cameo_export_requirements_preview. A guarded-action envelope plus, when a project is open, the
     * requirement count and five sample CSV rows, scoped like cameo_export_requirements. Nothing is written.
     *
     * @param p tool arguments
     * @return the preview envelope
     * @throws Exception NOT_FOUND for a bad scope ID, or any read failure
     */
    static JsonObject exportRequirementsPreview(JsonObject p) throws Exception {
        String format = Gs.str(p, "format", "csv").trim().toLowerCase(Locale.ROOT);
        JsonObject response = FeatureProbes.guardedAction("requirements", "cameo_export_requirements_preview", Support.request(p));
        if (Support.currentProject() != null) {
            JsonObject collected = Edt.read(project -> collect(project, scopeIds(p), 5000));
            JsonArray reqs = collected.getAsJsonArray("requirements");
            response.addProperty("requirementCount", reqs.size());
            response.addProperty("truncated", collected.get("truncated").getAsBoolean());
            JsonArray sample = new JsonArray();
            for (int i = 0; i < Math.min(5, reqs.size()); i++) {
                Map<String, String> row = RequirementsCodec.exportRow(reqs.get(i).getAsJsonObject());
                sample.add(Gs.toElement(row));
            }
            response.add("sampleRows", sample);
        }
        response.addProperty("format", format);
        response.add("columns", Gs.strings(RequirementsCodec.EXPORT_COLUMNS));
        if (Gs.has(p, "output_path")) {
            response.add("outputPathCheck", RequirementsCodec.describeOutputPath(Gs.str(p, "output_path")));
        }
        response.addProperty("nextStep", "Call cameo_export_requirements with the same scope to produce the export "
                + "(format json or csv, optional output_path).");
        return response;
    }

    // ------------------------------------------------------------------ import

    /**
     * Rows from every supported source in the request: patch_plan rows, requirements/rows/source_rows,
     * csv_text, source_path.
     *
     * <p>Explicit sources are additive. The rows of a patch_plan (unwrapped from their "raw" member) are used
     * only when no explicit source is given.
     *
     * @param p tool arguments
     * @return all rows, possibly empty
     * @throws ToolException INVALID_PARAMS for a malformed source, or errors from reading source_path
     */
    static List<Map<String, String>> rows(JsonObject p) {
        List<Map<String, String>> rows = new ArrayList<>();
        boolean explicit = false;
        // Explicit sources are additive; a patch_plan is only used when none of them is given.
        for (String key : new String[]{"requirements", "rows", "source_rows"}) {
            if (Gs.has(p, key)) {
                if (!p.get(key).isJsonArray()) {
                    throw ToolException.invalid("Parameter '" + key + "' must be an array of objects.");
                }
                rows.addAll(RequirementsCodec.rowsFromJsonArray(p.getAsJsonArray(key), key));
                explicit = true;
            }
        }
        String csv = Gs.str(p, "csv_text");
        if (csv != null && !csv.trim().isEmpty()) {
            rows.addAll(RequirementsCodec.parseCsv(csv));
            explicit = true;
        }
        String sourcePath = Gs.str(p, "source_path");
        if (sourcePath != null && !sourcePath.trim().isEmpty()) {
            rows.addAll(RequirementsCodec.readSourceFile(sourcePath));
            explicit = true;
        }
        JsonObject plan = Gs.object(p, "patch_plan");
        if (!explicit && plan != null) {
            for (String key : new String[]{"rows", "requirements"}) {
                if (plan.has(key) && plan.get(key).isJsonArray()) {
                    JsonArray arr = plan.getAsJsonArray(key);
                    // Normalized preview rows carry the original values under "raw".
                    JsonArray raw = new JsonArray();
                    for (JsonElement e : arr) {
                        if (e.isJsonObject() && e.getAsJsonObject().has("raw") && e.getAsJsonObject().get("raw").isJsonObject()) {
                            raw.add(e.getAsJsonObject().get("raw"));
                        } else {
                            raw.add(e);
                        }
                    }
                    rows.addAll(RequirementsCodec.rowsFromJsonArray(raw, "patch_plan." + key));
                    break;
                }
            }
        }
        return rows;
    }

    /**
     * Import target: target_package_id, else the targetPackageId recorded in a patch_plan from a previous
     * preview.
     *
     * @param p tool arguments
     * @return the trimmed ID, or null
     */
    private static String targetPackageId(JsonObject p) {
        String id = Gs.str(p, "target_package_id");
        if (id == null) {
            JsonObject plan = Gs.object(p, "patch_plan");
            id = plan == null ? null : Gs.str(plan, "targetPackageId");
        }
        return id == null || id.trim().isEmpty() ? null : id.trim();
    }

    /**
     * Shared body of cameo_preview_requirements_import and the dry-run path of
     * cameo_apply_requirements_import. Normalizes and validates rows and returns a patch plan that can be fed
     * back as patch_plan. When a target is given and a project is open, each row is also matched against
     * existing requirements under it.
     *
     * @param p tool arguments
     * @param message preview message
     * @return the preview envelope with rowCount, validation, optional target and patchPlan
     * @throws Exception row-source errors, or any model read failure
     */
    static JsonObject importPreview(JsonObject p, String message) throws Exception {
        JsonObject request = Support.request(p);
        JsonObject response = Support.previewAccepted("importExport", request, message);
        List<Map<String, String>> rows = rows(p);
        String target = targetPackageId(p);
        JsonArray normalized = new JsonArray();
        for (int i = 0; i < rows.size(); i++) {
            normalized.add(RequirementsCodec.normalize(rows.get(i), i));
        }
        JsonObject validation = RequirementsCodec.validate(rows);
        // Matching against existing requirements needs the model, so it is skipped when no project is open.
        if (target != null && Support.currentProject() != null) {
            JsonObject targetInfo = Edt.read(project -> compareWithTarget(project, target, normalized));
            response.add("target", targetInfo);
        }
        response.addProperty("rowCount", rows.size());
        response.add("validation", validation);
        response.addProperty("patchPlanReady", true);
        JsonObject patchPlan = new JsonObject();
        patchPlan.addProperty("operation", "requirements-import");
        patchPlan.addProperty("writePerformed", false);
        patchPlan.addProperty("requiresStableElementIdsOrExternalKeys", true);
        patchPlan.addProperty("nativeReqifImportEnabled", false);
        if (target != null) {
            patchPlan.addProperty("targetPackageId", target);
        }
        patchPlan.addProperty("rowCount", rows.size());
        patchPlan.add("rows", normalized);
        response.add("patchPlan", patchPlan);
        response.add("capabilities", capabilities());
        return response;
    }

    /** Mark each normalized row create/existing against requirements already under the target package. */
    private static JsonObject compareWithTarget(Project project, String targetId, JsonArray normalized) {
        JsonObject info = new JsonObject();
        info.addProperty("targetPackageId", targetId);
        Element target = Support.element(project, targetId);
        if (!(target instanceof Package)) {
            info.addProperty("valid", false);
            info.addProperty("problem", target == null ? "Target package not found." : "Target is not a Package.");
            return info;
        }
        info.addProperty("valid", true);
        info.add("package", Support.ref(target));
        Map<String, String> byName = new HashMap<>();
        Map<String, String> byReqId = new HashMap<>();
        JsonObject collected = collect(project, java.util.Collections.singletonList(targetId), 5000);
        for (JsonElement e : collected.getAsJsonArray("requirements")) {
            JsonObject r = e.getAsJsonObject();
            Map<String, String> row = RequirementsCodec.exportRow(r);
            if (!row.get("name").isEmpty()) {
                byName.put(row.get("name").toLowerCase(Locale.ROOT), row.get("id"));
            }
            if (!row.get("requirementId").isEmpty()) {
                byReqId.put(row.get("requirementId"), row.get("id"));
            }
        }
        int creates = 0;
        int matches = 0;
        for (JsonElement e : normalized) {
            JsonObject row = e.getAsJsonObject();
            String ext = Gs.str(row, "externalId");
            String name = Gs.str(row, "name");
            // Match by requirement Id tag first, then by case-insensitive name.
            String match = ext != null ? byReqId.get(ext) : null;
            if (match == null && name != null) {
                match = byName.get(name.toLowerCase(Locale.ROOT));
            }
            if (match != null) {
                row.addProperty("action", "exists");
                row.addProperty("existingElementId", match);
                matches++;
            } else {
                row.addProperty("action", "create");
                creates++;
            }
        }
        info.addProperty("existingRequirementCount", collected.getAsJsonArray("requirements").size());
        info.addProperty("plannedCreates", creates);
        info.addProperty("matchingExisting", matches);
        info.addProperty("note", "cameo_apply_requirements_import creates a new element for every row, including rows "
                + "marked 'exists'; remove those rows first to avoid duplicates.");
        return info;
    }

    /**
     * cameo_import_requirements_preview. A guarded-action envelope plus the import preview's rowCount,
     * validation, target and patchPlan.
     *
     * @param p tool arguments
     * @return the preview envelope
     * @throws Exception row-source errors, or any model read failure
     */
    static JsonObject importRequirementsPreview(JsonObject p) throws Exception {
        JsonObject response = FeatureProbes.guardedAction("requirements", "cameo_import_requirements_preview", Support.request(p));
        JsonObject preview = importPreview(p, "Requirements import preview accepted. No model write was performed.");
        for (String key : new String[]{"rowCount", "validation", "target", "patchPlan"}) {
            if (preview.has(key)) {
                response.add(key, preview.get(key));
            }
        }
        return response;
    }

    /**
     * cameo_apply_requirements_import. dry_run defaults to true, so a real write needs dry_run=false and
     * allow_write=true. Rows are validated before the write session so a bad row imports nothing. Each row
     * becomes a Class with the SysML Requirement stereotype (when found), Id and Text tags and a comment, all
     * in one undoable session with a 120 s limit. Rows are never matched against existing elements, so
     * repeated imports create duplicates.
     *
     * @param p tool arguments
     * @return the dry-run preview, or the created elements and warnings
     * @throws ToolException UNSUPPORTED for format reqif, PRECONDITION_FAILED without allow_write,
     *     INVALID_PARAMS for a bad format, a missing or non-Package target, no rows or a row without a name
     * @throws Exception NO_PROJECT, TIMEOUT or other failures from the write session
     */
    static JsonObject apply(JsonObject p) throws Exception {
        String format = Gs.str(p, "format", "json");
        if (format == null || format.trim().isEmpty()) {
            format = "json";
        }
        format = format.trim().toLowerCase(Locale.ROOT);
        if ("reqif".equals(format)) {
            JsonObject response = Support.unsupported("importExport",
                    "Native ReqIF apply remains gated until a sample ReqIF import/export roundtrip is captured.",
                    "Use JSON/CSV apply for disposable-package tests; keep ReqIF preview-only.");
            response.add("request", Support.request(p));
            throw Support.refusal("UNSUPPORTED", response);
        }
        if (!Arrays.asList("json", "csv").contains(format)) {
            throw ToolException.invalid("format must be json, csv or reqif (got '" + format + "').");
        }
        if (Gs.bool(p, "dry_run", true)) {
            JsonObject response = importPreview(p, "Requirements import dry run accepted. No model write was performed.");
            response.addProperty("dryRun", true);
            return response;
        }
        if (!Gs.bool(p, "allow_write", false)) {
            JsonObject response = Support.unsupported("importExport",
                    "Requirements import apply requires allow_write=true in addition to dry_run=false.",
                    "Preview first, then repeat the request with dry_run=false, allow_write=true, and a disposable target_package_id.");
            response.add("request", Support.request(p));
            throw Support.refusal("PRECONDITION_FAILED", response);
        }
        String targetPackageId = targetPackageId(p);
        if (targetPackageId == null) {
            throw ToolException.invalid("Missing required parameter 'target_package_id'.");
        }
        List<Map<String, String>> rows = rows(p);
        if (rows.isEmpty()) {
            throw ToolException.invalid("No requirements supplied. Use requirements, rows, csv_text or a patch_plan from "
                    + "cameo_preview_requirements_import.");
        }
        for (int i = 0; i < rows.size(); i++) {
            if (RequirementsCodec.name(rows.get(i)) == null) {
                throw ToolException.invalid("Row " + i + " needs name, id, externalId, or key. Nothing was imported.");
            }
        }
        final String finalFormat = format;
        return Edt.write("Apply requirements import", project -> {
            Element target = Support.element(project, targetPackageId);
            if (!(target instanceof Package)) {
                throw ToolException.invalid("target_package_id must reference a Package: " + targetPackageId);
            }
            ElementsFactory ef = project.getElementsFactory();
            Stereotype requirementStereo = findStereotype(project, "requirement");
            JsonArray created = new JsonArray();
            JsonArray warnings = new JsonArray();
            // Each row becomes a Class with the SysML Requirement stereotype (when available), Id/Text tags and a comment.
            for (Map<String, String> row : rows) {
                String name = RequirementsCodec.name(row);
                com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class requirement = ef.createClassInstance();
                requirement.setName(name);
                ModelElementsManager.getInstance().addElement(requirement, target);
                boolean stereotyped = false;
                if (requirementStereo != null) {
                    if (!StereotypesHelper.hasStereotype(requirement, requirementStereo)) {
                        StereotypesHelper.addStereotype(requirement, requirementStereo);
                    }
                    stereotyped = true;
                }
                String externalId = RequirementsCodec.externalId(row);
                String text = RequirementsCodec.text(row);
                String documentation = RequirementsCodec.documentation(row);
                boolean textTagged = false;
                if (stereotyped) {
                    if (externalId != null) {
                        setTag(requirement, requirementStereo, "Id", externalId, warnings);
                    }
                    if (text != null) {
                        textTagged = setTag(requirement, requirementStereo, "Text", text, warnings);
                    }
                }
                // When Text went into the tag, the comment carries only documentation; otherwise text (or documentation) is the comment.
                String body = textTagged ? documentation : firstNonBlank(text, documentation);
                if (body != null) {
                    Comment comment = ef.createCommentInstance();
                    comment.setBody(body);
                    ModelElementsManager.getInstance().addElement(comment, requirement);
                }
                JsonObject entry = ElementJson.toJson(requirement);
                if (externalId != null) {
                    entry.addProperty("externalId", externalId);
                }
                created.add(entry);
            }
            JsonObject response = new JsonObject();
            response.addProperty("applied", true);
            response.addProperty("writePerformed", true);
            response.addProperty("format", finalFormat);
            response.addProperty("targetPackageId", targetPackageId);
            response.addProperty("createdCount", created.size());
            response.addProperty("requirementStereotypeApplied", requirementStereo != null);
            if (requirementStereo == null) {
                warnings.add("No 'Requirement' stereotype found (is SysML loaded?); plain classes were created.");
            }
            response.add("created", created);
            response.add("warnings", warnings);
            response.add("capabilities", capabilities());
            response.add("request", Support.request(p));
            return response;
        }, 120);
    }

    /**
     * Sets a stereotype tag only if the stereotype defines it. Failures are recorded as warnings, not thrown.
     *
     * @param element element with the stereotype applied
     * @param stereotype the applied stereotype
     * @param tag tag (attribute) name
     * @param value value to store
     * @param warnings receives a message when setting fails
     * @return true when the value was stored
     */
    private static boolean setTag(Element element, Stereotype stereotype, String tag, String value, JsonArray warnings) {
        try {
            boolean hasTag = false;
            for (Property prop : stereotype.getAttribute()) {
                if (tag.equals(prop.getName())) {
                    hasTag = true;
                    break;
                }
            }
            if (!hasTag) {
                return false;
            }
            StereotypesHelper.setStereotypePropertyValue(element, stereotype, tag, value);
            return true;
        } catch (Exception e) {
            warnings.add("Could not set tag " + tag + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Finds a stereotype by name (case-insensitive), preferring one owned at any depth by a "SysML", "SysML
     * Profile" or "Requirements" package, since other loaded profiles can define a stereotype with the same
     * name.
     *
     * @param project the open project
     * @param stereotypeName name to look for
     * @return the best match, or null if none
     */
    private static Stereotype findStereotype(Project project, String stereotypeName) {
        Collection<Stereotype> all = StereotypesHelper.getAllStereotypes(project);
        if (all == null) {
            return null;
        }
        List<Stereotype> matches = new ArrayList<>();
        for (Stereotype s : all) {
            if (s.getName() != null && stereotypeName.equalsIgnoreCase(s.getName())) {
                matches.add(s);
            }
        }
        for (String preferred : Arrays.asList("SysML", "SysML Profile", "Requirements")) {
            for (Stereotype s : matches) {
                if (ownerChainContains(s, preferred)) {
                    return s;
                }
            }
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * True if the element or any owner up the chain has the given name (case-insensitive).
     *
     * @param element starting element
     * @param ownerName name to look for
     * @return whether the name occurs in the owner chain
     */
    private static boolean ownerChainContains(Element element, String ownerName) {
        Element current = element;
        while (current != null) {
            if (current instanceof NamedElement) {
                String n = ((NamedElement) current).getName();
                if (n != null && ownerName.equalsIgnoreCase(n)) {
                    return true;
                }
            }
            current = current.getOwner();
        }
        return false;
    }

    /**
     * First non-blank string.
     *
     * @param values candidates, may contain nulls
     * @return the first non-blank value, trimmed, or null
     */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }
}
