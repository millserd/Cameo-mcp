// family: server
package com.cameomcp.plugin.tools;

import com.cameomcp.json.tree.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Bind}: a binding is registered under both its snake_case and camelCase argument
 * spellings.
 */
class BindTest {
    @Test
    void addsBothSpellings() {
        JsonObject in = new JsonObject();
        in.addProperty("element_id", "E1");
        in.addProperty("parentId", "P1");
        in.addProperty("name", "n");
        JsonObject out = Bind.withAliases(in);
        assertEquals("E1", out.get("elementId").getAsString());
        assertEquals("P1", out.get("parent_id").getAsString());
        assertEquals(5, out.size(), "name has no twin");
    }
}
