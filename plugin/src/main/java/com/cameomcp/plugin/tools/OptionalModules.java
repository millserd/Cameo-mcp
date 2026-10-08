package com.cameomcp.plugin.tools;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.CameoLog;
import com.google.gson.JsonObject;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads {@code cameo-mcp-optional.jar}: code compiled against APIs of optional Cameo
 * plugins (Dependency Matrix, Generic Table, Relation Map).
 * <p>
 * Instead of declaring hard {@code required-plugin} dependencies (which would stop the
 * whole plugin from loading if one of them is absent or versioned differently in 2026x),
 * the optional jar is loaded by a class loader that searches this plugin first and then
 * every loaded Cameo plugin. If an API is missing, only the tools that need it fail,
 * with a clear UNSUPPORTED error.
 */
public final class OptionalModules {

    private static volatile File pluginDirectory;
    private static volatile ClassLoader loader;
    private static final Map<String, GsonService> SERVICES = new ConcurrentHashMap<>();

    private OptionalModules() {
    }

    public static void setPluginDirectory(File dir) {
        pluginDirectory = dir;
    }

    /** Call {@code operation} on the optional service class (fully qualified name). */
    public static JsonObject call(String serviceClass, String operation, JsonObject params) throws Exception {
        return service(serviceClass).call(operation, params);
    }

    /**
     * Loads a class from Cameo or from any loaded Cameo plugin, for APIs of plugins this one does
     * not declare as required (such as SysML v2 in 2026x).
     */
    public static Class<?> cameoClass(String name) throws ClassNotFoundException {
        try {
            return Class.forName(name, true, loader());
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    public static GsonService service(String serviceClass) {
        GsonService s = SERVICES.get(serviceClass);
        if (s != null) {
            return s;
        }
        try {
            Class<?> c = Class.forName(serviceClass, true, loader());
            s = (GsonService) c.getDeclaredConstructor().newInstance();
            SERVICES.put(serviceClass, s);
            return s;
        } catch (ToolException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
            throw new ToolException("UNSUPPORTED", "This feature needs an optional Cameo plugin that is not "
                    + "available in this installation (" + cause + ").");
        }
    }

    private static synchronized ClassLoader loader() throws Exception {
        if (loader != null) {
            return loader;
        }
        File jar = locateJar();
        ClassLoader own = OptionalModules.class.getClassLoader();
        List<ClassLoader> fallbacks = pluginClassLoaders();
        ClassLoader parent = new ClassLoader(own) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                for (ClassLoader cl : fallbacks) {
                    try {
                        return cl.loadClass(name);
                    } catch (ClassNotFoundException | LinkageError ignored) {
                        // next
                    }
                }
                throw new ClassNotFoundException(name);
            }

            @Override
            protected URL findResource(String name) {
                for (ClassLoader cl : fallbacks) {
                    URL u = cl.getResource(name);
                    if (u != null) {
                        return u;
                    }
                }
                return null;
            }
        };
        if (jar == null) {
            // Development / tests: optional classes may already be on our class path.
            loader = parent;
        } else {
            loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, parent);
        }
        CameoLog.fileOnly("Optional module loader ready (" + (jar == null ? "classpath" : jar.getName()) + ", "
                + fallbacks.size() + " Cameo plugin class loaders).");
        return loader;
    }

    private static File locateJar() {
        File dir = pluginDirectory;
        if (dir == null) {
            try {
                File self = new File(OptionalModules.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                dir = self.isFile() ? self.getParentFile() : null;
            } catch (Exception ignored) {
                dir = null;
            }
        }
        if (dir == null) {
            return null;
        }
        File jar = new File(dir, "cameo-mcp-optional.jar");
        return jar.isFile() ? jar : null;
    }

    /** Class loaders of all loaded Cameo plugins, via reflection (API differs across releases). */
    private static List<ClassLoader> pluginClassLoaders() {
        List<ClassLoader> out = new ArrayList<>();
        try {
            Class<?> utils = Class.forName("com.nomagic.magicdraw.plugins.PluginUtils");
            Method getPlugins = utils.getMethod("getPlugins");
            Object plugins = getPlugins.invoke(null);
            if (plugins instanceof Iterable) {
                for (Object p : (Iterable<?>) plugins) {
                    if (p != null) {
                        ClassLoader cl = p.getClass().getClassLoader();
                        if (cl != null && cl != OptionalModules.class.getClassLoader() && !out.contains(cl)) {
                            out.add(cl);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            CameoLog.fileOnly("Could not enumerate Cameo plugins: " + t);
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && !out.contains(ctx)) {
            out.add(ctx);
        }
        return Collections.unmodifiableList(out);
    }
}
