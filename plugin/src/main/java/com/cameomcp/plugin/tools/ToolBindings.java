package com.cameomcp.plugin.tools;

import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.CameoLog;

import java.util.Arrays;
import java.util.List;

/**
 * Binds every tool family's implementation. Each family has a class with
 * {@code public static void bind(ToolRegistry)}; they are loaded by name so families
 * stay independent. A family that fails to load is logged and its tools report
 * NOT_IMPLEMENTED instead of taking the whole plugin down.
 */
public final class ToolBindings {

    public static final List<String> BINDERS = Arrays.asList(
            "com.cameomcp.plugin.tools.server.ServerBindings",
            "com.cameomcp.plugin.tools.model.ModelBindings",
            "com.cameomcp.plugin.tools.diagrams.DiagramBindings",
            "com.cameomcp.plugin.tools.artifacts.ArtifactBindings",
            "com.cameomcp.plugin.tools.advanced.AdvancedBindings",
            "com.cameomcp.plugin.tools.analysis.AnalysisBindings");

    private ToolBindings() {
    }

    public static void bindAll(ToolRegistry registry) {
        for (String binder : BINDERS) {
            try {
                Class.forName(binder).getMethod("bind", ToolRegistry.class).invoke(null, registry);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
                CameoLog.error("Tool family " + binder + " could not be loaded", cause);
            }
        }
        List<String> unbound = registry.unboundTools();
        if (!unbound.isEmpty()) {
            CameoLog.warn(unbound.size() + " tools have no implementation in this build: " + unbound);
        }
    }
}
