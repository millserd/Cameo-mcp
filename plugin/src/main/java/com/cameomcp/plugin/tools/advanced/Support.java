package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.CameoInfo;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonNull;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonPrimitive;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared diagnostics for optional CATIA Magic / Cameo product integrations
 * (port of the reference bridge's OptionalCapabilitySupport plus helpers).
 */
final class Support {

    /** Static helpers only. */
    private Support() {
    }

    // ------------------------------------------------------------------ envelopes

    /**
     * Common head of every capability answer: family name, mode label and a snapshot of the open project.
     *
     * @param family tool family name, such as "simulation"
     * @param mode mode label, such as "read-only" or "unsupported"
     * @return a new object with family, mode and projectState
     */
    static JsonObject baseCapabilities(String family, String mode) {
        JsonObject json = new JsonObject();
        json.addProperty("family", family);
        json.addProperty("mode", mode);
        json.add("projectState", projectState());
        return json;
    }

    /**
     * The active project, or null when no project is open or the Application singleton is unavailable (for
     * example outside Cameo in unit tests). Never throws.
     *
     * @return the open project, or null
     */
    static Project currentProject() {
        try {
            return Application.getInstance().getProject();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Open, name, file, remote and dirty flags for the active project. Each getter is guarded so a capability
     * probe never fails on them.
     *
     * @return a new object; "open" is false when no project is open
     */
    static JsonObject projectState() {
        JsonObject state = new JsonObject();
        Project project = currentProject();
        state.addProperty("open", project != null);
        if (project != null) {
            try {
                state.addProperty("name", safe(project.getName()));
                state.addProperty("filePath", safe(project.getFileName()));
                state.addProperty("remote", project.isRemote());
                state.addProperty("dirty", project.isDirty());
            } catch (Throwable t) {
                state.addProperty("warning", "Could not read project state: " + t);
            }
        }
        return state;
    }

    /**
     * Capability answer for a family that cannot run here, with the reason and what the caller should do
     * next.
     *
     * @param family tool family name
     * @param reason why the family is unavailable
     * @param nextAction what the agent should do instead
     * @return a capability object with available=false and status "unsupported"
     */
    static JsonObject unsupported(String family, String reason, String nextAction) {
        JsonObject json = baseCapabilities(family, "unsupported");
        json.addProperty("available", false);
        json.addProperty("status", "unsupported");
        json.addProperty("reason", reason);
        json.addProperty("nextAction", nextAction);
        return json;
    }

    /**
     * Standard envelope for preview-only tools: echoes the request and states that nothing was written.
     *
     * @param family tool family name
     * @param request the cleaned request to echo, or null for an empty object
     * @param message human-readable explanation
     * @return an object with preview=true and writePerformed=false
     */
    static JsonObject previewAccepted(String family, JsonObject request, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("family", family);
        json.addProperty("preview", true);
        json.addProperty("writePerformed", false);
        json.addProperty("message", message);
        json.add("request", request == null ? new JsonObject() : request);
        json.add("projectState", projectState());
        return json;
    }

    /**
     * Refuse a gated operation: the refusal payload travels as the error's details so the
     * agent sees the same contract the reference bridge returned with its non-2xx status.
     *
     * @param code error code, such as PRECONDITION_FAILED or UNSUPPORTED
     * @param payload refusal body; its "reason" (or "message") and "nextAction" build the error message
     * @return the exception to throw; its details are the payload as plain Java maps
     */
    @SuppressWarnings("unchecked")
    static ToolException refusal(String code, JsonObject payload) {
        String reason = Gs.str(payload, "reason", Gs.str(payload, "message", "Operation refused."));
        String next = Gs.str(payload, "nextAction");
        String message = next == null ? reason : reason + " Next: " + next;
        Object details = Gs.fromTree(payload);
        return new ToolException(code, message, details instanceof Map ? (Map<String, Object>) details : null);
    }

    /**
     * Arguments as received, minus JSON nulls, for "request" echoes.
     *
     * @param params tool arguments, or null
     * @return a new object without JSON-null members (shares the member values with params)
     */
    static JsonObject request(JsonObject params) {
        JsonObject out = new JsonObject();
        if (params != null) {
            for (Map.Entry<String, JsonElement> e : params.entrySet()) {
                if (e.getValue() != null && !e.getValue().isJsonNull()) {
                    out.add(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /**
     * Null-to-empty for strings that go into JSON properties or messages.
     *
     * @param value any string, or null
     * @return value, or "" when null
     */
    static String safe(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ class probes

    /**
     * Tries to load each class (without initializing it) and reports found and missing names plus the jar
     * each came from. Optional products are detected this way rather than by version number, since their
     * presence varies by install.
     *
     * @param classNames fully qualified class names to look for
     * @return an object with found, missing and per-class details arrays
     */
    static JsonObject classProbe(String... classNames) {
        JsonObject probe = new JsonObject();
        JsonArray found = new JsonArray();
        JsonArray missing = new JsonArray();
        JsonArray details = new JsonArray();
        for (String className : classNames) {
            JsonObject detail = new JsonObject();
            detail.addProperty("className", className);
            try {
                Class<?> cls = loadClass(className);
                detail.addProperty("found", true);
                detail.addProperty("loadedName", cls.getName());
                detail.addProperty("codeSource", codeSource(cls));
                found.add(className);
            } catch (ClassNotFoundException | LinkageError | SecurityException e) {
                detail.addProperty("found", false);
                detail.addProperty("errorType", e.getClass().getName());
                detail.addProperty("message", safe(e.getMessage()));
                missing.add(className);
            }
            details.add(detail);
        }
        probe.addProperty("allFound", missing.size() == 0);
        probe.add("classesFound", found);
        probe.add("classesMissing", missing);
        probe.add("classes", details);
        return probe;
    }

    /**
     * Boolean form of {@link #classProbe} for a single class.
     *
     * @param className fully qualified class name
     * @return true when any known class loader can load it
     */
    static boolean classAvailable(String className) {
        try {
            loadClass(className);
            return true;
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return false;
        }
    }

    /**
     * Load a class from this plugin, Cameo core, or any loaded Cameo plugin (optional products live there).
     *
     * <p>Tries the context, plugin, Cameo core and system loaders, then every loaded Cameo plugin's loader.
     * The class is not initialized.
     *
     * @param className fully qualified class name
     * @return the loaded class
     * @throws ClassNotFoundException if no loader finds it; the last loader's failure is attached as
     *     suppressed
     */
    static Class<?> loadClass(String className) throws ClassNotFoundException {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(Support.class.getClassLoader());
        try {
            loaders.add(Application.class.getClassLoader());
        } catch (Throwable ignored) {
            // not inside Cameo
        }
        loaders.add(ClassLoader.getSystemClassLoader());
        // Optional products (simulation, report wizard, ...) ship as plugins with their own class loaders,
        // so a class missing from our loader may still be reachable through one of theirs.
        loaders.addAll(pluginClassLoaders());
        ClassNotFoundException last = null;
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        ClassNotFoundException e = new ClassNotFoundException(className);
        if (last != null) {
            e.addSuppressed(last);
        }
        throw e;
    }

    /**
     * Class loaders of every loaded Cameo plugin. PluginUtils is called reflectively so this compiles and
     * runs without it; any failure yields an empty list.
     *
     * @return plugin class loaders, possibly empty
     */
    private static List<ClassLoader> pluginClassLoaders() {
        List<ClassLoader> out = new ArrayList<>();
        try {
            Class<?> utils = Class.forName("com.nomagic.magicdraw.plugins.PluginUtils");
            Object plugins = utils.getMethod("getPlugins").invoke(null);
            if (plugins instanceof Iterable) {
                for (Object p : (Iterable<?>) plugins) {
                    if (p != null) {
                        ClassLoader cl = p.getClass().getClassLoader();
                        if (cl != null && !out.contains(cl)) {
                            out.add(cl);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // not inside Cameo
        }
        return out;
    }

    /**
     * Jar or folder the class was loaded from.
     *
     * @param cls a loaded class
     * @return the code source location, or "" when the JVM does not expose it
     */
    private static String codeSource(Class<?> cls) {
        try {
            CodeSource source = cls.getProtectionDomain().getCodeSource();
            if (source == null) {
                return "";
            }
            URL location = source.getLocation();
            return location != null ? location.toString() : "";
        } catch (SecurityException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ plugin folders

    /**
     * Plugin folders under {@code <install>/plugins} whose name contains any hint, as {name, path} objects.
     *
     * @param names case-insensitive substrings to match against folder names
     * @return one {name, path} object per matching folder
     */
    static JsonArray pluginDirectories(String... names) {
        JsonArray array = new JsonArray();
        for (File child : pluginFolders()) {
            String lower = child.getName().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (lower.contains(name.toLowerCase(Locale.ROOT))) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", child.getName());
                    entry.addProperty("path", child.getAbsolutePath());
                    array.add(entry);
                    break;
                }
            }
        }
        return array;
    }

    /**
     * Same as {@link #pluginDirectories} but as plain absolute paths (feature probe contract).
     *
     * @param hints case-insensitive substrings to match against folder names
     * @return absolute paths of matching folders; empty when hints is empty
     */
    static JsonArray pluginDirectoryPaths(Collection<String> hints) {
        JsonArray matches = new JsonArray();
        if (hints.isEmpty()) {
            return matches;
        }
        for (File child : pluginFolders()) {
            String name = child.getName().toLowerCase(Locale.ROOT);
            for (String hint : hints) {
                if (name.contains(hint.toLowerCase(Locale.ROOT))) {
                    matches.add(child.getAbsolutePath());
                    break;
                }
            }
        }
        return matches;
    }

    /**
     * Immediate subdirectories of {@code <install>/plugins}.
     *
     * @return plugin folders, or an empty list when the install root cannot be located
     */
    private static List<File> pluginFolders() {
        List<File> out = new ArrayList<>();
        File root = installRoot();
        if (root == null) {
            return out;
        }
        File[] children = new File(root, "plugins").listFiles(File::isDirectory);
        if (children != null) {
            for (File c : children) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Locates the Cameo install folder. Candidates are tried in order: the cameo.home and md.home system
     * properties, the CAMEO_HOME environment variable, CameoInfo, then the working directory. The first
     * candidate that contains a plugins folder wins.
     *
     * @return the install folder, or null if no candidate has a plugins folder
     */
    static File installRoot() {
        List<String> candidates = new ArrayList<>();
        candidates.add(System.getProperty("cameo.home"));
        candidates.add(System.getProperty("md.home"));
        candidates.add(System.getenv("CAMEO_HOME"));
        try {
            Object root = CameoInfo.describe().get("installRoot");
            candidates.add(root == null ? null : String.valueOf(root));
        } catch (Throwable ignored) {
            // not inside Cameo
        }
        candidates.add(System.getProperty("user.dir", "."));
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && new File(c, "plugins").isDirectory()) {
                return new File(c);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ model helpers

    /**
     * Lenient element lookup.
     *
     * @param project the open project
     * @param id element ID, possibly blank
     * @return the UML element, or null for blank IDs, unknown IDs and IDs of non-UML objects (for example
     *     SysML v2 elements)
     */
    static Element element(Project project, String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        Object o = project.getElementByID(id.trim());
        return o instanceof Element ? (Element) o : null;
    }

    /**
     * Like {@link #element} but throws when the element is missing.
     *
     * @param project the open project
     * @param id element ID
     * @param what label for the error message, such as "Requirement"
     * @return the UML element
     * @throws ToolException SYSML2_ELEMENT when the ID names a SysML v2 element, otherwise NOT_FOUND
     */
    static Element requireElement(Project project, String id, String what) {
        Element e = element(project, id);
        if (e == null) {
            throw com.cameomcp.plugin.cameo.ProjectKind.notUmlElement(id == null ? null : project.getElementByID(id.trim()),
                    what + " '" + id + "' not found. Use cameo_query_elements to find valid IDs.");
        }
        return e;
    }

    /**
     * Element reference JSON that never throws: falls back to just the ID plus a warning if serialization
     * fails.
     *
     * @param element a non-null element
     * @return the element reference
     */
    static JsonObject ref(Element element) {
        try {
            return ElementJson.toJsonReference(element);
        } catch (Throwable t) {
            JsonObject o = new JsonObject();
            o.addProperty("id", element.getID());
            o.addProperty("warning", "Could not serialize element: " + t);
            return o;
        }
    }

    // ------------------------------------------------------------------ reflection value dumps

    /**
     * Port of the reference PropertySerializer.serializeValue (diagnostic dumps of native objects).
     *
     * <p>Strings, numbers, booleans and characters become primitives; elements become compact element JSON.
     * Collections and maps become wrappers with type and count; their first 250 items are included unless
     * summaryOnly is set. Other objects become class name and toString.
     *
     * @param value the object to dump, or null
     * @param includeRaw for unknown objects, also dump one level of zero-argument get/is getter values
     * @param summaryOnly for collections and maps, give only type and count
     * @return the JSON form; JSON null for a null value
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
            try {
                return ElementJson.toJsonCompact((Element) value);
            } catch (Throwable t) {
                return new JsonPrimitive(String.valueOf(value));
            }
        }
        if (value instanceof Collection<?>) {
            JsonArray array = new JsonArray();
            // Collections and maps are capped at 250 entries and their items are serialized in summary mode,
            // which keeps dumps of large native structures bounded.
            if (!summaryOnly) {
                int count = 0;
                for (Object item : (Collection<?>) value) {
                    if (count++ >= 250) {
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
                int count = 0;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (count++ >= 250) {
                        break;
                    }
                    entries.add(String.valueOf(entry.getKey()), serializeValue(entry.getValue(), includeRaw, true));
                }
                wrapper.add("entries", entries);
            }
            return wrapper;
        }
        // Unknown object: class name and toString, plus (with includeRaw) one level of zero-arg get/is getter values.
        JsonObject fallback = new JsonObject();
        fallback.addProperty("className", value.getClass().getName());
        fallback.addProperty("string", String.valueOf(value));
        if (includeRaw) {
            JsonObject getters = new JsonObject();
            for (Method method : value.getClass().getMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) {
                    continue;
                }
                String name = method.getName();
                if ((!name.startsWith("get") && !name.startsWith("is")) || "getClass".equals(name)) {
                    continue;
                }
                try {
                    getters.add(name, serializeValue(method.invoke(value), false, true));
                } catch (Throwable ignored) {
                    // diagnostic dump; inaccessible getters are expected
                }
            }
            if (getters.size() > 0) {
                fallback.add("getterValues", getters);
            }
        }
        return fallback;
    }

    /**
     * {@link ElementJson#invokeZeroArg} rendered as a string.
     *
     * @param target object to call the getter on
     * @param methodName zero-argument method name
     * @return the result as a string, or "" when it returned null or failed
     */
    static String stringGetter(Object target, String methodName) {
        Object v = ElementJson.invokeZeroArg(target, methodName);
        return v == null ? "" : String.valueOf(v);
    }
}
