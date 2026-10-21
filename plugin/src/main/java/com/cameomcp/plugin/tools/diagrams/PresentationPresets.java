package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.diagrams.CompartmentAliasResolver.normalizeKey;

/**
 * Intent-level presentation presets: which native presentation property names a request such as
 * {@code showTriggers} maps to, and diagram-type-aware repair defaults. Pure logic.
 */
public final class PresentationPresets {

    /** Not instantiable; all members are static. */
    private PresentationPresets() {
    }

    /** A boolean request matched against presentation property names. */
    public static final class Selection {
        /** The boolean value requested. */
        public final boolean value;
        /** Property names that match exactly, compared after {@link CompartmentAliasResolver#normalizeKey}. */
        public final List<String> exactNames;
        /** Normalized tokens that match when a property name contains them. */
        public final List<String> containsTokens;

        /**
         * Creates a selection.
         *
         * @param value the boolean value requested
         * @param exactNames property names that match exactly
         * @param containsTokens normalized tokens that match by containment
         */
        public Selection(boolean value, List<String> exactNames, List<String> containsTokens) {
            this.value = value;
            this.exactNames = exactNames;
            this.containsTokens = containsTokens;
        }

        /**
         * Whether a property name is one this selection controls: equal to one of the exact names, or
         * containing one of the tokens, both compared after normalizeKey (lower-case alphanumerics).
         *
         * @param propertyName the native property name
         * @return true on a match
         */
        public boolean matches(String propertyName) {
            String normalized = normalizeKey(propertyName);
            for (String exact : exactNames) {
                if (normalizeKey(exact).equals(normalized)) {
                    return true;
                }
            }
            for (String token : containsTokens) {
                if (normalized.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Value to store: "Suppress X" properties are inverted.
         *
         * @param propertyName the native property name
         * @return the requested value, inverted for "Suppress" properties
         */
        public boolean valueFor(String propertyName) {
            return normalizeKey(propertyName).startsWith("suppress") ? !value : value;
        }
    }

    /**
     * Shorthand for {@code new Selection(...)}.
     *
     * @param value the boolean value requested
     * @param exact property names that match exactly
     * @param contains normalized tokens that match by containment
     * @return the selection
     */
    public static Selection sel(boolean value, List<String> exact, List<String> contains) {
        return new Selection(value, exact, contains);
    }

    /**
     * Shorthand for an unmodifiable list.
     *
     * @param v the items
     * @return the list
     */
    private static List<String> l(String... v) {
        return Collections.unmodifiableList(Arrays.asList(v));
    }

    /**
     * Selection for one canonical intent key, for example "showTriggers". Each lists the native names plus
     * looser contains-tokens, because property names vary by shape type and Cameo version (Trigger or Event,
     * Guard or Condition). Unknown keys match their own name.
     *
     * @param canonicalKey the intent key, in any case or spelling
     * @param value the boolean value requested
     * @return the selection
     */
    public static Selection forCanonicalKey(String canonicalKey, boolean value) {
        String n = normalizeKey(canonicalKey);
        switch (n) {
            case "showname":
                return sel(value, l("Show Name"), l("showname"));
            case "showstereotype":
                return sel(value, l("Show Stereotype"), l("showstereotype"));
            case "showtype":
                return sel(value, l("Show Type"), l("showtype"));
            case "showtriggers":
                return sel(value, l("Show Triggers", "Show Trigger", "Show Events", "Show Event"),
                        l("showtrigger", "showtriggers", "showevent", "showevents"));
            case "showguard":
                return sel(value, l("Show Guard", "Show Guards", "Show Condition", "Show Conditions"),
                        l("showguard", "showguards", "showcondition", "showconditions"));
            case "showeffect":
                return sel(value, l("Show Effect", "Show Effects", "Show Action", "Show Actions"),
                        l("showeffect", "showeffects", "showaction", "showactions"));
            case "showconveyed":
                return sel(value, l(), l("conveyed", "informationflow"));
            case "showitemproperty":
                return sel(value, l(), l("itemproperty"));
            case "showdirection":
                return sel(value, l(), l("showdirection", "direction"));
            case "showproperties":
                return sel(value, l("Show Properties", "Suppress Properties"), l("showproperties", "suppressproperties"));
            case "showoperations":
                return sel(value, l("Show Operations", "Suppress Operations"), l("showoperations", "suppressoperations"));
            case "showconstraints":
                return sel(value, l("Show Constraints", "Suppress Constraints"), l("showconstraints", "suppressconstraints"));
            case "showtaggedvalues":
                return sel(value, l("Show Tagged Values"), l("showtaggedvalues"));
            case "showports":
                return sel(value, l("Show Ports", "Suppress Ports"), l("showports", "suppressports"));
            // Attributes only exist as a "Suppress" property, so valueFor inverts the request.
            case "showattributes":
                return sel(value, l("Suppress Attributes"), l("suppressattributes"));
            case "showelementproperties":
                return sel(value, l("Show Element Properties"), l("showelementproperties"));
            case "showfullports":
                return sel(value, l("Show Full Ports", "Suppress Full Ports"), l("showfullports", "suppressfullports"));
            case "showparts":
                return sel(value, l("Show Parts", "Suppress Parts"), l("showparts", "suppressparts"));
            case "showcontent":
                return sel(value, l("Show Content", "Suppress Content"), l("showcontent", "suppresscontent"));
            case "showreferences":
                return sel(value, l("Show References", "Suppress References"), l("showreferences", "suppressreferences"));
            case "showvalues":
                return sel(value, l("Show Values", "Suppress Values"), l("showvalues", "suppressvalues"));
            case "showflowproperties":
                return sel(value, l("Show Flow Properties", "Suppress Flow Properties"),
                        l("showflowproperties", "suppressflowproperties"));
            case "showproxyports":
                return sel(value, l("Show Proxy Ports", "Suppress Proxy Ports"), l("showproxyports", "suppressproxyports"));
            case "showbehaviors":
                return sel(value, l("Show Behaviors", "Suppress Behaviors"), l("showbehaviors", "suppressbehaviors"));
            case "showreceptions":
                return sel(value, l("Show Receptions", "Suppress Receptions"), l("showreceptions", "suppressreceptions"));
            case "showstructure":
                return sel(value, l("Show Structure", "Suppress Structure"), l("showstructure", "suppressstructure"));
            case "showallocatedelements":
                return sel(value, l(), l("allocatedelements", "allocatedfrom"));
            case "applyallocationnaming":
                return sel(value, l("Apply SysML 1.7 Allocation Compartment Naming"), l("allocationcompartmentnaming"));
            default:
                return sel(value, l(canonicalKey), l(n));
        }
    }

    /**
     * One Selection per canonical key, all requesting the same boolean value. Used by the repair tools.
     *
     * @param value the boolean value requested
     * @param canonicalKeys the intent keys
     * @return key to selection, in the given order
     */
    public static Map<String, Selection> selectionMap(boolean value, List<String> canonicalKeys) {
        Map<String, Selection> m = new LinkedHashMap<>();
        for (String k : canonicalKeys) {
            m.put(k, forCanonicalKey(k, value));
        }
        return m;
    }

    // ------------------------------------------------------------------ presets of the label tools

    /**
     * Selections for cameo_set_transition_label_presentation: which transition label parts to show.
     *
     * @param showName show the transition name
     * @param showTriggers show the triggers
     * @param showGuard show the guard
     * @param showEffect show the effect
     * @return intent key to selection
     */
    public static Map<String, Selection> transitionLabels(boolean showName, boolean showTriggers, boolean showGuard,
                                                          boolean showEffect) {
        Map<String, Selection> m = new LinkedHashMap<>();
        m.put("showName", sel(showName, l("Show Name"), l("showname")));
        m.put("showTriggers", sel(showTriggers, l(), l("showtrigger", "showtriggers")));
        m.put("showGuard", sel(showGuard, l(), l("showguard")));
        m.put("showEffect", sel(showEffect, l(), l("showeffect")));
        return m;
    }

    /**
     * Selections for cameo_set_item_flow_label_presentation: which item-flow label parts to show.
     *
     * @param showName show the flow name
     * @param showConveyed show the conveyed items
     * @param showItemProperty show the item property
     * @param showDirection show the direction
     * @param showStereotype show the stereotype
     * @return intent key to selection
     */
    public static Map<String, Selection> itemFlowLabels(boolean showName, boolean showConveyed,
                                                        boolean showItemProperty, boolean showDirection,
                                                        boolean showStereotype) {
        Map<String, Selection> m = new LinkedHashMap<>();
        m.put("showName", sel(showName, l("Show Name"), l("showname")));
        m.put("showConveyed", sel(showConveyed, l(), l("conveyed", "informationflow")));
        m.put("showItemProperty", sel(showItemProperty, l(), l("itemproperty")));
        m.put("showDirection", sel(showDirection, l(), l("showdirection", "direction")));
        m.put("showStereotype", sel(showStereotype, l("Show Stereotype"), l("showstereotype")));
        return m;
    }

    /**
     * Selections for cameo_set_allocation_compartment_presentation: allocated-element, element-property and
     * port compartments, plus the SysML 1.7 allocation compartment naming option.
     *
     * @param showAllocatedElements show the allocated elements compartment
     * @param showElementProperties show the element properties compartment
     * @param showPorts show ports
     * @param showFullPorts show full ports
     * @param applyAllocationNaming use the SysML 1.7 allocation compartment naming
     * @return intent key to selection
     */
    public static Map<String, Selection> allocationCompartments(boolean showAllocatedElements,
                                                                boolean showElementProperties, boolean showPorts,
                                                                boolean showFullPorts, boolean applyAllocationNaming) {
        Map<String, Selection> m = new LinkedHashMap<>();
        m.put("showAllocatedElements", sel(showAllocatedElements, l(), l("allocatedelements", "allocatedfrom")));
        m.put("showElementProperties", sel(showElementProperties, l("Show Element Properties"),
                l("showelementproperties")));
        m.put("showPorts", sel(showPorts, l("Show Ports"), l("showports")));
        m.put("showFullPorts", sel(showFullPorts, l("Show Full Ports", "Suppress Full Ports"),
                l("showfullports", "suppressfullports")));
        m.put("applyAllocationNaming", sel(applyAllocationNaming,
                l("Apply SysML 1.7 Allocation Compartment Naming"), l("allocationcompartmentnaming")));
        return m;
    }

    /**
     * True when a property name hints that a shape supports allocation compartments.
     *
     * @param propertyName the native property name
     * @return true for the allocation-related property names
     */
    public static boolean isAllocationProperty(String propertyName) {
        String n = normalizeKey(propertyName);
        return n.contains("allocatedelements") || n.contains("allocatedfrom")
                || n.contains("allocationcompartmentnaming") || n.contains("showelementproperties")
                || n.contains("showfullports") || n.contains("suppressfullports");
    }

    /**
     * Short canonical key for well-known compartment properties (get_shape_properties' "compartments").
     *
     * @param propertyName the native property name, may be null
     * @return the short key, such as "properties", or null when the property is not a known compartment
     */
    public static String canonicalCompartmentKey(String propertyName) {
        if (propertyName == null) {
            return null;
        }
        switch (normalizeKey(propertyName)) {
            case "showproperties":
                return "properties";
            case "showoperations":
            case "suppressoperations":
                return "operations";
            case "showconstraints":
                return "constraints";
            case "showtaggedvalues":
                return "tagged_values";
            case "showports":
                return "ports";
            case "suppressattributes":
                return "attributes";
            case "showstereotype":
                return "stereotype";
            case "showname":
                return "name";
            case "showtype":
                return "type";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ repair defaults

    /** Diagram-type-aware keys used by the repair tools. */
    public static final class RepairDefaults {
        /** The diagram type as given. */
        public final String diagramType;
        /** The diagram type after {@link CompartmentAliasResolver#normalizeKey}. */
        public final String normalizedDiagramType;
        /** Union of the shape and path label keys. */
        public final List<String> hiddenLabelKeys;
        /** Label keys reset on shapes. */
        public final List<String> shapeLabelKeys;
        /** Label keys reset on paths. */
        public final List<String> pathLabelKeys;
        /** Conveyed-item label keys for item flows. */
        public final List<String> conveyedItemKeys;
        /** Compartment keys touched on shapes; empty for most diagram types. */
        public final List<String> compartmentKeys;
        /** Whether path labels are reset unless the agent says otherwise. */
        public final boolean resetPathLabelsByDefault;

        /**
         * Creates the defaults.
         *
         * @param diagramType the diagram type as given
         * @param normalized the normalized diagram type
         * @param hidden the union of shape and path label keys
         * @param shape the shape label keys
         * @param path the path label keys
         * @param conveyed the conveyed-item label keys
         * @param compartments the compartment keys
         * @param reset whether path labels are reset by default
         */
        RepairDefaults(String diagramType, String normalized, List<String> hidden, List<String> shape,
                       List<String> path, List<String> conveyed, List<String> compartments, boolean reset) {
            this.diagramType = diagramType;
            this.normalizedDiagramType = normalized;
            this.hiddenLabelKeys = hidden;
            this.shapeLabelKeys = shape;
            this.pathLabelKeys = path;
            this.conveyedItemKeys = conveyed;
            this.compartmentKeys = compartments;
            this.resetPathLabelsByDefault = reset;
        }

        /**
         * The defaults as JSON, for tool results.
         *
         * @return every field, with lists as string arrays
         */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("diagramType", diagramType);
            json.addProperty("normalizedDiagramType", normalizedDiagramType);
            json.addProperty("resetPathLabelsByDefault", resetPathLabelsByDefault);
            json.add("hiddenLabelKeys", Gs.strings(hiddenLabelKeys));
            json.add("shapeLabelKeys", Gs.strings(shapeLabelKeys));
            json.add("pathLabelKeys", Gs.strings(pathLabelKeys));
            json.add("conveyedItemKeys", Gs.strings(conveyedItemKeys));
            json.add("compartmentKeys", Gs.strings(compartmentKeys));
            return json;
        }
    }

    /**
     * Which label and compartment keys the repair tools touch for a diagram type, chosen by substring
     * on the normalized type name; the general defaults cover unrecognized types. hiddenLabelKeys is the
     * union of the shape and path label keys.
     *
     * @param diagramType the diagram type, may be null
     * @return the defaults
     */
    public static RepairDefaults repairDefaults(String diagramType) {
        String n = normalizeKey(diagramType);
        List<String> shape = l("showName", "showStereotype");
        List<String> path = l("showName");
        List<String> conveyed = l("showConveyed", "showItemProperty", "showDirection");
        List<String> compartments = l();

        // State machines: transitions carry triggers/guards/effects on the path label.
        if (n.contains("statemachine")) {
            shape = l("showName");
            path = l("showName", "showTriggers", "showGuard", "showEffect");
            conveyed = l("showName", "showConveyed", "showItemProperty", "showDirection");
        } else if (n.contains("internalblock") || n.equals("ibd")) {
            shape = l("showName", "showStereotype");
            path = l("showName");
            conveyed = l("showConveyed", "showItemProperty", "showDirection", "showStereotype");
            compartments = l("showPorts", "showFullPorts", "showElementProperties", "showAllocatedElements",
                    "applyAllocationNaming");
        } else if (n.contains("blockdefinition") || n.equals("bdd") || n.contains("classdiagram")) {
            shape = l("showName", "showStereotype", "showType");
            path = l("showName");
            compartments = l("showProperties", "showOperations", "showConstraints", "showTaggedValues", "showPorts",
                    "showAttributes");
        } else if (n.contains("requirement")) {
            shape = l("showName", "showStereotype", "showType");
            path = l("showName");
            compartments = l("showConstraints", "showTaggedValues");
        } else if (n.contains("usecase")) {
            shape = l("showName", "showStereotype");
            path = l();
            compartments = l();
        } else if (n.contains("activity")) {
            shape = l("showName", "showStereotype");
            path = l("showName");
            compartments = l();
        } else if (n.contains("sequence")) {
            shape = l("showName");
            path = l("showName");
            compartments = l();
        } else if (n.contains("component") || n.contains("deployment") || n.contains("package")
                || n.contains("compositestructure")) {
            shape = l("showName", "showStereotype", "showType");
            path = l("showName");
            compartments = l("showProperties", "showOperations", "showConstraints", "showTaggedValues", "showPorts",
                    "showAttributes");
        }
        LinkedHashSet<String> hidden = new LinkedHashSet<>(shape);
        hidden.addAll(path);
        return new RepairDefaults(diagramType == null ? "" : diagramType, n,
                Collections.unmodifiableList(new java.util.ArrayList<>(hidden)), shape, path, conveyed, compartments,
                true);
    }
}
