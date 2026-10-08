package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every tool Cameo MCP offers, as Cameo-free specs.
 * <p>
 * Each family lives in its own class with a {@code public static List<ToolSpec> specs()}.
 * Families are listed by name and loaded reflectively so they stay independent of each
 * other; a missing family class is a build error caught by {@code CatalogTest}.
 */
public final class Catalog {

    /** Family classes in the order tools are listed to clients. */
    public static final List<String> FAMILIES = Collections.unmodifiableList(Arrays.asList(
            "com.cameomcp.catalog.ServerTools",
            "com.cameomcp.catalog.ModelTools",
            "com.cameomcp.catalog.DiagramTools",
            "com.cameomcp.catalog.ArtifactTools",
            "com.cameomcp.catalog.AdvancedTools",
            "com.cameomcp.catalog.AnalysisTools"));

    /** Tools that run arbitrary script code; blocked when the user turns macros off. */
    private static final Set<String> SCRIPT_TOOLS = new HashSet<>(Arrays.asList(
            "cameo_execute_macro", "cameo_execute_probe"));

    public static final String INSTRUCTIONS = String.join("\n",
            "Cameo MCP drives a running Cameo Systems Modeler / CATIA Magic (2024x or 2026x) through its Java API.",
            "Start with cameo_status (is Cameo up, is a project open?) and cameo_get_project (root model id).",
            "Find elements with cameo_query_elements or cameo_list_containment_children; every write tool takes element ids from those results.",
            "Each write is one undoable Cameo step. Writes are serialized: run them one at a time, not in parallel.",
            "Prefer compact views and paging (limit/offset) on large models; request full detail only for the elements you need.",
            "Diagram images can be large: use cameo_get_diagram_image with include_image=false or max_width first.",
            "cameo_execute_macro runs Groovy inside Cameo as an escape hatch; prefer structured tools. Call cameo_reset_session if a macro leaves a session open.",
            "cameo_list_tools filters this catalog by family or keyword. The resource cameo://guide is the full usage guide.");

    private Catalog() {
    }

    public static boolean isScriptTool(String name) {
        return SCRIPT_TOOLS.contains(name);
    }

    /** All specs in family order. Throws if a family class is missing or two tools share a name. */
    public static List<ToolSpec> all() {
        List<ToolSpec> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String family : FAMILIES) {
            for (ToolSpec s : family(family)) {
                if (!seen.add(s.name())) {
                    throw new IllegalStateException("Duplicate tool name " + s.name() + " in " + family);
                }
                out.add(s);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<ToolSpec> family(String className) {
        try {
            Class<?> c = Class.forName(className);
            return (List<ToolSpec>) c.getMethod("specs").invoke(null);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Tool family class missing: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot load tool family " + className + ": " + e.getCause(), e);
        }
    }
}
