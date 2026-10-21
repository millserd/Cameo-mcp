// family: artifacts
package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the artifact helper classes: string-list parsing, scalar helpers, type tokens, traceability
 * direction and filters, explicit roots, criteria templates, reflective calls and probe guards.
 */
class ArtifactHelpersTest {

    @Test
    void stringListsAcceptArraysCsvAndJsonText() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        a.add("x");
        a.add("y");
        o.add("arr", a);
        o.addProperty("csv", "a, b,,c");
        o.addProperty("json", "[\"p\",\"q\"]");
        o.add("empty", new JsonArray());
        assertEquals(Arrays.asList("x", "y"), ArtifactSupport.optStrList(o, "arr"));
        assertEquals(Arrays.asList("a", "b", "c"), ArtifactSupport.optStrList(o, "csv"));
        assertEquals(Arrays.asList("p", "q"), ArtifactSupport.optStrList(o, "json"));
        assertEquals(Collections.emptyList(), ArtifactSupport.optStrList(o, "empty"));
        assertNull(ArtifactSupport.optStrList(o, "missing"));
    }

    @Test
    void scalarHelpers() {
        JsonObject o = Gs.obj("s", "  v ", "blank", " ", "n", 7, "t", 1000.0, "b", "true");
        assertEquals("v", ArtifactSupport.optStr(o, "s"));
        assertNull(ArtifactSupport.optStr(o, "blank"));
        assertEquals(7, ArtifactSupport.optInt(o, "n"));
        assertNull(ArtifactSupport.optInt(o, "missing"));
        assertTrue(ArtifactSupport.optBool(o, "b"));
        assertEquals(600, ArtifactSupport.timeoutSeconds(o, "t"));
        assertEquals(120, ArtifactSupport.timeoutSeconds(o, "missing"));
        assertEquals(1, ArtifactSupport.timeoutSeconds(Gs.obj("t", 0.2), "t"));
        assertEquals(12, ArtifactSupport.clamp(50, 1, 12, 3));
        assertEquals(3, ArtifactSupport.clamp(null, 1, 12, 3));
    }

    @Test
    void typeTokenNormalization() {
        assertEquals("ValueProperty", ArtifactSupport.normalizeTypeName("value-property"));
        assertEquals("OpaqueAction", ArtifactSupport.normalizeTypeName("opaque action"));
        assertEquals("valueproperty", ArtifactSupport.normalizeTypeToken("value_Property"));
    }

    @Test
    void traceabilityDirectionAndFilter() {
        assertEquals("both", TraceabilityGraph.normalizeDirection(null));
        assertEquals("both", TraceabilityGraph.normalizeDirection(" Both "));
        assertEquals("incoming", TraceabilityGraph.normalizeDirection("INCOMING"));
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> TraceabilityGraph.normalizeDirection("sideways")).code());
        Set<String> f = TraceabilityGraph.normalizedFilter(Arrays.asList("DeriveReqt", "Derive Reqt", "satisfy", ""));
        assertEquals(2, f.size());
        assertTrue(f.contains("derivereqt"));
        assertTrue(f.contains("satisfy"));
    }

    @Test
    void explicitRootsDetection() {
        assertFalse(TraceabilityGraph.hasExplicitRoots(Gs.obj("relation_map_id", "rm")));
        assertTrue(TraceabilityGraph.hasExplicitRoots(Gs.obj("context_element_id", "e")));
        assertTrue(TraceabilityGraph.hasExplicitRoots(Gs.obj("root_element_ids", List.of("a"))));
        assertFalse(TraceabilityGraph.hasExplicitRoots(Gs.obj("root_element_ids", List.of())));
    }

    @Test
    void criteriaTemplates() {
        assertEquals(15, CriteriaTemplates.all().size());
        CriteriaTemplates.Template raw = CriteriaTemplates.get("dependency.direct");
        assertFalse(raw.isDsl());
        JsonObject rawJson = raw.toJson();
        assertEquals("Dependency", rawJson.get("rawExpression").getAsString());
        assertEquals("raw", rawJson.get("expressionKind").getAsString());
        CriteriaTemplates.Template satisfy = CriteriaTemplates.get(" satisfy.both ");
        assertTrue(satisfy.isDsl());
        JsonObject j = satisfy.toJson();
        assertEquals("Satisfy:both", j.get("rawExpression").getAsString());
        assertEquals("dslRelation", j.get("expressionKind").getAsString());
        assertEquals("BOTH", j.get("dslDirection").getAsString());
        assertTrue(j.get("verifiedWithUiDiff").getAsBoolean());
        assertFalse(CriteriaTemplates.get("satisfy.sourceToTarget").verifiedWithUiDiff);
        assertNull(CriteriaTemplates.get("nope"));
        assertEquals(15, CriteriaTemplates.listing().get("count").getAsInt());
    }

    /** Fixture for Reflect: int vs Integer parameters and statics. */
    public static class Target {
        public int last;

        public void setDepth(int d) {
            last = d;
        }

        public Integer getDepth() {
            return last;
        }

        public void take(long v) {
            last = (int) v;
        }

        public static String hello(String who) {
            return "hi " + who;
        }

        public void boom() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void reflectMatchesBoxedAndPrimitiveParameters() throws Exception {
        Target t = new Target();
        Reflect.invoke(t, "setDepth", Integer.valueOf(4));
        assertEquals(4, Reflect.invoke(t, "getDepth"));
        Reflect.invoke(t, "take", Integer.valueOf(9));
        assertEquals(9, t.last);
        assertEquals("hi x", Reflect.invokeStatic(Target.class, "hello", "x"));
        assertThrows(NoSuchMethodException.class, () -> Reflect.invoke(t, "setDepth", "four"));
        assertThrows(NoSuchMethodException.class, () -> Reflect.invoke(t, "hello", "x"));
        assertThrows(IllegalStateException.class, () -> Reflect.invoke(t, "boom"));
        assertNull(Reflect.tryInvoke(t, "missing"));
        assertTrue(Reflect.hasMethod(Target.class, "setDepth", 1));
    }

    @Test
    void probeTemplatesAndGuards() throws Exception {
        assertEquals(5, Probes.templates().get("count").getAsInt());
        assertTrue(Probes.allowedClassName("com.nomagic.magicdraw.core.Project"));
        assertFalse(Probes.allowedClassName("java.lang.Runtime"));

        ToolException write = assertThrows(ToolException.class,
                () -> Probes.execute(Gs.obj("mode", "write", "requires_project", false)));
        assertEquals("PRECONDITION_FAILED", write.code());

        JsonObject refused = Probes.execute(Gs.obj("language", "groovy", "script", "println 1",
                "requires_project", false, "description", "why"));
        assertFalse(refused.get("executed").getAsBoolean());
        assertTrue(refused.get("refused").getAsBoolean());
        assertEquals(9, refused.get("scriptLength").getAsInt());
        assertEquals("why", refused.get("description").getAsString());

        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class, () -> Probes.execute(
                Gs.obj("requires_project", false, "class_name", "java.lang.Runtime"))).code());
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class, () -> Probes.execute(
                Gs.obj("requires_project", false, "operation", "deleteEverything"))).code());
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class, () -> Probes.execute(
                Gs.obj("requires_project", false, "template", "nope.unknown"))).code());

        JsonObject methods = Probes.execute(Gs.obj("requires_project", false,
                "class_name", "com.nomagic.magicdraw.core.Project"));
        assertEquals("com.nomagic.magicdraw.core.Project", methods.get("className").getAsString());
        assertTrue(methods.getAsJsonArray("methods").size() > 0);

        assertEquals("NO_PROJECT", assertThrows(ToolException.class,
                () -> Probes.execute(Gs.obj("template", "ui.dumpSelectionApi"))).code());
    }
}
