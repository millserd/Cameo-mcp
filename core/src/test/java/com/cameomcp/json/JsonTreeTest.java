package com.cameomcp.json;

import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonNull;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParseException;
import com.cameomcp.json.tree.JsonParser;
import com.cameomcp.json.tree.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTreeTest {

    @Test
    void roundTripsAndKeepsNumberText() {
        String text = "{\"a\":1,\"b\":1.50,\"c\":-2e3,\"s\":\"x\\\"y\\n\\u0001\",\"t\":true,\"n\":null,\"arr\":[1,[],{}]}";
        JsonElement e = JsonParser.parseString(text);
        assertEquals("{\"a\":1,\"b\":1.50,\"c\":-2e3,\"s\":\"x\\\"y\\n\\u0001\",\"t\":true,\"n\":null,\"arr\":[1,[],{}]}",
                e.toString());
        JsonObject o = e.getAsJsonObject();
        assertEquals(1, o.get("a").getAsInt());
        assertEquals("1.50", o.get("b").getAsString());
        assertEquals(-2000.0, o.get("c").getAsDouble());
        assertTrue(o.get("n").isJsonNull());
        assertTrue(o.get("t").getAsBoolean());
    }

    @Test
    void numbersCompareByValue() {
        assertEquals(new JsonPrimitive(1), JsonParser.parseString("1.0"));
        assertEquals(new JsonPrimitive(2L), new JsonPrimitive(2));
        assertEquals(5, new JsonPrimitive("5").getAsInt());
    }

    @Test
    void nullsAndCopies() {
        JsonObject o = new JsonObject();
        o.addProperty("s", (String) null);
        o.add("x", null);
        JsonArray a = new JsonArray();
        a.add((String) null);
        o.add("a", a);
        assertEquals(JsonNull.INSTANCE, o.get("s"));
        assertEquals("{\"s\":null,\"x\":null,\"a\":[null]}", o.toString());
        JsonObject copy = o.deepCopy();
        assertEquals(o, copy);
        assertNotSame(o.get("a"), copy.get("a"));
        assertEquals("{\n  \"s\": null,\n  \"x\": null,\n  \"a\": [\n    null\n  ]\n}", o.toPrettyString());
    }

    @Test
    void singleElementArrayActsAsItsValue() {
        assertEquals("x", JsonParser.parseString("[\"x\"]").getAsString());
        assertThrows(IllegalStateException.class, () -> JsonParser.parseString("[1,2]").getAsInt());
    }

    @Test
    void strictParsing() {
        assertTrue(JsonParser.parseString("  ").isJsonNull());
        assertThrows(JsonParseException.class, () -> JsonParser.parseString("{a:1}"));
        assertThrows(JsonParseException.class, () -> JsonParser.parseString("[1,]"));
        assertThrows(JsonParseException.class, () -> JsonParser.parseString("01"));
        assertThrows(JsonParseException.class, () -> JsonParser.parseString("{} x"));
    }
}
