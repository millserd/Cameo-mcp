// family: model
package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.ToolException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of the reference's test_state_machine_semantics.py validation rules. */
class StateMachineSemanticsTest {

    @Test
    void triggerKindIsNormalized() {
        assertEquals("change", ModelText.triggerKind(" Change ", "when ready", null));
        assertEquals("signal", ModelText.triggerKind("SIGNAL", null, "sig-9"));
    }

    @Test
    void changeTriggersRequireExpression() {
        ToolException e = assertThrows(ToolException.class, () -> ModelText.triggerKind("change", "", null));
        assertTrue(e.getMessage().contains("change triggers require a non-empty expression"));
        assertThrows(ToolException.class, () -> ModelText.triggerKind("change", "   ", null));
        assertThrows(ToolException.class, () -> ModelText.triggerKind("change", null, "sig"));
    }

    @Test
    void signalTriggersRequireSignalId() {
        ToolException e = assertThrows(ToolException.class, () -> ModelText.triggerKind("signal", null, null));
        assertTrue(e.getMessage().contains("signal triggers require signal_id"));
        assertThrows(ToolException.class, () -> ModelText.triggerKind("signal", "x", " "));
    }

    @Test
    void unknownTriggerKindIsRejected() {
        ToolException e = assertThrows(ToolException.class, () -> ModelText.triggerKind("time", "after 5s", null));
        assertEquals("INVALID_PARAMS", e.code());
        assertThrows(ToolException.class, () -> ModelText.triggerKind(null, "x", "y"));
    }

    @Test
    void stateBehaviorSlotRules() {
        // omitted slot: unchanged unless clear_unspecified
        assertEquals(ModelText.SlotAction.KEEP, ModelText.slotAction(null, false));
        assertEquals(ModelText.SlotAction.CLEAR, ModelText.slotAction(null, true));
        // empty string clears that specific slot
        assertEquals(ModelText.SlotAction.CLEAR, ModelText.slotAction("", false));
        assertEquals(ModelText.SlotAction.CLEAR, ModelText.slotAction("  ", true));
        // text sets it
        assertEquals(ModelText.SlotAction.SET, ModelText.slotAction("initialize reader", false));
    }

    @Test
    void languageMustBeNonEmpty() {
        assertEquals("StructuredText", ModelText.requireLanguage("StructuredText"));
        ToolException e = assertThrows(ToolException.class, () -> ModelText.requireLanguage(" "));
        assertTrue(e.getMessage().contains("language must be a non-empty string"));
        assertThrows(ToolException.class, () -> ModelText.requireLanguage(null));
    }
}
