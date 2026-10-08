// family: sysml2
package com.cameomcp.plugin.tools.sysml2;

import com.cameomcp.catalog.Catalog;
import com.cameomcp.catalog.Sysml2Tools;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.cameomcp.plugin.tools.sysml2.FakeKerml.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sysml2ModelTest {

    private static FakeKerml.Node vehicleModel() {
        return node("Namespace", null,
                node("Package", "Vehicle",
                        node("PartDefinition", "Vehicle",
                                node("PartUsage", "engine"),
                                node("PartUsage", "wheels")),
                        node("PartDefinition", "Engine",
                                node("AttributeUsage", "power")),
                        node("RequirementDefinition", "MassLimit")));
    }

    @Test
    void detectsKermlElementsByInterfaceName() {
        assertTrue(Sysml2Model.isElement(node("Package", "P")));
        assertFalse(Sysml2Model.isElement("Package"));
        assertFalse(Sysml2Model.isElement(null));
    }

    @Test
    void compactJsonReadsThroughPublicInterfaces() {
        FakeKerml.Node root = vehicleModel();
        Object pkg = Sysml2Model.children(root).get(0);
        JsonObject json = Sysml2Model.compact(pkg);
        assertEquals("Package", json.get("type").getAsString());
        assertEquals("Vehicle", json.get("name").getAsString());
        assertEquals(3, json.get("childCount").getAsInt());
        assertTrue(json.get("id").getAsString().startsWith("local-"));
        assertTrue(json.get("elementId").getAsString().startsWith("uuid-"));
        assertFalse(json.has("isLibraryElement"));
    }

    @Test
    void detailListsOwnerAndMembers() {
        FakeKerml.Node root = vehicleModel();
        Object pkg = Sysml2Model.children(root).get(0);
        Object vehicle = Sysml2Model.children(pkg).get(0);
        JsonObject json = Sysml2Model.detail(vehicle, 1);
        assertEquals("Vehicle", json.getAsJsonObject("owner").get("name").getAsString());
        assertEquals(1, json.getAsJsonArray("ownedElements").size());
        assertTrue(json.get("ownedElementsTruncated").getAsBoolean());
        assertEquals("Vehicle::Vehicle", json.get("qualifiedName").getAsString());
    }

    @Test
    void queryFiltersByTypeAndNameAndPages() {
        List<Object> roots = List.of(vehicleModel());
        JsonObject parts = Sysml2Model.query(roots, null, "partdefinition", false, 10, 0);
        assertEquals(2, parts.get("count").getAsInt());
        assertFalse(parts.get("hasMore").getAsBoolean());

        JsonObject byName = Sysml2Model.query(roots, "ENG", null, false, 10, 0);
        JsonArray found = byName.getAsJsonArray("elements");
        Set<String> names = new HashSet<>();
        found.forEach(e -> names.add(e.getAsJsonObject().get("name").getAsString()));
        assertEquals(new HashSet<>(Arrays.asList("engine", "Engine")), names);

        JsonObject page1 = Sysml2Model.query(roots, null, "PartUsage", false, 1, 0);
        assertEquals(1, page1.get("count").getAsInt());
        assertTrue(page1.get("hasMore").getAsBoolean());
        assertEquals(1, page1.get("nextOffset").getAsInt());
        JsonObject page2 = Sysml2Model.query(roots, null, "PartUsage", false, 1, 1);
        assertEquals("wheels", page2.getAsJsonArray("elements").get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(page2.get("hasMore").getAsBoolean());
    }

    @Test
    void querySkipsLibrariesUnlessAsked() {
        FakeKerml.Node lib = node("Namespace", null, node("PartDefinition", "Part").library()).library();
        List<Object> roots = List.of(vehicleModel(), lib);
        assertEquals(2, Sysml2Model.query(roots, null, "PartDefinition", false, 10, 0).get("count").getAsInt());
        assertEquals(3, Sysml2Model.query(roots, null, "PartDefinition", true, 10, 0).get("count").getAsInt());
    }

    @Test
    void findsByLocalIdOrElementIdAndCounts() {
        FakeKerml.Node root = vehicleModel();
        Object engine = Sysml2Model.children(Sysml2Model.children(root).get(0)).get(1);
        String elementId = Sysml2Model.str(engine, "getElementId");
        String localId = Sysml2Model.str(engine, "getID");
        assertSame(engine, Sysml2Model.findUnder(List.of(root), elementId));
        assertSame(engine, Sysml2Model.findUnder(List.of(root), localId));
        assertNull(Sysml2Model.findUnder(List.of(root), "nope"));
        assertEquals(8, Sysml2Model.count(root));
        assertEquals(2, Sysml2Model.count(engine));
    }

    @Test
    void sliceCutsNestedTextAndDedents() {
        String text = "package P {\n    part def Engine {\n        attribute power;\n    }\n    part def Wheel;\n}\n";
        int start = text.indexOf("part def Engine");
        int end = text.indexOf("}", start) + 1;
        assertEquals("part def Engine {\n    attribute power;\n}", Sysml2Model.slice(text, start, end));
        // inclusive end index: the closing brace is pulled in
        assertEquals("part def Engine {\n    attribute power;\n}", Sysml2Model.slice(text, start, end - 1));
        int wheel = text.indexOf("part def Wheel");
        assertEquals("part def Wheel;", Sysml2Model.slice(text, wheel, text.indexOf(";", wheel)));
        assertEquals("", Sysml2Model.slice(text, -1, 4));
        assertEquals("", Sysml2Model.slice(text, 5, 5));
    }

    @Test
    void everySysml2SpecIsBoundAndWellFormed() {
        ToolRegistry r = new ToolRegistry().addSpecs(Sysml2Tools.specs());
        Sysml2Bindings.bind(r);
        assertTrue(r.unboundTools().isEmpty(), "unbound: " + r.unboundTools());
        assertEquals(10, r.size());
        assertTrue(Catalog.FAMILIES.contains(Sysml2Tools.class.getName()));
        for (ToolSpec s : Sysml2Tools.specs()) {
            assertTrue(s.name().startsWith("cameo_sysml2_"), s.name());
            assertEquals("sysml2", s.family());
            for (Object p : s.parameters().keySet()) {
                assertTrue(((String) p).matches("[a-z][a-z0-9_]*"), s.name() + "." + p);
            }
            boolean writes = s.name().endsWith("create_from_text") || s.name().endsWith("rename_element")
                    || s.name().endsWith("delete_element");
            assertEquals(!writes, s.isReadOnly(), s.name());
        }
    }
}
