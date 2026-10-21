// family: advanced
package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParser;
import com.cameomcp.json.tree.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CriteriaExpressions}: the template table, filtering by target, kind and direction
 * canonicalization, building from templates, rejection of bad input and parsing.
 */
class CriteriaExpressionsTest {

    @Test
    void templateTableMatchesReference() {
        JsonArray all = CriteriaExpressions.templates(null);
        assertEquals(9, all.size());
        JsonObject first = all.get(0).getAsJsonObject();
        assertEquals("refine.targetToSource", first.get("id").getAsString());
        assertTrue(first.get("verifiedWithUiDiff").getAsBoolean());
        assertEquals("Refine", first.getAsJsonObject("expression").get("relationshipKind").getAsString());
        int verified = 0;
        for (JsonElement e : all) {
            if (e.getAsJsonObject().get("verifiedWithUiDiff").getAsBoolean()) {
                verified++;
            }
        }
        assertEquals(6, verified);
    }

    @Test
    void templatesFilterByTarget() {
        assertEquals(1, CriteriaExpressions.templates("matrix").size());
        assertEquals("dependency.direct", CriteriaExpressions.templates("TABLE").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(9, CriteriaExpressions.templates("relationMap").size());
        assertEquals(0, CriteriaExpressions.templates("legend").size());
    }

    @Test
    void canonicalizesKindsAndDirections() {
        assertEquals("DeriveReqt", CriteriaExpressions.canonicalKind("derive"));
        assertEquals("DeriveReqt", CriteriaExpressions.canonicalKind("«deriveReqt»"));
        assertEquals("Satisfy", CriteriaExpressions.canonicalKind(" satisfy "));
        assertEquals("Allocate", CriteriaExpressions.canonicalKind("allocatedTo"));
        assertEquals("Custom", CriteriaExpressions.canonicalKind("Custom"));
        assertEquals("sourceToTarget", CriteriaExpressions.canonicalDirection("forward"));
        assertEquals("targetToSource", CriteriaExpressions.canonicalDirection("target_to_source"));
        assertEquals("both", CriteriaExpressions.canonicalDirection(null));
        assertNull(CriteriaExpressions.canonicalDirection("sideways"));
        assertEquals("refine", CriteriaExpressions.relationshipKindFromId("refine.both"));
        assertEquals("", CriteriaExpressions.relationshipKindFromId(null));
    }

    @Test
    void buildMatchesVerifiedTemplates() {
        JsonObject e = CriteriaExpressions.build(null, "satisfy", "both", "relationMap");
        assertEquals("Satisfy", e.get("relationshipKind").getAsString());
        assertEquals("satisfy.both", e.get("templateId").getAsString());
        assertTrue(e.get("verifiedWithUiDiff").getAsBoolean());
        assertFalse(e.has("warnings"));

        JsonObject unverified = CriteriaExpressions.build(null, "Satisfy", "sourceToTarget", null);
        assertEquals("satisfy.sourceToTarget", unverified.get("templateId").getAsString());
        assertFalse(unverified.get("verifiedWithUiDiff").getAsBoolean());

        JsonObject noTemplate = CriteriaExpressions.build(null, "Verify", null, "matrix");
        assertEquals("both", noTemplate.get("direction").getAsString());
        assertFalse(noTemplate.has("templateId"));
        assertFalse(noTemplate.get("verifiedWithUiDiff").getAsBoolean());

        JsonObject wrongTarget = CriteriaExpressions.build(null, "Refine", "both", "matrix");
        assertTrue(wrongTarget.get("warnings").toString().contains("relationMap"));

        JsonObject unknownKind = CriteriaExpressions.build(null, "Frobnicate", "both", null);
        assertTrue(unknownKind.get("warnings").toString().contains("not a known"));
    }

    @Test
    void buildFromTemplateId() {
        JsonObject e = CriteriaExpressions.build("deriveReqt.targetToSource", null, null, null);
        assertEquals("DeriveReqt", e.get("relationshipKind").getAsString());
        assertEquals("targetToSource", e.get("direction").getAsString());
        assertTrue(e.get("verifiedWithUiDiff").getAsBoolean());
        assertThrows(ToolException.class, () -> CriteriaExpressions.build("nope.both", null, null, null));
    }

    @Test
    void buildRejectsBadInput() {
        assertEquals("INVALID_PARAMS",
                assertThrows(ToolException.class, () -> CriteriaExpressions.build(null, null, "both", null)).code());
        assertEquals("INVALID_PARAMS",
                assertThrows(ToolException.class, () -> CriteriaExpressions.build(null, "Satisfy", "sideways", null)).code());
    }

    @Test
    void parsesObjectsStringsAndTemplateIds() {
        JsonObject fromObject = CriteriaExpressions.parse(JsonParser.parseString(
                "{\"relationshipKind\":\"refine\",\"direction\":\"incoming\"}"));
        assertTrue(fromObject.get("parsed").getAsBoolean());
        assertEquals("object", fromObject.get("inputForm").getAsString());
        assertEquals("refine.targetToSource", fromObject.get("matchedTemplateId").getAsString());
        assertTrue(fromObject.get("verifiedWithUiDiff").getAsBoolean());

        JsonObject fromId = CriteriaExpressions.parse(new JsonPrimitive("satisfy.both"));
        assertEquals("templateId", fromId.get("inputForm").getAsString());
        assertEquals("Satisfy", fromId.getAsJsonObject("criteriaExpression").get("relationshipKind").getAsString());

        JsonObject fromText = CriteriaExpressions.parse(new JsonPrimitive("Allocate: sourceToTarget"));
        assertEquals("allocate.sourceToTarget", fromText.get("matchedTemplateId").getAsString());
        assertFalse(fromText.get("verifiedWithUiDiff").getAsBoolean());

        JsonObject dotted = CriteriaExpressions.parse(new JsonPrimitive("verify.forward"));
        assertEquals("Verify", dotted.getAsJsonObject("criteriaExpression").get("relationshipKind").getAsString());
        assertEquals("sourceToTarget", dotted.getAsJsonObject("criteriaExpression").get("direction").getAsString());

        JsonObject jsonString = CriteriaExpressions.parse(new JsonPrimitive("{\"templateId\":\"allocate.both\"}"));
        assertEquals("Allocate", jsonString.getAsJsonObject("criteriaExpression").get("relationshipKind").getAsString());

        JsonObject buildResult = CriteriaExpressions.parse(JsonParser.parseString(
                "{\"built\":true,\"criteriaExpression\":{\"relationshipKind\":\"DeriveReqt\",\"direction\":\"both\"}}"));
        assertEquals("deriveReqt.both", buildResult.get("matchedTemplateId").getAsString());
    }

    @Test
    void parseReportsIssues() {
        JsonObject bad = CriteriaExpressions.parse(new JsonPrimitive("Satisfy sideways"));
        assertFalse(bad.get("parsed").getAsBoolean());
        assertTrue(bad.get("issues").toString().contains("Unrecognized direction"));

        JsonObject empty = CriteriaExpressions.parse(JsonParser.parseString("{}"));
        assertFalse(empty.get("parsed").getAsBoolean());
        assertTrue(empty.get("issues").toString().contains("No relationship kind"));

        assertThrows(ToolException.class, () -> CriteriaExpressions.parse(null));
        assertThrows(ToolException.class, () -> CriteriaExpressions.parse(JsonParser.parseString("[1]")));
        assertThrows(ToolException.class, () -> CriteriaExpressions.parse(new JsonPrimitive("{broken")));
    }
}
