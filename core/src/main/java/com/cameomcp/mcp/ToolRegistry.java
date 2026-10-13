package com.cameomcp.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Specs (name, schema, description) and handlers are registered separately: core declares every
 * spec so tools/list works without Cameo (stdio launcher, tests), and the plugin binds handlers
 * later. Both maps keep insertion order so tools/list is stable between runs.
 */
/** Tool specs plus the handlers bound to them. */
public final class ToolRegistry {

    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    /** Adds tool declarations. Fails fast on a duplicate name, which is always a catalog bug. */
    public ToolRegistry addSpecs(Collection<ToolSpec> list) {
        for (ToolSpec s : list) {
            if (specs.putIfAbsent(s.name(), s) != null) {
                throw new IllegalStateException("Duplicate tool spec: " + s.name());
            }
        }
        return this;
    }

    /** Bind a handler to an existing spec. */
    public ToolRegistry bind(String name, ToolHandler handler) {
        if (!specs.containsKey(name)) {
            throw new IllegalStateException("No spec for tool: " + name);
        }
        if (handlers.putIfAbsent(name, handler) != null) {
            throw new IllegalStateException("Tool bound twice: " + name);
        }
        return this;
    }

    /** Bind the same handler to every spec that has none yet (used for offline stand-ins). */
    public ToolRegistry bindRemaining(ToolHandler handler) {
        for (String name : specs.keySet()) {
            handlers.putIfAbsent(name, handler);
        }
        return this;
    }

    /** The declaration for a tool name, or null if none. */
    public ToolSpec spec(String name) {
        return specs.get(name);
    }

    /** The bound handler for a tool name, or null if the spec has no implementation yet. */
    public ToolHandler handler(String name) {
        return handlers.get(name);
    }

    /** All specs, in declaration order. */
    public Collection<ToolSpec> specs() {
        return Collections.unmodifiableCollection(specs.values());
    }

    /** Names of specs with no handler. Tests assert this is empty; cameo_status reports it. */
    public List<String> unboundTools() {
        List<String> out = new ArrayList<>();
        for (String n : specs.keySet()) {
            if (!handlers.containsKey(n)) {
                out.add(n);
            }
        }
        return out;
    }

    public int size() {
        return specs.size();
    }
}
