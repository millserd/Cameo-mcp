package com.cameomcp.optional.relationmap;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.GsonService;
import com.cameomcp.plugin.tools.artifacts.ArtifactSupport;
import com.cameomcp.plugin.tools.artifacts.CriteriaTemplates;
import com.cameomcp.plugin.tools.artifacts.JsonDiff;
import com.cameomcp.plugin.tools.artifacts.PresentationJson;
import com.cameomcp.plugin.tools.artifacts.PropertyJson;
import com.cameomcp.plugin.tools.artifacts.Reflect;
import com.cameomcp.plugin.tools.artifacts.TraceabilityGraph;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.export.image.ImageExporter;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.magicdraw.visualization.relationshipmap.GraphUtils;
import com.nomagic.magicdraw.visualization.relationshipmap.RelationMapManager;
import com.nomagic.magicdraw.visualization.relationshipmap.RelationshipMapUtilities;
import com.nomagic.magicdraw.visualization.relationshipmap.model.settings.GraphSettings;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native Relation Maps through the Relation Map plugin API (GraphSettings and the map display). */
public final class RelationMapService implements GsonService {

    private static final String DISPLAY_CLASS =
            "com.nomagic.magicdraw.visualization.relationshipmap.ui.diagram.view.AbstractGraphDisplay";

    @FunctionalInterface
    private interface BooleanSetter {
        void set(boolean value);
    }

    public RelationMapService() {
    }

    @Override
    public JsonObject call(String operation, JsonObject p) throws Exception {
        try {
            return dispatch(operation, p);
        } catch (LinkageError e) {
            throw new ToolException("UNSUPPORTED", "The Relation Map API of this Cameo version does not match what "
                    + "this tool expects (" + e + "). Use cameo_execute_probe with template "
                    + "relationMap.listGraphSettingsMethods to inspect the available API.");
        }
    }

    private JsonObject dispatch(String operation, JsonObject p) throws Exception {
        switch (operation) {
            case "get": {
                String id = mapId(p);
                return Edt.read(project -> serialize(project, id));
            }
            case "create":
                return create(p);
            case "configure":
                return configure(p);
            case "refresh":
                return refresh(p);
            case "rawSettings":
                return rawSettings(p);
            case "presentations":
                return presentations(p);
            case "setCriteria":
                return setCriteria(p);
            case "expand":
                return expandCollapse(p, true);
            case "collapse":
                return expandCollapse(p, false);
            case "render":
                return render(p);
            case "verify":
                return verify(p);
            case "compare":
                return compare(p);
            case "graph":
                return graph(p);
            case "snapshotSettings": {
                String id = mapId(p);
                return Edt.read(project -> snapshotSettings(ArtifactSupport.relationMap(project, id)));
            }
            case "probeTemplate":
                return probeTemplate(Gs.reqStr(p, "template"));
            case "invokeGraphSettingsGetter":
                return invokeGetter(p);
            default:
                throw new ToolException("NOT_IMPLEMENTED", "Unknown relation map operation " + operation);
        }
    }

    private static String mapId(JsonObject p) {
        return Gs.reqStr(p, "relation_map_id").trim();
    }

    // ------------------------------------------------------------------ create / configure

    private JsonObject create(JsonObject p) throws Exception {
        String parentId = Gs.reqStr(p, "parent_id").trim();
        String name = ArtifactSupport.optStr(p, "name");
        String finalName = name == null ? "Relation Map" : name;
        boolean refresh = Gs.bool(p, "refresh", false);
        return Edt.write("Create Relation Map", project -> {
            Element parent = ArtifactSupport.element(project, parentId, "Parent element");
            if (!(parent instanceof Namespace)) {
                throw ToolException.invalid("Parent element '" + parentId + "' is not a Namespace; pick a package.");
            }
            Diagram diagram = ModelElementsManager.getInstance()
                    .createDiagram(ArtifactSupport.RELATION_MAP_TYPE, (Namespace) parent);
            if (diagram == null) {
                throw new ToolException("TOOL_FAILED", "Cameo did not create the Relation Map.");
            }
            diagram.setName(finalName);
            applySettings(project, new GraphSettings(diagram), p);
            if (refresh) {
                RelationshipMapUtilities.refreshMap(diagram);
            }
            JsonObject response = new JsonObject();
            response.addProperty("created", true);
            response.addProperty("refreshRequested", refresh);
            response.add("relationMap", serialize(project, diagram.getID()));
            return response;
        }, 120);
    }

    private JsonObject configure(JsonObject p) throws Exception {
        String id = mapId(p);
        boolean refresh = Gs.bool(p, "refresh", false);
        return Edt.write("Configure Relation Map", project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            applySettings(project, new GraphSettings(diagram), p);
            if (refresh) {
                RelationshipMapUtilities.refreshMap(diagram);
            }
            JsonObject response = new JsonObject();
            response.addProperty("configured", true);
            response.addProperty("refreshRequested", refresh);
            response.add("relationMap", serialize(project, id));
            return response;
        }, 120);
    }

    private void applySettings(Project project, GraphSettings settings, JsonObject p) {
        String contextId = ArtifactSupport.optStr(p, "context_element_id");
        if (contextId != null) {
            settings.setContextElement(ArtifactSupport.element(project, contextId, "Context element"));
            settings.setMakeElementAsContext(Gs.bool(p, "make_element_as_context", true));
        } else {
            applyBool(p, "make_element_as_context", settings::setMakeElementAsContext);
        }
        List<String> scopeIds = ArtifactSupport.optStrList(p, "scope_ids");
        if (scopeIds != null) {
            settings.setScopeRoots(ArtifactSupport.elements(project, scopeIds, "Scope element"));
        }
        List<String> typeIds = ArtifactSupport.optStrList(p, "element_type_ids");
        if (typeIds != null) {
            settings.setElementTypes(ArtifactSupport.elements(project, typeIds, "Element type"));
        }
        List<String> criteria = ArtifactSupport.optStrList(p, "dependency_criteria");
        if (criteria != null) {
            settings.setDependencyCriterion(criteria);
        }
        Integer depth = ArtifactSupport.optInt(p, "depth");
        if (depth != null) {
            setDepth(settings, depth);
        }
        String layout = ArtifactSupport.optStr(p, "layout");
        if (layout != null) {
            settings.setLayout(layout);
        }
        applyBool(p, "legend_enabled", settings::setLegendEnabled);
        applyBool(p, "show_full_types", settings::setShowFullTypes);
        applyBool(p, "show_stereotypes", settings::setShowStereotypes);
        applyBool(p, "show_parameters", settings::setShowParameters);
        applyBool(p, "show_element_numbers", settings::setShowElementNumbers);
        applyBool(p, "single_node_per_element", settings::setEnabledSingleNodePerElementMode);
        applyBool(p, "short_node_names", settings::setEnabledShortNodeNamesMode);
        applyBool(p, "types_include_subtypes", settings::setTypesIncludeSubtypes);
        applyBool(p, "types_include_custom_types", settings::setIncludeCustomTypes);
        settings.setInitialized(true);
    }

    private static void applyBool(JsonObject p, String key, BooleanSetter setter) {
        if (Gs.has(p, key)) {
            setter.set(Gs.bool(p, key, false));
        }
    }

    // ------------------------------------------------------------------ serialization

    static JsonObject serialize(Project project, String id) {
        Diagram diagram = ArtifactSupport.relationMap(project, id);
        DiagramPresentationElement dpe = project.getDiagram(diagram);
        GraphSettings settings = new GraphSettings(diagram);
        JsonObject response = ArtifactSupport.diagramSummary(dpe, ArtifactSupport.RELATION_MAP_TYPE);
        JsonObject s = new JsonObject();
        s.addProperty("initialized", settings.isInitialized());
        s.add("depth", Gs.toElement(depth(settings)));
        s.addProperty("layout", settings.getLayout());
        s.addProperty("legendEnabled", settings.isLegendEnabled());
        s.addProperty("showFullTypes", settings.isShowFullTypes());
        s.addProperty("showStereotypes", settings.isShowStereotypes());
        s.addProperty("showParameters", settings.isShowParameters());
        s.addProperty("showElementNumbers", settings.isShowElementNumbers());
        s.addProperty("singleNodePerElement", settings.isEnabledSingleNodePerElementMode());
        s.addProperty("shortNodeNames", settings.isEnabledShortNodeNamesMode());
        s.addProperty("typesIncludeSubtypes", settings.isTypesIncludeSubtypes());
        s.addProperty("typesIncludeCustomTypes", settings.isTypesIncludeCustomTypes());
        s.addProperty("valid", safeIsValid(settings));
        Element context = settings.getContextElement();
        if (context != null) {
            s.add("contextElement", ElementJson.toJsonCompact(context));
        }
        s.add("scopeRoots", ArtifactSupport.compactArray(settings.getScopeRoots()));
        s.add("elementTypes", ArtifactSupport.compactArray(settings.getElementTypes()));
        s.add("dependencyCriteria", ArtifactSupport.stringArray(settings.getDependencyCriterion()));
        response.add("settings", s);
        return response;
    }

    /** GraphUtils.isGraphSettingValid, late-bound (its parameter type is undocumented). */
    static boolean safeIsValid(GraphSettings settings) {
        try {
            return Boolean.TRUE.equals(Reflect.invokeStatic(GraphUtils.class, "isGraphSettingValid", settings));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** GraphSettings depth (int or Integer depending on the release), or null when unreadable. */
    static Object depth(GraphSettings settings) {
        Object v = Reflect.tryInvoke(settings, "getDepth");
        return v instanceof Number ? v : null;
    }

    static void setDepth(GraphSettings settings, int depth) {
        try {
            Reflect.invoke(settings, "setDepth", Integer.valueOf(depth));
        } catch (Exception | LinkageError e) {
            throw new ToolException("UNSUPPORTED", "Cannot set the Relation Map depth through the API of this "
                    + "Cameo version (" + ArtifactSupport.describe(e) + ").");
        }
    }

    private static JsonObject snapshotSettings(Diagram diagram) {
        GraphSettings settings = new GraphSettings(diagram);
        JsonObject json = new JsonObject();
        json.addProperty("settingsClassName", settings.getClass().getName());
        json.addProperty("initialized", settings.isInitialized());
        json.add("depth", Gs.toElement(depth(settings)));
        json.addProperty("layout", settings.getLayout());
        json.addProperty("legendEnabled", settings.isLegendEnabled());
        json.addProperty("valid", safeIsValid(settings));
        json.add("contextElement", settings.getContextElement() != null
                ? ElementJson.toJsonCompact(settings.getContextElement()) : new JsonObject());
        JsonArray criteria = ArtifactSupport.stringArray(settings.getDependencyCriterion());
        json.add("dependencyCriteria", criteria);
        json.addProperty("criteriaCount", criteria.size());
        return json;
    }

    // ------------------------------------------------------------------ refresh / raw / presentations

    private JsonObject refresh(JsonObject p) throws Exception {
        String id = mapId(p);
        int timeout = ArtifactSupport.timeoutSeconds(p, "timeout");
        return Edt.write("Refresh Relation Map", project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            RelationshipMapUtilities.refreshMap(diagram);
            JsonObject response = new JsonObject();
            response.addProperty("refreshed", true);
            response.addProperty("refreshTimeoutSeconds", timeout);
            response.add("relationMap", serialize(project, id));
            return response;
        }, timeout);
    }

    private JsonObject rawSettings(JsonObject p) throws Exception {
        String id = mapId(p);
        boolean includeRaw = Gs.bool(p, "include_raw", false);
        boolean summaryOnly = Gs.bool(p, "summary_only", false);
        return Edt.read(project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            GraphSettings settings = new GraphSettings(diagram);
            JsonObject response = serialize(project, id);
            JsonArray warnings = new JsonArray();
            JsonObject raw = new JsonObject();
            raw.addProperty("settingsClassName", settings.getClass().getName());
            raw.addProperty("valid", safeIsValid(settings));
            List<String> criteria = settings.getDependencyCriterion();
            raw.addProperty("criteriaCount", criteria != null ? criteria.size() : 0);
            raw.add("publicNoArgGetters", PropertyJson.reflectGetterValues(settings, includeRaw, summaryOnly, warnings));
            response.add("rawSettings", raw);
            response.add("warnings", warnings);
            return response;
        });
    }

    private JsonObject presentations(JsonObject p) throws Exception {
        String id = mapId(p);
        int limit = Math.max(0, Gs.integer(p, "limit", 250));
        int offset = Math.max(0, Gs.integer(p, "offset", 0));
        boolean includeProperties = Gs.bool(p, "include_properties", false);
        boolean includeRaw = Gs.bool(p, "include_raw", false);
        boolean summaryOnly = Gs.bool(p, "summary_only", true);
        return Edt.read(project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            DiagramPresentationElement dpe = project.getDiagram(diagram);
            JsonArray warnings = new JsonArray();
            try {
                dpe.ensureLoaded();
                warnings.add("Relation Map ensureLoaded() was called before presentation inspection");
            } catch (Exception e) {
                warnings.add("ensureLoaded failed: " + e.getMessage());
            }
            Map<String, String> parentById = new LinkedHashMap<>();
            List<PresentationElement> all = PresentationJson.flatten(dpe, parentById);
            JsonArray array = new JsonArray();
            int end = (int) Math.min(all.size(), (long) offset + limit);
            for (int i = offset; i < end; i++) {
                PresentationElement pe = all.get(i);
                array.add(includeProperties
                        ? PresentationJson.presentationWithProperties(pe, parentById.get(pe.getID()), includeRaw,
                        summaryOnly)
                        : PresentationJson.presentationSummary(pe, parentById.get(pe.getID())));
            }
            JsonObject response = new JsonObject();
            response.add("relationMap", PresentationJson.diagramSummary(dpe));
            response.addProperty("presentationCount", all.size());
            response.add("countsByType", PresentationJson.countByType(all));
            response.addProperty("limit", limit);
            response.addProperty("offset", offset);
            response.addProperty("returnedPresentationCount", array.size());
            response.addProperty("hasMore", end < all.size());
            if (end < all.size()) {
                response.addProperty("nextOffset", end);
            }
            response.add("presentations", array);
            response.add("warnings", warnings);
            return response;
        });
    }

    // ------------------------------------------------------------------ criteria

    private JsonObject setCriteria(JsonObject p) throws Exception {
        String id = mapId(p);
        String mode = ArtifactSupport.optStr(p, "mode");
        String finalMode = mode == null ? "replace" : mode.toLowerCase(java.util.Locale.ROOT);
        if (!finalMode.equals("replace") && !finalMode.equals("append") && !finalMode.equals("remove")) {
            throw ToolException.invalid("mode must be replace, append, or remove (got '" + mode + "').");
        }
        JsonArray requested = criteriaArray(p);
        boolean refresh = Gs.bool(p, "refresh", false);
        return Edt.write("Set Relation Map Criteria", project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            GraphSettings settings = new GraphSettings(diagram);
            List<String> before = settings.getDependencyCriterion() != null
                    ? new ArrayList<>(settings.getDependencyCriterion()) : new ArrayList<>();
            List<String> criteria = criteriaFromRequest(project, requested);
            List<String> after;
            if ("replace".equals(finalMode)) {
                after = criteria;
            } else if ("append".equals(finalMode)) {
                after = new ArrayList<>(before);
                after.addAll(criteria);
            } else {
                after = new ArrayList<>(before);
                after.removeAll(criteria);
            }
            settings.setDependencyCriterion(after);
            settings.setInitialized(true);
            if (refresh) {
                RelationshipMapUtilities.refreshMap(diagram);
            }
            JsonObject response = new JsonObject();
            response.addProperty("criteriaUpdated", true);
            response.addProperty("mode", finalMode);
            response.addProperty("refreshed", refresh);
            response.addProperty("beforeCriteriaCount", before.size());
            response.addProperty("afterCriteriaCount", after.size());
            response.add("beforeCriteria", ArtifactSupport.stringArray(before));
            response.add("afterCriteria", ArtifactSupport.stringArray(after));
            response.add("relationMap", serialize(project, id));
            return response;
        }, 600);
    }

    /** "criteria" as an array; a JSON string holding an array or a single entry is accepted too. */
    static JsonArray criteriaArray(JsonObject p) {
        if (!Gs.has(p, "criteria")) {
            return new JsonArray();
        }
        JsonElement e = p.get("criteria");
        if (e.isJsonArray()) {
            return e.getAsJsonArray();
        }
        if (e.isJsonPrimitive()) {
            String s = e.getAsString().trim();
            if (s.startsWith("[")) {
                try {
                    JsonElement parsed = JsonParser.parseString(s);
                    if (parsed.isJsonArray()) {
                        return parsed.getAsJsonArray();
                    }
                } catch (RuntimeException ignored) {
                    // treat as a single raw expression
                }
            }
        }
        JsonArray one = new JsonArray();
        one.add(e);
        return one;
    }

    static List<String> criteriaFromRequest(Project project, JsonArray requested) {
        List<String> out = new ArrayList<>();
        for (JsonElement item : requested) {
            if (item == null || item.isJsonNull()) {
                continue;
            }
            if (item.isJsonPrimitive()) {
                String s = item.getAsString();
                CriteriaTemplates.Template t = CriteriaTemplates.get(s);
                out.add(t != null && !s.contains("<") ? toExpression(project, t) : s);
                continue;
            }
            if (!item.isJsonObject()) {
                throw ToolException.invalid("criteria entries must be strings or objects.");
            }
            JsonObject o = item.getAsJsonObject();
            String raw = Gs.str(o, "rawExpression", Gs.str(o, "raw_expression"));
            if (raw != null) {
                out.add(raw);
                continue;
            }
            String key = Gs.str(o, "template");
            if (key != null) {
                CriteriaTemplates.Template t = CriteriaTemplates.get(key);
                if (t == null) {
                    throw ToolException.invalid("Unknown criteria template: " + key
                            + ". Use cameo_list_relation_map_criteria_templates.");
                }
                out.add(toExpression(project, t));
                continue;
            }
            String type = Gs.str(o, "relationshipType", Gs.str(o, "relationship_type"));
            String direction = Gs.str(o, "direction");
            if (type != null) {
                out.add(direction != null ? type + ":" + direction : type);
                continue;
            }
            throw ToolException.invalid("A criteria object needs rawExpression, template, or relationshipType.");
        }
        return out;
    }

    private static String toExpression(Project project, CriteriaTemplates.Template t) {
        if (!t.isDsl()) {
            return t.expression;
        }
        Stereotype stereotype = findStereotype(project, t.stereotypeName);
        try {
            return dslExpression(stereotype, t.dslDirection, t.displayName);
        } catch (ToolException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            throw new ToolException("UNSUPPORTED", "Cannot build the native criteria expression for template '"
                    + t.key + "' with this Cameo version (" + ArtifactSupport.describe(e) + "). Copy the raw "
                    + "expression from a map configured in the UI (cameo_dump_relation_map_raw_settings) and pass "
                    + "it as {\"rawExpression\": ...}.");
        }
    }

    /**
     * new DSLRelationExpressionSpecification(stereotype), setDirection(Direction.X), wrapped by
     * ExpressionSpecificationUtil.makeCallExpressionSpecification(expr, true), named, converted to text.
     * Late-bound: this package is not in the public javadoc and its parameter types are unverified.
     */
    private static String dslExpression(Stereotype stereotype, String direction, String displayName)
            throws Exception {
        ClassLoader cl = RelationMapService.class.getClassLoader();
        String pkg = "com.nomagic.magicdraw.expressions.specification.";
        Class<?> dslType = Class.forName(pkg + "DSLRelationExpressionSpecification", true, cl);
        Object expression = null;
        for (java.lang.reflect.Constructor<?> c : dslType.getConstructors()) {
            if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isInstance(stereotype)) {
                expression = c.newInstance(stereotype);
                break;
            }
        }
        if (expression == null) {
            throw new NoSuchMethodException(dslType.getName() + "(Stereotype)");
        }
        Class<?> directionType = Class.forName(pkg + "Direction", true, cl);
        Object dir = directionType.getField(direction).get(null);
        Reflect.invoke(expression, "setDirection", dir);
        Class<?> util = Class.forName(pkg + "ExpressionSpecificationUtil", true, cl);
        Object call = Reflect.invokeStatic(util, "makeCallExpressionSpecification", expression, Boolean.TRUE);
        if (call == null) {
            throw new IllegalStateException("makeCallExpressionSpecification returned null");
        }
        Reflect.invoke(call, "setName", displayName);
        Object text = Reflect.invokeStatic(util, "convertToString", call);
        if (text == null) {
            throw new IllegalStateException("convertToString returned null");
        }
        return String.valueOf(text);
    }

    private static Stereotype findStereotype(Project project, String name) {
        Collection<Stereotype> all = StereotypesHelper.getAllStereotypes(project);
        if (all != null) {
            for (Stereotype s : all) {
                if (s != null && name.equals(s.getName())) {
                    return s;
                }
            }
        }
        throw new ToolException("PRECONDITION_FAILED", "Stereotype '" + name + "' needed by the criteria template "
                + "was not found in the project. Is the SysML profile used by this project?");
    }

    // ------------------------------------------------------------------ expand / collapse / render

    private JsonObject expandCollapse(JsonObject p, boolean expand) throws Exception {
        String id = mapId(p);
        boolean refresh = Gs.bool(p, "refresh", false);
        String mode = ArtifactSupport.optStr(p, "mode");
        String finalMode = mode == null ? "all" : mode;
        String layout = expand ? ArtifactSupport.optStr(p, "layout") : null;
        Integer depth = expand ? ArtifactSupport.optInt(p, "depth") : null;
        List<String> elementIds = ArtifactSupport.optStrList(p, "element_ids");
        int timeout = ArtifactSupport.timeoutSeconds(p, "timeout");
        return Edt.write((expand ? "Expand" : "Collapse") + " Relation Map", project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            DiagramPresentationElement dpe = project.getDiagram(diagram);
            dpe.ensureLoaded();
            int before = PresentationJson.flatten(dpe, null).size();
            JsonObject graphBefore = graphSummary(project, id, diagram);
            JsonObject displayBefore = displaySummary(dpe);
            if (depth != null) {
                setDepth(new GraphSettings(diagram), depth);
            }
            JsonArray attempts = new JsonArray();
            boolean applied = invokeDisplayAction(dpe, expand, attempts);
            if (refresh && applied) {
                RelationshipMapUtilities.refreshMap(diagram);
            }
            String layoutWarning = null;
            if (layout != null && applied) {
                try {
                    new GraphSettings(diagram).setLayout(layout);
                    PresentationJson.layout(dpe);
                } catch (Exception | LinkageError e) {
                    layoutWarning = "Relation Map layout failed: " + ArtifactSupport.describe(e);
                }
            }
            dpe.ensureLoaded();
            int after = PresentationJson.flatten(dpe, null).size();
            JsonObject response = new JsonObject();
            response.addProperty(expand ? "expanded" : "collapsed", applied);
            response.addProperty("supported", applied);
            response.addProperty("mode", finalMode);
            response.addProperty("refreshed", refresh && applied);
            response.addProperty("actionTimeoutSeconds", timeout);
            if (depth != null) {
                response.addProperty("depth", depth);
            }
            if (elementIds != null && !elementIds.isEmpty()) {
                response.add("requestedElementIds", ArtifactSupport.stringArray(elementIds));
                response.addProperty("elementIdsNote", "Per-element " + (expand ? "expansion" : "collapse")
                        + " is not available through the Relation Map API; the action applies to the whole map.");
            }
            response.addProperty("presentationCountBefore", before);
            response.addProperty("presentationCountAfter", after);
            response.addProperty("presentationCountDelta", after - before);
            response.add("graphBefore", graphBefore);
            response.add("graphAfter", graphSummary(project, id, diagram));
            response.add("displayBefore", displayBefore);
            response.add("displayAfter", displaySummary(dpe));
            response.add("attempts", attempts);
            if (layoutWarning != null) {
                response.addProperty("layoutWarning", layoutWarning);
            }
            if (!applied) {
                response.addProperty("unsupportedReason", expand
                        ? "Relation Map display expansion failed"
                        : "No Cameo Relation Map collapse API has been validated; generic diagram close is not "
                        + "treated as collapse");
            }
            return response;
        }, timeout);
    }

    private JsonObject render(JsonObject p) throws Exception {
        String id = mapId(p);
        boolean refresh = Gs.bool(p, "refresh", false);
        String expand = ArtifactSupport.optStr(p, "expand");
        boolean doExpand = expand != null && !"none".equalsIgnoreCase(expand);
        String layout = ArtifactSupport.optStr(p, "layout");
        Integer depth = ArtifactSupport.optInt(p, "depth");
        int scale = ArtifactSupport.clamp(ArtifactSupport.optInt(p, "scale_percentage"), 25, 1000, 200);
        boolean includeImage = Gs.bool(p, "include_image", true);
        boolean exportImage = Gs.bool(p, "export_image", true);
        boolean includeSummary = Gs.bool(p, "include_presentation_summary", true);
        int timeout = ArtifactSupport.clamp(ArtifactSupport.optInt(p, "render_timeout_seconds"), 1, 600, 120);
        boolean mutates = refresh || layout != null || doExpand || depth != null;
        Edt.ModelAction<JsonObject> action = project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            DiagramPresentationElement dpe = project.getDiagram(diagram);
            dpe.ensureLoaded();
            int before = PresentationJson.flatten(dpe, null).size();
            JsonArray warnings = new JsonArray();
            JsonArray expandAttempts = new JsonArray();
            boolean refreshed = false;
            if (depth != null) {
                try {
                    setDepth(new GraphSettings(diagram), depth);
                } catch (Exception e) {
                    warnings.add("Relation Map depth update failed: " + ArtifactSupport.describe(e));
                }
            }
            if (doExpand && !invokeDisplayAction(dpe, true, expandAttempts)) {
                warnings.add("Expand requested but Relation Map display expansion failed");
            }
            if (refresh) {
                try {
                    RelationshipMapUtilities.refreshMap(diagram);
                    refreshed = true;
                } catch (Exception e) {
                    warnings.add("Relation Map refresh failed: " + ArtifactSupport.describe(e));
                }
            }
            if (layout != null) {
                try {
                    new GraphSettings(diagram).setLayout(layout);
                    PresentationJson.layout(dpe);
                } catch (Exception | LinkageError e) {
                    warnings.add("Relation Map layout failed: " + ArtifactSupport.describe(e));
                }
            }
            dpe.ensureLoaded();
            List<PresentationElement> presentations = PresentationJson.flatten(dpe, null);
            BufferedImage image = null;
            if (exportImage) {
                try {
                    image = ImageExporter.export(dpe, false, scale);
                    if (image == null) {
                        warnings.add("Relation Map image export returned no image (empty map?)");
                    }
                } catch (Exception e) {
                    warnings.add("Relation Map image export failed: " + ArtifactSupport.describe(e));
                }
            } else {
                warnings.add("Relation Map image export skipped because exportImage=false");
            }
            JsonObject response = new JsonObject();
            response.addProperty("rendered", image != null);
            response.addProperty("refreshRequested", refresh);
            response.addProperty("refreshed", refreshed);
            response.addProperty("exportImage", exportImage);
            response.addProperty("renderTimeoutSeconds", timeout);
            response.addProperty("scalePercentage", scale);
            response.addProperty("presentationCountBefore", before);
            response.addProperty("presentationCountAfter", presentations.size());
            response.addProperty("presentationCountDelta", presentations.size() - before);
            response.add("graphSummary", graphSummary(project, id, diagram));
            response.add("expandAttempts", expandAttempts);
            if (includeSummary) {
                response.add("presentationCountsByType", PresentationJson.countByType(presentations));
            }
            if (image != null) {
                response.add("image", imageJson(image, includeImage));
            }
            response.add("warnings", warnings);
            return response;
        };
        return mutates
                ? Edt.write("Render Relation Map", action, timeout)
                : Edt.onEdt("Render Relation Map", action, timeout);
    }

    private static JsonObject imageJson(BufferedImage image, boolean includeImage) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        JsonObject json = new JsonObject();
        json.addProperty("format", "png");
        json.addProperty("width", image.getWidth());
        json.addProperty("height", image.getHeight());
        json.addProperty("imageBytes", bytes.length);
        json.addProperty("imageIncluded", includeImage);
        if (includeImage) {
            json.addProperty("image", Base64.getEncoder().encodeToString(bytes));
        }
        return json;
    }

    private boolean invokeDisplayAction(DiagramPresentationElement dpe, boolean expand, JsonArray attempts) {
        JsonObject attempt = new JsonObject();
        attempt.addProperty("className", DISPLAY_CLASS);
        attempt.addProperty("method", expand ? "expandElements" : "collapseElements");
        attempts.add(attempt);
        if (!expand) {
            attempt.addProperty("invoked", false);
            attempt.addProperty("unsupportedReason", "No validated Relation Map display collapse API is available");
            return false;
        }
        try {
            if (!dpe.isDiagramWindowOpen()) {
                dpe.open();
            }
            Object display = display(dpe);
            if (display == null) {
                attempt.addProperty("invoked", false);
                attempt.addProperty("error", "Relation Map display is unavailable");
                return false;
            }
            attempt.addProperty("nodeCountBefore", invokeInt(display, "getNodeCount"));
            invokeNoArg(display, "expandElements");
            invokeNoArg(display, "refreshView");
            attempt.addProperty("nodeCountAfter", invokeInt(display, "getNodeCount"));
            attempt.addProperty("invoked", true);
            return true;
        } catch (Exception | LinkageError e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            attempt.addProperty("invoked", false);
            attempt.addProperty("error", c.getClass().getSimpleName() + ": " + c.getMessage());
            return false;
        }
    }

    private static Object display(DiagramPresentationElement dpe) throws Exception {
        Object component = Reflect.invokeStatic(RelationMapManager.class, "getRMDiagramComponent", dpe);
        if (component == null) {
            return null;
        }
        invokeNoArg(component, "ensureDisplayIsBuilt");
        return invokeNoArg(component, "getRelationMapDisplay");
    }

    private static JsonObject displaySummary(DiagramPresentationElement dpe) {
        JsonObject summary = new JsonObject();
        try {
            summary.addProperty("diagramWindowOpen", dpe.isDiagramWindowOpen());
            summary.addProperty("diagramWindowActive", dpe.isDiagramWindowActive());
            if (!dpe.isDiagramWindowOpen()) {
                summary.addProperty("available", false);
                summary.addProperty("reason", "Relation Map diagram window is not open");
                return summary;
            }
            Object display = display(dpe);
            if (display == null) {
                summary.addProperty("available", false);
                summary.addProperty("reason", "Relation Map display is unavailable");
                return summary;
            }
            summary.addProperty("available", true);
            summary.addProperty("nodeCount", invokeInt(display, "getNodeCount"));
            Object contains = invokeNoArg(display, "containsGraph");
            summary.addProperty("containsGraph", Boolean.TRUE.equals(contains));
            Object root = invokeNoArg(display, "getRoot");
            summary.addProperty("rootChildCount", root != null ? invokeInt(root, "getChildCount") : 0);
            JsonArray types = new JsonArray();
            Object dataManager = invokeNoArg(display, "getDataManager");
            Object values = dataManager != null ? invokeNoArg(dataManager, "getRelationshipTypes") : null;
            if (values instanceof Iterable<?>) {
                for (Object t : (Iterable<?>) values) {
                    types.add(relationshipTypeName(t));
                }
            }
            summary.add("relationshipTypes", types);
        } catch (Exception | LinkageError e) {
            summary.addProperty("available", false);
            summary.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return summary;
    }

    private static String relationshipTypeName(Object t) {
        if (t == null) {
            return "";
        }
        try {
            Object name = t.getClass().getMethod("getName").invoke(t);
            if (name != null) {
                return String.valueOf(name);
            }
        } catch (Exception ignored) {
            // fall back to toString
        }
        return String.valueOf(t);
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static int invokeInt(Object target, String method) throws Exception {
        Object v = invokeNoArg(target, method);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    // ------------------------------------------------------------------ verify / compare / graph

    private JsonObject graphSummary(Project project, String id, Diagram diagram) {
        JsonObject summary = new JsonObject();
        try {
            JsonObject request = new JsonObject();
            request.addProperty("direction", "both");
            request.addProperty("max_depth", 3);
            request.addProperty("max_nodes", 1000);
            JsonObject graph = TraceabilityGraph.build(project, request, id,
                    new GraphSettings(diagram).getContextElement());
            summary.addProperty("available", true);
            summary.addProperty("nodeCount", graph.get("nodeCount").getAsInt());
            summary.addProperty("edgeCount", graph.get("edgeCount").getAsInt());
            summary.addProperty("truncated", graph.get("truncated").getAsBoolean());
        } catch (Exception e) {
            summary.addProperty("available", false);
            summary.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return summary;
    }

    private JsonObject verify(JsonObject p) throws Exception {
        String id = mapId(p);
        int minNodes = ArtifactSupport.clamp(ArtifactSupport.optInt(p, "expected_min_nodes"), 0, 100000, 0);
        int minEdges = ArtifactSupport.clamp(ArtifactSupport.optInt(p, "expected_min_edges"), 0, 100000, 0);
        int minRendered = ArtifactSupport.clamp(ArtifactSupport.optInt(p, "expected_rendered_nodes"), 0, 100000, 0);
        int maxDepth = ArtifactSupport.clamp(ArtifactSupport.optInt(p, "max_depth"), 1, 12, 3);
        return Edt.read(project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            DiagramPresentationElement dpe = project.getDiagram(diagram);
            GraphSettings settings = new GraphSettings(diagram);
            JsonObject request = new JsonObject();
            if (Gs.has(p, "relationship_types")) {
                request.add("relationship_types", p.get("relationship_types"));
            }
            request.addProperty("max_depth", maxDepth);
            request.addProperty("max_nodes", 1000);
            request.addProperty("direction", "both");
            JsonObject graph = TraceabilityGraph.build(project, request, id, settings.getContextElement());
            dpe.ensureLoaded();
            List<PresentationElement> presentations = PresentationJson.flatten(dpe, null);
            int rendered = presentations.size();
            boolean graphPasses = graph.get("nodeCount").getAsInt() >= minNodes
                    && graph.get("edgeCount").getAsInt() >= minEdges;
            boolean settingsPasses = safeIsValid(settings);
            boolean renderPasses = rendered >= minRendered;
            JsonObject checks = new JsonObject();
            checks.addProperty("graphTraversalPasses", graphPasses);
            checks.addProperty("settingsValidityPasses", settingsPasses);
            checks.addProperty("renderedNodeCountPasses", renderPasses);
            JsonObject response = new JsonObject();
            response.addProperty("ok", graphPasses && settingsPasses && renderPasses);
            response.add("checks", checks);
            response.add("graph", graph);
            response.addProperty("renderedPresentationCount", rendered);
            response.addProperty("expectedMinNodes", minNodes);
            response.addProperty("expectedMinEdges", minEdges);
            response.addProperty("expectedRenderedNodes", minRendered);
            response.add("settings", serialize(project, id).get("settings"));
            response.add("presentationCountsByType", PresentationJson.countByType(presentations));
            return response;
        });
    }

    private JsonObject compare(JsonObject p) throws Exception {
        String leftId = Gs.reqStr(p, "left_relation_map_id").trim();
        String rightId = Gs.reqStr(p, "right_relation_map_id").trim();
        boolean includePresentations = Gs.bool(p, "include_presentations", true);
        boolean includeRaw = Gs.bool(p, "include_raw", false);
        return Edt.read(project -> {
            JsonObject left = capture(project, leftId, includePresentations, includeRaw);
            JsonObject right = capture(project, rightId, includePresentations, includeRaw);
            JsonObject response = new JsonObject();
            response.add("left", left);
            response.add("right", right);
            response.add("diff", JsonDiff.diff(left, right, Collections.<String>emptySet(), 500, true));
            return response;
        });
    }

    private JsonObject capture(Project project, String id, boolean includePresentations, boolean includeRaw) {
        JsonObject json = serialize(project, id);
        Diagram diagram = ArtifactSupport.relationMap(project, id);
        GraphSettings settings = new GraphSettings(diagram);
        JsonArray warnings = new JsonArray();
        JsonObject raw = new JsonObject();
        raw.addProperty("settingsClassName", settings.getClass().getName());
        raw.add("publicNoArgGetters", PropertyJson.reflectGetterValues(settings, includeRaw, true, warnings));
        json.add("rawSettings", raw);
        if (includePresentations) {
            DiagramPresentationElement dpe = project.getDiagram(diagram);
            dpe.ensureLoaded();
            Map<String, String> parentById = new LinkedHashMap<>();
            List<PresentationElement> presentations = PresentationJson.flatten(dpe, parentById);
            json.addProperty("presentationCount", presentations.size());
            json.add("presentationCountsByType", PresentationJson.countByType(presentations));
            json.add("presentations", PresentationJson.summarize(presentations, parentById));
        }
        json.add("warnings", warnings);
        return json;
    }

    private JsonObject graph(JsonObject p) throws Exception {
        String id = mapId(p);
        return Edt.read(project -> {
            Diagram diagram = ArtifactSupport.relationMap(project, id);
            return TraceabilityGraph.build(project, p, id, new GraphSettings(diagram).getContextElement());
        });
    }

    // ------------------------------------------------------------------ probes

    private JsonObject probeTemplate(String template) throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("template", template);
        response.addProperty("executed", true);
        switch (template) {
            case "relationMap.listGraphSettingsMethods":
                response.add("methods", PropertyJson.methods(GraphSettings.class));
                return response;
            case "relationMap.dumpCriteriaClasses":
                response.add("criteria", Edt.read(this::criteriaClasses));
                return response;
            case "relationMap.dumpPresentationClasses":
                response.add("presentations", Edt.read(this::presentationClasses));
                return response;
            default:
                throw ToolException.invalid("Unknown probe template: " + template
                        + ". Use cameo_list_probe_templates.");
        }
    }

    private JsonArray criteriaClasses(Project project) {
        JsonArray array = new JsonArray();
        Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
        if (diagrams != null) {
            for (DiagramPresentationElement dpe : diagrams) {
                if (!ArtifactSupport.isRelationMap(dpe)) {
                    continue;
                }
                GraphSettings settings = new GraphSettings(dpe.getDiagram());
                JsonObject entry = new JsonObject();
                entry.addProperty("diagramId", dpe.getDiagram().getID());
                entry.addProperty("diagramName", dpe.getName());
                JsonArray criteria = new JsonArray();
                List<?> values = settings.getDependencyCriterion();
                if (values != null) {
                    for (Object c : values) {
                        JsonObject cj = new JsonObject();
                        cj.addProperty("className", c != null ? c.getClass().getName() : "null");
                        cj.addProperty("string", String.valueOf(c));
                        criteria.add(cj);
                    }
                }
                entry.add("criteria", criteria);
                array.add(entry);
            }
        }
        return array;
    }

    private JsonArray presentationClasses(Project project) {
        JsonArray array = new JsonArray();
        Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
        if (diagrams != null) {
            for (DiagramPresentationElement dpe : diagrams) {
                if (!ArtifactSupport.isRelationMap(dpe)) {
                    continue;
                }
                dpe.ensureLoaded();
                JsonObject entry = new JsonObject();
                entry.addProperty("diagramId", dpe.getDiagram().getID());
                entry.addProperty("diagramName", dpe.getName());
                JsonArray classes = new JsonArray();
                for (PresentationElement pe : PresentationJson.flatten(dpe, null)) {
                    classes.add(pe.getClass().getName());
                }
                entry.addProperty("count", classes.size());
                entry.add("classes", classes);
                array.add(entry);
            }
        }
        return array;
    }

    private JsonObject invokeGetter(JsonObject p) throws Exception {
        String id = mapId(p);
        String methodName = Gs.reqStr(p, "method_name").trim();
        return Edt.read(project -> {
            Object element = project.getElementByID(id);
            if (!(element instanceof Diagram)) {
                throw new ToolException("NOT_FOUND", "Relation Map diagram '" + id + "' not found.");
            }
            Method method;
            try {
                method = GraphSettings.class.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                throw ToolException.invalid("GraphSettings has no public no-arg method " + methodName
                        + ". Use template relationMap.listGraphSettingsMethods.");
            }
            if (method.getReturnType() == Void.TYPE
                    || (!methodName.startsWith("get") && !methodName.startsWith("is"))) {
                throw ToolException.invalid("Only public no-arg GraphSettings getters (get*/is*) are allowed.");
            }
            Object value = method.invoke(new GraphSettings((Diagram) element));
            JsonObject response = new JsonObject();
            response.addProperty("className", GraphSettings.class.getName());
            response.addProperty("methodName", methodName);
            response.add("value", PropertyJson.serializeValue(value, true, false));
            return response;
        });
    }
}
