// family: server
package com.cameomcp.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Json} reader and writer: nested round trips, key order, integral doubles written as
 * integers, control-character escaping, big numbers, empty containers and rejection of malformed input.
 */
class JsonTest {

    @Test
    void roundTripsNestedStructures() {
        String text = "{\"a\":1,\"b\":[true,false,null,\"x\\n\\\"y\\\"\"],\"c\":{\"d\":-2.5e3,\"e\":\"\\u00e9\"}}";
        Object parsed = Json.parse(text);
        assertEquals(1L, JsonPath.get(parsed, "a"));
        assertEquals("x\n\"y\"", JsonPath.get(parsed, "b", 3));
        assertEquals(-2500.0, JsonPath.get(parsed, "c", "d"));
        assertEquals("\u00e9", JsonPath.str(parsed, "c", "e"));
        String written = Json.write(parsed);
        assertEquals(written, Json.write(Json.parse(written)));
        assertEquals(written, Json.write(Json.parse(Json.pretty(parsed))));
    }

    @Test
    void preservesKeyOrderAndWritesIntegralDoublesAsIntegers() {
        Map<String, Object> m = Json.obj("z", 1, "a", 2.0, "m", 2.5);
        assertEquals("{\"z\":1,\"a\":2,\"m\":2.5}", Json.write(m));
    }

    @Test
    void escapesControlCharacters() {
        assertEquals("\"\\u0001\\t\"", Json.write("\u0001\t"));
    }

    @Test
    void handlesBigNumbersAndEmptyContainers() {
        Object v = Json.parse("[12345678901234567890, {}, []]");
        List<?> l = (List<?>) v;
        assertEquals(new java.math.BigDecimal("12345678901234567890"), l.get(0));
        assertTrue(((Map<?, ?>) l.get(1)).isEmpty());
        assertTrue(((List<?>) l.get(2)).isEmpty());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(JsonException.class, () -> Json.parse("{} x"));
        assertThrows(JsonException.class, () -> Json.parse("\"abc"));
    }
}
