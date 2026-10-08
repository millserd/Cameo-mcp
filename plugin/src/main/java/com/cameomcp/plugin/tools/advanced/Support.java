package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.CameoInfo;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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

    private Support() {
    }

    // ------------------------------------------------------------------ envelopes

    static JsonObject baseCapabilities(String family, String mode) {
        JsonObject json = new JsonObject();
        json.addProperty("family", family);
        json.addProperty("mode", mode);
        json.add("projectState", projectState());
        return json;
    }

    static Project currentProject() {
        try {
            return Application.getInstance().getProject();
        } catch (Throwable t) {
            return null;
        }
    }

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

    static JsonObject unsupported(String family, String reason, String nextAction) {
        JsonObject json = baseCapabilities(family, "unsupported");
        json.addProperty("available", false);
        json.addProperty("status", "unsupported");
        json.addProperty("reason", reason);
        json.addProperty("nextAction", nextAction);
        return json;
    }

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
     */
    @SuppressWarnings("unchecked")
    static ToolException refusal(String code, JsonObject payload) {
        String reason = Gs.str(payload, "reason", Gs.str(payload, "message", "Operation refused."));
        String next = Gs.str(payload, "nextAction");
        String message = next == null ? reason : reason + " Next: " + next;
        Object details = Gs.fromGson(payload);
        return new ToolException(code, message, details instanceof Map ? (Map<String, Object>) details : null);
    }

    /** Arguments as received, minus JSON nulls, for "request" echoes. */
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

    static String safe(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ class probes

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

    static boolean classAvailable(String className) {
        try {
            loadClass(className);
            return true;
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return false;
        }
    }

    /** Load a class from this plugin, Cameo core, or any loaded Cameo plugin (optional products live there). */
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

    /** Plugin folders under {@code <install>/plugins} whose name contains any hint, as {name, path} objects. */
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

    /** Same as {@link #pluginDirectories} but as plain absolute paths (feature probe contract). */
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

    static Element element(Project project, String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        Object o = project.getElementByID(id.trim());
        return o instanceof Element ? (Element) o : null;
    }

    static Element requireElement(Project project, String id, String what) {
        Element e = element(project, id);
        if (e == null) {
            throw com.cameomcp.plugin.cameo.ProjectKind.notUmlElement(id == null ? null : project.getElementByID(id.trim()),
                    what + " '" + id + "' not found. Use cameo_query_elements to find valid IDs.");
        }
        return e;
    }

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

    /** Port of the reference PropertySerializer.serializeValue (diagnostic dumps of native objects). */
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

    static Object invokeZeroArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }

    static String stringGetter(Object target, String methodName) {
        Object v = invokeZeroArg(target, methodName);
        return v == null ? "" : String.valueOf(v);
    }
}
