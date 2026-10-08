package com.cameomcp.plugin.tools.diagrams;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps normalized compartment keys ({@code showAttributes}, {@code suppressParts}, {@code ports})
 * to the native Cameo presentation property names that control them.
 */
public final class CompartmentAliasResolver {

    private static final Map<String, String[]> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("properties", new String[]{"Suppress Properties", "Show Properties"});
        ALIASES.put("operations", new String[]{"Show Operations", "Suppress Operations"});
        ALIASES.put("constraints", new String[]{"Suppress Constraints", "Show Constraints"});
        ALIASES.put("taggedvalues", new String[]{"Show Tagged Values"});
        ALIASES.put("ports", new String[]{"Suppress Ports", "Show Ports"});
        ALIASES.put("attributes", new String[]{"Suppress Attributes"});
        ALIASES.put("fullports", new String[]{"Suppress Full Ports", "Show Full Ports"});
        ALIASES.put("parts", new String[]{"Suppress Parts", "Show Parts"});
        ALIASES.put("content", new String[]{"Suppress Content", "Show Content"});
        ALIASES.put("references", new String[]{"Suppress References", "Show References"});
        ALIASES.put("values", new String[]{"Suppress Values", "Show Values"});
        ALIASES.put("flowproperties", new String[]{"Suppress Flow Properties", "Show Flow Properties"});
        ALIASES.put("proxyports", new String[]{"Suppress Proxy Ports", "Show Proxy Ports"});
        ALIASES.put("behaviors", new String[]{"Suppress Behaviors", "Show Behaviors"});
        ALIASES.put("receptions", new String[]{"Suppress Receptions", "Show Receptions"});
        ALIASES.put("structure", new String[]{"Suppress Structure", "Show Structure"});
        ALIASES.put("stereotype", new String[]{"Show Stereotype"});
        ALIASES.put("name", new String[]{"Show Name"});
        ALIASES.put("type", new String[]{"Show Type"});
    }

    private CompartmentAliasResolver() {
    }

    public static List<String> candidatePropertyNames(String requestedKey) {
        String normalized = normalizeKey(requestedKey);
        if (normalized.startsWith("show") && normalized.length() > 4) {
            normalized = normalized.substring(4);
        } else if (normalized.startsWith("suppress") && normalized.length() > 8) {
            normalized = normalized.substring(8);
        }
        String[] candidates = ALIASES.get(normalized);
        if (candidates == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(Arrays.asList(candidates));
    }

    /** Lower-case alphanumerics only: "Show Tagged Values" -> "showtaggedvalues". */
    public static String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }
}
