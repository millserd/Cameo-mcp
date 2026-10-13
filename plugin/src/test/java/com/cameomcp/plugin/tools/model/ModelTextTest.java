// family: model
package com.cameomcp.plugin.tools.model;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.ToolException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTextTest {

    @Test
    void elementKindsAcceptReferenceAliases() {
        assertEquals("usecase", ModelText.elementKind("Use-Case"));
        assertEquals("usecase", ModelText.elementKind("UseCase"));
        assertEquals("statemachine", ModelText.elementKind("state machine"));
        assertEquals("pseudostate", ModelText.elementKind("InitialState"));
        assertEquals("pseudostate", ModelText.elementKind("initial-state"));
        assertEquals("interfaceblock", ModelText.elementKind("InterfaceBlock"));
        assertEquals("flowproperty", ModelText.elementKind("flow-property"));
        assertEquals("activityfinalnode", ModelText.elementKind("final-node"));
        assertEquals("flowfinalnode", ModelText.elementKind("FlowFinalNode"));
        assertEquals("callbehavioraction", ModelText.elementKind("action"));
        assertEquals("activitypartition", ModelText.elementKind("partition"));
        assertNull(ModelText.elementKind("Spaceship"));
    }

    @Test
    void relationshipKinds() {
        assertEquals("directedassociation", ModelText.relationshipKind("Directed-Association"));
        assertEquals("controlflow", ModelText.relationshipKind("control_flow"));
        assertEquals("derive", ModelText.relationshipKind("DeriveReqt"));
        assertEquals("itemflow", ModelText.relationshipKind("item-flow"));
        assertEquals("DeriveReqt", ModelText.abstractionStereotype("derive"));
        assertEquals("Satisfy", ModelText.abstractionStereotype("satisfy"));
        assertNull(ModelText.abstractionStereotype("association"));
        assertNull(ModelText.relationshipKind("Friendship"));
    }

    @Test
    void creationStereotypesPutSysmlDefaultFirstAndDeduplicate() {
        assertEquals(Arrays.asList("block"), ModelText.effectiveCreationStereotypes("block", "Block"));
        assertEquals(Arrays.asList("requirement", "extendedRequirement"),
                ModelText.effectiveCreationStereotypes("requirement", "extendedRequirement"));
        assertEquals(Arrays.asList("custom"), ModelText.effectiveCreationStereotypes("class", "custom"));
        assertTrue(ModelText.effectiveCreationStereotypes("class", null).isEmpty());
        assertEquals("flowProperty", ModelText.defaultCreationStereotype("flowproperty"));
    }

    @Test
    void stereotypePreferenceRanksProfilesAndDetectsAmbiguity() {
        List<String> prefs = ModelText.preferredStereotypeContexts("Satisfy");
        assertEquals("Requirements", prefs.get(0));
        assertEquals(Arrays.asList("Allocations", "SysML", "SysMLProfile"), ModelText.preferredStereotypeContexts("allocate"));
        assertEquals("sysmlprofile", ModelText.normalizeContextToken("SysML Profile"));
        assertEquals("portsandflows", ModelText.normalizeContextToken("Ports & Flows"));

        List<List<String>> candidates = new ArrayList<>();
        candidates.add(Arrays.asList("MyProfile"));
        candidates.add(Arrays.asList("Requirements", "SysML"));
        assertEquals(1, ModelText.pickPreferred(candidates, prefs));

        List<List<String>> tie = new ArrayList<>();
        tie.add(Arrays.asList("SysML"));
        tie.add(Arrays.asList("SysML Profile", "SysML"));
        assertEquals(-1, ModelText.pickPreferred(tie, ModelText.preferredStereotypeContexts("block")),
                "a tie on the best rank is ambiguous");
        List<List<String>> none = new ArrayList<>();
        none.add(Arrays.asList("A"));
        none.add(Arrays.asList("B"));
        assertEquals(-1, ModelText.pickPreferred(none, prefs));
        List<List<String>> single = new ArrayList<>();
        single.add(Arrays.asList("Anything"));
        assertEquals(0, ModelText.pickPreferred(single, prefs));
    }

    @Test
    void typeNamesNormalize() {
        assertEquals("UseCase", ModelText.normalizeTypeName("use-case"));
        assertEquals("StateMachine", ModelText.normalizeTypeName("state_machine"));
        assertTrue(ModelText.typeMatches("package", "Package"));
        assertTrue(ModelText.typeMatches("use case", null, "UseCase"));
        assertFalse(ModelText.typeMatches("Class", "Package"));
    }

    @Test
    void viewValidation() {
        assertEquals("compact", ModelText.view(null));
        assertEquals("full", ModelText.view(" FULL "));
        assertThrows(ToolException.class, () -> ModelText.view("verbose"));
    }

    @Test
    void pagingMatchesReferenceLayout() {
        int[] range = new int[2];
        Map<String, Object> p = ModelText.page(120, 50, 50, range);
        assertEquals(50, range[0]);
        assertEquals(100, range[1]);
        assertEquals(true, p.get("hasMore"));
        assertEquals(100, p.get("nextOffset"));
        assertEquals("offset:100", p.get("nextCursor"));
        assertEquals(0, p.get("previousOffset"));

        Map<String, Object> last = ModelText.page(120, 500, 50, range);
        assertEquals(120, range[0]);
        assertEquals(120, range[1]);
        assertEquals(false, last.get("hasMore"));
        assertEquals(120, last.get("offset"));
        assertFalse(last.containsKey("nextOffset"));
    }

    @Test
    void multiplicityBounds() {
        assertEquals(-1, ModelText.parseUpper("*"));
        assertEquals(-1, ModelText.parseUpper(-1));
        assertEquals(5, ModelText.parseUpper("5"));
        assertEquals(3, ModelText.parseUpper(3.0));
        assertNull(ModelText.parseUpper(null));
        assertThrows(ToolException.class, () -> ModelText.parseUpper("many"));
        assertEquals(0, ModelText.parseLower(0L));
        assertThrows(ToolException.class, () -> ModelText.parseLower(-1));
        assertThrows(ToolException.class, () -> ModelText.parseLower("*"));
    }

    @Test
    void parameterDirections() {
        assertEquals("in", ModelText.parameterDirection("Input"));
        assertEquals("inout", ModelText.parameterDirection("in-out"));
        assertEquals("return", ModelText.parameterDirection("result"));
        assertThrows(ToolException.class, () -> ModelText.parameterDirection("sideways"));
    }

    // ------------------------------------------------------------------ bulk create plans

    private static Map<String, Object> entry(Object... kv) {
        return new LinkedHashMap<>(Json.obj(kv));
    }

    @Test
    void validPlanReturnsRefNames() {
        List<Map<String, Object>> plan = Arrays.asList(
                entry("ref", "pkg", "type", "Package", "name", "P", "parent_id", "_model"),
                entry("ref", "a", "type", "Block", "name", "A", "parent_id", "ref:pkg"),
                entry("type", "Block", "name", "B", "parentId", "ref:pkg"),
                entry("type", "Satisfy", "source_id", "ref:a", "target_id", "_req"));
        assertEquals(Arrays.asList("pkg", "a", null, null), ModelText.validatePlan(plan, 500));
    }

    @Test
    void planRejectsForwardRefsDuplicatesAndOversize() {
        ToolException fwd = assertThrows(ToolException.class, () -> ModelText.validatePlan(Arrays.asList(
                entry("type", "Block", "name", "A", "parent_id", "ref:pkg"),
                entry("ref", "pkg", "type", "Package", "name", "P", "parent_id", "_m")), 500));
        assertTrue(fwd.getMessage().contains("Entry 0"), fwd.getMessage());
        ToolException dup = assertThrows(ToolException.class, () -> ModelText.validatePlan(Arrays.asList(
                entry("ref", "x", "type", "Package", "name", "P", "parent_id", "_m"),
                entry("ref", "x", "type", "Package", "name", "Q", "parent_id", "_m")), 500));
        assertTrue(dup.getMessage().contains("already used by entry 0"), dup.getMessage());
        ToolException self = assertThrows(ToolException.class, () -> ModelText.validatePlan(Arrays.asList(
                entry("ref", "x", "type", "Package", "name", "P", "parent_id", "ref:x")), 500));
        assertTrue(self.getMessage().contains("ref:x"));
        assertThrows(ToolException.class, () -> ModelText.validatePlan(new ArrayList<>(), 500));
        List<Map<String, Object>> big = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            big.add(entry("type", "Package", "name", "P" + i, "parent_id", "_m"));
        }
        assertThrows(ToolException.class, () -> ModelText.validatePlan(big, 2));
    }

    @Test
    void resolveRefsReplacesIdFieldsAndLists() {
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("pkg", "_1");
        refs.put("item", "_2");
        Map<String, Object> resolved = ModelText.resolveRefs(entry(
                "type", "ItemFlow", "name", "ref:pkg", "owner_id", "REF:pkg",
                "conveyed_ids", Arrays.asList("ref:item", "_raw"), "source_id", "_s"), refs, "Entry 3");
        assertEquals("_1", resolved.get("owner_id"));
        assertEquals("ref:pkg", resolved.get("name"), "non-id fields are left alone");
        assertEquals(Arrays.asList("_2", "_raw"), resolved.get("conveyed_ids"));
        assertEquals("_s", resolved.get("source_id"));
        ToolException e = assertThrows(ToolException.class,
                () -> ModelText.resolveRefs(entry("parent_id", "ref:nope"), refs, "Entry 4"));
        assertTrue(e.getMessage().startsWith("Entry 4"));
    }

    @Test
    void relationshipEntriesAreDetected() {
        assertTrue(ModelText.isRelationshipEntry(entry("type", "Trace", "source_id", "a", "target_id", "b")));
        assertTrue(ModelText.isRelationshipEntry(entry("type", "Trace", "sourceId", "a")));
        assertFalse(ModelText.isRelationshipEntry(entry("type", "Block", "parent_id", "p")));
        assertTrue(ModelText.isRelationshipEntry(entry("kind", "relationship")));
        assertFalse(ModelText.isRelationshipEntry(entry("kind", "Element", "source_id", "x")));
        assertThrows(ToolException.class, () -> ModelText.isRelationshipEntry(entry("kind", "other")));
        assertTrue(ModelText.isIdField("parentId"));
        assertTrue(ModelText.isIdField("conveyed_ids"));
        assertFalse(ModelText.isIdField("name"));
        assertFalse(ModelText.isIdField("ref"));
    }
}
