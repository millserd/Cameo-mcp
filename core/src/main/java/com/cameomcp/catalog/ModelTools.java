package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.util.Arrays;
import java.util.List;

import static com.cameomcp.mcp.ToolSpec.REQUIRED;
import static com.cameomcp.mcp.ToolSpec.tool;

/**
 * Project, UI context, element query/mutation, stereotypes and profiles, relationships,
 * specification access, state-machine semantics and Groovy macros.
 */
public final class ModelTools {

    /** Family name used in docs and in cameo_list_tools filters. */
    public static final String FAMILY = "model";

    /** Element types cameo_create_element understands (aliases are case-insensitive; '-' and ' ' optional). */
    public static final String ELEMENT_TYPES = "Structural: Package, Profile, Class, Property, Port, FlowProperty, "
            + "Interface, DataType, Enumeration, Signal, Component, Operation. Profiles: Stereotype (parent must be a "
            + "Profile; bind with metaclasses). SysML aliases (stereotype applied automatically; fails if SysML is "
            + "not available): Block, ConstraintBlock, InterfaceBlock, Requirement, ValueType, FlowProperty. "
            + "Behavioral: Activity, UseCase, Actor, StateMachine (gets a default Region), State, Pseudostate / "
            + "InitialState (parent: Region, StateMachine or State). Activity nodes: InitialNode, ActivityFinalNode, "
            + "FlowFinalNode, DecisionNode, MergeNode, ForkNode, JoinNode, CallBehaviorAction, OpaqueAction, "
            + "ActivityPartition, InputPin, OutputPin. Other: Comment, Constraint";

    /** Relationship types cameo_create_relationship understands. */
    public static final String RELATIONSHIP_TYPES = "Association, DirectedAssociation, Composition, Generalization, "
            + "Dependency, Include, Extend, ControlFlow, ObjectFlow, Allocate, Satisfy, Derive, Refine, Trace, Verify, "
            + "Transition, Connector, InformationFlow, ItemFlow";

    /** Static methods only. */
    private ModelTools() {
    }

    /**
     * The family's specs, in the order clients see them. Descriptions and parameter help are the
     * text agents read when choosing a tool, so they say what the tool returns and which tool to call next.
     *
     * @return the specs, a fixed-size list built on every call
     */
    public static List<ToolSpec> specs() {
        return Arrays.asList(
                // ---------------------------------------------------------------- project
                tool("cameo_get_project", FAMILY).readOnly()
                        .desc("Get the open project: name, filePath, isRemote (Teamwork Cloud), isDirty (unsaved "
                                + "changes), projectKind, primaryModelId and primaryModelName. primaryModelId is the usual "
                                + "root for cameo_list_containment_children and the default parent for new packages. "
                                + "projectKind is 'uml' (UML / SysML v1) or 'sysml2' (a Cameo 2026x SysML v2 project, "
                                + "which has no primary model: use the cameo_sysml2_* tools there)."),
                tool("cameo_save_project", FAMILY).sideEffect()
                        .desc("Save the open project to its existing location (same as File > Save). Call after a "
                                + "batch of changes you want to keep. Fails for a project that was never saved to a "
                                + "file (ask the user to use File > Save As once). Returns saved and filePath. For "
                                + "Teamwork Cloud projects this saves locally; committing is done in Cameo.")
                        .timeout(300),

                // ---------------------------------------------------------------- UI context
                tool("cameo_get_ui_state", FAMILY).readOnly()
                        .desc("Inspect the live Cameo UI: project, activeDiagram (id, name, type, owner), "
                                + "selectedElements (deduplicated union of diagram symbol selection and containment-tree "
                                + "selection), selectedPresentations (symbols selected on the active diagram with "
                                + "presentationId, elementId, bounds), browserSelection, counts, and warnings for UI APIs "
                                + "this Cameo version does not offer. Use after the user says 'this diagram' or 'the "
                                + "selected block'.")
                        .bool("summary_only", "Cap selectedPresentations at 50 entries.", false),
                tool("cameo_get_active_diagram", FAMILY).readOnly()
                        .desc("Get the diagram currently open and focused in Cameo as a small payload: "
                                + "activeDiagram {id, name, type, owner} plus warnings. activeDiagram is {} when no "
                                + "diagram is open. Use its id with the diagram tools."),
                tool("cameo_get_ui_selection", FAMILY).readOnly()
                        .desc("Get what the user has selected: selectedElements (model elements), "
                                + "selectedPresentations (diagram symbols with presentationId), browserSelection "
                                + "(containment tree) and counts. Use the returned ids as inputs to other tools."),

                // ---------------------------------------------------------------- queries
                tool("cameo_query_elements", FAMILY).readOnly()
                        .desc("Search model elements by metaclass, name, scope and stereotype, sorted by type, name, "
                                + "id and paged (limit/offset, hasMore, nextOffset). Use it to find ids before creating "
                                + "or linking elements and to avoid duplicates. type is a UML metaclass such as Class, "
                                + "Package, Property, Port, Activity, StateMachine, State, Pseudostate, UseCase, Actor; "
                                + "SysML Blocks and Requirements are Class + stereotype 'block' / 'requirement'. name uses Cameo's name finder. With no filter at all it "
                                + "lists every element under the scope, so always page.")
                        .str("type", "UML metaclass filter, e.g. Class, Package, Property, Port, Activity, State. "
                                + "SysML concepts are stereotypes: use type=Class with stereotype=block.")
                        .str("name", "Element name to match.")
                        .str("package_id", "Restrict the search to the subtree of this element id (package, model, "
                                + "block...). Aliases: package, owner_id, root_id. Default: the primary model.")
                        .str("stereotype", "Applied stereotype name, case-insensitive (e.g. block, requirement, "
                                + "interfaceBlock).")
                        .bool("recursive", "Search nested packages too; false = direct children of the scope only.", true)
                        .integer("limit", "Page size (1-1000).", 200, 1, 1000)
                        .integer("offset", "Zero-based offset into the sorted result set.", 0)
                        .enumStrDefault("view", "compact (id, type, name, owner, type refs) or full (adds "
                                + "documentation, stereotypes, tagged values).", "compact", "compact", "full"),
                tool("cameo_get_element", FAMILY).readOnly()
                        .desc("Get full details of one element: id, type, humanType, name, qualifiedName, owner, "
                                + "stereotypes, documentation, taggedValues, type/multiplicity/aggregation for "
                                + "properties, port flags, connector ends, information-flow fields and childCount. "
                                + "For every UML property and constraint field use cameo_get_specification.")
                        .str("element_id", "Element id from a query, tree or selection result.", REQUIRED),
                tool("cameo_get_containment_tree", FAMILY).readOnly()
                        .desc("Nested containment tree (id, type, name, stereotypes, children) from a root down to "
                                + "depth levels; nodes at the depth limit report childCount. Payloads grow fast: on "
                                + "large models prefer cameo_list_containment_children and drill down.")
                        .str("root_id", "Root element id (alias parent_id). Default: the primary model.")
                        .integer("depth", "Levels to traverse (1-10).", 3, 1, 10)
                        .enumStrDefault("view", "compact, or full to embed each node's full element JSON.",
                                "compact", "compact", "full"),
                tool("cameo_list_containment_children", FAMILY).readOnly()
                        .desc("List the immediate children of one element, sorted by name and paged, with "
                                + "totalChildren, matchedChildren, hasMore and nextOffset. The scalable way to browse a "
                                + "big model: start with no root_id (primary model) and follow ids down.")
                        .str("root_id", "Parent element id (alias parent_id). Default: the primary model.")
                        .integer("limit", "Page size (1-500).", 50, 1, 500)
                        .integer("offset", "Zero-based offset into the filtered children.", 0)
                        .str("type", "Child type filter, e.g. Package, Class, Diagram (matches metaclass or "
                                + "human type, case-insensitive).")
                        .str("name", "Case-insensitive substring filter on the child name.")
                        .str("stereotype", "Applied stereotype filter, case-insensitive.")
                        .enumStrDefault("view", "compact or full.", "compact", "compact", "full"),

                // ---------------------------------------------------------------- element mutation
                tool("cameo_create_element", FAMILY).write()
                        .desc("Create one model element under parent_id and return it. Supported type values: "
                                + ELEMENT_TYPES + ". Optional fields apply only where they make sense (type_id for typed "
                                + "elements, multiplicity for properties/ports/pins, port flags for ports, direction for "
                                + "flow properties) and fail with a clear message otherwise. Example: "
                                + "{type: 'Block', name: 'Engine', parent_id: '<package id>'}. To build many elements and "
                                + "relationships at once use cameo_create_elements (one undo step).")
                        .str("type", "Element type, see the list above (e.g. Package, Block, Requirement, Port, State).", REQUIRED)
                        .str("name", "Element name.", REQUIRED)
                        .str("parent_id", "Owner element id (package, block, activity, state machine, region...).", REQUIRED)
                        .str("stereotype", "Extra stereotype to apply (SysML aliases get theirs automatically).")
                        .str("documentation", "Documentation text (stored as an owned Comment).")
                        .str("behavior_id", "CallBehaviorAction only: the Activity/Behavior it calls.")
                        .str("represents_id", "ActivityPartition only: the Block/Class (performer) it represents.")
                        .str("type_id", "Typed elements (Property, Port, FlowProperty, pins): id of the type.")
                        .integer("lower", "Lower multiplicity bound (>= 0).")
                        .any("upper", "Upper multiplicity bound: integer, or -1 / \"*\" for unlimited.")
                        .bool("is_ordered", "Multiplicity isOrdered flag.")
                        .bool("is_unique", "Multiplicity isUnique flag.")
                        .enumStr("aggregation", "Property aggregation.", false, "none", "shared", "composite")
                        .bool("is_behavior", "Port isBehavior flag.")
                        .bool("is_conjugated", "Port isConjugated flag.")
                        .bool("is_service", "Port isService flag.")
                        .str("direction", "FlowProperty direction: in, out or inout.")
                        .strArray("metaclasses", "Stereotype only: UML metaclass names to extend, e.g. [\"Class\"]."),
                tool("cameo_create_elements", FAMILY).write().timeout(180)
                        .desc("Bulk-create many elements and relationships in ONE undoable step, all-or-nothing: if "
                                + "any entry fails the whole batch is rolled back and the error names the entry. Each "
                                + "entry is an object with the parameters of cameo_create_element (type, name, "
                                + "parent_id, ...) or, when it has source_id and target_id, of cameo_create_relationship. "
                                + "Give an entry a caller-chosen \"ref\" and point later entries at it with "
                                + "\"ref:<name>\" in any *_id field (parent_id, type_id, source_id, owner_id, "
                                + "conveyed_ids items...). An entry may also carry tagged_values {tag: value} (applied "
                                + "with its stereotype). Entries run in order, so a ref must be defined before use. "
                                + "Example: [{ref:'pkg', type:'Package', name:'Power', parent_id:'<model id>'}, "
                                + "{ref:'bat', type:'Block', name:'Battery', parent_id:'ref:pkg'}, {ref:'req', "
                                + "type:'Requirement', name:'Capacity', parent_id:'ref:pkg', tagged_values:{id:'R1', "
                                + "text:'Shall store 5 kWh'}}, {type:'Satisfy', source_id:'ref:bat', "
                                + "target_id:'ref:req'}]. Returns refs {ref: id} and one result per entry.")
                        .objArray("elements", "Entries to create, in order (max 500).", REQUIRED)
                        .enumStrDefault("view", "Per-entry result detail: compact (id, type, name) or full "
                                + "(full element JSON).", "compact", "compact", "full"),
                tool("cameo_modify_element", FAMILY).write()
                        .desc("Rename an element and/or replace its documentation (first owned Comment, created if "
                                + "missing). For any other property use cameo_set_specification.")
                        .str("element_id", "Element to modify.", REQUIRED)
                        .str("name", "New name; omit to keep.")
                        .str("documentation", "New documentation text; omit to keep."),
                tool("cameo_move_element", FAMILY).write()
                        .desc("Move (reparent) an element to another owner, keeping its id, relationships and "
                                + "diagram symbols. Validates first: the new owner must exist and be editable, must not "
                                + "be the element itself or one of its descendants, and must be able to own it (States and "
                                + "Pseudostates go into a Region; a StateMachine or State owner resolves to its first "
                                + "Region). Returns previousOwner and the moved element.")
                        .str("element_id", "Element to move.", REQUIRED)
                        .str("new_owner_id", "Id of the new owner (package, block, region...). Alias: owner_id.", REQUIRED),
                tool("cameo_delete_element", FAMILY).destructive()
                        .desc("Permanently delete an element with everything it owns; relationships attached to it "
                                + "and its diagram symbols go too. One undo step (cameo_undo restores it). Check "
                                + "cameo_get_relationships first if unsure what depends on it.")
                        .str("element_id", "Element to delete.", REQUIRED),

                // ---------------------------------------------------------------- stereotypes & profiles
                tool("cameo_apply_stereotype", FAMILY).write()
                        .desc("Apply a stereotype to an element (e.g. block, requirement, valueType, testCase, "
                                + "rationale). The stereotype is looked up across all profiles loaded in the project, "
                                + "preferring SysML; pass profile to disambiguate. The profile must be applied to an "
                                + "enclosing package for custom stereotypes (cameo_apply_profile).")
                        .str("element_id", "Target element.", REQUIRED)
                        .str("stereotype", "Stereotype name.", REQUIRED)
                        .str("profile", "Profile name when the stereotype name is ambiguous (e.g. SysML, MD Customization for SysML)."),
                tool("cameo_remove_stereotype", FAMILY).destructive()
                        .desc("Unapply a stereotype from an element. Its tagged values are removed with it. Returns "
                                + "removed=false when the stereotype was not applied.")
                        .str("element_id", "Target element.", REQUIRED)
                        .str("stereotype", "Name of the applied stereotype to remove (case-insensitive).", REQUIRED)
                        .str("profile", "Profile name to disambiguate."),
                tool("cameo_set_tagged_values", FAMILY).write()
                        .desc("Set tagged values of a stereotype already applied to the element. values maps tag "
                                + "name to value: strings, numbers, booleans, lists for multi-valued tags, "
                                + "{\"id\": \"...\"} for element references, and enumeration literal names (or "
                                + "{\"id\"}) for enum tags. Example: stereotype 'requirement', values {\"id\": "
                                + "\"REQ-001\", \"text\": \"The system shall...\"}.")
                        .str("element_id", "Stereotyped element.", REQUIRED)
                        .str("stereotype", "Stereotype that defines the tags.", REQUIRED)
                        .obj("values", "Tag name -> value.", REQUIRED),
                tool("cameo_set_stereotype_metaclasses", FAMILY).write()
                        .desc("Set the UML metaclasses a stereotype extends (its base classes) with Cameo's native "
                                + "API, replacing manual Extension wiring. Examples: [\"Class\"], [\"Property\"], "
                                + "[\"Class\", \"Component\"].")
                        .str("stereotype_id", "Stereotype element id.", REQUIRED)
                        .strArray("metaclasses", "Non-empty list of UML metaclass names.", REQUIRED),
                tool("cameo_apply_profile", FAMILY).write()
                        .desc("Apply a profile to a package or model so its stereotypes can be used inside it. "
                                + "Give profile_id or profile_name. Returns applied (false when it was already "
                                + "applied) and the package's appliedProfiles.")
                        .str("package_id", "Package or model receiving the profile.", REQUIRED)
                        .str("profile_id", "Profile element id.")
                        .str("profile_name", "Profile name, used when profile_id is not known."),

                // ---------------------------------------------------------------- relationships
                tool("cameo_create_relationship", FAMILY).write()
                        .desc("Create a relationship from source_id to target_id. type: " + RELATIONSHIP_TYPES + ". "
                                + "Ownership is inferred (nearest package of the source, activity for flows, region for "
                                + "transitions) unless owner_id is given. Connector requires owner_id (the block / "
                                + "structured classifier owning the parts) and accepts *_part_with_port_id for nested "
                                + "ports. InformationFlow/ItemFlow take owner_id as a package or IBD context element, "
                                + "plus realizing_connector_id, conveyed_ids and item_property_id. Satisfy: source = "
                                + "design element, target = requirement. Verify: source = test case. Transition source "
                                + "and target must be in the same Region.")
                        .str("type", "Relationship type, see the list above.", REQUIRED)
                        .str("source_id", "Source (client / specific / from) element.", REQUIRED)
                        .str("target_id", "Target (supplier / general / to) element.", REQUIRED)
                        .str("name", "Optional relationship name.")
                        .str("guard", "Guard text for Transition, ControlFlow or ObjectFlow.")
                        .str("owner_id", "Owner: required for Connector; package/context for flows and dependencies.")
                        .str("source_part_with_port_id", "Connector: part property owning the source port.")
                        .str("target_part_with_port_id", "Connector: part property owning the target port.")
                        .str("realizing_connector_id", "InformationFlow/ItemFlow: connector realizing the flow.")
                        .strArray("conveyed_ids", "InformationFlow/ItemFlow: classifier ids of conveyed items.")
                        .str("item_property_id", "ItemFlow: property typing the item flow."),
                tool("cameo_get_relationships", FAMILY).readOnly()
                        .desc("List relationships of an element: outgoing and incoming directed relationships "
                                + "(dependencies, satisfy/verify/trace/allocate, generalizations, include/extend, "
                                + "information flows, activity edges) with sources/targets, and undirected ones "
                                + "(associations, connectors with their ends) with relatedElements.")
                        .str("element_id", "Element id.", REQUIRED)
                        .enumStrDefault("direction", "Which directed relationships to return; undirected ones are "
                                + "always included.", "both", "both", "incoming", "outgoing"),

                // ---------------------------------------------------------------- specification
                tool("cameo_get_specification", FAMILY).readOnly()
                        .desc("Read an element's specification like Cameo's Specification window: properties "
                                + "(name, visibility, isAbstract, multiplicity, aggregation, type, direction, port "
                                + "flags, subject...), documentation, appliedStereotypes with taggedValues per "
                                + "stereotype, and constraints (named owned constraints such as a use case's Pre "
                                + "Condition, Post Condition, Goal, Assumption). Call before cameo_set_specification.")
                        .str("element_id", "Element id.", REQUIRED),
                tool("cameo_set_specification", FAMILY).write()
                        .desc("Set specification properties and/or named constraint fields. Each property name is "
                                + "matched first against tagged values of applied stereotypes, then against UML "
                                + "properties: name, visibility (public/private/protected/package), isAbstract, "
                                + "isFinalSpecialization, documentation, type (element id or {\"id\"}), lower, upper "
                                + "(\"*\" = unlimited), isOrdered, isUnique, aggregation, direction (parameters: "
                                + "in/out/inout/return; flow properties: in/out/inout), isBehavior, isConjugated, "
                                + "isService, represents, parameter, and other simple UML features. Names that match "
                                + "nothing are returned in unrecognized. constraints maps a constraint name to its "
                                + "text and creates or updates an owned Constraint, e.g. {\"Pre Condition\": "
                                + "\"Card inserted\"}.")
                        .str("element_id", "Element id.", REQUIRED)
                        .obj("properties", "Property name -> value.")
                        .obj("constraints", "Constraint name -> text."),
                tool("cameo_set_usecase_subject", FAMILY).write()
                        .desc("Set (or append to) the subject classifiers of a UseCase, i.e. the system boundary "
                                + "it belongs to.")
                        .str("element_id", "UseCase id.", REQUIRED)
                        .strArray("subject_ids", "Classifier ids (blocks, components...) to use as subjects.", REQUIRED)
                        .bool("append", "Keep existing subjects and add these.", false),

                // ---------------------------------------------------------------- state machines
                tool("cameo_get_transition_triggers", FAMILY).readOnly()
                        .desc("Read a transition's triggers: triggerCount and per trigger id, name, eventId, "
                                + "eventType (ChangeEvent, SignalEvent, TimeEvent...), signalId/signalName and "
                                + "changeExpression.")
                        .str("transition_id", "Transition id.", REQUIRED),
                tool("cameo_set_transition_trigger", FAMILY).write()
                        .desc("Create (by default replacing existing ones) a trigger on a transition. "
                                + "trigger_kind 'change' needs expression (stored as a ChangeEvent expression); "
                                + "'signal' needs signal_id of an existing Signal (a SignalEvent is created). The event "
                                + "is owned by the enclosing state machine's nearest package. Returns the transition's "
                                + "triggers after the change.")
                        .str("transition_id", "Transition id.", REQUIRED)
                        .enumStr("trigger_kind", "Trigger semantics.", REQUIRED, "change", "signal")
                        .str("expression", "Change-event expression text (trigger_kind=change).")
                        .str("signal_id", "Signal element id (trigger_kind=signal).")
                        .str("name", "Optional trigger name.")
                        .bool("replace", "Remove existing triggers first.", true),
                tool("cameo_get_state_behaviors", FAMILY).readOnly()
                        .desc("Read a state's entry, doActivity and exit behaviors (id, name, body, language, "
                                + "humanType), null when a slot is empty.")
                        .str("state_id", "State id.", REQUIRED),
                tool("cameo_set_state_behaviors", FAMILY).write()
                        .desc("Set a state's entry / do / exit behaviors as OpaqueBehaviors with body text. An empty "
                                + "string clears that slot; an omitted slot is kept unless clear_unspecified=true. An "
                                + "existing OpaqueBehavior in a slot is updated in place. Returns the behaviors after "
                                + "the change.")
                        .str("state_id", "State id.", REQUIRED)
                        .str("entry", "Entry behavior body.")
                        .str("do_activity", "Do-activity behavior body.")
                        .str("exit_behavior", "Exit behavior body.")
                        .str("language", "Language stored with each body.", "Opaque")
                        .bool("clear_unspecified", "Clear slots that are not given.", false),

                // ---------------------------------------------------------------- macros
                tool("cameo_execute_macro", FAMILY).write().openWorld().timeout(600)
                        .desc("Escape hatch: run a Groovy script inside Cameo's JVM on the UI thread, for operations "
                                + "no structured tool covers. Bound variables: project, application, primaryModel (null "
                                + "in a SysML v2 project), projectKind ('uml' or 'sysml2'), ef (ElementsFactory). No editing session is opened for you: wrap model changes in "
                                + "SessionManager.getInstance().createSession(project, 'name') ... closeSession(project) "
                                + "and cancelSession on error. A stale session found before the run is cancelled; a "
                                + "session the script leaves open is closed on success and cancelled on failure "
                                + "(reported in warnings). Returns success, result (toString of the last expression), "
                                + "output (println), errors, and error/stackTrace on failure (a failed run is flagged "
                                + "as a tool error). Return JSON text for "
                                + "structured results. The script keeps running in Cameo if the timeout expires.")
                        .str("script", "Script source.", REQUIRED)
                        .str("engine", "javax.script engine name; falls back to groovy, then JavaScript engines. See "
                                + "cameo_list_macro_engines.", "groovy")
                        .integer("timeout", "Seconds to wait for the script (5-600).", 60, 5, 600),
                tool("cameo_list_macro_engines", FAMILY).readOnly().noProject()
                        .desc("List the javax.script engines cameo_execute_macro can use (name, version, language, "
                                + "aliases), as seen from this plugin's class loader and the thread context class loader."));
    }
}
