package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The validated diagram request tokens and how they map to native Cameo diagram types.
 * Pure logic, no Cameo dependency.
 */
public final class DiagramTypes {

    /** One validated diagram type. */
    public static final class Spec {
        public final String canonical;
        /** Native Cameo type names to try, in order; the first is the documented one. */
        public final List<String> nativeTypes;
        public final String family;
        public final List<String> aliases;

        Spec(String canonical, String family, List<String> nativeTypes, String... aliases) {
            this.canonical = canonical;
            this.family = family;
            this.nativeTypes = Collections.unmodifiableList(nativeTypes);
            this.aliases = Collections.unmodifiableList(Arrays.asList(aliases));
        }

        public String nativeType() {
            return nativeTypes.get(0);
        }
    }

    // Lookup table: canonical name, every native type name and every alias, all keyed by lookupKey().
    public static final List<Spec> VALIDATED;
    private static final Map<String, Spec> BY_KEY = new LinkedHashMap<>();

    static {
        // Order here is the order cameo_list_diagram_types reports. A spec may list several native names;
        // createDiagram tries them in order (e.g. both requirement diagram spellings) until one works.
        List<Spec> l = new ArrayList<>();
        l.add(new Spec("Class", "uml", list("Class Diagram"), "class", "ClassDiagram", "Class Diagram"));
        l.add(new Spec("Package", "uml", list("Package Diagram"), "package", "PackageDiagram", "Package Diagram"));
        l.add(new Spec("UseCase", "uml", list("Use Case Diagram"), "usecase", "use case", "UseCaseDiagram", "Use Case Diagram"));
        l.add(new Spec("Activity", "uml", list("Activity Diagram"), "activity", "ActivityDiagram", "Activity Diagram"));
        l.add(new Spec("Sequence", "uml", list("Sequence Diagram"), "sequence", "SequenceDiagram", "Sequence Diagram"));
        l.add(new Spec("StateMachine", "uml", list("State Machine Diagram"), "statemachine", "state machine",
                "StateMachineDiagram", "State Machine Diagram"));
        l.add(new Spec("Component", "uml", list("Component Diagram"), "component", "ComponentDiagram", "Component Diagram"));
        l.add(new Spec("Deployment", "uml", list("Deployment Diagram"), "deployment", "DeploymentDiagram", "Deployment Diagram"));
        l.add(new Spec("CompositeStructure", "uml", list("Composite Structure Diagram"), "compositestructure",
                "composite structure", "CompositeStructureDiagram", "Composite Structure Diagram"));
        l.add(new Spec("Object", "uml", list("Object Diagram"), "object", "ObjectDiagram", "Object Diagram"));
        l.add(new Spec("Communication", "uml", list("Communication Diagram"), "communication", "CommunicationDiagram",
                "Communication Diagram"));
        l.add(new Spec("InteractionOverview", "uml", list("Interaction Overview Diagram"), "interactionoverview",
                "interaction overview", "InteractionOverviewDiagram", "Interaction Overview Diagram"));
        l.add(new Spec("Timing", "uml", list("Timing Diagram"), "timing", "TimingDiagram", "Timing Diagram"));
        l.add(new Spec("Profile", "uml", list("Profile Diagram"), "profile", "ProfileDiagram", "Profile Diagram"));
        l.add(new Spec("BDD", "sysml", list("SysML Block Definition Diagram"), "bdd", "BlockDefinitionDiagram",
                "Block Definition Diagram", "SysML BDD", "SysML Block Definition Diagram"));
        l.add(new Spec("IBD", "sysml", list("SysML Internal Block Diagram"), "ibd", "InternalBlockDiagram",
                "Internal Block Diagram", "SysML IBD", "SysML Internal Block Diagram"));
        l.add(new Spec("Requirement Diagram", "sysml", list("Requirement Diagram", "SysML Requirement Diagram"),
                "requirement", "requirements", "RequirementDiagram", "Requirement Diagram", "SysML Requirement Diagram"));
        l.add(new Spec("Parametric Diagram", "sysml", list("SysML Parametric Diagram"), "parametric",
                "ParametricDiagram", "Parametric Diagram", "SysML Parametric Diagram"));
        l.add(new Spec("RelationMap", "analysis", list("Relation Map Diagram"), "relation map", "relationship map",
                "RelationMap", "Relation Map", "Relation Map Diagram", "Relationship Map", "Relationship Map Diagram"));
        l.add(new Spec("Content Diagram", "analysis", list("Content Diagram"), "content", "content diagram",
                "ContentDiagram", "Content Diagram"));
        VALIDATED = Collections.unmodifiableList(l);
        for (Spec s : VALIDATED) {
            BY_KEY.put(lookupKey(s.canonical), s);
            for (String n : s.nativeTypes) {
                BY_KEY.put(lookupKey(n), s);
            }
            for (String a : s.aliases) {
                BY_KEY.put(lookupKey(a), s);
            }
        }
    }

    private DiagramTypes() {
    }

    private static List<String> list(String... v) {
        return Arrays.asList(v);
    }

    /*
     * Normalizes a type token for lookup: splits camelCase and acronym boundaries, lower-cases, treats
     * '-' and '_' as spaces and collapses whitespace.
     */
    /** "StateMachineDiagram" / "state_machine-diagram" -> "state machine diagram". */
    public static String lookupKey(String value) {
        if (value == null) {
            return "";
        }
        // "StateMachine" -> "State Machine"
        String spaced = value.trim().replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        // "BDDiagram"-style runs: split before the last capital of an acronym followed by lower case.
        spaced = spaced.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        String normalized = spaced.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
        return String.join(" ", normalized.trim().split("\\s+"));
    }

    /** The validated spec for an input token or alias, or null for unknown (native) types. */
    public static Spec find(String input) {
        if (input == null) {
            return null;
        }
        Spec s = BY_KEY.get(lookupKey(input));
        if (s == null) {
            // "class diagram" style keys without spaces, e.g. "usecasediagram"
            String squashed = lookupKey(input).replace(" ", "");
            for (Map.Entry<String, Spec> e : BY_KEY.entrySet()) {
                if (e.getKey().replace(" ", "").equals(squashed)) {
                    return e.getValue();
                }
            }
        }
        return s;
    }

    /** Native type names to try when creating a diagram for {@code input}. Unknown inputs pass through. */
    public static List<String> nativeCandidates(String input) {
        Spec s = find(input);
        if (s != null) {
            return s.nativeTypes;
        }
        return Collections.singletonList(input == null ? "" : input.trim());
    }

    // Relation maps need extra setup (context, scope, criteria) after creation; see DiagramService.createDiagram.
    public static boolean isRelationMap(String nativeType) {
        return "Relation Map Diagram".equals(nativeType);
    }

    // cameo_list_diagram_types: the validated types with their native names, fallbacks and aliases.
    public static JsonObject listJson() {
        JsonArray arr = new JsonArray();
        for (Spec s : VALIDATED) {
            JsonObject o = new JsonObject();
            o.addProperty("canonical", s.canonical);
            o.addProperty("nativeType", s.nativeType());
            if (s.nativeTypes.size() > 1) {
                o.add("nativeTypeFallbacks", Gs.strings(s.nativeTypes.subList(1, s.nativeTypes.size())));
            }
            o.addProperty("family", s.family);
            o.add("aliases", Gs.strings(s.aliases));
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("count", arr.size());
        out.add("diagramTypes", arr);
        out.addProperty("note", "Pass the canonical token (or any alias) as cameo_create_diagram type. Any other "
                + "string is passed to Cameo unchanged as a native diagram type name (e.g. 'SysML Activity Diagram').");
        return out;
    }
}
