package com.cameomcp.plugin.tools;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.CameoLog;
import com.cameomcp.json.tree.JsonObject;

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

    /** Where the plugin is installed; set by CameoMcpPlugin.init, null in tests. */
    private static volatile File pluginDirectory;
    /**
     * The optional-module class loader. Built once on first use, because Cameo's plugin list is only
     * complete after start-up.
     */
    private static volatile ClassLoader loader;
    /**
     * One instance per service class, created on first call. Two threads racing on the first call may each
     * create one; either instance works because services keep no state.
     */
    private static final Map<String, TreeService> SERVICES = new ConcurrentHashMap<>();

    /** Static methods only. */
    private OptionalModules() {
    }

    /**
     * Records the plugin folder so the optional jar can be found next to the main jar.
     *
     * @param dir the plugin folder, or null
     */
    public static void setPluginDirectory(File dir) {
        pluginDirectory = dir;
    }

    /**
     * Call {@code operation} on the optional service class (fully qualified name).
     *
     * @param serviceClass fully qualified class name of a {@link TreeService} in the optional jar
     * @param operation the operation name the service understands
     * @param params the tool arguments
     * @return the service's result
     * @throws ToolException UNSUPPORTED when the service or the Cameo plugin it needs is not available
     * @throws Exception whatever the service throws
     */
    public static JsonObject call(String serviceClass, String operation, JsonObject params) throws Exception {
        return service(serviceClass).call(operation, params);
    }

    /**
     * Loads a class from Cameo or from any loaded Cameo plugin, for APIs of plugins this one does
     * not declare as required (such as SysML v2 in 2026x).
     *
     * @param name fully qualified class name
     * @return the class, initialized
     * @throws ClassNotFoundException if no loader has it, or the loader chain cannot be built
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

    /**
     * The cached service instance for the class, loading it on first use. Any failure to load
     * (missing jar, missing optional Cameo plugin, linkage error) becomes an UNSUPPORTED tool error.
     *
     * @param serviceClass fully qualified class name
     * @return the service
     * @throws ToolException UNSUPPORTED when it cannot be loaded or instantiated
     */
    public static TreeService service(String serviceClass) {
        TreeService s = SERVICES.get(serviceClass);
        if (s != null) {
            return s;
        }
        try {
            Class<?> c = Class.forName(serviceClass, true, loader());
            s = (TreeService) c.getDeclaredConstructor().newInstance();
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

    /**
     * Builds the loader chain: optional jar, then (this plugin's loader, then every other Cameo
     * plugin's loader). The custom parent only consults the other plugins when our own loader
     * can't find a class, so our bundled classes always win.
     *
     * @return the cached or new loader
     * @throws Exception if the jar URL cannot be formed
     */
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

    /**
     * Finds cameo-mcp-optional.jar next to the plugin jar.
     *
     * @return the jar, or null when running from a build tree
     */
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
        // The context loader catches classes from Cameo's own lib folder that no plugin loader exposes.
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && !out.contains(ctx)) {
            out.add(ctx);
        }
        return Collections.unmodifiableList(out);
    }
}
