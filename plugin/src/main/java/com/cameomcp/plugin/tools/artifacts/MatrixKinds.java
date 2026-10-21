package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.ToolException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The validated native matrix kinds: API token, native Cameo diagram type, accepted aliases,
 * default row/column type domains and example domains (Cameo-free, unit-tested).
 */
public enum MatrixKinds {
    // Each constant: apiName, native diagramType, aliases, default row types, default column types,
    // then the row/column type examples that were validated against a live Cameo install.
    /** Refine Requirement Matrix. */
    REFINE("refine", "Refine Requirement Matrix",
            Arrays.asList("refine", "refine matrix", "refine requirement matrix"),
            Arrays.asList("Block"), Arrays.asList("Requirement"),
            Arrays.asList("Activity"), Arrays.asList("Requirement")),
    /** Derive Requirement Matrix. */
    DERIVE("derive", "Derive Requirement Matrix",
            Arrays.asList("derive", "derive matrix", "derive requirement matrix"),
            Arrays.asList("Requirement"), Arrays.asList("Requirement"),
            Arrays.asList("Requirement"), Arrays.asList("Requirement")),
    /** Satisfy Requirement Matrix. */
    SATISFY("satisfy", "Satisfy Requirement Matrix",
            Arrays.asList("satisfy", "satisfy matrix", "satisfy requirement matrix"),
            Arrays.asList("Block"), Arrays.asList("Requirement"),
            Arrays.asList("Block", "Component", "Property"), Arrays.asList("Requirement")),
    /** SysML Allocation Matrix. */
    ALLOCATION("allocation", "SysML Allocation Matrix",
            Arrays.asList("allocation", "allocation matrix", "system allocation matrix", "sysml allocation matrix"),
            Arrays.asList("Block"), Arrays.asList("Block"),
            Arrays.asList("Block", "Property", "UseCase"), Arrays.asList("Block", "Property", "Component")),
    /** Dependency Matrix. */
    DEPENDENCY("dependency", "Dependency Matrix",
            Arrays.asList("dependency", "dependency matrix"),
            Arrays.asList("NamedElement"), Arrays.asList("NamedElement"),
            Arrays.asList("Activity", "OpaqueAction", "Block", "Requirement"),
            Arrays.asList("Activity", "OpaqueAction", "Block", "Requirement"));

    /** Short token used in tool parameters and results (e.g. "satisfy"). */
    public final String apiName;
    /** Exact Cameo diagram type name; compared verbatim by {@link #fromDiagramType}. */
    public final String diagramType;
    /** Other accepted spellings, matched after removing punctuation and case. */
    public final List<String> aliases;
    /** Row types the matrix creation service uses when the caller gives none. */
    public final List<String> defaultRowTypes;
    /** Column types the matrix creation service uses when the caller gives none. */
    public final List<String> defaultColumnTypes;
    /** Row type examples that were checked against a live Cameo install. */
    public final List<String> rowTypeExamples;
    /** Column type examples that were checked against a live Cameo install. */
    public final List<String> columnTypeExamples;

    /**
     * Creates a kind.
     *
     * @param apiName the short token
     * @param diagramType the native diagram type name
     * @param aliases other accepted spellings
     * @param defaultRowTypes default row types
     * @param defaultColumnTypes default column types
     * @param rowTypeExamples checked row type examples
     * @param columnTypeExamples checked column type examples
     */
    MatrixKinds(String apiName, String diagramType, List<String> aliases, List<String> defaultRowTypes,
                List<String> defaultColumnTypes, List<String> rowTypeExamples, List<String> columnTypeExamples) {
        this.apiName = apiName;
        this.diagramType = diagramType;
        this.aliases = Collections.unmodifiableList(aliases);
        this.defaultRowTypes = Collections.unmodifiableList(defaultRowTypes);
        this.defaultColumnTypes = Collections.unmodifiableList(defaultColumnTypes);
        this.rowTypeExamples = Collections.unmodifiableList(rowTypeExamples);
        this.columnTypeExamples = Collections.unmodifiableList(columnTypeExamples);
    }

    /**
     * Kind for user input (token, native type name or alias, any case/punctuation), or null.
     *
     * @param raw the user's text, may be null
     * @return the kind, or null
     */
    public static MatrixKinds fromInput(String raw) {
        if (raw == null) {
            return null;
        }
        // Squashing lets "Satisfy-Matrix", "satisfy matrix" and "SATISFYMATRIX" all match.
        String key = squash(raw);
        if (key.isEmpty()) {
            return null;
        }
        for (MatrixKinds k : values()) {
            if (squash(k.apiName).equals(key) || squash(k.diagramType).equals(key)) {
                return k;
            }
            for (String alias : k.aliases) {
                if (squash(alias).equals(key)) {
                    return k;
                }
            }
        }
        return null;
    }

    /**
     * Like {@link #fromInput} but throws an invalid-params ToolException listing the supported kinds.
     *
     * @param raw the user's text
     * @return the kind
     * @throws ToolException INVALID_PARAMS for an unsupported kind
     */
    public static MatrixKinds require(String raw) {
        MatrixKinds k = fromInput(raw);
        if (k == null) {
            throw ToolException.invalid("Unsupported matrix kind: " + raw
                    + ". Supported: refine, derive, satisfy, allocation, dependency (see cameo_list_matrix_kinds).");
        }
        return k;
    }

    /**
     * Kind for a native diagram type string, or null when the diagram is not a supported matrix.
     *
     * @param diagramType the native type name
     * @return the kind, or null
     */
    public static MatrixKinds fromDiagramType(String diagramType) {
        for (MatrixKinds k : values()) {
            if (k.diagramType.equals(diagramType)) {
                return k;
            }
        }
        return null;
    }

    /** Lower-cases and drops everything except ASCII letters and digits, for lenient matching. */
    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /**
     * The cameo_list_matrix_kinds result: {count, matrixKinds:[{kind, nativeType, aliases, ...}]}.
     *
     * @return the catalog as plain JSON values
     */
    public static Map<String, Object> catalog() {
        List<Object> kinds = new ArrayList<>();
        for (MatrixKinds k : values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k.apiName);
            m.put("nativeType", k.diagramType);
            m.put("aliases", new ArrayList<>(k.aliases));
            m.put("validatedRowTypeExamples", new ArrayList<>(k.rowTypeExamples));
            m.put("validatedColumnTypeExamples", new ArrayList<>(k.columnTypeExamples));
            m.put("defaultRowTypes", new ArrayList<>(k.defaultRowTypes));
            m.put("defaultColumnTypes", new ArrayList<>(k.defaultColumnTypes));
            kinds.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", kinds.size());
        out.put("matrixKinds", kinds);
        return out;
    }
}
