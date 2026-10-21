// family: diagrams
package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.catalog.DiagramTools;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every diagram-family spec is bound and well formed.
 */
class DiagramCatalogBindingTest {

    @Test
    void everyDiagramSpecIsBound() {
        ToolRegistry r = new ToolRegistry().addSpecs(DiagramTools.specs());
        DiagramBindings.bind(r);
        assertTrue(r.unboundTools().isEmpty(), "unbound: " + r.unboundTools());
        assertEquals(29, r.size());
    }

    @Test
    void specsAreWellFormed() {
        List<ToolSpec> specs = DiagramTools.specs();
        Set<String> names = new HashSet<>();
        for (ToolSpec s : specs) {
            assertTrue(names.add(s.name()), "duplicate " + s.name());
            assertEquals("diagrams", s.family());
            assertFalse(s.description().isEmpty(), s.name());
            for (Object p : s.parameters().keySet()) {
                assertTrue(((String) p).matches("[a-z][a-z0-9_]*"), s.name() + "." + p);
            }
        }
        assertTrue(names.contains("cameo_export_diagram_image"));
        assertTrue(names.contains("cameo_delete_diagram"));
        assertTrue(names.contains("cameo_rename_diagram"));
    }
}
