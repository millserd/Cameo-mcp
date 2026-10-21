package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonParseException;
import com.cameomcp.json.tree.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cameo-free requirements import/export logic: CSV and JSON parsing, row normalization and
 * validation, CSV writing, and file path checks. Unit-tested without Cameo.
 */
final class RequirementsCodec {

    /** Largest import source file accepted. */
    static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;

    /** Default CSV export columns. */
    static final List<String> EXPORT_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "id", "name", "requirementId", "text", "documentation", "qualifiedName", "ownerId", "stereotypes", "type"));

    /**
     * Column aliases for the requirement name. All alias lists are compared after {@link #value} lowercases
     * headers and drops underscores and spaces, so "Requirement ID", "requirement_id" and "requirementId" all
     * match "requirementid".
     */
    private static final List<String> NAME_KEYS = Arrays.asList("name", "title", "requirementname");
    /**
     * Column aliases for the external requirement id, in priority order. "id" comes last: a bare id column is
     * treated as the external requirement id.
     */
    private static final List<String> EXTERNAL_ID_KEYS = Arrays.asList(
            "externalid", "requirementid", "reqid", "key", "id");
    /** Column aliases for the requirement text. */
    private static final List<String> TEXT_KEYS = Arrays.asList("text", "requirementtext", "description");
    /** Column alias for the element documentation. */
    private static final List<String> DOC_KEYS = Collections.singletonList("documentation");

    /** Static helpers only. */
    private RequirementsCodec() {
    }

    // ------------------------------------------------------------------ CSV reading

    /**
     * Parse CSV text with a header row into rows keyed by header. Quoted fields may span lines.
     *
     * <p>A leading byte-order mark is removed, and the delimiter is detected from the header line. The first
     * non-blank record is the header; blank records are skipped, cells beyond the header width are dropped
     * and columns with an empty header are ignored.
     *
     * @param text CSV text, or null
     * @return one map per data row, keyed by trimmed header, in file order; empty for null text
     * @throws ToolException INVALID_PARAMS for an unterminated quoted field
     */
    static List<Map<String, String>> parseCsv(String text) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (text == null) {
            return rows;
        }
        // Strip a leading byte-order mark (U+FEFF), which Excel writes at the start of UTF-8 CSV files.
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        char delimiter = detectDelimiter(body);
        List<List<String>> records = parseRecords(body, delimiter);
        List<String> headers = null;
        for (List<String> cells : records) {
            if (isBlank(cells)) {
                continue;
            }
            // The first non-blank record is the header; cells beyond the header width are dropped.
            if (headers == null) {
                headers = new ArrayList<>();
                for (String h : cells) {
                    headers.add(h.trim());
                }
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(headers.size(), cells.size()); i++) {
                if (!headers.get(i).isEmpty()) {
                    row.put(headers.get(i), cells.get(i));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Comma by default; semicolon or tab when the header line uses those and no commas.
     *
     * @param text CSV text; only the first line is inspected
     * @return ',', ';' or '\t'
     */
    static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        if (header.indexOf(',') >= 0) {
            return ',';
        }
        if (header.indexOf('\t') >= 0) {
            return '\t';
        }
        if (header.indexOf(';') >= 0) {
            return ';';
        }
        return ',';
    }

    /**
     * Minimal RFC 4180-style tokenizer. Quoted fields may contain the delimiter, doubled quotes and line
     * breaks. CRLF, LF and lone CR all end a record. Quotes are recognized anywhere in a cell, not only at
     * its start. An unterminated quote is a user error, not silently accepted.
     *
     * @param text CSV text
     * @param delimiter cell separator
     * @return records as lists of raw (untrimmed) cells
     * @throws ToolException INVALID_PARAMS for an unterminated quoted field
     */
    static List<List<String>> parseRecords(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == delimiter) {
                cells.add(current.toString());
                current.setLength(0);
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(current.toString());
                current.setLength(0);
                records.add(cells);
                cells = new ArrayList<>();
            } else {
                current.append(ch);
            }
        }
        if (quoted) {
            throw ToolException.invalid("CSV text has an unterminated quoted field.");
        }
        if (current.length() > 0 || !cells.isEmpty()) {
            cells.add(current.toString());
            records.add(cells);
        }
        return records;
    }

    /**
     * True for a record with no content.
     *
     * @param cells one record
     * @return true when every cell is null or whitespace
     */
    private static boolean isBlank(List<String> cells) {
        for (String c : cells) {
            if (c != null && !c.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ JSON reading

    /**
     * Rows from an array of objects; scalar values are kept as strings, nested values ignored.
     *
     * @param array array of row objects, or null
     * @param what member name for error messages
     * @return one row per entry; empty for null
     * @throws ToolException INVALID_PARAMS when an entry is not an object
     */
    static List<Map<String, String>> rowsFromJsonArray(JsonArray array, String what) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (array == null) {
            return rows;
        }
        int index = 0;
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                throw ToolException.invalid("Every entry of '" + what + "' must be an object (entry " + index + ").");
            }
            rows.add(rowFromObject(item.getAsJsonObject()));
            index++;
        }
        return rows;
    }

    /**
     * Converts one JSON object to a row. Primitive values become strings (numbers and booleans included);
     * arrays, objects and nulls are dropped.
     *
     * @param object a row object
     * @return the row, in member order
     */
    static Map<String, String> rowFromObject(JsonObject object) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String key : object.keySet()) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                row.put(key, value.getAsString());
            }
        }
        return row;
    }

    /**
     * JSON document: an array of rows, or an object with a "requirements" or "rows" array.
     *
     * <p>The "sourceRows" key is accepted too.
     *
     * @param text JSON text, or null
     * @return the rows
     * @throws ToolException INVALID_PARAMS for invalid JSON or an unexpected shape
     */
    static List<Map<String, String>> parseJsonText(String text) {
        JsonElement root;
        try {
            root = JsonParser.parseString(text == null ? "" : text.trim());
        } catch (JsonParseException e) {
            throw ToolException.invalid("Source is not valid JSON: " + e.getMessage());
        }
        if (root.isJsonArray()) {
            return rowsFromJsonArray(root.getAsJsonArray(), "source");
        }
        if (root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            for (String key : new String[]{"requirements", "rows", "sourceRows"}) {
                if (o.has(key) && o.get(key).isJsonArray()) {
                    return rowsFromJsonArray(o.getAsJsonArray(key), key);
                }
            }
        }
        throw ToolException.invalid("JSON source must be an array of requirement objects or an object with a "
                + "'requirements' or 'rows' array.");
    }

    // ------------------------------------------------------------------ files

    /**
     * Read requirement rows from a .csv/.tsv/.txt or .json file (format sniffed when the extension is
     * unknown).
     *
     * <p>The file is read as UTF-8. With an unknown extension, text starting with '[' or '{' is parsed as
     * JSON, anything else as CSV.
     *
     * @param path absolute file path
     * @return the rows
     * @throws ToolException INVALID_PARAMS or NOT_FOUND from {@link #checkInputPath}, TOOL_FAILED when the
     *     file cannot be read, or a parse error
     */
    static List<Map<String, String>> readSourceFile(String path) {
        File file = checkInputPath(path);
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("TOOL_FAILED", "Could not read '" + file + "': " + e.getMessage());
        }
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return parseJsonText(text);
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt")) {
            return parseCsv(text);
        }
        String trimmed = text.startsWith("﻿") ? text.substring(1).trim() : text.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return parseJsonText(trimmed);
        }
        return parseCsv(text);
    }

    /**
     * Checks an import source path: it must be an absolute, existing regular file no larger than {@link
     * #MAX_SOURCE_BYTES}.
     *
     * @param path the source_path argument
     * @return the file
     * @throws ToolException INVALID_PARAMS for an empty, relative, non-file or oversize path, NOT_FOUND when
     *     it does not exist
     */
    static File checkInputPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw ToolException.invalid("source_path is empty.");
        }
        File file = new File(path.trim());
        if (!file.isAbsolute()) {
            throw ToolException.invalid("source_path must be an absolute path (got '" + path + "').");
        }
        if (!file.exists()) {
            throw new ToolException("NOT_FOUND", "Source file '" + file + "' does not exist.");
        }
        if (!file.isFile()) {
            throw ToolException.invalid("source_path '" + file + "' is not a regular file.");
        }
        if (file.length() > MAX_SOURCE_BYTES) {
            throw ToolException.invalid("Source file '" + file + "' is larger than " + (MAX_SOURCE_BYTES / (1024 * 1024))
                    + " MB; split it into smaller imports.");
        }
        return file;
    }

    /**
     * Non-throwing description of an output path, for previews.
     *
     * @param path the output path argument, or null
     * @return outputPath, absolute, exists, isDirectory, parentExists, ok, and a problem or overwrite warning
     */
    static JsonObject describeOutputPath(String path) {
        JsonObject o = new JsonObject();
        o.addProperty("outputPath", path);
        if (path == null || path.trim().isEmpty()) {
            o.addProperty("ok", false);
            o.addProperty("problem", "No output path given.");
            return o;
        }
        File file = new File(path.trim());
        o.addProperty("absolute", file.isAbsolute());
        o.addProperty("exists", file.exists());
        o.addProperty("isDirectory", file.isDirectory());
        File parent = file.getAbsoluteFile().getParentFile();
        o.addProperty("parentExists", parent != null && parent.isDirectory());
        String problem = null;
        if (!file.isAbsolute()) {
            problem = "Output path must be absolute.";
        } else if (file.isDirectory()) {
            problem = "Output path is a directory; give a file name.";
        }
        o.addProperty("ok", problem == null);
        if (problem != null) {
            o.addProperty("problem", problem);
        } else if (file.exists()) {
            o.addProperty("warning", "File exists and would be overwritten only with the explicit overwrite flag.");
        }
        return o;
    }

    /**
     * Validate an output file path. Refuses relative paths, directories and existing files unless
     * {@code overwrite} is true; creates the parent folder when missing.
     *
     * @param path the output_path argument
     * @param overwrite whether an existing file may be replaced
     * @param flagName the overwrite argument's name, used in the error message
     * @return the file
     * @throws ToolException INVALID_PARAMS for an empty, relative or directory path, CONFLICT for an existing
     *     file without overwrite, TOOL_FAILED when the parent folder cannot be created
     */
    static File checkOutputPath(String path, boolean overwrite, String flagName) {
        if (path == null || path.trim().isEmpty()) {
            throw ToolException.invalid("output_path is empty.");
        }
        File file = new File(path.trim());
        if (!file.isAbsolute()) {
            throw ToolException.invalid("output_path must be an absolute path (got '" + path + "').");
        }
        if (file.isDirectory()) {
            throw ToolException.invalid("output_path '" + file + "' is a directory; give a file name.");
        }
        if (file.exists() && !overwrite) {
            throw new ToolException("CONFLICT", "File '" + file + "' already exists. Choose another output_path or pass "
                    + flagName + "=true to replace it.");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new ToolException("TOOL_FAILED", "Could not create output directory '" + parent + "'.");
        }
        return file;
    }

    // ------------------------------------------------------------------ normalization

    /**
     * First non-blank value for any alias key. Header names are normalized (lower case, no underscores or
     * spaces) before lookup; when two headers normalize the same, the first wins.
     *
     * @param row one row
     * @param keys normalized alias keys in priority order
     * @return the value, or null when no alias has a non-blank value
     */
    static String value(Map<String, String> row, List<String> keys) {
        Map<String, String> lower = new HashMap<>();
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey() != null) {
                lower.putIfAbsent(e.getKey().trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", ""),
                        e.getValue());
            }
        }
        for (String k : keys) {
            String v = lower.get(k);
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * External requirement id from any {@link #EXTERNAL_ID_KEYS} column.
     *
     * @param row one row
     * @return the id, or null
     */
    static String externalId(Map<String, String> row) {
        return value(row, EXTERNAL_ID_KEYS);
    }

    /**
     * Element name: the name column (name, title or requirementName), else the external id from
     * {@link #EXTERNAL_ID_KEYS}.
     *
     * @param row one row
     * @return the name, or null when the row has neither
     */
    static String name(Map<String, String> row) {
        String n = value(row, NAME_KEYS);
        return n != null ? n : externalId(row);
    }

    /**
     * Requirement text from text, requirementText or description columns.
     *
     * @param row one row
     * @return the text, or null
     */
    static String text(Map<String, String> row) {
        return value(row, TEXT_KEYS);
    }

    /**
     * Element documentation from the documentation column.
     *
     * @param row one row
     * @return the documentation, or null
     */
    static String documentation(Map<String, String> row) {
        return value(row, DOC_KEYS);
    }

    /**
     * One normalized row: name, externalId, text, documentation, plus the row index and raw values.
     *
     * @param row one row
     * @param index zero-based row index
     * @return the normalized row; absent fields are omitted
     */
    static JsonObject normalize(Map<String, String> row, int index) {
        JsonObject o = new JsonObject();
        o.addProperty("row", index);
        addIfNotNull(o, "name", name(row));
        addIfNotNull(o, "externalId", externalId(row));
        addIfNotNull(o, "text", text(row));
        addIfNotNull(o, "documentation", documentation(row));
        JsonObject raw = new JsonObject();
        for (Map.Entry<String, String> e : row.entrySet()) {
            raw.addProperty(e.getKey(), e.getValue());
        }
        o.add("raw", raw);
        return o;
    }

    /**
     * Adds a string member only when the value is not null.
     *
     * @param o target object
     * @param key member name
     * @param value value, or null
     */
    private static void addIfNotNull(JsonObject o, String key, String value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    /**
     * Row-level checks: missing name/id (error), duplicate external ids and names (warning).
     *
     * <p>Rows without text or documentation get an "info" issue. Row numbers are zero-based; duplicate names
     * are compared case-insensitively.
     *
     * @param rows rows to check
     * @return rowCount, errorCount, warningCount, issues, and valid (no errors and at least one row)
     */
    static JsonObject validate(List<Map<String, String>> rows) {
        JsonArray issues = new JsonArray();
        Map<String, Integer> seenIds = new HashMap<>();
        Map<String, Integer> seenNames = new HashMap<>();
        int errors = 0;
        int warnings = 0;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String name = name(row);
            if (name == null) {
                issues.add(issue(i, "error", "MISSING_NAME", "Row needs name, id, externalId, or key."));
                errors++;
                continue;
            }
            String ext = externalId(row);
            if (ext != null) {
                Integer prev = seenIds.put(ext, i);
                if (prev != null) {
                    issues.add(issue(i, "warning", "DUPLICATE_EXTERNAL_ID",
                            "External id '" + ext + "' also appears in row " + prev + "."));
                    warnings++;
                }
            }
            Integer prevName = seenNames.put(name.toLowerCase(Locale.ROOT), i);
            if (prevName != null) {
                issues.add(issue(i, "warning", "DUPLICATE_NAME", "Name '" + name + "' also appears in row " + prevName + "."));
                warnings++;
            }
            if (text(row) == null && documentation(row) == null) {
                issues.add(issue(i, "info", "NO_TEXT", "Row has no text/documentation/description column value."));
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("rowCount", rows.size());
        o.addProperty("errorCount", errors);
        o.addProperty("warningCount", warnings);
        o.addProperty("valid", errors == 0 && !rows.isEmpty());
        o.add("issues", issues);
        return o;
    }

    /**
     * Builds one validation issue.
     *
     * @param row zero-based row index
     * @param severity "error", "warning" or "info"
     * @param code machine-readable issue code
     * @param message human-readable explanation
     * @return the issue object
     */
    private static JsonObject issue(int row, String severity, String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("row", row);
        o.addProperty("severity", severity);
        o.addProperty("code", code);
        o.addProperty("message", message);
        return o;
    }

    // ------------------------------------------------------------------ export

    /**
     * Flatten an element JSON (ElementJson.toJson shape) into an export row.
     *
     * <p>The SysML Requirement's Id and Text tagged values become requirementId and text.
     *
     * @param element element JSON
     * @return a row keyed by {@link #EXPORT_COLUMNS}
     */
    static Map<String, String> exportRow(JsonObject element) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("id", str(element, "id"));
        row.put("name", str(element, "name"));
        JsonObject tags = element.has("taggedValues") && element.get("taggedValues").isJsonObject()
                ? element.getAsJsonObject("taggedValues") : new JsonObject();
        row.put("requirementId", flat(tags.get("Id")));
        row.put("text", flat(tags.get("Text")));
        row.put("documentation", str(element, "documentation"));
        row.put("qualifiedName", str(element, "qualifiedName"));
        row.put("ownerId", str(element, "ownerId"));
        row.put("stereotypes", flat(element.get("stereotypes")));
        row.put("type", str(element, "type"));
        return row;
    }

    /**
     * One member of an element JSON, flattened with {@link #flat}.
     *
     * @param o object to read
     * @param key member name
     * @return the flattened member, or "" when missing or null
     */
    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? flat(o.get(key)) : "";
    }

    /**
     * Flattens an exported JSON value to one CSV-friendly string. Arrays are joined with "; " (empty items
     * skipped); objects give their value, name, id or string member, else their JSON text.
     *
     * @param e value, or null
     * @return the flat string; "" for null
     */
    static String flat(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : e.getAsJsonArray()) {
                String s = flat(item);
                if (!s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        JsonObject o = e.getAsJsonObject();
        for (String k : new String[]{"value", "name", "id", "string"}) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                return o.get(k).getAsString();
            }
        }
        return o.toString();
    }

    /**
     * CSV with a header row and CRLF line endings; missing columns become empty cells.
     *
     * @param columns column names, in order
     * @param rows rows keyed by column name
     * @return the CSV text
     */
    static String toCsv(List<String> columns, List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendCsvLine(sb, columns);
        for (Map<String, String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : columns) {
                String v = row.get(c);
                cells.add(v == null ? "" : v);
            }
            appendCsvLine(sb, cells);
        }
        return sb.toString();
    }

    /**
     * Appends one comma-separated, CRLF-terminated line.
     *
     * @param sb target
     * @param cells cell values
     */
    private static void appendCsvLine(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvCell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /**
     * Quotes a CSV cell only when needed: a comma, quote or line break, or leading or trailing whitespace
     * that would otherwise be lost. Quotes inside are doubled.
     *
     * @param value cell value, or null
     * @return the encoded cell; "" for null
     */
    static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || (!value.isEmpty()
                && (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1))));
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
