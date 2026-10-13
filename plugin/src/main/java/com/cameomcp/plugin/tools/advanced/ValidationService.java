package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.annotation.Annotation;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.Finder;
import com.nomagic.magicdraw.validation.RuleViolationResult;
import com.nomagic.magicdraw.validation.ValidationHelper;
import com.nomagic.magicdraw.validation.ValidationRunData;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Native Cameo validation (reference ValidationHandler + ValidationSerializer). */
final class ValidationService {

    static final String DEFAULT_SUITE = "UML Standard Profile::Validation Profile::Active Validation::UML Correctness";
    private static final int DISCOVERY_LIMIT = 250;
    private static final int RESULT_STORE_LIMIT = 50;
    private static final int DEFAULT_PAGE = 500;
    private static final Map<String, JsonObject> RESULTS = Collections.synchronizedMap(
            new LinkedHashMap<String, JsonObject>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonObject> eldest) {
                    return size() > RESULT_STORE_LIMIT;
                }
            });

    private ValidationService() {
    }

    static JsonObject capabilities() {
        JsonObject response = Support.baseCapabilities("validation", "native");
        JsonObject probe = Support.classProbe(
                "com.nomagic.magicdraw.validation.ValidationHelper",
                "com.nomagic.magicdraw.validation.ValidationRunData",
                "com.nomagic.magicdraw.validation.RuleViolationResult");
        boolean all = probe.get("allFound").getAsBoolean();
        response.addProperty("available", all);
        response.addProperty("status", all ? "available" : "missing-class");
        response.add("classProbe", probe);
        JsonArray tools = new JsonArray();
        tools.add("cameo_get_validation_capabilities");
        tools.add("cameo_list_validation_suites");
        tools.add("cameo_run_native_validation");
        tools.add("cameo_run_validation");
        tools.add("cameo_get_validation_result");
        response.add("routes", tools);
        response.addProperty("resultStoreLimit", RESULT_STORE_LIMIT);
        response.addProperty("defaultSuite", DEFAULT_SUITE);
        return response;
    }

    static JsonObject suites() throws Exception {
        return Edt.read(project -> {
            JsonArray knownSuites = new JsonArray();
            addKnownSuite(project, knownSuites, DEFAULT_SUITE);
            addKnownSuite(project, knownSuites, "UML Standard Profile::Validation Profile::Active Validation");
            JsonArray candidateSuites = new JsonArray();
            JsonArray candidateConstraints = new JsonArray();
            Element root = project.getPrimaryModel();
            if (root != null) {
                Collection<? extends Element> packages = Finder.byTypeRecursively().find(root, new Class[]{Package.class});
                for (Element element : packages) {
                    if (candidateSuites.size() >= DISCOVERY_LIMIT) {
                        break;
                    }
                    if (element instanceof Package && looksValidationRelated(element)) {
                        candidateSuites.add(suite((Package) element));
                    }
                }
                Collection<? extends Element> constraints = Finder.byTypeRecursively().find(root, new Class[]{Constraint.class});
                for (Element element : constraints) {
                    if (candidateConstraints.size() >= DISCOVERY_LIMIT) {
                        break;
                    }
                    if (element instanceof Constraint && looksValidationRelated(element)) {
                        candidateConstraints.add(constraint((Constraint) element));
                    }
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("available", true);
            response.add("projectState", Support.projectState());
            response.add("knownSuites", knownSuites);
            response.add("candidateSuites", candidateSuites);
            response.add("candidateConstraints", candidateConstraints);
            response.addProperty("knownSuiteCount", knownSuites.size());
            response.addProperty("candidateSuiteCount", candidateSuites.size());
            response.addProperty("candidateConstraintCount", candidateConstraints.size());
            response.addProperty("discoveryLimit", DISCOVERY_LIMIT);
            response.addProperty("listingConfidence", knownSuites.size() > 0 ? "high" : "heuristic");
            return response;
        });
    }

    /** Options shared by both run tools, already normalized. */
    static final class RunOptions {
        String suiteId;
        String suiteQualifiedName;
        List<String> constraintIds = new ArrayList<>();
        List<String> scopeElementIds = new ArrayList<>();
        boolean wholeProject = true;
        Boolean recursive;
        boolean excludeReadOnly = true;
        String minimumSeverity;
        boolean openNativeWindow;
        String name;
        int maxFindings = DEFAULT_PAGE;
        long timeoutMs = 0;
        JsonObject request;
    }

    static JsonObject runNative(JsonObject p) throws Exception {
        RunOptions o = new RunOptions();
        o.suiteId = Gs.str(p, "suite_id");
        o.suiteQualifiedName = Gs.str(p, "suite_qualified_name");
        o.constraintIds = Gs.strList(p, "constraint_ids");
        o.scopeElementIds = Gs.strList(p, "scope_element_ids");
        o.wholeProject = Gs.bool(p, "whole_project", o.scopeElementIds.isEmpty());
        o.recursive = Gs.has(p, "recursive") ? Gs.bool(p, "recursive", true) : Boolean.TRUE;
        o.excludeReadOnly = Gs.bool(p, "exclude_read_only", true);
        o.minimumSeverity = Gs.str(p, "minimum_severity");
        o.openNativeWindow = Gs.bool(p, "open_native_window", false);
        o.name = Gs.str(p, "name");
        o.maxFindings = Math.max(0, Gs.integer(p, "max_findings", DEFAULT_PAGE));
        o.request = Support.request(p);
        return run(o);
    }

    static JsonObject runBounded(JsonObject p) throws Exception {
        RunOptions o = new RunOptions();
        o.suiteId = Gs.str(p, "suite_id");
        o.constraintIds = Gs.strList(p, "constraint_ids");
        o.scopeElementIds = Gs.strList(p, "scope_element_ids");
        String scopeMode = Gs.str(p, "scope_mode");
        if (scopeMode == null || scopeMode.trim().isEmpty()) {
            scopeMode = o.scopeElementIds.isEmpty() ? "project" : "elements";
        }
        scopeMode = scopeMode.trim().toLowerCase(Locale.ROOT);
        if (!scopeMode.equals("project") && !scopeMode.equals("elements")) {
            throw ToolException.invalid("scope_mode must be 'project' or 'elements' (got '" + scopeMode + "').");
        }
        if (scopeMode.equals("elements") && o.scopeElementIds.isEmpty()) {
            throw ToolException.invalid("scope_mode=elements needs scope_element_ids.");
        }
        o.wholeProject = scopeMode.equals("project");
        o.minimumSeverity = Gs.str(p, "min_severity");
        o.maxFindings = Math.max(0, Gs.integer(p, "max_findings", DEFAULT_PAGE));
        o.timeoutMs = Math.max(1000, Math.min(600_000, Gs.integer(p, "timeout_ms", 30000)));
        o.request = Support.request(p);
        JsonObject result = run(o);
        result.addProperty("scopeMode", scopeMode);
        return result;
    }

    private static JsonObject run(RunOptions o) throws Exception {
        if (o.timeoutMs <= 0) {
            return Edt.read(project -> execute(project, o));
        }
        Project project = Edt.project();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                future.complete(execute(project, o));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "cameo-mcp-validation");
        worker.setDaemon(true);
        worker.start();
        try {
            return future.get(o.timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ToolException("TIMEOUT", "Validation did not finish within " + o.timeoutMs + " ms. It may still be "
                    + "running in Cameo; narrow the scope (scope_mode=elements with scope_element_ids) or raise timeout_ms.");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            throw e;
        }
    }

    private static JsonObject execute(Project project, RunOptions o) {
        long started = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        String taskName = o.name != null && !o.name.trim().isEmpty() ? o.name.trim() : "MCP native validation";
        ValidationRunData runData = buildRunData(project, o, taskName);
        runData.setEnableSettingsDialog(false);
        if (o.recursive != null) {
            runData.setAddElementsRecursively(o.recursive);
        }
        Collection<RuleViolationResult> findings = ValidationHelper.validate(runData, taskName, null);
        if (o.openNativeWindow) {
            final Collection<RuleViolationResult> shown = findings;
            SwingUtilities.invokeLater(() -> {
                try {
                    ValidationHelper.openValidationWindow(runData, "MCP_" + runId, shown);
                } catch (Throwable ignored) {
                    // UI convenience only
                }
            });
        }
        JsonArray all = findings(findings);
        JsonObject stored = new JsonObject();
        stored.addProperty("runId", runId);
        stored.addProperty("startedAt", Instant.ofEpochMilli(started).toString());
        stored.addProperty("completedAt", Instant.now().toString());
        stored.addProperty("durationMillis", System.currentTimeMillis() - started);
        stored.addProperty("openNativeWindow", o.openNativeWindow);
        stored.addProperty("wholeProject", o.wholeProject);
        stored.add("projectState", Support.projectState());
        stored.add("request", o.request);
        stored.addProperty("findingCount", all.size());
        stored.add("countsBySeverity", countsBySeverity(all));
        stored.add("findings", all);
        RESULTS.put(runId, stored);
        return page(stored, 0, o.maxFindings);
    }

    static JsonObject result(JsonObject p) {
        String runId = Gs.reqStr(p, "run_id").trim();
        JsonObject stored = RESULTS.get(runId);
        if (stored == null) {
            throw new ToolException("NOT_FOUND", "No validation result cached for run_id '" + runId + "'. Only the last "
                    + RESULT_STORE_LIMIT + " runs since Cameo started are kept; run cameo_run_native_validation again.");
        }
        int offset = Math.max(0, Gs.integer(p, "offset", 0));
        int limit = Math.max(0, Gs.integer(p, "limit", DEFAULT_PAGE));
        return page(stored, offset, limit);
    }

    /** Copy of a stored result with one page of findings. */
    private static JsonObject page(JsonObject stored, int offset, int limit) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : stored.entrySet()) {
            if (!"findings".equals(e.getKey())) {
                out.add(e.getKey(), e.getValue());
            }
        }
        JsonArray all = stored.getAsJsonArray("findings");
        JsonArray pageArr = new JsonArray();
        for (int i = offset; i < all.size() && pageArr.size() < limit; i++) {
            pageArr.add(all.get(i));
        }
        out.add("findings", pageArr);
        out.addProperty("offset", offset);
        out.addProperty("returnedFindingCount", pageArr.size());
        boolean more = offset + pageArr.size() < all.size();
        out.addProperty("hasMore", more);
        if (more) {
            out.addProperty("nextOffset", offset + pageArr.size());
            out.addProperty("hint", "More findings are cached: call cameo_get_validation_result with run_id and offset="
                    + (offset + pageArr.size()) + ".");
        }
        return out;
    }

    private static JsonObject countsBySeverity(JsonArray findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement f : findings) {
            String sev = "unknown";
            JsonObject ann = Gs.object(f.getAsJsonObject(), "annotation");
            JsonObject s = ann == null ? null : Gs.object(ann, "severity");
            if (s != null && Gs.has(s, "name")) {
                sev = Gs.str(s, "name");
            }
            counts.merge(sev, 1, Integer::sum);
        }
        JsonObject o = new JsonObject();
        counts.forEach(o::addProperty);
        return o;
    }

    @SuppressWarnings("deprecation")
    private static ValidationRunData buildRunData(Project project, RunOptions o, String taskName) {
        EnumerationLiteral severity = severity(project, o.minimumSeverity);
        Collection<Element> scope = scopeElements(project, o.scopeElementIds);
        if (!o.constraintIds.isEmpty()) {
            List<Constraint> constraints = new ArrayList<>();
            for (String constraintId : o.constraintIds) {
                Element element = Support.element(project, constraintId);
                if (!(element instanceof Constraint)) {
                    throw new ToolException("NOT_FOUND", "Constraint '" + constraintId + "' not found. Use "
                            + "cameo_list_validation_suites to find constraint IDs.");
                }
                constraints.add((Constraint) element);
            }
            try {
                return new ValidationRunData(constraints, taskName, o.wholeProject, scope, severity, o.excludeReadOnly);
            } catch (LinkageError e) {
                return new ValidationRunData(constraints, taskName, scope, severity, o.excludeReadOnly);
            }
        }
        Element suiteElement;
        if (o.suiteId != null && !o.suiteId.trim().isEmpty()) {
            suiteElement = Support.element(project, o.suiteId);
        } else if (o.suiteQualifiedName != null && !o.suiteQualifiedName.trim().isEmpty()) {
            suiteElement = Finder.byQualifiedName().find(project, o.suiteQualifiedName.trim());
        } else {
            suiteElement = Finder.byQualifiedName().find(project, DEFAULT_SUITE);
        }
        if (!(suiteElement instanceof Package)) {
            throw ToolException.invalid("Validation suite package not found; provide suite_id (a Package id from "
                    + "cameo_list_validation_suites) or constraint_ids.");
        }
        try {
            return new ValidationRunData((Package) suiteElement, o.wholeProject, scope, severity, o.excludeReadOnly);
        } catch (LinkageError e) {
            return new ValidationRunData((Package) suiteElement, scope, severity, o.excludeReadOnly);
        }
    }

    private static Collection<Element> scopeElements(Project project, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.singletonList(com.cameomcp.plugin.cameo.ProjectKind.primaryModel(project));
        }
        List<Element> elements = new ArrayList<>();
        for (String id : ids) {
            elements.add(Support.requireElement(project, id, "Scope element"));
        }
        return elements;
    }

    private static EnumerationLiteral severity(Project project, String requested) {
        if (requested == null || requested.trim().isEmpty() || "any".equalsIgnoreCase(requested.trim())) {
            return null;
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        String constant;
        switch (normalized) {
            case "fatal":
                constant = Annotation.FATAL;
                break;
            case "warning":
            case "warn":
                constant = Annotation.WARNING;
                break;
            case "info":
                constant = Annotation.INFO;
                break;
            case "debug":
                constant = Annotation.DEBUG;
                break;
            case "error":
                constant = Annotation.ERROR;
                break;
            default:
                throw ToolException.invalid("Severity must be any, debug, info, warning, error or fatal (got '"
                        + requested + "').");
        }
        return Annotation.getSeverityLevel(project, constant);
    }

    private static void addKnownSuite(Project project, JsonArray array, String qualifiedName) {
        Element element = Finder.byQualifiedName().find(project, qualifiedName);
        if (element instanceof Package) {
            JsonObject suite = suite((Package) element);
            suite.addProperty("qualifiedNameProbe", qualifiedName);
            suite.addProperty("discoveryType", "knownQualifiedName");
            array.add(suite);
        }
    }

    private static boolean looksValidationRelated(Element element) {
        String text = element instanceof NamedElement ? ((NamedElement) element).getName() : "";
        if (text == null) {
            text = "";
        }
        Element owner = element.getOwner();
        if (owner instanceof NamedElement) {
            String ownerName = ((NamedElement) owner).getName();
            if (ownerName != null) {
                text += " " + ownerName;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("validation") || lower.contains("correctness") || lower.contains("constraint")
                || lower.contains("suite") || lower.contains("sysml") || lower.contains("requirement");
    }

    // ------------------------------------------------------------------ serialization (ValidationSerializer)

    static JsonObject suite(Package suite) {
        JsonObject json = Support.ref(suite);
        json.addProperty("suiteId", suite.getID());
        json.addProperty("suiteName", Support.safe(suite.getName()));
        json.addProperty("discoveryType", "package");
        return json;
    }

    static JsonObject constraint(Constraint constraint) {
        JsonObject json = Support.ref(constraint);
        json.addProperty("constraintId", constraint.getID());
        json.addProperty("constraintName", Support.safe(constraint.getName()));
        json.addProperty("discoveryType", "constraint");
        return json;
    }

    static JsonObject finding(RuleViolationResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("message", Support.safe(result.getErrorMessage()));
        json.addProperty("ignored", result.isIgnored());
        json.addProperty("systemValidationResult", result.isSystemValidationResult());
        Constraint rule = result.getRule();
        if (rule != null) {
            json.add("rule", constraint(rule));
        }
        Annotation annotation = result.getAnnotation();
        if (annotation != null) {
            JsonObject a = new JsonObject();
            a.addProperty("kind", Support.safe(annotation.getKind()));
            a.addProperty("text", Support.safe(annotation.getText()));
            a.addProperty("toolTipText", Support.safe(annotation.getToolTipText()));
            EnumerationLiteral severity = annotation.getSeverity();
            if (severity != null) {
                a.add("severity", Support.ref(severity));
            }
            json.add("annotation", a);
        }
        Object targetObject = result.getTargetObject();
        if (targetObject instanceof Element) {
            json.add("target", Support.ref((Element) targetObject));
        } else if (result.getElement() instanceof Element) {
            json.add("target", Support.ref((Element) result.getElement()));
        } else if (targetObject != null) {
            JsonObject target = new JsonObject();
            target.addProperty("className", targetObject.getClass().getName());
            target.addProperty("string", String.valueOf(targetObject));
            json.add("target", target);
        }
        Object parentObject = result.getParentObject();
        if (parentObject instanceof Element) {
            json.add("parent", Support.ref((Element) parentObject));
        }
        return json;
    }

    static JsonArray findings(Collection<RuleViolationResult> results) {
        JsonArray array = new JsonArray();
        if (results == null) {
            return array;
        }
        for (RuleViolationResult r : results) {
            if (r != null) {
                try {
                    array.add(finding(r));
                } catch (Throwable t) {
                    JsonObject bad = new JsonObject();
                    bad.addProperty("message", "Could not serialize finding: " + t);
                    array.add(bad);
                }
            }
        }
        return array;
    }
}
