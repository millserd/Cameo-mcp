// family: advanced
package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RequirementsCodec}: CSV parsing (quotes, multi-line fields, BOM, delimiters, short rows,
 * unterminated quotes), JSON rows, normalization, validation, export and CSV quoting, and the input and
 * output path checks.
 */
class RequirementsCodecTest {

    @Test
    void parsesCsvWithHeaderQuotesAndEscapedQuotes() {
        List<Map<String, String>> rows = RequirementsCodec.parseCsv(
                "id,name,text\r\nR1,Speed,\"The car shall reach 100 km/h, \"\"safely\"\"\"\n\nR2,Brake,Stop\n");
        assertEquals(2, rows.size());
        assertEquals("R1", rows.get(0).get("id"));
        assertEquals("The car shall reach 100 km/h, \"safely\"", rows.get(0).get("text"));
        assertEquals("Brake", rows.get(1).get("name"));
    }

    @Test
    void quotedFieldsMaySpanLines() {
        List<Map<String, String>> rows = RequirementsCodec.parseCsv("name,text\nA,\"line1\nline2\"\nB,x");
        assertEquals(2, rows.size());
        assertEquals("line1\nline2", rows.get(0).get("text"));
        assertEquals("B", rows.get(1).get("name"));
    }

    @Test
    void stripsBomAndDetectsSemicolonAndTab() {
        assertEquals("R1", RequirementsCodec.parseCsv("﻿id;name\nR1;Speed").get(0).get("id"));
        assertEquals("Speed", RequirementsCodec.parseCsv("id\tname\nR1\tSpeed").get(0).get("name"));
    }

    @Test
    void shortRowsKeepOnlyPresentCells() {
        Map<String, String> row = RequirementsCodec.parseCsv("id,name,text\nR1,Speed").get(0);
        assertEquals(2, row.size());
        assertFalse(row.containsKey("text"));
    }

    @Test
    void unterminatedQuoteIsRejected() {
        ToolException e = assertThrows(ToolException.class, () -> RequirementsCodec.parseCsv("name\n\"open"));
        assertEquals("INVALID_PARAMS", e.code());
    }

    @Test
    void jsonRowsKeepScalarsAndRejectNonObjects() {
        JsonArray arr = JsonParser.parseString("[{\"id\":\"R1\",\"name\":\"Speed\",\"priority\":2,\"tags\":[\"a\"]}]")
                .getAsJsonArray();
        Map<String, String> row = RequirementsCodec.rowsFromJsonArray(arr, "rows").get(0);
        assertEquals("2", row.get("priority"));
        assertFalse(row.containsKey("tags"));
        assertThrows(ToolException.class,
                () -> RequirementsCodec.rowsFromJsonArray(JsonParser.parseString("[1]").getAsJsonArray(), "rows"));
    }

    @Test
    void jsonDocumentFormsAreAccepted() {
        assertEquals(1, RequirementsCodec.parseJsonText("[{\"name\":\"A\"}]").size());
        assertEquals(2, RequirementsCodec.parseJsonText("{\"requirements\":[{\"name\":\"A\"},{\"name\":\"B\"}]}").size());
        assertEquals(1, RequirementsCodec.parseJsonText("{\"rows\":[{\"name\":\"A\"}]}").size());
        assertThrows(ToolException.class, () -> RequirementsCodec.parseJsonText("{\"x\":1}"));
        assertThrows(ToolException.class, () -> RequirementsCodec.parseJsonText("{not json"));
    }

    @Test
    void normalizationFollowsReferencePrecedence() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ID", "R-7");
        row.put("Description", "Do it");
        assertEquals("R-7", RequirementsCodec.name(row));
        assertEquals("R-7", RequirementsCodec.externalId(row));
        assertEquals("Do it", RequirementsCodec.text(row));

        Map<String, String> exported = new LinkedHashMap<>();
        exported.put("id", "_19_0_cameo_id");
        exported.put("name", "Speed");
        exported.put("requirementId", "REQ-1");
        exported.put("text", "Fast");
        exported.put("documentation", "Doc");
        assertEquals("REQ-1", RequirementsCodec.externalId(exported), "requirementId beats the Cameo element id");
        assertEquals("Speed", RequirementsCodec.name(exported));
        assertEquals("Doc", RequirementsCodec.documentation(exported));

        JsonObject n = RequirementsCodec.normalize(exported, 3);
        assertEquals(3, n.get("row").getAsInt());
        assertEquals("REQ-1", n.get("externalId").getAsString());
        assertEquals("_19_0_cameo_id", n.getAsJsonObject("raw").get("id").getAsString());
    }

    @Test
    void validationFlagsMissingNamesAndDuplicates() {
        Map<String, String> a = Map.of("id", "R1", "name", "Speed", "text", "x");
        Map<String, String> b = Map.of("id", "R1", "name", "speed");
        Map<String, String> c = Map.of("text", "orphan");
        JsonObject v = RequirementsCodec.validate(Arrays.asList(a, b, c));
        assertEquals(3, v.get("rowCount").getAsInt());
        assertEquals(1, v.get("errorCount").getAsInt());
        assertEquals(2, v.get("warningCount").getAsInt());
        assertFalse(v.get("valid").getAsBoolean());
        String issues = v.get("issues").toString();
        assertTrue(issues.contains("MISSING_NAME"));
        assertTrue(issues.contains("DUPLICATE_EXTERNAL_ID"));
        assertTrue(issues.contains("DUPLICATE_NAME"));
        assertTrue(issues.contains("NO_TEXT"));
        assertFalse(RequirementsCodec.validate(java.util.Collections.emptyList()).get("valid").getAsBoolean());
    }

    @Test
    void exportRowFlattensTaggedValuesAndCsvRoundTrips() {
        JsonObject element = JsonParser.parseString("{\"id\":\"E1\",\"name\":\"Speed, max\",\"type\":\"Class\","
                + "\"ownerId\":\"P1\",\"qualifiedName\":\"M::Speed\",\"stereotypes\":[\"Requirement\",\"Extra\"],"
                + "\"documentation\":\"multi\\nline\",\"taggedValues\":{\"Id\":\"REQ-1\",\"Text\":\"Say \\\"hi\\\"\"}}")
                .getAsJsonObject();
        Map<String, String> row = RequirementsCodec.exportRow(element);
        assertEquals("REQ-1", row.get("requirementId"));
        assertEquals("Requirement; Extra", row.get("stereotypes"));

        String csv = RequirementsCodec.toCsv(RequirementsCodec.EXPORT_COLUMNS, List.of(row));
        assertTrue(csv.startsWith("id,name,requirementId,text,documentation,"));
        List<Map<String, String>> back = RequirementsCodec.parseCsv(csv);
        assertEquals(1, back.size());
        assertEquals("Speed, max", back.get(0).get("name"));
        assertEquals("Say \"hi\"", back.get(0).get("text"));
        assertEquals("multi\nline", back.get(0).get("documentation"));
        assertEquals("REQ-1", RequirementsCodec.externalId(back.get(0)));
    }

    @Test
    void csvCellQuoting() {
        assertEquals("plain", RequirementsCodec.csvCell("plain"));
        assertEquals("\"a,b\"", RequirementsCodec.csvCell("a,b"));
        assertEquals("\" padded\"", RequirementsCodec.csvCell(" padded"));
        assertEquals("", RequirementsCodec.csvCell(null));
    }

    @Test
    void readsSourceFilesByExtensionAndSniffing(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("reqs.csv");
        Files.write(csv, "name,text\nA,x\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("A", RequirementsCodec.readSourceFile(csv.toString()).get(0).get("name"));

        Path json = dir.resolve("reqs.json");
        Files.write(json, "{\"requirements\":[{\"name\":\"B\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("B", RequirementsCodec.readSourceFile(json.toString()).get(0).get("name"));

        Path unknown = dir.resolve("reqs.data");
        Files.write(unknown, "[{\"name\":\"C\"}]".getBytes(StandardCharsets.UTF_8));
        assertEquals("C", RequirementsCodec.readSourceFile(unknown.toString()).get(0).get("name"));
    }

    @Test
    void inputPathChecks(@TempDir Path dir) {
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> RequirementsCodec.checkInputPath("relative/reqs.csv")).code());
        assertEquals("NOT_FOUND", assertThrows(ToolException.class,
                () -> RequirementsCodec.checkInputPath(dir.resolve("missing.csv").toString())).code());
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> RequirementsCodec.checkInputPath(dir.toString())).code());
    }

    @Test
    void outputPathNeverOverwritesWithoutFlag(@TempDir Path dir) throws Exception {
        File existing = dir.resolve("out.csv").toFile();
        Files.write(existing.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        ToolException e = assertThrows(ToolException.class,
                () -> RequirementsCodec.checkOutputPath(existing.getPath(), false, "overwrite"));
        assertEquals("CONFLICT", e.code());
        assertTrue(e.getMessage().contains("overwrite=true"));
        assertEquals(existing, RequirementsCodec.checkOutputPath(existing.getPath(), true, "overwrite"));

        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> RequirementsCodec.checkOutputPath("out.csv", true, "overwrite")).code());
        assertEquals("INVALID_PARAMS", assertThrows(ToolException.class,
                () -> RequirementsCodec.checkOutputPath(dir.toString(), true, "overwrite")).code());

        File nested = dir.resolve("a/b/out.json").toFile();
        RequirementsCodec.checkOutputPath(nested.getPath(), false, "overwrite");
        assertTrue(nested.getParentFile().isDirectory(), "parent folders are created");
        assertFalse(nested.exists(), "the file itself is not created by the check");

        JsonObject d = RequirementsCodec.describeOutputPath(existing.getPath());
        assertTrue(d.get("ok").getAsBoolean());
        assertTrue(d.get("exists").getAsBoolean());
        assertTrue(d.has("warning"));
        assertFalse(RequirementsCodec.describeOutputPath("rel.csv").get("ok").getAsBoolean());
    }
}
