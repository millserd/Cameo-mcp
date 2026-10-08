// family: artifacts
package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.ToolException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ports the reference client's matrix-kind normalization tests and cameo_list_matrix_kinds test. */
class MatrixKindsTest {

    @Test
    void normalizesAliases() {
        assertEquals(MatrixKinds.REFINE, MatrixKinds.fromInput("Refine Requirement Matrix"));
        assertEquals(MatrixKinds.SATISFY, MatrixKinds.fromInput("Satisfy Requirement Matrix"));
        assertEquals(MatrixKinds.ALLOCATION, MatrixKinds.fromInput("System Allocation Matrix"));
        assertEquals(MatrixKinds.ALLOCATION, MatrixKinds.fromInput("SysML Allocation Matrix"));
        assertEquals(MatrixKinds.DEPENDENCY, MatrixKinds.fromInput("Dependency Matrix"));
        assertEquals(MatrixKinds.DERIVE, MatrixKinds.fromInput("derive"));
        assertEquals(MatrixKinds.DERIVE, MatrixKinds.fromInput("DeriveRequirementMatrix"));
        assertEquals(MatrixKinds.REFINE, MatrixKinds.fromInput("refine-matrix"));
        assertEquals(MatrixKinds.SATISFY, MatrixKinds.fromInput("  SATISFY  "));
        assertNull(MatrixKinds.fromInput("trace"));
        assertNull(MatrixKinds.fromInput(""));
        assertNull(MatrixKinds.fromInput(null));
    }

    @Test
    void requireRejectsUnknownKinds() {
        ToolException e = assertThrows(ToolException.class, () -> MatrixKinds.require("trace"));
        assertEquals("INVALID_PARAMS", e.code());
        assertTrue(e.getMessage().contains("refine, derive, satisfy, allocation, dependency"));
    }

    @Test
    void mapsNativeDiagramTypes() {
        assertEquals(MatrixKinds.ALLOCATION, MatrixKinds.fromDiagramType("SysML Allocation Matrix"));
        assertEquals(MatrixKinds.DEPENDENCY, MatrixKinds.fromDiagramType("Dependency Matrix"));
        assertNull(MatrixKinds.fromDiagramType("Generic Table"));
        assertNull(MatrixKinds.fromDiagramType(""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogListsAllValidatedKinds() {
        Map<String, Object> c = MatrixKinds.catalog();
        List<Object> kinds = (List<Object>) c.get("matrixKinds");
        assertEquals(kinds.size(), c.get("count"));
        assertEquals(5, kinds.size());
        Map<String, Object> satisfy = (Map<String, Object>) kinds.get(2);
        assertEquals("satisfy", satisfy.get("kind"));
        assertEquals("Satisfy Requirement Matrix", satisfy.get("nativeType"));
        assertEquals(List.of("Block", "Component", "Property"), satisfy.get("validatedRowTypeExamples"));
        assertEquals(List.of("Requirement"), satisfy.get("validatedColumnTypeExamples"));
    }

    @Test
    void defaultsMatchTheBridge() {
        assertEquals(List.of("Block"), MatrixKinds.REFINE.defaultRowTypes);
        assertEquals(List.of("NamedElement"), MatrixKinds.DEPENDENCY.defaultColumnTypes);
        assertEquals(List.of("Block"), MatrixKinds.ALLOCATION.defaultColumnTypes);
    }
}
