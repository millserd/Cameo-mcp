// family: diagrams
package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeFilterTest {

    private static JsonArray shapes() {
        return JsonParser.parseString("["
                + "{\"presentationId\":\"p1\",\"shapeType\":\"SwimlaneView\",\"elementType\":\"Activity\",\"bounds\":{\"x\":0},\"childCount\":2},"
                + "{\"presentationId\":\"p2\",\"shapeType\":\"SwimlaneHeaderView\",\"elementType\":\"Activity Partition\",\"parentPresentationId\":\"p1\"},"
                + "{\"presentationId\":\"p3\",\"shapeType\":\"OpaqueActionView\",\"elementType\":\"Opaque Action\",\"parentPresentationId\":\"p1\"},"
                + "{\"presentationId\":\"p4\",\"shapeType\":\"ControlFlowView\",\"elementType\":\"Control Flow\"},"
                + "{\"presentationId\":\"p5\",\"shapeType\":\"OpaqueActionView\",\"elementType\":\"Opaque Action\"}"
                + "]").getAsJsonArray();
    }

    @Test
    void pagesAndReportsNextOffset() {
        ShapeFilter.Options o = new ShapeFilter.Options();
        o.limit = 2;
        o.offset = 1;
        JsonObject r = ShapeFilter.filter("d", shapes(), o);
        assertEquals(2, r.get("count").getAsInt());
        assertEquals(5, r.get("totalCount").getAsInt());
        assertTrue(r.get("hasMore").getAsBoolean());
        assertEquals(3, r.get("nextOffset").getAsInt());
        assertEquals("p2", r.getAsJsonArray("shapes").get(0).getAsJsonObject().get("presentationId").getAsString());
    }

    @Test
    void filtersCaseInsensitivelyAndByParent() {
        ShapeFilter.Options o = new ShapeFilter.Options();
        o.shapeType = "opaqueactionview";
        assertEquals(2, ShapeFilter.filter("d", shapes(), o).get("totalCount").getAsInt());
        o.parentPresentationId = "p1";
        JsonObject r = ShapeFilter.filter("d", shapes(), o);
        assertEquals(1, r.get("totalCount").getAsInt());
        assertFalse(r.get("hasMore").getAsBoolean());
        assertFalse(r.has("nextOffset"));
        assertEquals("p1", r.getAsJsonObject("filters").get("parentPresentationId").getAsString());
    }

    @Test
    void offsetBeyondEndIsClamped() {
        ShapeFilter.Options o = new ShapeFilter.Options();
        o.offset = 99;
        JsonObject r = ShapeFilter.filter("d", shapes(), o);
        assertEquals(5, r.get("offset").getAsInt());
        assertEquals(0, r.getAsJsonArray("shapes").size());
    }

    @Test
    void projectionDropsBoundsAndChildCountWithoutMutatingInput() {
        JsonArray input = shapes();
        ShapeFilter.Options o = new ShapeFilter.Options();
        o.includeBounds = false;
        o.includeChildCount = false;
        JsonObject first = ShapeFilter.filter("d", input, o).getAsJsonArray("shapes").get(0).getAsJsonObject();
        assertFalse(first.has("bounds"));
        assertFalse(first.has("childCount"));
        assertTrue(input.get(0).getAsJsonObject().has("bounds"));
    }

    @Test
    void summaryCountsSortedByCountThenName() {
        ShapeFilter.Options o = new ShapeFilter.Options();
        o.summaryOnly = true;
        JsonObject r = ShapeFilter.filter("d", shapes(), o);
        assertFalse(r.has("shapes"));
        JsonObject counts = r.getAsJsonObject("shapeTypeCounts");
        assertEquals("OpaqueActionView", counts.keySet().iterator().next());
        assertEquals(2, counts.get("OpaqueActionView").getAsInt());
        assertEquals(2, r.get("parentedShapeCount").getAsInt());
        assertEquals(4, r.getAsJsonObject("elementTypeCounts").size());
    }
}
