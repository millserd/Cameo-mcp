// family: analysis
package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.tools.analysis.J;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodologyRegistryTest {

    @Test
    void oosemAndUafPacksAreRegistered() {
        List<Map<String, Object>> packs = MethodologyRegistry.listPacks();
        assertTrue(packs.size() >= 2);
        assertEquals("oosem", packs.get(0).get("id"));
        assertTrue(J.list(packs.get(0).get("recipes")).size() >= 7);
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> p : packs) {
            ids.add(p.get("id"));
        }
        assertTrue(ids.contains("uaf"));
    }

    @Test
    void oosemPackExposesReviewAndEvidenceSections() {
        Map<String, Object> pack = MethodologyRegistry.getPack("OOSEM");
        assertTrue(J.list(pack.get("review_sections")).size() >= 3);
        assertTrue(J.list(pack.get("evidence_sections")).size() >= 3);
        Set<Object> phases = new HashSet<>();
        for (Map<String, Object> phase : J.maps(pack.get("method_phases"))) {
            phases.add(phase.get("id"));
        }
        assertTrue(phases.contains("requirements"));
    }

    @Test
    void packRecipeLookupIsStable() {
        Map<String, Object> recipe = MethodologyRegistry.getRecipe("oosem", "use_case_model");
        assertEquals("Use Case Model", recipe.get("title"));
        assertEquals("needs", recipe.get("phase_id"));
    }

    @Test
    void logicalArchitectureSatisfyDirectionMatchesRuntime() {
        Map<String, Object> recipe = MethodologyRegistry.getRecipe("oosem", "logical_architecture_scaffold");
        Map<String, Object> satisfy = null;
        for (Map<String, Object> rel : J.maps(recipe.get("mandatory_relationships"))) {
            if ("satisfy".equals(rel.get("relationship"))) {
                satisfy = rel;
                break;
            }
        }
        assertEquals("logical_block", satisfy.get("source_role"));
        assertEquals("system_requirement", satisfy.get("target_role"));
    }

    @Test
    void unknownPackAndRecipeAreNotFound() {
        ToolException e = assertThrows(ToolException.class, () -> MethodologyRegistry.getPack("nope"));
        assertEquals("NOT_FOUND", e.code());
        assertTrue(e.getMessage().startsWith("Unknown methodology pack: nope"));
        ToolException r = assertThrows(ToolException.class, () -> MethodologyRegistry.getRecipe("oosem", "nope"));
        assertTrue(r.getMessage().startsWith("Unknown recipe for pack oosem: nope"));
    }

    @Test
    void returnedPacksAreCopies() {
        Map<String, Object> pack = MethodologyRegistry.getPack("oosem");
        pack.put("title", "changed");
        assertEquals("OOSEM Viewpoint Pack", MethodologyRegistry.getPack("oosem").get("title"));
    }

    @Test
    void everyRecipeHasAnExecutableBuilder() {
        for (Map<String, Object> pack : MethodologyRegistry.listPacks()) {
            MethodologyRuntime.PackDefinition runtime = MethodologyService.buildRuntimePack(pack, new java.util.LinkedHashMap<>(), null, false);
            assertEquals(J.list(pack.get("recipes")).size(), runtime.recipes().size());
        }
    }
}
