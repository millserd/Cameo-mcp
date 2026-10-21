package com.cameomcp.plugin.tools;

import com.cameomcp.json.tree.JsonObject;

/**
 * Entry point implemented by classes in the optional jar (code that needs APIs from
 * optional Cameo plugins). The main plugin calls them through {@link OptionalModules}.
 */
public interface TreeService {
    /**
     * Runs one named operation of the service.
     *
     * @param operation which operation, e.g. "createMatrix"; each service documents its own set
     * @param params    the tool arguments (with snake_case/camelCase aliases)
     * @return the structured tool result
     */
    JsonObject call(String operation, JsonObject params) throws Exception;
}
