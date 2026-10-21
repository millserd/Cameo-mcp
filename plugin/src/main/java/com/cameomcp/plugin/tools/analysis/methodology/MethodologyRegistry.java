package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.tools.analysis.J;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Built-in methodology packs (OOSEM viewpoint pack, UAF starter pack). The packs are data:
 * phases, recipes, checks and evidence sections in the snake_case shape that
 * {@code cameo_get_methodology_pack} returns. Every accessor returns a deep copy.
 */
public final class MethodologyRegistry {

    /** Parsed packs keyed by lower-case pack ID; null until first use. */
    private static volatile Map<String, Map<String, Object>> packs;

    /** Static methods only. */
    private MethodologyRegistry() {
    }

    /**
     * Parses the embedded pack JSON once (double-checked locking on a volatile field) into a map keyed
     * by lower-cased pack ID; a duplicate ID is a data error and fails fast.
     *
     * @return the parsed packs, shared and not to be modified
     * @throws IllegalStateException if the embedded JSON has a duplicate pack ID
     */
    private static Map<String, Map<String, Object>> packs() {
        Map<String, Map<String, Object>> local = packs;
        if (local == null) {
            synchronized (MethodologyRegistry.class) {
                local = packs;
                if (local == null) {
                    Map<String, Map<String, Object>> parsed = new TreeMap<>();
                    Map<String, Object> root = Json.parseObject(MethodologyPackData.json());
                    for (Map<String, Object> pack : J.maps(root.get("packs"))) {
                        String key = J.str(pack.get("id")).toLowerCase(Locale.ROOT);
                        if (parsed.containsKey(key)) {
                            throw new IllegalStateException("Duplicate methodology pack id: " + pack.get("id"));
                        }
                        parsed.put(key, pack);
                    }
                    packs = local = parsed;
                }
            }
        }
        return local;
    }

    /**
     * All packs sorted by id.
     *
     * @return deep copies of every pack
     */
    public static List<Map<String, Object>> listPacks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> pack : packs().values()) {
            out.add(Json.copy(pack));
        }
        return out;
    }

    /**
     * One pack by id (case-insensitive).
     *
     * @param packId pack ID, any case
     * @return a deep copy of the pack
     * @throws ToolException NOT_FOUND for an unknown or null ID
     */
    public static Map<String, Object> getPack(String packId) {
        Map<String, Object> pack = packId == null ? null : packs().get(packId.toLowerCase(Locale.ROOT));
        if (pack == null) {
            throw new ToolException("NOT_FOUND", "Unknown methodology pack: " + packId
                    + ". Use cameo_list_methodology_packs to list valid pack ids (" + String.join(", ", packs().keySet()) + ").");
        }
        return Json.copy(pack);
    }

    /**
     * One recipe of a pack by exact id.
     *
     * @param packId pack ID, any case
     * @param recipeId recipe ID, exact case
     * @return a deep copy of the recipe
     * @throws ToolException NOT_FOUND for an unknown pack or recipe
     */
    public static Map<String, Object> getRecipe(String packId, String recipeId) {
        Map<String, Object> pack = getPack(packId);
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> recipe : J.maps(pack.get("recipes"))) {
            if (J.str(recipe.get("id")).equals(recipeId)) {
                // The pack is already a deep copy, so the recipe can be returned without another copy.
                return recipe;
            }
            ids.add(J.str(recipe.get("id")));
        }
        throw new ToolException("NOT_FOUND", "Unknown recipe for pack " + packId + ": " + recipeId
                + ". Valid recipe ids: " + String.join(", ", ids) + ".");
    }

    /**
     * The recipe list of an already-loaded pack (no copy).
     *
     * @param pack a pack map
     * @return its recipes
     */
    public static List<Map<String, Object>> recipes(Map<String, Object> pack) {
        return J.maps(pack.get("recipes"));
    }
}
