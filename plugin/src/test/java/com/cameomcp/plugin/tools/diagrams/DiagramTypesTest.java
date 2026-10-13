// family: diagrams
package com.cameomcp.plugin.tools.diagrams;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagramTypesTest {

    private static String nativeOf(String input) {
        return DiagramTypes.nativeCandidates(input).get(0);
    }

    @Test
    void canonicalTokensResolveToNativeTypes() {
        assertEquals("SysML Block Definition Diagram", nativeOf("BDD"));
        assertEquals("SysML Internal Block Diagram", nativeOf("IBD"));
        assertEquals("Use Case Diagram", nativeOf("UseCase"));
        assertEquals("State Machine Diagram", nativeOf("StateMachine"));
        assertEquals("Composite Structure Diagram", nativeOf("CompositeStructure"));
        assertEquals("Interaction Overview Diagram", nativeOf("InteractionOverview"));
        assertEquals("SysML Parametric Diagram", nativeOf("Parametric Diagram"));
        assertEquals("Relation Map Diagram", nativeOf("RelationMap"));
        assertEquals("Content Diagram", nativeOf("content"));
    }

    @Test
    void aliasesAreNormalized() {
        assertEquals("SysML Internal Block Diagram", nativeOf("InternalBlockDiagram"));
        assertEquals("SysML Internal Block Diagram", nativeOf("SysML IBD"));
        assertEquals("SysML Internal Block Diagram", nativeOf("internal_block-diagram"));
        assertEquals("Class Diagram", nativeOf("ClassDiagram"));
        assertEquals("State Machine Diagram", nativeOf("StateMachineDiagram"));
        assertEquals("State Machine Diagram", nativeOf("state machine diagram"));
        assertEquals("Relation Map Diagram", nativeOf("relationship map"));
        assertEquals("Use Case Diagram", nativeOf("usecasediagram"));
    }

    @Test
    void requirementDiagramHasFallback() {
        assertEquals(Arrays.asList("Requirement Diagram", "SysML Requirement Diagram"),
                DiagramTypes.nativeCandidates("requirements"));
        assertEquals("Requirement Diagram", DiagramTypes.find("SysML Requirement Diagram").canonical);
    }

    @Test
    void unknownTypesPassThroughTrimmed() {
        assertNull(DiagramTypes.find("SysML Activity Diagram"));
        assertEquals(Collections.singletonList("SysML Activity Diagram"),
                DiagramTypes.nativeCandidates("  SysML Activity Diagram "));
    }

    @Test
    void lookupKeySplitsCamelCase() {
        assertEquals("internal block diagram", DiagramTypes.lookupKey("InternalBlockDiagram"));
        assertEquals("sys ml ibd", DiagramTypes.lookupKey("SysML IBD"));
    }

    @Test
    void listJsonHasAllTypes() {
        JsonObject json = DiagramTypes.listJson();
        assertEquals(20, json.get("count").getAsInt());
        assertTrue(json.getAsJsonArray("diagramTypes").get(0).getAsJsonObject().has("aliases"));
        assertTrue(DiagramTypes.isRelationMap("Relation Map Diagram"));
    }
}
