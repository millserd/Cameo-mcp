// family: model
package com.cameomcp.plugin.tools.model;

import com.cameomcp.catalog.ModelTools;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every model-family spec in the catalog is bound, that expected tools and required parameters
 * exist, and that the macro engine falls back from Groovy to JavaScript.
 */
class ModelCatalogBindingTest {

    private static final List<String> EXPECTED = Arrays.asList(
            "cameo_get_project", "cameo_save_project", "cameo_get_ui_state", "cameo_get_active_diagram",
            "cameo_get_ui_selection", "cameo_query_elements", "cameo_get_element", "cameo_get_containment_tree",
            "cameo_list_containment_children", "cameo_create_element", "cameo_modify_element", "cameo_delete_element",
            "cameo_apply_stereotype", "cameo_set_tagged_values", "cameo_set_stereotype_metaclasses",
            "cameo_apply_profile", "cameo_create_relationship", "cameo_get_relationships", "cameo_get_specification",
            "cameo_set_specification", "cameo_set_usecase_subject", "cameo_get_transition_triggers",
            "cameo_set_transition_trigger", "cameo_get_state_behaviors", "cameo_set_state_behaviors",
            "cameo_execute_macro", "cameo_remove_stereotype", "cameo_move_element", "cameo_create_elements",
            "cameo_list_macro_engines");

    @Test
    void everySpecIsBoundAndExpectedToolsExist() {
        ToolRegistry r = new ToolRegistry().addSpecs(ModelTools.specs());
        ModelBindings.bind(r);
        assertTrue(r.unboundTools().isEmpty(), "unbound: " + r.unboundTools());
        Set<String> names = new HashSet<>();
        for (ToolSpec s : r.specs()) {
            names.add(s.name());
            assertEquals(ModelTools.FAMILY, s.family());
            assertFalse(s.description().isEmpty(), s.name());
        }
        assertEquals(new HashSet<>(EXPECTED), names);
    }

    @Test
    void kindsAndRequiredParameters() {
        ToolRegistry r = new ToolRegistry().addSpecs(ModelTools.specs());
        assertEquals(ToolSpec.Kind.DESTRUCTIVE, r.spec("cameo_delete_element").kind());
        assertEquals(ToolSpec.Kind.DESTRUCTIVE, r.spec("cameo_remove_stereotype").kind());
        assertEquals(ToolSpec.Kind.SIDE_EFFECT, r.spec("cameo_save_project").kind());
        assertTrue(r.spec("cameo_query_elements").isReadOnly());
        assertFalse(r.spec("cameo_list_macro_engines").requiresProject());
        assertEquals(Arrays.asList("type", "name", "parent_id"), r.spec("cameo_create_element").requiredParameters());
        assertEquals(Arrays.asList("type", "source_id", "target_id"),
                r.spec("cameo_create_relationship").requiredParameters());
        assertTrue(r.spec("cameo_execute_macro").timeoutSeconds() >= 600);
        assertTrue(r.spec("cameo_create_elements").parameters().containsKey("elements"));
    }

    @Test
    void macroEngineCandidatesFallBackToGroovyThenJavaScript() {
        assertEquals(Arrays.asList("python", "groovy", "Groovy", "javascript", "js", "nashorn", "graal.js"),
                MacroService.candidates("python"));
        assertEquals("groovy", MacroService.candidates(null).get(0));
        assertEquals(MacroService.FALLBACK_ENGINES.length, MacroService.candidates("groovy").size());
        // Works without Cameo: lists whatever javax.script engines the JDK has.
        assertTrue(MacroService.listEngines().has("engines"));
    }
}
