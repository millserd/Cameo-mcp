package com.cameomcp.plugin.tools.sysml2;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.CameoLog;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ProjectKind;
import com.cameomcp.plugin.tools.OptionalModules;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SysML v2 tools, on Cameo 2026x's SysML v2 plugin: model roots come from
 * {@code KerMLProjectFeature}, text export from {@code SysMLTextGeneratorBuilder}, parsing from
 * {@code SysMLTransientModelBuilder} and import from {@code TextualModelHelper}, the same calls
 * Cameo's own "import SysML v2 text" uses. Everything is reflective so the plugin still loads on
 * 2024x, where these tools report UNSUPPORTED.
 */
final class Sysml2Service {

    static final String KERML_FEATURE = "com.dassault_systemes.modeler.kerml.esi.feature.KerMLProjectFeature";
    static final String MODEL_ELEMENT_PROJECT = "com.dassault_systemes.modeler.foundation.project.ModelElementProject";
    static final String MODEL_ELEMENT = "com.dassault_systemes.modeler.foundation.model.ModelElement";
    static final String TEXT_GENERATOR = "com.dassault_systemes.modeler.sysml.textual.textgenerator.SysMLTextGeneratorBuilder";
    static final String TRANSIENT_BUILDER = "com.dassault_systemes.modeler.sysml.textual.project.SysMLTransientModelBuilder";
    static final String TEXTUAL_MODEL_HELPER = "com.dassault_systemes.modeler.magic.sysml.core.TextualModelHelper";
    static final String EMPTY_PROGRESS = "com.nomagic.task.EmptyProgressStatus";
    static final String PROGRESS = "com.nomagic.task.ProgressStatus";

    private static final Map<String, Object> CLASSES = new ConcurrentHashMap<>();
    private static final Object ABSENT = new Object();

    private Sysml2Service() {
    }

    // ------------------------------------------------------------------ classes

    /** The class, or null when this Cameo does not have it. */
    static Class<?> optionalClass(String name) {
        Object c = CLASSES.get(name);
        if (c == null) {
            try {
                c = OptionalModules.cameoClass(name);
            } catch (ClassNotFoundException | LinkageError e) {
                c = ABSENT;
            }
            CLASSES.put(name, c);
        }
        return c == ABSENT ? null : (Class<?>) c;
    }

    static Class<?> requireClass(String name) {
        Class<?> c = optionalClass(name);
        if (c == null) {
            throw unsupported();
        }
        return c;
    }

    static ToolException unsupported() {
        return new ToolException("UNSUPPORTED", "SysML v2 needs Cameo 2026x or later with the SysML v2 plugin "
                + "enabled; this installation does not provide it.");
    }

    private static Project requireSysml2Project(Project project) {
        requireClass(Sysml2Model.KERML_ELEMENT);
        if (!ProjectKind.isSysml2(project)) {
            throw new ToolException("NOT_SYSML2_PROJECT", "The open project is a UML / SysML v1 project. The "
                    + "cameo_sysml2_* tools need a SysML v2 project (File > New Project > SysML v2 in Cameo 2026x).");
        }
        return project;
    }

    // ------------------------------------------------------------------ roots and lookup

    /** Root namespaces of the project itself, or of the project and every used library. */
    static List<Object> roots(Project project, boolean includeLibraries) {
        Class<?> feature = requireClass(KERML_FEATURE);
        Class<?> mep = requireClass(MODEL_ELEMENT_PROJECT);
        try {
            Object roots;
            if (includeLibraries) {
                roots = feature.getMethod("getAllRoots", mep).invoke(null, project);
            } else {
                Object primary = feature.getMethod("getPrimaryProjectFeature", mep).invoke(null, project);
                roots = primary == null ? null : Sysml2Model.call(primary, "getRoots");
            }
            List<Object> out = new ArrayList<>();
            if (roots instanceof Collection) {
                for (Object r : (Collection<?>) roots) {
                    if (Sysml2Model.isElement(r)) {
                        out.add(r);
                    }
                }
            }
            return out;
        } catch (InvocationTargetException e) {
            throw failure("Reading the SysML v2 roots", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw unsupported();
        }
    }

    static Object element(Project project, String id, String param) {
        if (id == null || id.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter '" + param + "'.");
        }
        String key = id.trim();
        Object found = project.getElementByID(key);
        if (Sysml2Model.isElement(found)) {
            return found;
        }
        found = Sysml2Model.findUnder(roots(project, false), key);
        if (found != null) {
            return found;
        }
        if (project.getElementByID(key) != null) {
            throw ToolException.invalid(param + " '" + key + "' is a UML element, not a SysML v2 element. Use the "
                    + "UML / SysML v1 tools for it.");
        }
        throw new ToolException("NOT_FOUND", "SysML v2 element '" + key + "' (" + param + ") not found. Use "
                + "cameo_sysml2_list_roots, cameo_sysml2_list_children or cameo_sysml2_query to find ids.");
    }

    // ------------------------------------------------------------------ read tools

    static JsonObject status() {
        JsonObject json = new JsonObject();
        boolean model = optionalClass(Sysml2Model.KERML_ELEMENT) != null && optionalClass(KERML_FEATURE) != null;
        json.addProperty("available", model);
        JsonObject ops = new JsonObject();
        ops.addProperty("modelAccess", model);
        ops.addProperty("textExport", model && optionalClass(TEXT_GENERATOR) != null);
        ops.addProperty("textValidation", model && optionalClass(TRANSIENT_BUILDER) != null);
        ops.addProperty("textImport", model && optionalClass(TRANSIENT_BUILDER) != null
                && optionalClass(TEXTUAL_MODEL_HELPER) != null);
        ops.addProperty("delete", model && optionalClass(MODEL_ELEMENT) != null);
        json.add("operations", ops);
        Project project = Application.getInstance().getProject();
        json.addProperty("projectOpen", project != null);
        if (project != null) {
            String kind = ProjectKind.of(project);
            json.addProperty("projectName", project.getName());
            json.addProperty("projectKind", kind);
            if (model && ProjectKind.SYSML2.equals(kind)) {
                try {
                    json.addProperty("rootCount", roots(project, false).size());
                } catch (ToolException e) {
                    json.addProperty("rootsError", e.getMessage());
                }
            }
        }
        if (!model) {
            json.addProperty("hint", "This Cameo has no SysML v2 support (it needs 2026x or later with the SysML v2 "
                    + "plugin). Use the UML / SysML v1 tools.");
        } else if (project != null && !ProjectKind.SYSML2.equals(ProjectKind.of(project))) {
            json.addProperty("hint", "SysML v2 is available, but the open project is UML / SysML v1. Open or create a "
                    + "SysML v2 project to use the cameo_sysml2_* tools.");
        }
        return json;
    }

    static JsonObject listRoots(boolean includeLibraries, int membersLimit) throws Exception {
        return Edt.onEdt("List SysML v2 roots", project -> {
            requireSysml2Project(project);
            JsonArray out = new JsonArray();
            for (Object root : roots(project, includeLibraries)) {
                JsonObject r = Sysml2Model.compact(root);
                JsonArray members = new JsonArray();
                List<Object> kids = Sysml2Model.children(root);
                for (int i = 0; i < kids.size() && i < membersLimit; i++) {
                    members.add(Sysml2Model.compact(kids.get(i)));
                }
                r.add("members", members);
                if (kids.size() > membersLimit) {
                    r.addProperty("membersTruncated", true);
                }
                out.add(r);
            }
            JsonObject json = new JsonObject();
            json.addProperty("count", out.size());
            json.add("roots", out);
            return json;
        });
    }

    static JsonObject listChildren(String id, int limit, int offset) throws Exception {
        return Edt.onEdt("List SysML v2 children", project -> {
            requireSysml2Project(project);
            Object parent = element(project, id, "element_id");
            List<Object> kids = Sysml2Model.children(parent);
            JsonArray out = new JsonArray();
            for (int i = offset; i < kids.size() && out.size() < limit; i++) {
                out.add(Sysml2Model.compact(kids.get(i)));
            }
            JsonObject json = new JsonObject();
            json.add("parent", Sysml2Model.compact(parent));
            json.addProperty("total", kids.size());
            json.addProperty("offset", offset);
            json.addProperty("count", out.size());
            boolean more = offset + out.size() < kids.size();
            json.addProperty("hasMore", more);
            if (more) {
                json.addProperty("nextOffset", offset + out.size());
            }
            json.add("children", out);
            return json;
        });
    }

    static JsonObject getElement(String id, boolean includeText, int membersLimit) throws Exception {
        return Edt.onEdt("Get SysML v2 element", project -> {
            requireSysml2Project(project);
            Object element = element(project, id, "element_id");
            JsonObject json = Sysml2Model.detail(element, membersLimit);
            if (includeText) {
                GeneratedText text = textOf(project, element);
                json.addProperty("text", text.text);
                if (text.problem != null) {
                    json.addProperty("textWarning", text.problem);
                }
            }
            return json;
        }, 120);
    }

    static JsonObject query(String name, String type, String scopeId, boolean includeLibraries, int limit,
                            int offset) throws Exception {
        return Edt.onEdt("Query SysML v2 model", project -> {
            requireSysml2Project(project);
            Collection<Object> start = scopeId == null || scopeId.trim().isEmpty()
                    ? roots(project, includeLibraries)
                    : Collections.singletonList(element(project, scopeId, "scope_id"));
            return Sysml2Model.query(start, name, type, includeLibraries, limit, offset);
        }, 120);
    }

    static JsonObject getText(String id, int maxChars) throws Exception {
        return Edt.onEdt("Export SysML v2 text", project -> {
            requireSysml2Project(project);
            requireClass(TEXT_GENERATOR);
            List<Object> targets = id == null || id.trim().isEmpty()
                    ? roots(project, false)
                    : Collections.singletonList(element(project, id, "element_id"));
            StringBuilder text = new StringBuilder();
            JsonArray warnings = new JsonArray();
            for (Object target : targets) {
                GeneratedText t = textOf(project, target);
                if (t.problem != null) {
                    warnings.add(Sysml2Model.name(target) + ": " + t.problem);
                }
                if (t.text != null && !t.text.trim().isEmpty()) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append(t.text.trim()).append("\n");
                }
            }
            JsonObject json = new JsonObject();
            json.addProperty("elementCount", targets.size());
            boolean truncated = text.length() > maxChars;
            json.addProperty("length", text.length());
            json.addProperty("text", truncated ? text.substring(0, maxChars) : text.toString());
            json.addProperty("truncated", truncated);
            if (warnings.size() > 0) {
                json.add("warnings", warnings);
            }
            return json;
        }, 180);
    }

    /** Generated text plus a problem description when the generator timed out or reported an error. */
    static final class GeneratedText {
        final String text;
        final String problem;
        final Object result;

        GeneratedText(String text, String problem, Object result) {
            this.text = text;
            this.problem = problem;
            this.result = result;
        }
    }

    static final String RANGE_TYPE = "com.dassault_systemes.modeler.sysml.textual.core.ElementsRanges$RangeType";

    /**
     * Text for any element. Cameo 2026x generates text only from a root namespace (for a nested
     * element it reports the element as untransformed), so a nested element's text is cut out of
     * its root namespace's text using the element range the generator records.
     */
    static GeneratedText textOf(Project project, Object element) {
        Object root = element;
        for (Object o = Sysml2Model.owner(element); o != null && Sysml2Model.isElement(o); o = Sysml2Model.owner(o)) {
            root = o;
        }
        if (root == element) {
            return generate(project, element);
        }
        GeneratedText whole = generate(project, root);
        Object range = null;
        try {
            Object ranges = Sysml2Model.quiet(whole.result, "getElementsRanges");
            Class<?> rangeType = optionalClass(RANGE_TYPE);
            if (ranges != null && rangeType != null) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object full = Enum.valueOf((Class<? extends Enum>) rangeType.asSubclass(Enum.class), "FULL");
                range = ranges.getClass().getMethod("getRange", requireClass(Sysml2Model.KERML_ELEMENT), rangeType)
                        .invoke(ranges, element, full);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            range = null;
        }
        Object start = Sysml2Model.quiet(range, "getStartIndex");
        Object end = Sysml2Model.quiet(range, "getEndIndex");
        if (start instanceof Integer && end instanceof Integer) {
            String slice = Sysml2Model.slice(whole.text, (Integer) start, (Integer) end);
            if (!slice.isEmpty()) {
                return new GeneratedText(slice, null, whole.result);
            }
        }
        String problem = "Cameo gave no text range for this element, so the text of its whole root namespace is "
                + "returned" + (whole.problem != null ? "; " + whole.problem : "");
        return new GeneratedText(whole.text, problem, whole.result);
    }

    static GeneratedText generate(Project project, Object element) {
        Class<?> builderType = requireClass(TEXT_GENERATOR);
        Class<?> elementType = requireClass(Sysml2Model.KERML_ELEMENT);
        try {
            Object builder = builderType.getConstructor().newInstance();
            builderType.getMethod("setRootElement", elementType).invoke(builder, element);
            Method setContext = contextSetter(builderType);
            Method factory = setContext == null ? null : contextFactory(setContext.getParameterTypes()[0]);
            if (factory != null) {
                setContext.invoke(builder, factory.invoke(null, project));
            }
            Object result = builderType.getMethod("build").invoke(builder);
            Object text = Sysml2Model.call(result, "getText");
            String problem = null;
            if (Boolean.TRUE.equals(Sysml2Model.quiet(result, "hasTimedOut"))) {
                problem = "text generation timed out; the text may be incomplete";
            }
            Object error = Sysml2Model.quiet(result, "getError");
            if (error != null && Boolean.TRUE.equals(Sysml2Model.quiet(error, "isError"))) {
                problem = (problem == null ? "" : problem + "; ") + "generator reported: " + error;
            }
            return new GeneratedText(text == null ? "" : text.toString(), problem, result);
        } catch (InvocationTargetException e) {
            throw failure("SysML v2 text generation", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw unsupported();
        }
    }

    private static volatile Method contextFactory;
    private static volatile boolean contextFactorySearched;

    private static Method contextSetter(Class<?> builderType) {
        for (Method m : builderType.getMethods()) {
            if ("setContext".equals(m.getName()) && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    /**
     * The generator needs a per-project context (without it, 2026x fails with a NullPointerException).
     * Cameo builds it with a static factory taking the project, in an obfuscated class whose name may
     * change between releases, so the factory is found by signature: first in the class 2026x HF2
     * uses, then anywhere in the context type's package.
     */
    static Method contextFactory(Class<?> contextType) {
        if (contextFactorySearched) {
            return contextFactory;
        }
        synchronized (Sysml2Service.class) {
            if (!contextFactorySearched) {
                Class<?> mep = requireClass(MODEL_ELEMENT_PROJECT);
                ClassLoader cl = contextType.getClassLoader();
                String pkg = contextType.getPackage() != null ? contextType.getPackage().getName()
                        : contextType.getName().substring(0, contextType.getName().lastIndexOf('.'));
                Method found = factoryIn(pkg + ".n", cl, contextType, mep);
                if (found == null) {
                    for (String name : classesInPackage(contextType, pkg)) {
                        found = factoryIn(name, cl, contextType, mep);
                        if (found != null) {
                            break;
                        }
                    }
                }
                if (found == null) {
                    CameoLog.warn("SysML v2 text generator context factory not found; text export may fail.");
                }
                contextFactory = found;
                contextFactorySearched = true;
            }
        }
        return contextFactory;
    }

    private static Method factoryIn(String className, ClassLoader cl, Class<?> contextType, Class<?> mep) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == contextType
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == mep) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            // not here
        }
        return null;
    }

    private static List<String> classesInPackage(Class<?> member, String pkg) {
        List<String> out = new ArrayList<>();
        try {
            java.net.URL location = member.getProtectionDomain().getCodeSource().getLocation();
            java.io.File file = new java.io.File(location.toURI());
            if (!file.isFile()) {
                return out;
            }
            String prefix = pkg.replace('.', '/') + "/";
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName();
                    if (n.startsWith(prefix) && n.endsWith(".class") && n.indexOf('/', prefix.length()) < 0) {
                        out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // no jar to scan
        }
        return out;
    }

    // ------------------------------------------------------------------ text compile and import

    /** A parsed text: Cameo's transient model plus its diagnostics. */
    static final class Compiled {
        final Object transientRoot;
        final JsonArray diagnostics = new JsonArray();
        int errors;
        int warnings;

        Compiled(Object transientRoot) {
            this.transientRoot = transientRoot;
        }
    }

    static Compiled compile(Project project, String text) {
        Class<?> builderType = requireClass(TRANSIENT_BUILDER);
        Class<?> mep = requireClass(MODEL_ELEMENT_PROJECT);
        try {
            Object builder = builderType.getConstructor(mep).newInstance(project);
            Object unit = builderType.getMethod("build", String.class, Collection.class)
                    .invoke(builder, text, roots(project, true));
            Compiled out = new Compiled(Sysml2Model.call(unit, "getTransientRootNs"));
            Object diagnostics = Sysml2Model.call(unit, "getDiagnostics");
            if (diagnostics instanceof Collection) {
                for (Object d : (Collection<?>) diagnostics) {
                    JsonObject j = new JsonObject();
                    Object sev = Sysml2Model.quiet(d, "getSeverity");
                    String severity = sev == null ? "ERROR" : sev.toString().toUpperCase(Locale.ROOT);
                    j.addProperty("severity", severity);
                    j.add("line", num(Sysml2Model.quiet(d, "getLine")));
                    j.add("column", num(Sysml2Model.quiet(d, "getColumn")));
                    j.add("size", num(Sysml2Model.quiet(d, "getSize")));
                    j.addProperty("message", String.valueOf(Sysml2Model.quiet(d, "getMessage")));
                    out.diagnostics.add(j);
                    if ("ERROR".equals(severity)) {
                        out.errors++;
                    } else if ("WARNING".equals(severity)) {
                        out.warnings++;
                    }
                }
            }
            return out;
        } catch (InvocationTargetException e) {
            throw failure("Compiling the SysML v2 text", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw unsupported();
        }
    }

    private static com.google.gson.JsonElement num(Object v) {
        return v instanceof Number ? new com.google.gson.JsonPrimitive((Number) v) : com.google.gson.JsonNull.INSTANCE;
    }

    static JsonObject validateText(String text) throws Exception {
        return Edt.onEdt("Validate SysML v2 text", project -> {
            requireSysml2Project(project);
            Compiled c = compile(project, text);
            JsonObject json = new JsonObject();
            json.addProperty("valid", c.errors == 0);
            json.addProperty("errorCount", c.errors);
            json.addProperty("warningCount", c.warnings);
            json.add("diagnostics", c.diagnostics);
            JsonArray declared = new JsonArray();
            if (c.transientRoot != null) {
                for (Object e : Sysml2Model.children(c.transientRoot)) {
                    JsonObject d = new JsonObject();
                    d.addProperty("name", Sysml2Model.name(e));
                    d.addProperty("type", Sysml2Model.type(e));
                    d.addProperty("elementCount", Sysml2Model.count(e));
                    declared.add(d);
                }
            }
            json.add("declares", declared);
            return json;
        }, 180);
    }

    static JsonObject createFromText(String text, boolean allowWarnings) throws Exception {
        return Edt.exclusive("Create SysML v2 from text", project -> {
            requireSysml2Project(project);
            Class<?> helper = requireClass(TEXTUAL_MODEL_HELPER);
            Class<?> elementType = requireClass(Sysml2Model.KERML_ELEMENT);
            Class<?> progressType = requireClass(PROGRESS);
            Compiled c = compile(project, text);
            if (c.errors > 0 || (!allowWarnings && c.warnings > 0) || c.transientRoot == null) {
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("errorCount", c.errors);
                details.put("warningCount", c.warnings);
                details.put("diagnostics", com.cameomcp.plugin.cameo.Gs.fromGson(c.diagnostics));
                throw new ToolException("INVALID_PARAMS", "The SysML v2 text does not compile ("
                        + c.errors + " errors, " + c.warnings + " warnings); nothing was changed. "
                        + firstMessage(c), details);
            }
            List<Object> before = roots(project, false);
            Map<Object, Boolean> existing = new IdentityHashMap<>();
            for (Object r : before) {
                existing.put(r, Boolean.TRUE);
            }
            Object progress = requireClass(EMPTY_PROGRESS).getMethod("getDefault").invoke(null);
            try {
                helper.getMethod("textualModelIntoProject", Project.class, elementType, progressType)
                        .invoke(null, project, c.transientRoot, progress);
            } catch (InvocationTargetException e) {
                throw failure("Adding the SysML v2 text to the project", e.getCause());
            }
            JsonArray created = new JsonArray();
            JsonArray newRoots = new JsonArray();
            int count = 0;
            for (Object r : roots(project, false)) {
                if (!existing.containsKey(r)) {
                    newRoots.add(Sysml2Model.compact(r));
                    count += Sysml2Model.count(r);
                    for (Object member : Sysml2Model.children(r)) {
                        created.add(Sysml2Model.compact(member));
                    }
                }
            }
            CameoLog.info("SysML v2 text added to the project: " + created.size() + " top-level elements, "
                    + count + " elements in all.");
            JsonObject json = new JsonObject();
            json.addProperty("created", newRoots.size() > 0);
            if (newRoots.size() == 1) {
                json.addProperty("rootId", newRoots.get(0).getAsJsonObject().get("id").getAsString());
            }
            json.add("roots", newRoots);
            json.add("elements", created);
            json.addProperty("elementCount", count);
            json.addProperty("warningCount", c.warnings);
            if (c.warnings > 0) {
                json.add("diagnostics", c.diagnostics);
            }
            if (newRoots.size() == 0) {
                json.addProperty("hint", "Cameo reported no new root namespace. Check the containment tree and the "
                        + "Notification window; cameo_sysml2_query can find the new elements by name.");
            }
            return json;
        }, 300);
    }

    private static String firstMessage(Compiled c) {
        for (int i = 0; i < c.diagnostics.size(); i++) {
            JsonObject d = c.diagnostics.get(i).getAsJsonObject();
            if ("ERROR".equals(d.get("severity").getAsString())) {
                return "First error at line " + d.get("line") + ", column " + d.get("column") + ": "
                        + d.get("message").getAsString();
            }
        }
        return c.transientRoot == null ? "The compiler produced no model." : "";
    }

    // ------------------------------------------------------------------ edits

    static JsonObject rename(String id, String name) throws Exception {
        if (name == null || name.trim().isEmpty()) {
            throw ToolException.invalid("name must not be empty.");
        }
        return Edt.write("Rename SysML v2 element", project -> {
            requireSysml2Project(project);
            Object element = element(project, id, "element_id");
            requireEditable(element);
            String old = Sysml2Model.name(element);
            Sysml2Model.callWith(element, "setDeclaredName", new Class<?>[]{String.class}, name.trim());
            JsonObject json = Sysml2Model.compact(element);
            json.addProperty("previousName", old);
            return json;
        });
    }

    static JsonObject delete(String id) throws Exception {
        return Edt.write("Delete SysML v2 element", project -> {
            requireSysml2Project(project);
            Object element = element(project, id, "element_id");
            requireEditable(element);
            Class<?> modelElement = requireClass(MODEL_ELEMENT);
            JsonObject json = new JsonObject();
            json.add("deleted", Sysml2Model.compact(element));
            json.addProperty("elementCount", Sysml2Model.count(element));
            try {
                ModelElementsManager.class.getMethod("removeElement", modelElement)
                        .invoke(ModelElementsManager.getInstance(), element);
            } catch (InvocationTargetException e) {
                throw failure("Deleting the SysML v2 element", e.getCause());
            } catch (NoSuchMethodException e) {
                throw unsupported();
            }
            return json;
        });
    }

    private static void requireEditable(Object element) {
        if (Sysml2Model.isLibrary(element)) {
            throw new ToolException("READ_ONLY", "'" + Sysml2Model.name(element) + "' is a standard library element "
                    + "and cannot be changed.");
        }
        if (Boolean.FALSE.equals(Sysml2Model.quiet(element, "isEditable"))) {
            throw new ToolException("READ_ONLY", "'" + Sysml2Model.name(element) + "' is read-only (locked, in a "
                    + "used project, or not editable).");
        }
    }

    private static ToolException failure(String what, Throwable cause) {
        Throwable c = cause;
        while (c instanceof InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof ToolException) {
            return (ToolException) c;
        }
        CameoLog.error(what + " failed", c);
        return new ToolException("TOOL_FAILED", what + " failed: " + (c == null ? "unknown error"
                : c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "")));
    }
}
