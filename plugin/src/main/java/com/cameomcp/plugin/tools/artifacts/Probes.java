package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.CameoInfo;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.OptionalModules;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** cameo_list_probe_templates / cameo_execute_probe: controlled, read-only API discovery. */
public final class Probes {

    /** Package prefixes a reflection probe may touch. */
    static final String[] ALLOWED_PREFIXES = {
            "com.nomagic.magicdraw.visualization.relationshipmap.",
            "com.nomagic.magicdraw.uml.symbols.",
            "com.nomagic.magicdraw.core.",
            "com.nomagic.magicdraw.validation.",
            "com.nomagic.magicdraw.magicreport.",
            "com.nomagic.magicreport.",
            "com.nomagic.requirements.",
            "com.nomagic.magicdraw.esi.",
            "com.nomagic.magicdraw.simulation.",
            "com.nomagic.uml2.ext.magicdraw.",
    };

    // {key, description}. "relationMap.*" templates are executed by the optional Relation Map module.
    private static final String[][] TEMPLATES = {
            {"relationMap.listGraphSettingsMethods", "List public GraphSettings methods"},
            {"relationMap.dumpCriteriaClasses", "Dump GraphSettings criteria value classes"},
            {"relationMap.dumpPresentationClasses", "List presentation classes on Relation Maps"},
            {"diagram.dumpSelectedPresentationMethods", "List PresentationElement methods"},
            {"ui.dumpSelectionApi", "List likely UI selection APIs by reflection"},
    };

    private Probes() {
    }

    /** cameo_list_probe_templates: the built-in templates, all read-only. */
    public static JsonObject templates() {
        JsonArray templates = new JsonArray();
        for (String[] t : TEMPLATES) {
            JsonObject o = new JsonObject();
            o.addProperty("key", t[0]);
            o.addProperty("description", t[1]);
            o.addProperty("mode", "read");
            templates.add(o);
        }
        JsonObject response = new JsonObject();
        response.addProperty("count", templates.size());
        response.add("templates", templates);
        return response;
    }

    /** Non-read probe modes need either of these JVM system properties set to true at Cameo start-up. */
    static boolean writeProbesEnabled() {
        return Boolean.getBoolean("cameo.mcp.probes.write.enabled")
                || Boolean.getBoolean("cameo.bridge.probes.write.enabled");
    }

    /**
     * cameo_execute_probe: runs a named template or a restricted javaReflection operation. Scripts in
     * other languages are refused (not executed); the result says so and points to cameo_execute_macro.
     */
    public static JsonObject execute(JsonObject p) throws Exception {
        String mode = ArtifactSupport.optStr(p, "mode");
        if (mode == null) {
            mode = "read";
        }
        // Every operation below only reads; a non-read mode is just refused unless the gate is open.
        if (!"read".equalsIgnoreCase(mode) && !writeProbesEnabled()) {
            throw new ToolException("PRECONDITION_FAILED", "Write probes are disabled. Start Cameo with "
                    + "-Dcameo.mcp.probes.write.enabled=true to allow mode='" + mode + "', or use mode='read'.");
        }
        boolean requiresProject = Gs.bool(p, "requires_project", true);
        if (requiresProject && !CameoInfo.hasProject()) {
            throw ToolException.noProject();
        }
        String template = ArtifactSupport.optStr(p, "template");
        JsonObject result;
        if (template == null) {
            String language = ArtifactSupport.optStr(p, "language");
            if (language == null) {
                language = "javaReflection";
            }
            if ("javaReflection".equalsIgnoreCase(language)) {
                result = reflectionProbe(p);
            } else {
                String script = Gs.str(p, "script");
                result = new JsonObject();
                result.addProperty("executed", false);
                result.addProperty("refused", true);
                result.addProperty("reason", "Arbitrary script execution is intentionally disabled for probes; use a "
                        + "built-in probe template or language=javaReflection with operation=listMethods, "
                        + "invokeStaticNoArg or invokeGraphSettingsGetter. To run scripts use cameo_execute_macro.");
                result.addProperty("scriptLength", script != null ? script.length() : 0);
            }
        } else {
            result = executeTemplate(p, template);
        }
        String description = Gs.str(p, "description");
        if (description != null) {
            result.addProperty("description", description);
        }
        return result;
    }

    /**
     * javaReflection operations: listMethods and invokeStaticNoArg on allow-listed classes, or a
     * GraphSettings getter on a Relation Map (delegated to the optional module).
     */
    private static JsonObject reflectionProbe(JsonObject p) throws Exception {
        String operation = ArtifactSupport.optStr(p, "operation");
        if (operation == null) {
            operation = "listMethods";
        }
        JsonObject response = new JsonObject();
        response.addProperty("language", "javaReflection");
        response.addProperty("operation", operation);
        response.addProperty("executed", true);
        switch (operation) {
            case "listMethods": {
                Class<?> cls = safeProbeClass(Gs.reqStr(p, "class_name").trim());
                response.addProperty("className", cls.getName());
                response.add("methods", PropertyJson.methods(cls));
                return response;
            }
            case "invokeGraphSettingsGetter": {
                JsonObject r = OptionalModules.call(ArtifactSupport.RELATION_MAP_SERVICE, "invokeGraphSettingsGetter",
                        Gs.obj("relation_map_id", Gs.reqStr(p, "relation_map_id").trim(),
                                "method_name", Gs.reqStr(p, "method_name").trim()));
                // Merge the optional module's result into this response.
                for (String key : r.keySet()) {
                    response.add(key, r.get(key));
                }
                return response;
            }
            case "invokeStaticNoArg": {
                Class<?> cls = safeProbeClass(Gs.reqStr(p, "class_name").trim());
                String methodName = Gs.reqStr(p, "method_name").trim();
                Method method;
                try {
                    method = cls.getMethod(methodName);
                } catch (NoSuchMethodException e) {
                    throw ToolException.invalid("No public no-arg method " + methodName + " on " + cls.getName()
                            + ". Use operation=listMethods to see the available methods.");
                }
                // Restricting to static no-arg methods with a return value keeps the probe to simple lookups.
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() == Void.TYPE) {
                    throw ToolException.invalid("Only public static no-arg methods with return values are allowed.");
                }
                Object value = method.invoke(null);
                response.addProperty("className", cls.getName());
                response.addProperty("methodName", methodName);
                // includeRaw=true so unknown result types also dump their getter values.
                response.add("value", PropertyJson.serializeValue(value, true, true));
                return response;
            }
            default:
                throw ToolException.invalid("Unsupported javaReflection operation: " + operation
                        + ". Use listMethods, invokeStaticNoArg or invokeGraphSettingsGetter.");
        }
    }

    /** True when className starts with one of ALLOWED_PREFIXES. */
    static boolean allowedClassName(String className) {
        for (String prefix : ALLOWED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads an allow-listed class, trying this plugin's loader, then the thread context loader, then
     * the optional-module loader; NOT_FOUND when none has it (e.g. the owning Cameo plugin is absent).
     */
    static Class<?> safeProbeClass(String className) {
        if (!allowedClassName(className)) {
            throw ToolException.invalid("Class is outside the allowed Cameo probe package prefixes: " + className
                    + ". Allowed: " + String.join(", ", ALLOWED_PREFIXES));
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError first) {
            // Classes of optional Cameo plugins are visible through the optional-module loader.
            for (ClassLoader cl : new ClassLoader[]{Thread.currentThread().getContextClassLoader(), optionalLoader()}) {
                if (cl == null) {
                    continue;
                }
                try {
                    return Class.forName(className, false, cl);
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // next
                }
            }
            throw new ToolException("NOT_FOUND", "Class " + className + " is not available in this Cameo "
                    + "installation.");
        }
    }

    /** Class loader of the optional jar (which also sees other Cameo plugins), or null when it cannot load. */
    private static ClassLoader optionalLoader() {
        try {
            return OptionalModules.service(ArtifactSupport.RELATION_MAP_SERVICE).getClass().getClassLoader();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Runs a built-in template; non-Relation-Map templates only list public methods of core Cameo classes. */
    private static JsonObject executeTemplate(JsonObject p, String template) throws Exception {
        if (template.startsWith("relationMap.")) {
            return OptionalModules.call(ArtifactSupport.RELATION_MAP_SERVICE, "probeTemplate",
                    Gs.obj("template", template));
        }
        JsonObject response = new JsonObject();
        response.addProperty("template", template);
        response.addProperty("executed", true);
        if ("diagram.dumpSelectedPresentationMethods".equals(template)) {
            response.add("methods", PropertyJson.methods(PresentationElement.class));
        } else if ("ui.dumpSelectionApi".equals(template)) {
            JsonArray classes = new JsonArray();
            classes.add(Application.class.getName());
            classes.add(Project.class.getName());
            classes.add(DiagramPresentationElement.class.getName());
            response.add("candidateClasses", classes);
            response.add("applicationMethods", PropertyJson.methods(Application.class));
            response.add("projectMethods", PropertyJson.methods(Project.class));
            response.add("diagramPresentationMethods", PropertyJson.methods(DiagramPresentationElement.class));
        } else {
            throw ToolException.invalid("Unknown probe template: " + template + ". Use cameo_list_probe_templates.");
        }
        return response;
    }
}
