package com.cameomcp.plugin.tools;

import com.google.gson.JsonObject;

/**
 * Entry point implemented by classes in the optional jar (code that needs APIs from
 * optional Cameo plugins). The main plugin calls them through {@link OptionalModules}.
 */
public interface GsonService {
    JsonObject call(String operation, JsonObject params) throws Exception;
}
