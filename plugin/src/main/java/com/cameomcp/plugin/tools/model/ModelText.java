package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.ToolException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cameo-free text rules of the model family: type aliases, stereotype preferences,
 * multiplicity parsing, view/paging conventions and bulk-create references.
 * Kept separate from the Cameo-facing code so it can be unit-tested anywhere.
 */
public final class ModelText {

    // Detail levels accepted by the view parameter of query tools.
    public static final String VIEW_COMPACT = "compact";
    public static final String VIEW_FULL = "full";
    /** Prefix that marks a reference to an earlier entry of cameo_create_elements. */
    public static final String REF_PREFIX = "ref:";

    /** Normalized alias -> canonical element kind used by the element creator. */
    private static final Map<String, String> ELEMENT_KINDS = new LinkedHashMap<>();
    /** Normalized alias -> canonical relationship kind. */
    private static final Map<String, String> RELATIONSHIP_KINDS = new LinkedHashMap<>();

    // Each line: canonical kind first, then every alias key (already normalized by key()) that maps to it.
    static {
        kinds(ELEMENT_KINDS, "package", "package");
        kinds(ELEMENT_KINDS, "profile", "profile");
        kinds(ELEMENT_KINDS, "stereotype", "stereotype");
        kinds(ELEMENT_KINDS, "block", "block");
        kinds(ELEMENT_KINDS, "class", "class");
        kinds(ELEMENT_KINDS, "usecase", "usecase");
        kinds(ELEMENT_KINDS, "activity", "activity");
        kinds(ELEMENT_KINDS, "actor", "actor");
        kinds(ELEMENT_KINDS, "statemachine", "statemachine");
        kinds(ELEMENT_KINDS, "state", "state");
        kinds(ELEMENT_KINDS, "pseudostate", "pseudostate", "initialstate");
        kinds(ELEMENT_KINDS, "requirement", "requirement");
        kinds(ELEMENT_KINDS, "interfaceblock", "interfaceblock");
        kinds(ELEMENT_KINDS, "interface", "interface");
        kinds(ELEMENT_KINDS, "constraintblock", "constraintblock");
        kinds(ELEMENT_KINDS, "valuetype", "valuetype");
        kinds(ELEMENT_KINDS, "datatype", "datatype");
        kinds(ELEMENT_KINDS, "signal", "signal");
        kinds(ELEMENT_KINDS, "property", "property");
        kinds(ELEMENT_KINDS, "flowproperty", "flowproperty");
        kinds(ELEMENT_KINDS, "operation", "operation");
        kinds(ELEMENT_KINDS, "port", "port");
        kinds(ELEMENT_KINDS, "enumeration", "enumeration");
        kinds(ELEMENT_KINDS, "component", "component");
        kinds(ELEMENT_KINDS, "constraint", "constraint");
        kinds(ELEMENT_KINDS, "comment", "comment");
        kinds(ELEMENT_KINDS, "callbehavioraction", "callbehavioraction", "action");
        kinds(ELEMENT_KINDS, "activitypartition", "activitypartition", "partition");
        kinds(ELEMENT_KINDS, "initialnode", "initialnode");
        kinds(ELEMENT_KINDS, "activityfinalnode", "activityfinalnode", "activityfinal", "finalnode");
        kinds(ELEMENT_KINDS, "decisionnode", "decisionnode", "decision");
        kinds(ELEMENT_KINDS, "mergenode", "mergenode", "merge");
        kinds(ELEMENT_KINDS, "forknode", "forknode", "fork");
        kinds(ELEMENT_KINDS, "joinnode", "joinnode", "join");
        kinds(ELEMENT_KINDS, "flowfinalnode", "flowfinalnode", "flowfinal");
        kinds(ELEMENT_KINDS, "inputpin", "inputpin");
        kinds(ELEMENT_KINDS, "outputpin", "outputpin");
        kinds(ELEMENT_KINDS, "opaqueaction", "opaqueaction");

        kinds(RELATIONSHIP_KINDS, "association", "association");
        kinds(RELATIONSHIP_KINDS, "directedassociation", "directedassociation");
        kinds(RELATIONSHIP_KINDS, "composition", "composition");
        kinds(RELATIONSHIP_KINDS, "generalization", "generalization");
        kinds(RELATIONSHIP_KINDS, "dependency", "dependency");
        kinds(RELATIONSHIP_KINDS, "include", "include");
        kinds(RELATIONSHIP_KINDS, "extend", "extend");
        kinds(RELATIONSHIP_KINDS, "controlflow", "controlflow");
        kinds(RELATIONSHIP_KINDS, "objectflow", "objectflow");
        kinds(RELATIONSHIP_KINDS, "allocate", "allocate");
        kinds(RELATIONSHIP_KINDS, "satisfy", "satisfy");
        kinds(RELATIONSHIP_KINDS, "verify", "verify");
        kinds(RELATIONSHIP_KINDS, "derive", "derive", "derivereqt");
        kinds(RELATIONSHIP_KINDS, "refine", "refine");
        kinds(RELATIONSHIP_KINDS, "trace", "trace");
        kinds(RELATIONSHIP_KINDS, "transition", "transition");
        kinds(RELATIONSHIP_KINDS, "connector", "connector");
        kinds(RELATIONSHIP_KINDS, "informationflow", "informationflow");
        kinds(RELATIONSHIP_KINDS, "itemflow", "itemflow");
    }

    private ModelText() {
    }

    // Registers aliases for one canonical kind.
    private static void kinds(Map<String, String> map, String canonical, String... aliases) {
        for (String a : aliases) {
            map.put(a, canonical);
        }
    }

    /** Lower-case and drop '-', '_' and spaces: "Use-Case" and "use case" both become "usecase". */
    public static String key(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[-_ ]+", "");
    }

    /** Canonical element kind for a cameo_create_element type, or null when unsupported. */
    public static String elementKind(String type) {
        return ELEMENT_KINDS.get(key(type));
    }

    /** Canonical relationship kind for a cameo_create_relationship type, or null when unsupported. */
    public static String relationshipKind(String type) {
        return RELATIONSHIP_KINDS.get(key(type));
    }

    /** The INVALID_PARAMS error listing every element type cameo_create_element supports. */
    public static ToolException unsupportedElementType(String type) {
        return ToolException.invalid("Unsupported element type '" + type + "'. Supported: Package, Profile, Stereotype, "
                + "Block, Class, UseCase, Activity, Actor, StateMachine, State, Pseudostate, InitialState, Requirement, "
                + "InterfaceBlock, Interface, ConstraintBlock, ValueType, DataType, Signal, Property, FlowProperty, "
                + "Operation, Port, Enumeration, Component, Constraint, Comment, CallBehaviorAction, ActivityPartition, "
                + "InitialNode, ActivityFinalNode, DecisionNode, MergeNode, ForkNode, JoinNode, FlowFinalNode, "
                + "InputPin, OutputPin, OpaqueAction.");
    }

    /** The INVALID_PARAMS error listing every relationship type cameo_create_relationship supports. */
    public static ToolException unsupportedRelationshipType(String type) {
        return ToolException.invalid("Unsupported relationship type '" + type + "'. Supported: Association, "
                + "DirectedAssociation, Composition, Generalization, Dependency, Include, Extend, ControlFlow, "
                + "ObjectFlow, Allocate, Satisfy, Verify, Derive, Refine, Trace, Transition, Connector, "
                + "InformationFlow, ItemFlow.");
    }

    /** SysML stereotype applied automatically for an element kind, or null. */
    public static String defaultCreationStereotype(String kind) {
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case "block":
                return "block";
            case "requirement":
                return "requirement";
            case "interfaceblock":
                return "interfaceBlock";
            case "constraintblock":
                return "constraintBlock";
            case "valuetype":
                return "valueType";
            case "flowproperty":
                return "flowProperty";
            default:
                return null;
        }
    }

    /** Default stereotype first, then the explicit one when it is different (case-insensitive). */
    public static List<String> effectiveCreationStereotypes(String kind, String explicitStereotype) {
        List<String> out = new ArrayList<>();
        String dflt = defaultCreationStereotype(kind);
        if (dflt != null) {
            out.add(dflt);
        }
        if (explicitStereotype != null && !explicitStereotype.trim().isEmpty()) {
            String s = explicitStereotype.trim();
            boolean present = false;
            for (String existing : out) {
                if (existing.equalsIgnoreCase(s)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                out.add(s);
            }
        }
        return out;
    }

    /** Stereotype name used by a stereotyped-abstraction relationship kind, or null. */
    public static String abstractionStereotype(String relationshipKind) {
        switch (relationshipKind) {
            case "allocate":
                return "Allocate";
            case "satisfy":
                return "Satisfy";
            case "verify":
                return "Verify";
            case "derive":
                return "DeriveReqt";
            case "refine":
                return "Refine";
            case "trace":
                return "Trace";
            default:
                return null;
        }
    }

    /** Profile / package names to prefer when several stereotypes share a name, best first. */
    public static List<String> preferredStereotypeContexts(String stereotypeName) {
        switch (stereotypeName == null ? "" : stereotypeName.toLowerCase(Locale.ROOT)) {
            case "refine":
            case "derivereqt":
            case "satisfy":
            case "verify":
            case "trace":
                return Arrays.asList("Requirements", "SysML", "SysMLProfile");
            case "allocate":
                return Arrays.asList("Allocations", "SysML", "SysMLProfile");
            case "itemflow":
                return Arrays.asList("PortsAndFlows", "SysML", "SysMLProfile");
            default:
                return Arrays.asList("SysML", "SysMLProfile", "Requirements");
        }
    }

    /** Lower-case alphanumerics only, '&' read as "and": "SysML Profile" == "SysMLProfile". */
    public static String normalizeContextToken(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^a-z0-9]", "");
    }

    /**
     * Rank of a candidate by its context names (profile, owner chain): index of the first preferred
     * context it matches, or {@link Integer#MAX_VALUE} when it matches none.
     */
    public static int contextRank(List<String> candidateContexts, List<String> preferredContexts) {
        for (int i = 0; i < preferredContexts.size(); i++) {
            String preferred = normalizeContextToken(preferredContexts.get(i));
            for (String c : candidateContexts) {
                if (preferred.equals(normalizeContextToken(c))) {
                    return i;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Pick the best candidate index by context rank. Returns -1 when the choice is ambiguous
     * (no candidate matches a preferred context, or several share the best rank).
     */
    public static int pickPreferred(List<List<String>> candidateContexts, List<String> preferredContexts) {
        if (candidateContexts.size() == 1) {
            return 0;
        }
        int bestRank = Integer.MAX_VALUE;
        int best = -1;
        int ties = 0;
        for (int i = 0; i < candidateContexts.size(); i++) {
            int rank = contextRank(candidateContexts.get(i), preferredContexts);
            if (rank < bestRank) {
                bestRank = rank;
                best = i;
                ties = 1;
            } else if (rank == bestRank) {
                ties++;
            }
        }
        if (bestRank == Integer.MAX_VALUE || ties != 1) {
            return -1;
        }
        return best;
    }

    /** "Use_case diagram" -> "UseCaseDiagram" (capitalize parts split on '-', '_' and spaces). */
    public static String normalizeTypeName(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : input.split("[-_ ]+")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    /** True when the filter names the same type as any of the candidate names (normalized, case-insensitive). */
    public static boolean typeMatches(String filter, String... candidates) {
        String f = normalizeTypeName(filter);
        for (String c : candidates) {
            if (c != null && f.equalsIgnoreCase(normalizeTypeName(c))) {
                return true;
            }
        }
        return false;
    }

    /** compact (default) or full; anything else is an INVALID_PARAMS error. */
    public static String view(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return VIEW_COMPACT;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (VIEW_COMPACT.equals(v) || VIEW_FULL.equals(v)) {
            return v;
        }
        throw ToolException.invalid("view must be either 'compact' or 'full' (got '" + raw + "').");
    }

    /** Opaque paging cursor for an offset; agents pass it back unchanged. */
    public static String cursorToken(int offset) {
        return "offset:" + offset;
    }

    /**
     * Paging metadata in the reference layout: offset (clamped), hasMore, nextOffset/nextCursor,
     * previousOffset/previousCursor. Returns {start, end} through the given array.
     */
    public static Map<String, Object> page(int total, int offset, int limit, int[] range) {
        int start = Math.max(0, Math.min(offset, total));
        int end = Math.min(start + Math.max(limit, 0), total);
        range[0] = start;
        range[1] = end;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limit", limit);
        m.put("offset", start);
        m.put("hasMore", end < total);
        if (end < total) {
            m.put("nextOffset", end);
            m.put("nextCursor", cursorToken(end));
        }
        if (start > 0) {
            int prev = Math.max(0, start - limit);
            m.put("previousOffset", prev);
            m.put("previousCursor", cursorToken(prev));
        }
        return m;
    }

    /**
     * Upper multiplicity bound from JSON: integer, numeric string, or "*" / -1 for unlimited (-1).
     * Returns null when absent.
     */
    public static Integer parseUpper(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d != Math.rint(d)) {
                throw ToolException.invalid("upper must be an integer or '*'.");
            }
            return (int) d < 0 ? -1 : (int) d;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("*".equals(text)) {
            return -1;
        }
        try {
            int v = Integer.parseInt(text);
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            throw ToolException.invalid("upper must be an integer or '*' (got '" + text + "').");
        }
    }

    /** Lower multiplicity bound: a non-negative integer, or null when absent. */
    public static Integer parseLower(Object value) {
        if (value == null) {
            return null;
        }
        int v;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d != Math.rint(d)) {
                throw ToolException.invalid("lower must be an integer.");
            }
            v = (int) d;
        } else {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                v = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw ToolException.invalid("lower must be an integer (got '" + text + "').");
            }
        }
        if (v < 0) {
            throw ToolException.invalid("lower must be zero or greater.");
        }
        return v;
    }

    /** Parameter direction keyword -> canonical in / out / inout / return. */
    public static String parameterDirection(String value) {
        String n = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        switch (n) {
            case "in":
            case "input":
                return "in";
            case "out":
            case "output":
                return "out";
            case "inout":
            case "in-out":
            case "in_out":
            case "bidirectional":
                return "inout";
            case "return":
            case "result":
                return "return";
            default:
                throw ToolException.invalid("direction must be one of: in, out, inout, return (got '" + value + "').");
        }
    }

    // ------------------------------------------------------------------ bulk create references

    /** The ref name in a "ref:name" value, or null when the value is not a reference. */
    public static String refName(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = ((String) value).trim();
        if (s.length() > REF_PREFIX.length() && s.regionMatches(true, 0, REF_PREFIX, 0, REF_PREFIX.length())) {
            return s.substring(REF_PREFIX.length()).trim();
        }
        return null;
    }

    /** True for parameter names whose value is an element id (and may therefore be a ref). */
    public static boolean isIdField(String name) {
        String k = name == null ? "" : name;
        return k.endsWith("_id") || k.endsWith("_ids") || (k.endsWith("Id") && k.length() > 2)
                || (k.endsWith("Ids") && k.length() > 3);
    }

    /**
     * Replace every "ref:name" in the id fields of an entry with the id created for that ref.
     * Throws INVALID_PARAMS naming the entry when a ref is unknown (not yet created).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveRefs(Map<String, Object> entry, Map<String, String> refs, String where) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entry.entrySet()) {
            Object v = e.getValue();
            if (isIdField(e.getKey())) {
                if (v instanceof List) {
                    List<Object> list = new ArrayList<>();
                    for (Object item : (List<Object>) v) {
                        list.add(resolveOne(item, refs, where, e.getKey()));
                    }
                    v = list;
                } else {
                    v = resolveOne(v, refs, where, e.getKey());
                }
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    // One value: a "ref:name" becomes its created id; anything else passes through.
    private static Object resolveOne(Object v, Map<String, String> refs, String where, String field) {
        String ref = refName(v);
        if (ref == null) {
            return v;
        }
        String id = refs.get(ref);
        if (id == null) {
            throw ToolException.invalid(where + ": " + field + " refers to 'ref:" + ref + "', which no earlier entry "
                    + "defines. Known refs: " + (refs.isEmpty() ? "none" : String.join(", ", refs.keySet()))
                    + ". Entries run in order; define the ref before using it.");
        }
        return id;
    }

    /**
     * Static checks of a bulk plan before anything is created: entries are objects, refs are unique
     * and non-empty, and every "ref:x" points to an entry earlier in the list.
     * Returns the list of ref names (null for entries without ref).
     */
    @SuppressWarnings("unchecked")
    public static List<String> validatePlan(List<Map<String, Object>> entries, int maxEntries) {
        if (entries.isEmpty()) {
            throw ToolException.invalid("elements must be a non-empty array of entry objects.");
        }
        if (entries.size() > maxEntries) {
            throw ToolException.invalid("elements has " + entries.size() + " entries; the limit is " + maxEntries
                    + " per call. Split the batch.");
        }
        List<String> names = new ArrayList<>();
        Map<String, Integer> defined = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = entries.get(i);
            String where = "Entry " + i;
            for (Map.Entry<String, Object> e : entry.entrySet()) {
                if (!isIdField(e.getKey())) {
                    continue;
                }
                List<Object> values = e.getValue() instanceof List ? (List<Object>) e.getValue()
                        : Collections.singletonList(e.getValue());
                for (Object v : values) {
                    String ref = refName(v);
                    if (ref != null && !defined.containsKey(ref)) {
                        throw ToolException.invalid(where + ": " + e.getKey() + " uses 'ref:" + ref + "' before any "
                                + "earlier entry defines ref '" + ref + "'.");
                    }
                }
            }
            Object rawRef = entry.get("ref");
            String ref = rawRef == null ? null : String.valueOf(rawRef).trim();
            if (ref != null && ref.isEmpty()) {
                throw ToolException.invalid(where + ": ref must be a non-empty string.");
            }
            if (ref != null) {
                if (ref.regionMatches(true, 0, REF_PREFIX, 0, REF_PREFIX.length())) {
                    ref = ref.substring(REF_PREFIX.length()).trim();
                }
                Integer first = defined.putIfAbsent(ref, i);
                if (first != null) {
                    throw ToolException.invalid(where + ": ref '" + ref + "' is already used by entry " + first + ".");
                }
            }
            names.add(ref);
        }
        return names;
    }

    /** Whether a bulk entry describes a relationship (explicit kind, or source/target ids present). */
    public static boolean isRelationshipEntry(Map<String, Object> entry) {
        Object kind = entry.get("kind");
        if (kind != null) {
            String k = key(String.valueOf(kind));
            if (k.equals("relationship")) {
                return true;
            }
            if (k.equals("element")) {
                return false;
            }
            throw ToolException.invalid("kind must be 'element' or 'relationship' (got '" + kind + "').");
        }
        return entry.get("source_id") != null || entry.get("sourceId") != null
                || entry.get("target_id") != null || entry.get("targetId") != null;
    }

    // ------------------------------------------------------------------ state machines

    /** Validates trigger parameters and returns the normalized kind ("change" or "signal"). */
    public static String triggerKind(String triggerKind, String expression, String signalId) {
        String k = triggerKind == null ? "" : triggerKind.trim().toLowerCase(Locale.ROOT);
        if (!k.equals("change") && !k.equals("signal")) {
            throw ToolException.invalid("trigger_kind must be 'change' or 'signal'.");
        }
        if (k.equals("change") && (expression == null || expression.trim().isEmpty())) {
            throw ToolException.invalid("change triggers require a non-empty expression.");
        }
        if (k.equals("signal") && (signalId == null || signalId.trim().isEmpty())) {
            throw ToolException.invalid("signal triggers require signal_id.");
        }
        return k;
    }

    /** What to do with one state behavior slot. */
    public enum SlotAction { KEEP, CLEAR, SET }

    /**
     * Port of the reference slot rules: omitted (null) keeps the slot unless clearUnspecified;
     * blank text clears it; other text sets it.
     */
    public static SlotAction slotAction(String body, boolean clearUnspecified) {
        if (body == null) {
            return clearUnspecified ? SlotAction.CLEAR : SlotAction.KEEP;
        }
        return body.trim().isEmpty() ? SlotAction.CLEAR : SlotAction.SET;
    }

    /** The behavior language (e.g. "English", "Groovy"); must be non-blank. */
    public static String requireLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            throw ToolException.invalid("language must be a non-empty string.");
        }
        return language;
    }
}
