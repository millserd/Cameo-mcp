package com.cameomcp.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tool specs plus the handlers bound to them. */
public final class ToolRegistry {

    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

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

    public ToolSpec spec(String name) {
        return specs.get(name);
    }

    public ToolHandler handler(String name) {
        return handlers.get(name);
    }

    public Collection<ToolSpec> specs() {
        return Collections.unmodifiableCollection(specs.values());
    }

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
