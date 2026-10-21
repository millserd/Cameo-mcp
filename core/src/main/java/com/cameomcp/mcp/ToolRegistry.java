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

    /** Declared tools by name, in declaration order. */
    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    /** Bound handlers by tool name. */
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    /**
     * Adds tool declarations. Fails fast on a duplicate name, which is always a catalog bug.
     *
     * @param list the specs to add
     * @return this registry
     * @throws IllegalStateException if a name is already registered
     */
    public ToolRegistry addSpecs(Collection<ToolSpec> list) {
        for (ToolSpec s : list) {
            if (specs.putIfAbsent(s.name(), s) != null) {
                throw new IllegalStateException("Duplicate tool spec: " + s.name());
            }
        }
        return this;
    }

    /**
     * Bind a handler to an existing spec.
     *
     * @param name the tool name
     * @param handler the implementation
     * @return this registry
     * @throws IllegalStateException if no spec has that name, or a handler is already bound
     */
    public ToolRegistry bind(String name, ToolHandler handler) {
        if (!specs.containsKey(name)) {
            throw new IllegalStateException("No spec for tool: " + name);
        }
        if (handlers.putIfAbsent(name, handler) != null) {
            throw new IllegalStateException("Tool bound twice: " + name);
        }
        return this;
    }

    /**
     * Bind the same handler to every spec that has none yet (used for offline stand-ins).
     *
     * @param handler the stand-in implementation
     * @return this registry
     */
    public ToolRegistry bindRemaining(ToolHandler handler) {
        for (String name : specs.keySet()) {
            handlers.putIfAbsent(name, handler);
        }
        return this;
    }

    /**
     * The declaration for a tool name, or null if none.
     *
     * @param name the tool name
     * @return the spec, or null
     */
    public ToolSpec spec(String name) {
        return specs.get(name);
    }

    /**
     * The bound handler for a tool name, or null if the spec has no implementation yet.
     *
     * @param name the tool name
     * @return the handler, or null
     */
    public ToolHandler handler(String name) {
        return handlers.get(name);
    }

    /**
     * All specs, in declaration order.
     *
     * @return a read-only view of the specs
     */
    public Collection<ToolSpec> specs() {
        return Collections.unmodifiableCollection(specs.values());
    }

    /**
     * Names of specs with no handler. Tests assert this is empty; cameo_status reports it.
     *
     * @return a new list of unbound tool names, in declaration order
     */
    public List<String> unboundTools() {
        List<String> out = new ArrayList<>();
        for (String n : specs.keySet()) {
            if (!handlers.containsKey(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Returns the number of declared tools.
     *
     * @return the number of declared tools
     */
    public int size() {
        return specs.size();
    }
}
