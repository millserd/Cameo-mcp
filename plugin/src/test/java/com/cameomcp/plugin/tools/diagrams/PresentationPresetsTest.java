// family: diagrams
package com.cameomcp.plugin.tools.diagrams;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationPresetsTest {

    @Test
    void suppressPropertiesAreInverted() {
        PresentationPresets.Selection s = PresentationPresets.forCanonicalKey("showAttributes", true);
        assertTrue(s.matches("Suppress Attributes"));
        assertFalse(s.valueFor("Suppress Attributes"));
        assertTrue(PresentationPresets.forCanonicalKey("showPorts", true).valueFor("Show Ports"));
    }

    @Test
    void triggerSelectionMatchesVariants() {
        PresentationPresets.Selection s = PresentationPresets.forCanonicalKey("showTriggers", true);
        assertTrue(s.matches("Show Triggers"));
        assertTrue(s.matches("Show Event"));
        assertFalse(s.matches("Show Guard"));
    }

    @Test
    void unknownKeyFallsBackToExactAndToken() {
        PresentationPresets.Selection s = PresentationPresets.forCanonicalKey("Show Full Path", false);
        assertTrue(s.matches("Show Full Path"));
        assertFalse(s.valueFor("Show Full Path"));
    }

    @Test
    void repairDefaultsDependOnDiagramType() {
        PresentationPresets.RepairDefaults sm = PresentationPresets.repairDefaults("State Machine Diagram");
        assertEquals(Arrays.asList("showName", "showTriggers", "showGuard", "showEffect"), sm.hiddenLabelKeys);
        PresentationPresets.RepairDefaults ibd = PresentationPresets.repairDefaults("SysML Internal Block Diagram");
        assertTrue(ibd.compartmentKeys.contains("showAllocatedElements"));
        assertTrue(ibd.conveyedItemKeys.contains("showStereotype"));
        PresentationPresets.RepairDefaults bdd = PresentationPresets.repairDefaults("SysML Block Definition Diagram");
        assertEquals(Arrays.asList("showName", "showStereotype", "showType"), bdd.hiddenLabelKeys);
        PresentationPresets.RepairDefaults uc = PresentationPresets.repairDefaults("Use Case Diagram");
        assertTrue(uc.compartmentKeys.isEmpty());
        assertEquals("", PresentationPresets.repairDefaults(null).diagramType);
        assertEquals("statemachinediagram", sm.toJson().get("normalizedDiagramType").getAsString());
    }

    @Test
    void presetMapsAndCompartmentKeys() {
        Map<String, PresentationPresets.Selection> m =
                PresentationPresets.itemFlowLabels(false, true, true, true, false);
        assertTrue(m.get("showConveyed").matches("Show Conveyed Information"));
        assertTrue(PresentationPresets.isAllocationProperty("Show Allocated Elements"));
        assertEquals("tagged_values", PresentationPresets.canonicalCompartmentKey("Show Tagged Values"));
        assertEquals("attributes", PresentationPresets.canonicalCompartmentKey("Suppress Attributes"));
        assertNull(PresentationPresets.canonicalCompartmentKey("Fill Color"));
    }
}
