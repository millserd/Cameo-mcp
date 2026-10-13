package com.cameomcp.plugin;

import com.cameomcp.Version;
import com.cameomcp.plugin.tools.OptionalModules;
import com.cameomcp.plugin.ui.McpMenuConfigurator;
import com.nomagic.magicdraw.actions.ActionsConfiguratorsManager;
import com.nomagic.magicdraw.plugins.Plugin;

import java.io.File;

/**
 * Cameo plugin entry point. Starts the MCP server (unless auto-start is off), adds the
 * MCP main menu with the status window, and reports everything to the Cameo log.
 */
public class CameoMcpPlugin extends Plugin {

    // Set once in init(); read by the UI and by tools that locate bundled files.
    private static volatile File pluginDirectory;

    /**
     * Called by Cameo at start-up. Any failure is logged and swallowed: a broken MCP plugin must
     * never stop Cameo from starting.
     */
    @Override
    public void init() {
        try {
            pluginDirectory = resolvePluginDirectory();
            OptionalModules.setPluginDirectory(pluginDirectory);
            Settings settings = Settings.load();
            McpRuntime runtime = McpRuntime.init(settings);
            ActionsConfiguratorsManager.getInstance().addMainMenuConfigurator(new McpMenuConfigurator());
            CameoLog.info("Cameo MCP " + Version.get() + " loaded (" + runtime.registry().size()
                    + " tools). Open MCP > MCP Server Status to check the connection.");
            if (settings.autoStart) {
                runtime.start();
            } else {
                CameoLog.info("Auto-start is off. Start the server from MCP > Start Server.");
            }
        } catch (Throwable t) {
            CameoLog.error("Cameo MCP failed to initialize", t);
        }
    }

    /** Called by Cameo on exit; stops the server so the port is released. Always allows the exit. */
    @Override
    public boolean close() {
        McpRuntime rt = McpRuntime.get();
        if (rt != null) {
            try {
                rt.stop();
            } catch (Throwable ignored) {
                // Cameo is exiting
            }
        }
        return true;
    }

    /** The plugin has no edition or license requirements. */
    @Override
    public boolean isSupported() {
        return true;
    }

    /** The folder this plugin was installed in (holds the jars, stdio launcher, docs), or null. */
    public static File pluginDirectory() {
        return pluginDirectory;
    }

    /*
     * Asks Cameo's plugin descriptor for the install folder (reflectively, because the accessor is
     * not part of the public Plugin API in every release), else uses the folder of this jar.
     */
    private File resolvePluginDirectory() {
        try {
            Object descriptor = getClass().getMethod("getDescriptor").invoke(this);
            Object dir = descriptor.getClass().getMethod("getPluginDirectory").invoke(descriptor);
            if (dir instanceof File) {
                return (File) dir;
            }
        } catch (Throwable ignored) {
            // fall back to the jar location
        }
        try {
            File jar = new File(CameoMcpPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() ? jar.getParentFile() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
