# Cameo MCP tool reference

183 tools.

## server

### `cameo_status` (read)

Check that Cameo and the MCP plugin are up. Returns plugin version, Cameo product/version, endpoint and port, connected clients, whether a project is open, and recent errors. Call this first in every session.

### `cameo_get_capabilities` (read)

Machine-readable capability report: tool families and counts, which optional Cameo plugins (Dependency Matrix, Generic Table, Relation Map, Simulation, Teamwork, Report Wizard, DataHub) are present, whether macros are allowed, and supported MCP protocol versions.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `include_tools` | boolean |  | Also list every tool name grouped by family. Default: `false`. |

### `cameo_probe_bridge` (read)

Self-test of the MCP connection from inside Cameo: checks the HTTP endpoint answers, the /status health path, the bound port and host, the Swing event thread responsiveness and the editing-session state. Use when calls hang or fail unexpectedly.

### `cameo_list_tools` (read)

Search this server's tool catalog by family and/or keyword. Returns name, family, kind (read/write/destructive) and the first sentence of each description.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `family` | string |  | Family filter, e.g. server, model, diagrams, artifacts, advanced, analysis. |
| `query` | string |  | Case-insensitive keyword matched against tool names and descriptions. |

### `cameo_reset_session` (destructive)

Recovery: cancel (roll back) a Cameo editing session left open by a failed macro or interrupted write, so new writes can proceed. Partial changes from that session are discarded.

### `cameo_undo` (write)

Undo the most recent model change in Cameo (same as Edit > Undo). Each MCP write tool is one undoable step.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `steps` | integer |  | How many steps to undo (1-50). Default: `1`. |

### `cameo_redo` (write)

Redo the most recently undone model change (same as Edit > Redo).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `steps` | integer |  | How many steps to redo (1-50). Default: `1`. |

### `cameo_log` (side-effect)

Write a message to Cameo's Notification window and log file, so the human watching Cameo can follow what the agent is doing.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `message` | string | yes | Text to show. |
| `level` | string |  | Severity. One of: [info, warn, error]. Default: `info`. |

## model

### `cameo_get_project` (read)

Get the open project: name, filePath, isRemote (Teamwork Cloud), isDirty (unsaved changes), projectKind, primaryModelId and primaryModelName. primaryModelId is the usual root for cameo_list_containment_children and the default parent for new packages. projectKind is 'uml' (UML / SysML v1) or 'sysml2' (a Cameo 2026x SysML v2 project, which has no primary model: use the cameo_sysml2_* tools there).

### `cameo_save_project` (side-effect)

Save the open project to its existing location (same as File > Save). Call after a batch of changes you want to keep. Fails for a project that was never saved to a file (ask the user to use File > Save As once). Returns saved and filePath. For Teamwork Cloud projects this saves locally; committing is done in Cameo.

### `cameo_get_ui_state` (read)

Inspect the live Cameo UI: project, activeDiagram (id, name, type, owner), selectedElements (deduplicated union of diagram symbol selection and containment-tree selection), selectedPresentations (symbols selected on the active diagram with presentationId, elementId, bounds), browserSelection, counts, and warnings for UI APIs this Cameo version does not offer. Use after the user says 'this diagram' or 'the selected block'.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `summary_only` | boolean |  | Cap selectedPresentations at 50 entries. Default: `false`. |

### `cameo_get_active_diagram` (read)

Get the diagram currently open and focused in Cameo as a small payload: activeDiagram {id, name, type, owner} plus warnings. activeDiagram is {} when no diagram is open. Use its id with the diagram tools.

### `cameo_get_ui_selection` (read)

Get what the user has selected: selectedElements (model elements), selectedPresentations (diagram symbols with presentationId), browserSelection (containment tree) and counts. Use the returned ids as inputs to other tools.

### `cameo_query_elements` (read)

Search model elements by metaclass, name, scope and stereotype, sorted by type, name, id and paged (limit/offset, hasMore, nextOffset). Use it to find ids before creating or linking elements and to avoid duplicates. type is a UML metaclass such as Class, Package, Property, Port, Activity, StateMachine, State, Pseudostate, UseCase, Actor; SysML Blocks and Requirements are Class + stereotype 'block' / 'requirement'. name uses Cameo's name finder. With no filter at all it lists every element under the scope, so always page.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `type` | string |  | UML metaclass filter, e.g. Class, Package, Property, Port, Activity, State. SysML concepts are stereotypes: use type=Class with stereotype=block. |
| `name` | string |  | Element name to match. |
| `package_id` | string |  | Restrict the search to the subtree of this element id (package, model, block...). Aliases: package, owner_id, root_id. Default: the primary model. |
| `stereotype` | string |  | Applied stereotype name, case-insensitive (e.g. block, requirement, interfaceBlock). |
| `recursive` | boolean |  | Search nested packages too; false = direct children of the scope only. Default: `true`. |
| `limit` | integer |  | Page size (1-1000). Default: `200`. |
| `offset` | integer |  | Zero-based offset into the sorted result set. Default: `0`. |
| `view` | string |  | compact (id, type, name, owner, type refs) or full (adds documentation, stereotypes, tagged values). One of: [compact, full]. Default: `compact`. |

### `cameo_get_element` (read)

Get full details of one element: id, type, humanType, name, qualifiedName, owner, stereotypes, documentation, taggedValues, type/multiplicity/aggregation for properties, port flags, connector ends, information-flow fields and childCount. For every UML property and constraint field use cameo_get_specification.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id from a query, tree or selection result. |

### `cameo_get_containment_tree` (read)

Nested containment tree (id, type, name, stereotypes, children) from a root down to depth levels; nodes at the depth limit report childCount. Payloads grow fast: on large models prefer cameo_list_containment_children and drill down.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root_id` | string |  | Root element id (alias parent_id). Default: the primary model. |
| `depth` | integer |  | Levels to traverse (1-10). Default: `3`. |
| `view` | string |  | compact, or full to embed each node's full element JSON. One of: [compact, full]. Default: `compact`. |

### `cameo_list_containment_children` (read)

List the immediate children of one element, sorted by name and paged, with totalChildren, matchedChildren, hasMore and nextOffset. The scalable way to browse a big model: start with no root_id (primary model) and follow ids down.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root_id` | string |  | Parent element id (alias parent_id). Default: the primary model. |
| `limit` | integer |  | Page size (1-500). Default: `50`. |
| `offset` | integer |  | Zero-based offset into the filtered children. Default: `0`. |
| `type` | string |  | Child type filter, e.g. Package, Class, Diagram (matches metaclass or human type, case-insensitive). |
| `name` | string |  | Case-insensitive substring filter on the child name. |
| `stereotype` | string |  | Applied stereotype filter, case-insensitive. |
| `view` | string |  | compact or full. One of: [compact, full]. Default: `compact`. |

### `cameo_create_element` (write)

Create one model element under parent_id and return it. Supported type values: Structural: Package, Profile, Class, Property, Port, FlowProperty, Interface, DataType, Enumeration, Signal, Component, Operation. Profiles: Stereotype (parent must be a Profile; bind with metaclasses). SysML aliases (stereotype applied automatically; fails if SysML is not available): Block, ConstraintBlock, InterfaceBlock, Requirement, ValueType, FlowProperty. Behavioral: Activity, UseCase, Actor, StateMachine (gets a default Region), State, Pseudostate / InitialState (parent: Region, StateMachine or State). Activity nodes: InitialNode, ActivityFinalNode, FlowFinalNode, DecisionNode, MergeNode, ForkNode, JoinNode, CallBehaviorAction, OpaqueAction, ActivityPartition, InputPin, OutputPin. Other: Comment, Constraint. Optional fields apply only where they make sense (type_id for typed elements, multiplicity for properties/ports/pins, port flags for ports, direction for flow properties) and fail with a clear message otherwise. Example: {type: 'Block', name: 'Engine', parent_id: '<package id>'}. To build many elements and relationships at once use cameo_create_elements (one undo step).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `type` | string | yes | Element type, see the list above (e.g. Package, Block, Requirement, Port, State). |
| `name` | string | yes | Element name. |
| `parent_id` | string | yes | Owner element id (package, block, activity, state machine, region...). |
| `stereotype` | string |  | Extra stereotype to apply (SysML aliases get theirs automatically). |
| `documentation` | string |  | Documentation text (stored as an owned Comment). |
| `behavior_id` | string |  | CallBehaviorAction only: the Activity/Behavior it calls. |
| `represents_id` | string |  | ActivityPartition only: the Block/Class (performer) it represents. |
| `type_id` | string |  | Typed elements (Property, Port, FlowProperty, pins): id of the type. |
| `lower` | integer |  | Lower multiplicity bound (>= 0). |
| `upper` | any |  | Upper multiplicity bound: integer, or -1 / "*" for unlimited. |
| `is_ordered` | boolean |  | Multiplicity isOrdered flag. |
| `is_unique` | boolean |  | Multiplicity isUnique flag. |
| `aggregation` | string |  | Property aggregation. One of: [none, shared, composite]. |
| `is_behavior` | boolean |  | Port isBehavior flag. |
| `is_conjugated` | boolean |  | Port isConjugated flag. |
| `is_service` | boolean |  | Port isService flag. |
| `direction` | string |  | FlowProperty direction: in, out or inout. |
| `metaclasses` | string[] |  | Stereotype only: UML metaclass names to extend, e.g. ["Class"]. |

### `cameo_create_elements` (write)

Bulk-create many elements and relationships in ONE undoable step, all-or-nothing: if any entry fails the whole batch is rolled back and the error names the entry. Each entry is an object with the parameters of cameo_create_element (type, name, parent_id, ...) or, when it has source_id and target_id, of cameo_create_relationship. Give an entry a caller-chosen "ref" and point later entries at it with "ref:<name>" in any *_id field (parent_id, type_id, source_id, owner_id, conveyed_ids items...). An entry may also carry tagged_values {tag: value} (applied with its stereotype). Entries run in order, so a ref must be defined before use. Example: [{ref:'pkg', type:'Package', name:'Power', parent_id:'<model id>'}, {ref:'bat', type:'Block', name:'Battery', parent_id:'ref:pkg'}, {ref:'req', type:'Requirement', name:'Capacity', parent_id:'ref:pkg', tagged_values:{id:'R1', text:'Shall store 5 kWh'}}, {type:'Satisfy', source_id:'ref:bat', target_id:'ref:req'}]. Returns refs {ref: id} and one result per entry.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `elements` | object[] | yes | Entries to create, in order (max 500). |
| `view` | string |  | Per-entry result detail: compact (id, type, name) or full (full element JSON). One of: [compact, full]. Default: `compact`. |

### `cameo_modify_element` (write)

Rename an element and/or replace its documentation (first owned Comment, created if missing). For any other property use cameo_set_specification.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element to modify. |
| `name` | string |  | New name; omit to keep. |
| `documentation` | string |  | New documentation text; omit to keep. |

### `cameo_move_element` (write)

Move (reparent) an element to another owner, keeping its id, relationships and diagram symbols. Validates first: the new owner must exist and be editable, must not be the element itself or one of its descendants, and must be able to own it (States and Pseudostates go into a Region; a StateMachine or State owner resolves to its first Region). Returns previousOwner and the moved element.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element to move. |
| `new_owner_id` | string | yes | Id of the new owner (package, block, region...). Alias: owner_id. |

### `cameo_delete_element` (destructive)

Permanently delete an element with everything it owns; relationships attached to it and its diagram symbols go too. One undo step (cameo_undo restores it). Check cameo_get_relationships first if unsure what depends on it.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element to delete. |

### `cameo_apply_stereotype` (write)

Apply a stereotype to an element (e.g. block, requirement, valueType, testCase, rationale). The stereotype is looked up across all profiles loaded in the project, preferring SysML; pass profile to disambiguate. The profile must be applied to an enclosing package for custom stereotypes (cameo_apply_profile).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Target element. |
| `stereotype` | string | yes | Stereotype name. |
| `profile` | string |  | Profile name when the stereotype name is ambiguous (e.g. SysML, MD Customization for SysML). |

### `cameo_remove_stereotype` (destructive)

Unapply a stereotype from an element. Its tagged values are removed with it. Returns removed=false when the stereotype was not applied.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Target element. |
| `stereotype` | string | yes | Name of the applied stereotype to remove (case-insensitive). |
| `profile` | string |  | Profile name to disambiguate. |

### `cameo_set_tagged_values` (write)

Set tagged values of a stereotype already applied to the element. values maps tag name to value: strings, numbers, booleans, lists for multi-valued tags, {"id": "..."} for element references, and enumeration literal names (or {"id"}) for enum tags. Example: stereotype 'requirement', values {"id": "REQ-001", "text": "The system shall..."}.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Stereotyped element. |
| `stereotype` | string | yes | Stereotype that defines the tags. |
| `values` | object | yes | Tag name -> value. |

### `cameo_set_stereotype_metaclasses` (write)

Set the UML metaclasses a stereotype extends (its base classes) with Cameo's native API, replacing manual Extension wiring. Examples: ["Class"], ["Property"], ["Class", "Component"].

| Parameter | Type | Required | Description |
|---|---|---|---|
| `stereotype_id` | string | yes | Stereotype element id. |
| `metaclasses` | string[] | yes | Non-empty list of UML metaclass names. |

### `cameo_apply_profile` (write)

Apply a profile to a package or model so its stereotypes can be used inside it. Give profile_id or profile_name. Returns applied (false when it was already applied) and the package's appliedProfiles.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `package_id` | string | yes | Package or model receiving the profile. |
| `profile_id` | string |  | Profile element id. |
| `profile_name` | string |  | Profile name, used when profile_id is not known. |

### `cameo_create_relationship` (write)

Create a relationship from source_id to target_id. type: Association, DirectedAssociation, Composition, Generalization, Dependency, Include, Extend, ControlFlow, ObjectFlow, Allocate, Satisfy, Derive, Refine, Trace, Verify, Transition, Connector, InformationFlow, ItemFlow. Ownership is inferred (nearest package of the source, activity for flows, region for transitions) unless owner_id is given. Connector requires owner_id (the block / structured classifier owning the parts) and accepts *_part_with_port_id for nested ports. InformationFlow/ItemFlow take owner_id as a package or IBD context element, plus realizing_connector_id, conveyed_ids and item_property_id. Satisfy: source = design element, target = requirement. Verify: source = test case. Transition source and target must be in the same Region.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `type` | string | yes | Relationship type, see the list above. |
| `source_id` | string | yes | Source (client / specific / from) element. |
| `target_id` | string | yes | Target (supplier / general / to) element. |
| `name` | string |  | Optional relationship name. |
| `guard` | string |  | Guard text for Transition, ControlFlow or ObjectFlow. |
| `owner_id` | string |  | Owner: required for Connector; package/context for flows and dependencies. |
| `source_part_with_port_id` | string |  | Connector: part property owning the source port. |
| `target_part_with_port_id` | string |  | Connector: part property owning the target port. |
| `realizing_connector_id` | string |  | InformationFlow/ItemFlow: connector realizing the flow. |
| `conveyed_ids` | string[] |  | InformationFlow/ItemFlow: classifier ids of conveyed items. |
| `item_property_id` | string |  | ItemFlow: property typing the item flow. |

### `cameo_get_relationships` (read)

List relationships of an element: outgoing and incoming directed relationships (dependencies, satisfy/verify/trace/allocate, generalizations, include/extend, information flows, activity edges) with sources/targets, and undirected ones (associations, connectors with their ends) with relatedElements.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id. |
| `direction` | string |  | Which directed relationships to return; undirected ones are always included. One of: [both, incoming, outgoing]. Default: `both`. |

### `cameo_get_specification` (read)

Read an element's specification like Cameo's Specification window: properties (name, visibility, isAbstract, multiplicity, aggregation, type, direction, port flags, subject...), documentation, appliedStereotypes with taggedValues per stereotype, and constraints (named owned constraints such as a use case's Pre Condition, Post Condition, Goal, Assumption). Call before cameo_set_specification.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id. |

### `cameo_set_specification` (write)

Set specification properties and/or named constraint fields. Each property name is matched first against tagged values of applied stereotypes, then against UML properties: name, visibility (public/private/protected/package), isAbstract, isFinalSpecialization, documentation, type (element id or {"id"}), lower, upper ("*" = unlimited), isOrdered, isUnique, aggregation, direction (parameters: in/out/inout/return; flow properties: in/out/inout), isBehavior, isConjugated, isService, represents, parameter, and other simple UML features. Names that match nothing are returned in unrecognized. constraints maps a constraint name to its text and creates or updates an owned Constraint, e.g. {"Pre Condition": "Card inserted"}.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id. |
| `properties` | object |  | Property name -> value. |
| `constraints` | object |  | Constraint name -> text. |

### `cameo_set_usecase_subject` (write)

Set (or append to) the subject classifiers of a UseCase, i.e. the system boundary it belongs to.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | UseCase id. |
| `subject_ids` | string[] | yes | Classifier ids (blocks, components...) to use as subjects. |
| `append` | boolean |  | Keep existing subjects and add these. Default: `false`. |

### `cameo_get_transition_triggers` (read)

Read a transition's triggers: triggerCount and per trigger id, name, eventId, eventType (ChangeEvent, SignalEvent, TimeEvent...), signalId/signalName and changeExpression.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `transition_id` | string | yes | Transition id. |

### `cameo_set_transition_trigger` (write)

Create (by default replacing existing ones) a trigger on a transition. trigger_kind 'change' needs expression (stored as a ChangeEvent expression); 'signal' needs signal_id of an existing Signal (a SignalEvent is created). The event is owned by the enclosing state machine's nearest package. Returns the transition's triggers after the change.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `transition_id` | string | yes | Transition id. |
| `trigger_kind` | string | yes | Trigger semantics. One of: [change, signal]. |
| `expression` | string |  | Change-event expression text (trigger_kind=change). |
| `signal_id` | string |  | Signal element id (trigger_kind=signal). |
| `name` | string |  | Optional trigger name. |
| `replace` | boolean |  | Remove existing triggers first. Default: `true`. |

### `cameo_get_state_behaviors` (read)

Read a state's entry, doActivity and exit behaviors (id, name, body, language, humanType), null when a slot is empty.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `state_id` | string | yes | State id. |

### `cameo_set_state_behaviors` (write)

Set a state's entry / do / exit behaviors as OpaqueBehaviors with body text. An empty string clears that slot; an omitted slot is kept unless clear_unspecified=true. An existing OpaqueBehavior in a slot is updated in place. Returns the behaviors after the change.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `state_id` | string | yes | State id. |
| `entry` | string |  | Entry behavior body. |
| `do_activity` | string |  | Do-activity behavior body. |
| `exit_behavior` | string |  | Exit behavior body. |
| `language` | string |  | Language stored with each body. Default: `Opaque`. |
| `clear_unspecified` | boolean |  | Clear slots that are not given. Default: `false`. |

### `cameo_execute_macro` (write)

Escape hatch: run a Groovy script inside Cameo's JVM on the UI thread, for operations no structured tool covers. Bound variables: project, application, primaryModel (null in a SysML v2 project), projectKind ('uml' or 'sysml2'), ef (ElementsFactory). No editing session is opened for you: wrap model changes in SessionManager.getInstance().createSession(project, 'name') ... closeSession(project) and cancelSession on error. A stale session found before the run is cancelled; a session the script leaves open is closed on success and cancelled on failure (reported in warnings). Returns success, result (toString of the last expression), output (println), errors, and error/stackTrace on failure (a failed run is flagged as a tool error). Return JSON text for structured results. The script keeps running in Cameo if the timeout expires.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `script` | string | yes | Script source. |
| `engine` | string |  | javax.script engine name; falls back to groovy, then JavaScript engines. See cameo_list_macro_engines. Default: `groovy`. |
| `timeout` | integer |  | Seconds to wait for the script (5-600). Default: `60`. |

### `cameo_list_macro_engines` (read)

List the javax.script engines cameo_execute_macro can use (name, version, language, aliases), as seen from this plugin's class loader and the thread context class loader.

## diagrams

### `cameo_list_diagram_types` (read)

List the validated diagram type tokens accepted by cameo_create_diagram: the canonical token (Class, Package, UseCase, Activity, Sequence, StateMachine, Component, Deployment, CompositeStructure, Object, Communication, InteractionOverview, Timing, Profile, BDD, IBD, Requirement Diagram, Parametric Diagram, RelationMap, Content Diagram), the native Cameo diagram type it creates, its family (uml/sysml/analysis) and the aliases normalized to it.

### `cameo_list_diagrams` (read)

List every diagram in the open project with id, name, native type and owner (ownerId, ownerName). Use the id as diagram_id for the other diagram tools.

### `cameo_create_diagram` (write)

Create a SysML or UML diagram owned by a namespace. type accepts the tokens from cameo_list_diagram_types and common aliases ("BDD", "IBD", "InternalBlockDiagram", "StateMachineDiagram", "Requirement Diagram", "RelationMap"); any other string is passed to Cameo as a native type name such as "SysML Activity Diagram". SysML types need the SysML profile in the project. For a RelationMap the relation_map_* options are applied after creation through cameo_configure_relation_map (prefer cameo_create_relation_map for full control). Returns id, name, native type and parentId. Next: cameo_add_to_diagram.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `type` | string | yes | Diagram type token or alias, e.g. "BDD", "IBD", "Activity", "StateMachine", "Requirement Diagram". |
| `name` | string | yes | Display name of the diagram. |
| `parent_id` | string |  | ID of the owning namespace (Package, Model, Block/Class, Activity, StateMachine...). Omit to use the project's root model. |
| `relation_map_context_id` | string |  | RelationMap only: context/root element ID. |
| `relation_map_scope_ids` | string[] |  | RelationMap only: scope root element IDs. |
| `relation_map_element_types` | string[] |  | RelationMap only: metaclass or stereotype names to include, e.g. ["Block", "Requirement"]. |
| `relation_map_dependency_criteria` | string[] |  | RelationMap only: native structured-expression criteria strings for relationship filters. |
| `relation_map_depth` | integer |  | RelationMap only: traversal depth, -1 for indefinite, else 0-100. |

### `cameo_add_to_diagram` (write)

Place an existing model element on a diagram as a shape at (x, y) and return its presentationId and bounds. Leave width/height negative to keep Cameo's auto-size; if you set one you must set both. container_presentation_id nests the shape inside a container shape (system boundary, package, part, region). Adding an ActivityPartition creates or reuses a native swimlane holding all sibling partitions (do not pass a container for partitions). Cameo may auto-display related paths; tidy with cameo_prune_diagram_presentations. To draw relationships use cameo_add_diagram_paths.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `element_id` | string | yes | Model element ID to show. |
| `x` | integer |  | Left position in pixels. Default: `100`. |
| `y` | integer |  | Top position in pixels. Default: `100`. |
| `width` | integer |  | Shape width in pixels; negative keeps auto-size. Default: `-1`. |
| `height` | integer |  | Shape height in pixels; negative keeps auto-size. Default: `-1`. |
| `container_presentation_id` | string |  | Optional presentationId of a container shape (from cameo_list_diagram_shapes). |

### `cameo_get_diagram_image` (side-effect)

Render a diagram and return it as an MCP image content block plus metadata (id, name, format, width, height, scalePercentage, imageBytes, mimeType). Large diagrams can exceed client limits: first call with include_image=false to get the size, then use max_width/max_height (aspect ratio kept, never enlarged) and format="jpeg" to shrink the payload. scale_percentage renders natively at higher/lower resolution before any resize. webp is not available in Cameo's Java runtime and falls back to PNG (formatNote says so). To save the image to disk use cameo_export_diagram_image.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `include_image` | boolean |  | false returns metadata only (no image block). Default: `true`. |
| `format` | string |  | Encoding of the returned image. One of: [png, jpeg, jpg, webp]. Default: `png`. |
| `max_width` | integer |  | Maximum width in pixels of the returned image. |
| `max_height` | integer |  | Maximum height in pixels of the returned image. |
| `quality` | integer |  | JPEG quality 1-100. Default: `85`. |
| `scale_percentage` | integer |  | Native Cameo render scale, 25-1000 (default 100). |

### `cameo_export_diagram_image` (side-effect)

Export a diagram image straight to a file with Cameo's native exporter (PNG, JPEG or SVG) without sending the image through MCP. Returns path, bytes, width, height, format and the diagram bounds. Missing parent folders are created. Use for reports and documents; use cameo_get_diagram_image to look at a diagram.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `file_path` | string | yes | Absolute output path, e.g. C:\\out\\context.png or /tmp/context.svg. |
| `format` | string |  | Output format; default from the file extension, else png. One of: [png, jpeg, jpg, svg]. |
| `scale_percentage` | integer |  | Render scale, 25-1000 (default 100). |
| `dpi` | integer |  | Raster resolution in DPI (36-1200); default is Cameo's. |
| `overwrite` | boolean |  | Replace an existing file. Default: `true`. |

### `cameo_delete_diagram` (destructive)

Delete a diagram from the model. Only the diagram and its symbols are removed; the model elements it showed stay in the containment tree (use cameo_delete_element for those). Undo with cameo_undo.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |

### `cameo_rename_diagram` (write)

Rename a diagram. Returns the diagram with previousName.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `name` | string | yes | New diagram name. |

### `cameo_auto_layout` (write)

Apply Cameo's automatic layout to all shapes of a diagram (same as Layout > Quick Diagram Layout). Returns layoutApplied. Manual positions are lost; undo with cameo_undo.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |

### `cameo_list_diagram_shapes` (read)

List the shapes and paths on a diagram (flattened, nested ones carry parentPresentationId): presentationId, shapeType (presentation class such as ClassView, ControlFlowView, SwimlaneHeaderView), elementId/elementName/elementType and bounds. Call this before cameo_move_shapes, cameo_delete_shapes, cameo_add_diagram_paths or cameo_set_shape_properties: those need presentationId values, which are NOT model element IDs (one element can have a different presentation on each diagram). Paged with limit/offset (hasMore, nextOffset); summary_only returns counts by shape/element type instead of the list.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `limit` | integer |  | Maximum shapes to return. Default: `200`. |
| `offset` | integer |  | Zero-based offset into the filtered list. Default: `0`. |
| `shape_type` | string |  | Filter by presentation class, e.g. ControlFlowView (case-insensitive, exact). |
| `element_type` | string |  | Filter by model element human type, e.g. OpaqueAction, Activity Partition (case-insensitive, exact). |
| `parent_presentation_id` | string |  | Only children of this presentation element. |
| `include_bounds` | boolean |  | Include per-shape bounds. Default: `true`. |
| `include_child_count` | boolean |  | Include per-shape child counts. Default: `true`. |
| `summary_only` | boolean |  | Return counts/groupings instead of shapes. Default: `false`. |

### `cameo_get_shape_properties` (read)

Read the presentation (display) properties of one diagram shape or path: properties (name -> value), propertyList (with ids and class types) and compartments (well-known toggles: name, stereotype, type, properties, operations, attributes, ports, constraints, tagged_values). Use the names with cameo_set_shape_properties.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_id` | string | yes | presentationId from cameo_list_diagram_shapes. |

### `cameo_move_shapes` (write)

Move and/or resize shapes. Coordinates are pixels from the diagram's top-left (x right, y down). Each item needs presentationId, x, y, width and height (reuse current bounds from cameo_list_diagram_shapes to keep a size). Paths cannot be reshaped; use cameo_route_paths. Example: [{"presentationId": "abc", "x": 200, "y": 50, "width": 150, "height": 80}]. Returns a result per item.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `shapes` | object[] | yes | Shape updates. |

### `cameo_delete_shapes` (destructive)

Remove shapes or paths from a diagram canvas only; the model elements stay in the model and can be shown again (use cameo_delete_element to delete from the model). Child symbols are removed with their parent. Returns a result per id.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] | yes | presentationIds from cameo_list_diagram_shapes. |

### `cameo_add_diagram_paths` (write)

Draw existing model relationships as paths between shapes already on the diagram. The relationship must exist (cameo_create_relationship) and both ends must be shown; sourceShapeId/targetShapeId are presentationIds from cameo_list_diagram_shapes, NOT model element IDs. Example: [{"relationshipId": "rel-1", "sourceShapeId": "pe-a", "targetShapeId": "pe-b"}]. Returns the new path presentationIds.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `paths` | object[] | yes | Paths to draw. |

### `cameo_set_shape_properties` (write)

Set presentation properties of one shape or path by exact Cameo property name (see cameo_get_shape_properties). Common: "Show Name", "Show Stereotype", "Show Type", "Show Constraints", "Show Tagged Values", "Show Ports", "Suppress Attributes", "Suppress Operations", "Autosize" (booleans); "Fill Color", "Line Color", "Font Color" ("#RRGGBB"). Unknown names are reported per property. For intent-level compartment toggles use cameo_set_shape_compartments.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_id` | string | yes | presentationId from cameo_list_diagram_shapes. |
| `properties` | object | yes | Property name -> value, e.g. {"Show Constraints": true, "Fill Color": "#E0F0FF"}. |

### `cameo_set_shape_compartments` (write)

Show/hide compartments of one shape with normalized keys instead of raw property names: showAttributes, showOperations, showProperties, showPorts, showParts, showContent, showReferences, showValues, showFullPorts, showFlowProperties, showProxyPorts, showBehaviors, showReceptions, showStructure, showConstraints, showTaggedValues, showName, showStereotype, showType ("suppressX" works too). true always means visible; "Suppress ..." properties are inverted for you.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_id` | string | yes | presentationId from cameo_list_diagram_shapes. |
| `compartments` | object | yes | Key -> boolean, e.g. {"showAttributes": false, "showPorts": true}. |

### `cameo_set_transition_label_presentation` (write)

Apply a transition-label display preset (name, triggers, guard, effect) to transition paths of a state machine diagram, and optionally reset label positions. Safer than guessing raw property names. Each result lists supportedRequests/unsupportedRequests.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional presentationIds (from cameo_list_diagram_shapes) to limit the operation to; omit to target every eligible presentation on the diagram. |
| `show_name` | boolean |  | Show the transition name. Default: `true`. |
| `show_triggers` | boolean |  | Show triggers/events. Default: `true`. |
| `show_guard` | boolean |  | Show guards. Default: `false`. |
| `show_effect` | boolean |  | Show effects. Default: `false`. |
| `reset_labels` | boolean |  | Reset label positions afterwards. Default: `true`. |

### `cameo_set_item_flow_label_presentation` (write)

Apply an item-flow / information-flow label preset (name, conveyed items, item property, direction, stereotype) to flow paths, e.g. connectors carrying item flows on an IBD.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional presentationIds (from cameo_list_diagram_shapes) to limit the operation to; omit to target every eligible presentation on the diagram. |
| `show_name` | boolean |  | Show the flow name. Default: `false`. |
| `show_conveyed` | boolean |  | Show conveyed classifiers. Default: `true`. |
| `show_item_property` | boolean |  | Show the item property. Default: `true`. |
| `show_direction` | boolean |  | Show the flow direction arrow. Default: `true`. |
| `show_stereotype` | boolean |  | Show the stereotype. Default: `false`. |
| `reset_labels` | boolean |  | Reset label positions afterwards. Default: `true`. |

### `cameo_set_allocation_compartment_presentation` (write)

Apply a SysML allocation / full-port display preset to shapes that support it (allocatedFrom/allocatedTo compartments, element properties, ports, full ports, SysML 1.7 allocation compartment naming).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional presentationIds (from cameo_list_diagram_shapes) to limit the operation to; omit to target every eligible presentation on the diagram. |
| `show_allocated_elements` | boolean |  | Show allocated-elements compartments. Default: `true`. |
| `show_element_properties` | boolean |  | Show element properties. Default: `true`. |
| `show_ports` | boolean |  | Show ports. Default: `true`. |
| `show_full_ports` | boolean |  | Show full ports. Default: `true`. |
| `apply_allocation_naming` | boolean |  | Use SysML 1.7 allocation compartment naming. Default: `true`. |

### `cameo_repair_hidden_labels` (write)

Turn hidden labels back on with diagram-type-aware defaults (e.g. names and stereotypes on shapes; triggers/guards/effects on state-machine transitions). Use dry_run=true to preview. The result's repairDefaults shows the keys used.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional presentationIds (from cameo_list_diagram_shapes) to limit the operation to; omit to target every eligible presentation on the diagram. |
| `dry_run` | boolean |  | Preview what would change without modifying the diagram. Default: `false`. |

### `cameo_repair_label_positions` (write)

Reset path labels to Cameo's default positions, by default only on paths whose bounds (grown by overlap_padding px) overlap another path. Use after moving shapes or routing paths when labels pile up.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional presentationIds (from cameo_list_diagram_shapes) to limit the operation to; omit to target every eligible presentation on the diagram. |
| `dry_run` | boolean |  | Preview what would change without modifying the diagram. Default: `false`. |
| `only_overlapping` | boolean |  | Only reset paths that overlap another path. Default: `true`. |
| `overlap_padding` | integer |  | Padding in pixels used for the overlap test. Default: `40`. |

### `cameo_repair_conveyed_item_labels` (write)

Force conveyed-item / item-property / direction labels on flow paths that support them (diagram-type-aware) and optionally reset label positions.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional presentationIds (from cameo_list_diagram_shapes) to limit the operation to; omit to target every eligible presentation on the diagram. |
| `dry_run` | boolean |  | Preview what would change without modifying the diagram. Default: `false`. |
| `reset_labels` | boolean |  | Reset label positions afterwards. Default: `true`. |

### `cameo_normalize_compartment_presets` (write)

Show the standard compartments for the diagram type on every eligible shape (BDD/class: properties, operations, constraints, tagged values, ports, attributes; IBD: ports, full ports, element properties, allocations; requirement: constraints, tagged values). Comments/notes are skipped. Use dry_run=true to preview.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional presentationIds (from cameo_list_diagram_shapes) to limit the operation to; omit to target every eligible presentation on the diagram. |
| `dry_run` | boolean |  | Preview what would change without modifying the diagram. Default: `false`. |

### `cameo_prune_diagram_presentations` (destructive)

Delete unwanted symbols from a diagram with keep/drop rules (the model is not changed). Typical use after cameo_add_to_diagram auto-displays extra relationship paths: keep_element_ids=[ids you want] removes every other element's symbols. drop_element_types / drop_shape_types match substrings of the element type or presentation class (e.g. "Dependency", "RoleView"); exclude_* protect symbols. At least one rule is required. Run with dry_run=true first.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `keep_element_ids` | string[] |  | Keep only symbols of these model element IDs. |
| `drop_element_types` | string[] |  | Drop symbols whose element type / presentation class contains any of these. |
| `drop_shape_types` | string[] |  | Drop symbols whose presentation class contains any of these. |
| `exclude_element_ids` | string[] |  | Never drop symbols of these element IDs. |
| `exclude_presentation_ids` | string[] |  | Never drop these presentationIds. |
| `dry_run` | boolean |  | Preview what would change without modifying the diagram. Default: `false`. |

### `cameo_prune_path_decorations` (destructive)

Delete child decorations of paths (default: association end role labels, RoleView) while keeping the paths themselves. drop_child_shape_types matches presentation class substrings.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_ids` | string[] |  | Optional path presentationIds; omit for every path. |
| `drop_child_shape_types` | string[] |  | Child presentation classes to delete (default ["RoleView"]). |
| `dry_run` | boolean |  | Preview what would change without modifying the diagram. Default: `false`. |

### `cameo_reparent_shapes` (write)

Move existing symbols under a different container symbol on the same diagram (e.g. into a swimlane, region or system boundary). Each item: presentationId and parentPresentationId. Positions may need cameo_move_shapes afterwards.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `reparentings` | object[] | yes | Reparent operations. |

### `cameo_route_paths` (write)

Set the route of existing paths: breakPoints (ordered bend points), optional sourcePoint/targetPoint (attachment points) and resetLabels (default true). An empty breakPoints list makes the path straight. Example: [{"presentationId": "p1", "breakPoints": [{"x": 300, "y": 120}]}].

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `routes` | object[] | yes | Route updates. |

### `cameo_dump_diagram_properties` (read)

Diagnostic dump of a diagram's settings and a paged list of its presentation elements (counts by type). Use for before/after snapshots of diagrams, matrices, tables and relation maps. include_presentation_properties adds every symbol's properties (large); include_raw adds class names and getter values.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `include_raw` | boolean |  | Include raw class names and getter values. Default: `false`. |
| `include_presentation_properties` | boolean |  | Include each presentation's property manager. Default: `false`. |
| `summary_only` | boolean |  | Summarize collection/map values instead of listing them. Default: `true`. |
| `limit` | integer |  | Presentations per page. Default: `100`. |
| `offset` | integer |  | Presentation offset. Default: `0`. |

### `cameo_dump_presentation_properties` (read)

Diagnostic dump of every property of one presentation element, with its children.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram ID from cameo_list_diagrams or cameo_create_diagram. |
| `presentation_id` | string | yes | presentationId from cameo_list_diagram_shapes. |
| `include_raw` | boolean |  | Include raw class names and getter values. Default: `false`. |
| `summary_only` | boolean |  | Summarize collection/map values instead of listing them. Default: `false`. |

## artifacts

### `cameo_list_matrix_kinds` (read)

List the validated native matrix kinds (refine (Refine Requirement Matrix), derive (Derive Requirement Matrix), satisfy (Satisfy Requirement Matrix), allocation (SysML Allocation Matrix), dependency (Dependency Matrix)) with their accepted aliases and example row/column type domains. Use the 'kind' token with cameo_create_matrix and cameo_list_matrices.

### `cameo_list_matrices` (read)

List supported native matrix artifacts in the open project (id, name, kind, native matrixType, owner). This targets Cameo's native matrix artifacts, not diagrams or Generic Tables. Read one with cameo_get_matrix.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `kind` | string |  | Optional kind filter: refine, derive, satisfy, allocation or dependency. Aliases such as 'Refine Requirement Matrix' or 'System Allocation Matrix' are normalized. |
| `owner_id` | string |  | Optional package/namespace ID that owns the matrix artifact. |

### `cameo_get_matrix` (read)

Read one native matrix (refine (Refine Requirement Matrix), derive (Derive Requirement Matrix), satisfy (Satisfy Requirement Matrix), allocation (SysML Allocation Matrix), dependency (Dependency Matrix)) with row/column scopes and type domains, the row and column elements, and only the populated cells with the relationships that cause them (populatedCells[].dependencies[].cause). Empty cells are omitted: infer gaps from the rows x columns cross-product (totalCellCount). Needs the Dependency Matrix plugin.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `matrix_id` | string | yes | Diagram ID of the matrix artifact (from cameo_list_matrices). |

### `cameo_create_matrix` (write)

Create a native matrix artifact: refine (Refine Requirement Matrix), derive (Derive Requirement Matrix), satisfy (Satisfy Requirement Matrix), allocation (SysML Allocation Matrix), dependency (Dependency Matrix). The matrix is configured row-to-column and shows ALL rows/columns inside the scope so missing traceability stays visible. Returns the created matrix with its initial rows, columns and cells plus a receipt. Example: kind='satisfy', parent_id=<package>, row_types=['Block'], column_types=['Requirement']. Needs the Dependency Matrix plugin.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `kind` | string | yes | Matrix kind: refine, derive, satisfy, allocation or dependency (aliases such as 'Satisfy Requirement Matrix' are accepted). |
| `parent_id` | string | yes | Namespace/package ID that will own the matrix. |
| `name` | string |  | Display name. Defaults to Cameo's native matrix type name. |
| `scope_id` | string |  | Shared scope root for rows and columns. Defaults to parent_id when row_scope_id/column_scope_id are omitted. |
| `row_scope_id` | string |  | Explicit row scope root (overrides scope_id for rows). |
| `column_scope_id` | string |  | Explicit column scope root (overrides scope_id for columns). |
| `row_types` | string[] |  | Row-domain type tokens: a UML metaclass (Activity, Property, UseCase) or a stereotype (Block, Requirement, valueProperty). Omit for the kind's defaults; an empty list is rejected. |
| `column_types` | string[] |  | Column-domain type tokens, same rules as row_types. |

### `cameo_list_generic_tables` (read)

List native Generic Table artifacts in the open project (id, name, type, owner). Read one with cameo_get_generic_table.

### `cameo_get_generic_table` (read)

Get one native Generic Table: scope, row elements, columns (id + display name) and every cell value as text (cells[].row, columnId, value). The table is refreshed first so values are current. Needs the Generic Table plugin.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `table_id` | string | yes | ID of the Generic Table diagram (from cameo_list_generic_tables). |

### `cameo_list_generic_table_columns` (read)

List the native Generic Table column IDs that are possible for an element or element type, to pass as column_ids to cameo_create_generic_table. Provide element_id or element_type.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string |  | Basis element ID, such as an existing Block. |
| `element_type` | string |  | Element type token, such as 'Block' (stereotype) or 'Class' (metaclass). |

### `cameo_create_generic_table` (write)

Create and configure a native Generic Table: element types, scope, explicit rows and columns; then refresh it and return the populated table. Get valid column ids from cameo_list_generic_table_columns first. Needs the Generic Table plugin.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `parent_id` | string | yes | Namespace/package ID that will own the table. |
| `name` | string |  | Display name (default 'Generic Table'). |
| `element_types` | string[] |  | Table element types, such as ['Block'] or ['Requirement'] (stereotype or metaclass names). |
| `scope_ids` | string[] |  | Table scope element IDs (packages whose contents fill the table). |
| `row_element_ids` | string[] |  | Explicit row element IDs. |
| `column_ids` | string[] |  | Generic Table column IDs to display. |

### `cameo_list_relation_maps` (read)

List native Relation Map artifacts in the open project (id, name, type, owner).

### `cameo_get_relation_map` (read)

Get one native Relation Map with its persisted graph settings: context element, scope roots, element types, dependency criteria, depth, layout, display flags and whether Cameo considers the settings valid. Needs the Relation Map plugin.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |

### `cameo_create_relation_map` (write)

Create and configure a native Relation Map. Prefer this over cameo_create_diagram (type RelationMap) when the map must render relationships: it writes Cameo's native graph settings (context element, scope roots, element-type filters, dependency criteria, depth, layout, display flags). Afterwards use cameo_set_relation_map_criteria with templates, then cameo_verify_relation_map / cameo_render_relation_map.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `parent_id` | string | yes | Namespace/package ID that will own the Relation Map. |
| `name` | string |  | Display name (default 'Relation Map'). |
| `context_element_id` | string |  | Root/context model element ID the map starts from. |
| `scope_ids` | string[] |  | Scope root element IDs. |
| `element_type_ids` | string[] |  | Element IDs of metaclasses/stereotypes used as element-type filters. |
| `dependency_criteria` | string[] |  | Raw Cameo relation-map criterion expressions (replaces the list). Prefer cameo_set_relation_map_criteria with templates. |
| `depth` | integer |  | Traversal depth (Cameo's native value semantics). |
| `layout` | string |  | Native relation-map layout token. |
| `legend_enabled` | boolean |  | Show the legend. |
| `show_full_types` | boolean |  | Show fully qualified type names. |
| `show_stereotypes` | boolean |  | Show stereotypes. |
| `show_parameters` | boolean |  | Show parameters. |
| `show_element_numbers` | boolean |  | Show element numbers. |
| `single_node_per_element` | boolean |  | Collapse repeated appearances to one node per element. |
| `short_node_names` | boolean |  | Shorten displayed node names. |
| `types_include_subtypes` | boolean |  | Element-type filters include subtypes. |
| `types_include_custom_types` | boolean |  | Element-type filters include custom types. |
| `make_element_as_context` | boolean |  | Show the context element as the map's context node (default true when context_element_id is set). |

### `cameo_configure_relation_map` (write)

Update native graph settings of an existing Relation Map. Only the parameters you pass change; returns the map with its resulting settings.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `context_element_id` | string |  | Root/context model element ID the map starts from. |
| `scope_ids` | string[] |  | Scope root element IDs. |
| `element_type_ids` | string[] |  | Element IDs of metaclasses/stereotypes used as element-type filters. |
| `dependency_criteria` | string[] |  | Raw Cameo relation-map criterion expressions (replaces the list). Prefer cameo_set_relation_map_criteria with templates. |
| `depth` | integer |  | Traversal depth (Cameo's native value semantics). |
| `layout` | string |  | Native relation-map layout token. |
| `legend_enabled` | boolean |  | Show the legend. |
| `show_full_types` | boolean |  | Show fully qualified type names. |
| `show_stereotypes` | boolean |  | Show stereotypes. |
| `show_parameters` | boolean |  | Show parameters. |
| `show_element_numbers` | boolean |  | Show element numbers. |
| `single_node_per_element` | boolean |  | Collapse repeated appearances to one node per element. |
| `short_node_names` | boolean |  | Shorten displayed node names. |
| `types_include_subtypes` | boolean |  | Element-type filters include subtypes. |
| `types_include_custom_types` | boolean |  | Element-type filters include custom types. |
| `make_element_as_context` | boolean |  | Show the context element as the map's context node (default true when context_element_id is set). |

### `cameo_refresh_relation_map` (write)

Refresh a native Relation Map after model or settings changes (runs Cameo's own refresh). Can block Cameo for a long time on large maps, so call it deliberately.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `timeout` | number |  | Timeout in seconds (1-600). Default: `120.0`. |

### `cameo_dump_relation_map_raw_settings` (read)

Dump native Relation Map settings for UI-diff investigation: the sanitized settings plus every reflected public no-arg GraphSettings getter. Use before and after a human changes the map in the Cameo UI (or use cameo_create_snapshot/cameo_diff_snapshots).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `include_raw` | boolean |  | Include class names and nested getter values of non-JSON values. Default: `false`. |
| `summary_only` | boolean |  | Summarize collections and maps as counts only. Default: `false`. |

### `cameo_list_relation_map_presentations` (read)

List loaded Relation Map presentation elements (nodes, paths, legend symbols) with counts by type, paged. Use it to tell graph traversal success apart from rendered expansion/export failure. Property dumps are opt-in and summarized by default.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `include_properties` | boolean |  | Include each presentation's property manager and children. Default: `false`. |
| `include_raw` | boolean |  | Include raw class names/getter values in property dumps. Default: `false`. |
| `summary_only` | boolean |  | Summarize collection-valued properties as counts. Default: `true`. |
| `limit` | integer |  | Page size. Default: `250`. |
| `offset` | integer |  | Page start. Default: `0`. |

### `cameo_list_relation_map_criteria_templates` (read)

List built-in Relation Map criteria templates (e.g. satisfy.both, refine.both, deriveReqt.both, allocate.both, dependency.direct) for cameo_set_relation_map_criteria. Templates with verifiedWithUiDiff=false are placeholders until a snapshot diff from the Cameo UI confirms the exact native expression.

### `cameo_set_relation_map_criteria` (write)

Apply Relation Map dependency criteria. Each criteria entry is a raw expression string or an object: {"template": "satisfy.both"} (see cameo_list_relation_map_criteria_templates), {"rawExpression": "..."} (copied from a UI-made map via cameo_dump_relation_map_raw_settings), or {"relationshipType": "Satisfy", "direction": "both"}. Returns before/after criteria.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `mode` | string |  | replace = set exactly these, append = add, remove = delete these. One of: [replace, append, remove]. Default: `replace`. |
| `criteria` | any |  | List of criteria: strings or objects with template, rawExpression or relationshipType (+ optional direction). |
| `refresh` | boolean |  | Run a native refresh afterwards (can be slow). Default: `false`. |

### `cameo_expand_relation_map` (write)

Try to expand native Relation Map nodes through the open map display (opens the diagram if needed) and report presentation/graph/display counts before and after plus the attempts made. 'supported' is false with an unsupportedReason when expansion fails.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `mode` | string |  | Expansion mode, echoed in the result ('all' expands every node). Default: `all`. |
| `element_ids` | string[] |  | Element IDs to expand. Per-element expansion is not available through the API; recorded in the result only. |
| `depth` | integer |  | Optional traversal depth written to the map settings before expanding. |
| `refresh` | boolean |  | Run a native refresh after a successful expansion. Default: `false`. |
| `layout` | string |  | Optional layout token applied after expansion (re-lays out the map). |
| `timeout` | number |  | Timeout in seconds (1-600). Default: `120.0`. |

### `cameo_collapse_relation_map` (write)

Try to collapse native Relation Map nodes and report before/after counts. No collapse API of the Relation Map display has been validated, so this currently reports supported=false with the reason instead of closing the diagram.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `mode` | string |  | Collapse mode, echoed in the result. Default: `all`. |
| `element_ids` | string[] |  | Element IDs to collapse (recorded in the result only). |
| `refresh` | boolean |  | Run a native refresh after a successful collapse. Default: `false`. |
| `timeout` | number |  | Timeout in seconds (1-600). Default: `120.0`. |

### `cameo_render_relation_map` (side-effect)

Render/export a Relation Map as PNG (base64 in image.image) with presentation counts and a traversal summary. Native refresh is opt-in because it can block Cameo; expand, refresh, depth or layout run inside an undoable session, a plain render does not. Use include_image=false to get only sizes and counts.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `refresh` | boolean |  | Run a native refresh before rendering. Default: `false`. |
| `expand` | string |  | 'none' (default) or 'all' to expand the map display before rendering. Default: `none`. |
| `depth` | integer |  | Optional traversal depth written to the map settings before rendering. |
| `layout` | string |  | Optional layout token applied before rendering. |
| `scale_percentage` | integer |  | Image scale in percent (25-1000). Default: `200`. |
| `include_image` | boolean |  | Include the base64 PNG; false returns only width/height/bytes. Default: `true`. |
| `include_presentation_summary` | boolean |  | Include presentation counts by type. Default: `true`. |

### `cameo_verify_relation_map` (read)

Verify a Relation Map in three separate checks: model graph traversal from its context element (nodes/edges >= expected minimums), native settings validity, and rendered presentation count. ok is true only when all pass; the graph and settings are returned for diagnosis.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relation_map_id` | string | yes | ID of the Relation Map diagram element. |
| `expected_min_nodes` | integer |  | Minimum traversal node count. Default: `0`. |
| `expected_min_edges` | integer |  | Minimum traversal edge count. Default: `0`. |
| `expected_rendered_nodes` | integer |  | Minimum rendered presentation element count. Default: `0`. |
| `relationship_types` | string[] |  | Relationship names/stereotypes to traverse, e.g. ['Satisfy', 'Refine']. Omit for all. |
| `max_depth` | integer |  | Traversal depth (1-12). Default: `3`. |

### `cameo_compare_relation_maps` (read)

Compare two Relation Maps, usually one built in the Cameo UI and one built through MCP: captures settings, reflected GraphSettings getters and presentations of both and returns a path-level JSON diff (added/removed/changed/arrayLengthChanges).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `left_relation_map_id` | string | yes | First Relation Map ID (e.g. the UI-made reference). |
| `right_relation_map_id` | string | yes | Second Relation Map ID. |
| `include_presentations` | boolean |  | Include presentation summaries in the comparison. Default: `true`. |
| `include_raw` | boolean |  | Include raw getter values (class names, nested getters). Default: `false`. |

### `cameo_get_traceability_graph` (read)

Build a read-only relationship graph for traceability analysis by breadth-first traversal from root elements, independent of Relation Map criteria. Follows directed relationships (Refine, DeriveReqt, Satisfy, Allocate, Dependency, ...), activity flows, associations and connectors. Returns nodes (with depth/root) and edges (with sources, targets, traversalTargets). Provide root_element_ids, context_element_id, or a relation_map_id whose context element becomes the root.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root_element_ids` | string[] |  | One or more element IDs to start from. |
| `context_element_id` | string |  | Single root element ID (alternative to root_element_ids). |
| `relation_map_id` | string |  | Relation Map ID; without roots its persisted context element is used. |
| `relationship_types` | string[] |  | Relationship names/metaclasses/stereotypes to include, such as ['Refine', 'DeriveReqt', 'Satisfy', 'Allocate', 'Dependency']. Matching ignores case and punctuation. Omit for all. |
| `direction` | string |  | Edge direction to follow from each element. One of: [incoming, outgoing, both]. Default: `both`. |
| `max_depth` | integer |  | Traversal depth (1-12). Default: `3`. |
| `max_nodes` | integer |  | Safety cap on returned nodes (1-1000). Default: `250`. |

### `cameo_create_snapshot` (side-effect)

Capture an in-memory inspection snapshot for before/after diffs of UI actions (the model is not changed). target_type: project, element, diagram, relationMap or ui. Diagram/relationMap snapshots include presentation summaries and diagram properties by default. Workflow: snapshot, let a human change something in Cameo, snapshot again, then cameo_diff_snapshots. Snapshots live until Cameo restarts.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `target_type` | string | yes | What to capture. One of: [project, element, diagram, relationMap, ui]. |
| `target_id` | string |  | Element or diagram ID (required for element, diagram and relationMap). |
| `name` | string |  | Optional label for the snapshot. |
| `include_raw` | boolean |  | Include raw class names/getter values in property dumps. Default: `false`. |
| `include_presentations` | boolean |  | Include presentation summaries (diagram/relationMap; default true). |
| `include_properties` | boolean |  | Include the diagram property manager dump (default true). |

### `cameo_list_snapshots` (read)

List in-memory snapshots available for diffing (id, createdAt, name, target, options).

### `cameo_get_snapshot` (read)

Get one in-memory snapshot including its full captured payload.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `snapshot_id` | string | yes | Snapshot ID from cameo_create_snapshot or cameo_list_snapshots. |

### `cameo_delete_snapshot` (side-effect)

Delete an in-memory snapshot. Does not change the Cameo model.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `snapshot_id` | string | yes | Snapshot ID to delete. |

### `cameo_diff_snapshots` (read)

Diff two snapshot payloads to reveal what a Cameo UI action changed. Returns stable JSON paths such as $.relationMapSettings.dependencyCriteria[0] grouped as added, removed, changed and arrayLengthChanges, with before/after values when include_details is true.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `before_snapshot_id` | string | yes | Snapshot taken before the change. |
| `after_snapshot_id` | string | yes | Snapshot taken after the change. |
| `ignore_paths` | string[] |  | Path prefixes to skip, e.g. ['$.presentations', '$.diagram.name']. |
| `include_details` | boolean |  | Include before/after values for each change. Default: `true`. |
| `max_changes` | integer |  | Stop after this many changes (truncated=true). Default: `500`. |

### `cameo_list_probe_templates` (read)

List the safe built-in Cameo API discovery probes for cameo_execute_probe (Relation Map GraphSettings methods and criteria classes, presentation classes, UI selection APIs).

### `cameo_execute_probe` (side-effect)

Execute a controlled read-only discovery probe: a built-in template (see cameo_list_probe_templates), or language=javaReflection with operation listMethods (class_name), invokeStaticNoArg (class_name + method_name) or invokeGraphSettingsGetter (relation_map_id + method_name). Classes are limited to Cameo API packages. Arbitrary scripts are refused (use cameo_execute_macro instead); mode other than 'read' needs -Dcameo.mcp.probes.write.enabled=true.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `template` | string |  | Built-in probe template key, e.g. relationMap.listGraphSettingsMethods. |
| `mode` | string |  | Probe mode; only 'read' is enabled by default. Default: `read`. |
| `script` | string |  | Script text. Refused: arbitrary script execution is disabled for probes. |
| `language` | string |  | 'javaReflection' for reflection operations. Default: `javaReflection`. |
| `timeout_ms` | integer |  | Timeout hint in milliseconds. Default: `5000`. |
| `requires_project` | boolean |  | Fail when no project is open. Default: `true`. |
| `description` | string |  | Free-text note on why the probe is run (echoed). |
| `operation` | string |  | Reflection operation (default listMethods). One of: [listMethods, invokeStaticNoArg, invokeGraphSettingsGetter]. |
| `class_name` | string |  | Fully qualified class name in an allowed com.nomagic package. |
| `method_name` | string |  | Public no-arg method name (getters only for invokeGraphSettingsGetter). |
| `relation_map_id` | string |  | Relation Map ID for invokeGraphSettingsGetter. |

## advanced

### `cameo_get_validation_capabilities` (read)

Check whether Cameo's native validation API (ValidationHelper, ValidationRunData, RuleViolationResult) is usable, and list the validation tools. Call before cameo_run_native_validation. Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_list_validation_suites` (read)

List validation suites and constraints usable with cameo_run_native_validation: knownSuites (e.g. 'UML Standard Profile::Validation Profile::Active Validation::UML Correctness', found by qualified name) plus heuristic candidateSuites (packages) and candidateConstraints from the primary model whose name or owner mentions validation/constraint/suite/SysML/requirement (max 250 each). Use suiteId or constraintId values from the result.

### `cameo_run_native_validation` (read)

Run Cameo native validation for a suite package or explicit constraints and return findings (message, rule, annotation with severity, target and parent elements). Give suite_id or constraint_ids; with neither, the built-in 'UML Correctness' suite runs. Scope: scope_element_ids limits the run to those elements (whole_project defaults to false then, true otherwise). The full result is cached (last 50 runs): the response carries runId, findingCount, countsBySeverity and the first max_findings findings; page the rest with cameo_get_validation_result. Large projects can take minutes; scope runs when possible.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `suite_id` | string |  | Validation suite Package id from cameo_list_validation_suites. |
| `suite_qualified_name` | string |  | Alternative to suite_id: suite qualified name, e.g. 'UML Standard Profile::Validation Profile::Active Validation::UML Correctness'. |
| `constraint_ids` | string[] |  | Explicit Constraint ids to evaluate instead of a suite. |
| `scope_element_ids` | string[] |  | Elements to validate (default: the primary model). |
| `whole_project` | boolean |  | Validate the whole project. Default: true unless scope_element_ids is given. |
| `recursive` | boolean |  | Include owned elements of the scope recursively. Default: `true`. |
| `exclude_read_only` | boolean |  | Skip read-only elements (used projects/libraries). Default: `true`. |
| `minimum_severity` | string |  | Lowest severity to report (default any). One of: [any, debug, info, warning, error, fatal]. |
| `open_native_window` | boolean |  | Also show the results in Cameo's Validation Results window. Default: `false`. |
| `name` | string |  | Run name shown in Cameo. |
| `max_findings` | integer |  | Findings returned inline (the rest stay cached). Default: `500`. |

### `cameo_get_validation_result` (read)

Fetch a cached native validation run by run_id (from cameo_run_native_validation or cameo_run_validation), paging findings with offset/limit (hasMore/nextOffset). Only the last 50 runs since Cameo started are kept; NOT_FOUND otherwise.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `run_id` | string | yes | runId returned by a validation run. |
| `offset` | integer |  | First finding to return. Default: `0`. |
| `limit` | integer |  | Findings to return. Default: `500`. |

### `cameo_run_validation` (read)

Bounded native validation with a time limit: like cameo_run_native_validation but with scope_mode ('project' = whole project, 'elements' = only scope_element_ids; inferred from scope_element_ids when omitted), min_severity and timeout_ms. On timeout a TIMEOUT error is returned (Cameo may still finish in the background). Results are cached for cameo_get_validation_result.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `suite_id` | string |  | Validation suite Package id (default: UML Correctness). |
| `constraint_ids` | string[] |  | Explicit Constraint ids instead of a suite. |
| `scope_mode` | string |  | project or elements (default project, or elements when scope_element_ids is given). One of: [project, elements]. |
| `scope_element_ids` | string[] |  | Elements to validate when scope_mode=elements. |
| `min_severity` | string |  | Lowest severity to report (default any). One of: [any, debug, info, warning, error, fatal]. |
| `timeout_ms` | integer |  | Time limit in milliseconds (1000-600000). Default: `30000`. |
| `max_findings` | integer |  | Findings returned inline (the rest stay cached). Default: `500`. |

### `cameo_get_report_capabilities` (read)

Check whether the Report Wizard plugin API (TemplateHelper, GenerateTask) is available; lists known output formats (docx, pdf, html, xlsx, pptx). Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_list_report_templates` (read)

List Report Wizard templates (index/id, name, category, description, path, templateFileName, reportNames). Pass the id (index) or name as template_id/template_name to cameo_generate_report_preview and cameo_generate_report.

### `cameo_generate_report_preview` (read)

Dry-run a Report Wizard generation: echoes the request, resolves the template, checks the output path (absolute, existing file) and reports availability. Writes nothing. Call before cameo_generate_report.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `template_id` | string |  | Template index from cameo_list_report_templates (or a name/path). |
| `template_name` | string |  | Template name, e.g. 'Model Report'. |
| `report_name` | string |  | Report configuration inside the template (default: its default report). |
| `output_path` | string |  | Absolute output file path, e.g. C:/reports/model.docx. |
| `output_format` | string |  | docx, pdf, html, xlsx, pptx... (default: from the file extension). |
| `scope_element_ids` | string[] |  | Packages/elements to report on (default: primary model). |
| `recursive` | boolean |  | Include nested packages (default true). |
| `parameters` | object |  | Extra template options; may also carry any of the keys above in camelCase (outputPath, templateName, showAuxiliary, ...). |

### `cameo_generate_report` (side-effect)

Generate a Report Wizard document from a native template into output_path. Refused unless allow_write=true (it writes a file; an existing file at output_path is replaced). Runs inline on Cameo's UI thread and returns jobId, outputPath, outputBytes. Preview first with cameo_generate_report_preview.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `template_id` | string |  | Template index from cameo_list_report_templates (or a name/path). |
| `template_name` | string |  | Template name. |
| `report_name` | string |  | Report configuration inside the template. |
| `output_path` | string |  | Absolute output file path (required). |
| `output_format` | string |  | Output format (default: from the file extension, else docx). |
| `scope_element_ids` | string[] |  | Packages/elements to report on (default: primary model). |
| `recursive` | boolean |  | Include nested packages (default true). |
| `display_in_viewer` | boolean |  | Open the result in the system viewer (default false). |
| `parameters` | object |  | Extra template options (camelCase keys). |
| `allow_write` | boolean |  | Must be true to write the report file. Default: `false`. |
| `timeout_seconds` | integer |  | Time limit (10-600). Default: `120`. |

### `cameo_get_report_job` (read)

Look up a completed report generation by jobId (generation runs inline, so jobs are only 'completed'; the last 50 are kept). NOT_FOUND for unknown ids.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `job_id` | string | yes | jobId returned by cameo_generate_report. |

### `cameo_get_requirements_capabilities` (read)

Check whether native Requirements/ReqIF APIs (ReqIFUtils, ReqIFMappingManager) and the requirements plugin folder are present (feature probe: available, installed, classesFound/Missing, warnings). Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_get_import_export_capabilities` (read)

Describe requirements import/export support: CSV/JSON is handled by this server (csvJsonBridgeOwned), native ReqIF is probed only (nativeReqifAvailable) and its apply stays gated. Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_export_requirements` (side-effect)

Export requirement-like elements (SysML «requirement» or any element whose type/stereotype name contains 'requirement') under the given roots, depth-first, up to limit. format=json returns full element JSON (name, documentation, taggedValues incl. Id/Text); format=csv also returns csvText with columns id,name,requirementId,text,documentation,qualifiedName,ownerId,stereotypes,type; format=reqif is not written natively (JSON is returned). With output_path the export is also written to that absolute file; an existing file is never replaced unless overwrite=true.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `scope_ids` | string[] |  | Root elements/packages to export from (default: primary model). |
| `root_id` | string |  | Single root element id (combined with scope_ids). |
| `package_id` | string |  | Alias of root_id. |
| `format` | string |  | Export format. One of: [json, csv, reqif]. Default: `json`. |
| `output_path` | string |  | Optional absolute file path to write the export to. |
| `overwrite` | boolean |  | Replace an existing file at output_path. Default: `false`. |
| `limit` | integer |  | Maximum requirements (1-5000). Default: `1000`. |

### `cameo_preview_requirements_import` (read)

Preview a requirements import without writing: parses rows from requirements/source_rows (arrays of objects), csv_text (header row; comma, semicolon or tab) and/or source_path (absolute .csv/.json file, max 20 MB), normalizes each row (name <- name/title or the id; externalId <- externalId/requirementId/key/id; text <- text/description), validates (missing names, duplicates) and, with target_package_id, marks rows that already exist there. Returns a patchPlan to pass to cameo_apply_requirements_import.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `source_path` | string |  | Absolute path of a .csv or .json file to import. |
| `source_rows` | object[] |  | Requirement rows as objects, e.g. [{"id":"R1","name":"Speed","text":"..."}]. |
| `requirements` | object[] |  | Same as source_rows. |
| `csv_text` | string |  | CSV text with a header row, e.g. 'id,name,text\nR1,Speed,The car shall...'. |
| `target_package_id` | string |  | Package the requirements would be created in. |

### `cameo_apply_requirements_import` (write)

Create requirements in target_package_id from reviewed rows. Safety gates: dry_run defaults to true (returns the preview only); writing needs dry_run=false AND allow_write=true, otherwise PRECONDITION_FAILED. format=reqif is refused (native ReqIF apply is gated). Each row becomes a Class with the SysML Requirement stereotype (when SysML is loaded), Id/Text tags from externalId/text, and documentation; one undoable step. Rows come from requirements, rows, csv_text, or patch_plan.rows from cameo_preview_requirements_import. Every row is created, even ones that already exist: drop those first. Use a disposable package for first runs.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `patch_plan` | object |  | patchPlan from cameo_preview_requirements_import (rows and targetPackageId). |
| `target_package_id` | string |  | Package to create requirements in (default: patch_plan.targetPackageId). |
| `requirements` | object[] |  | Requirement rows as objects. |
| `rows` | object[] |  | Same as requirements. |
| `csv_text` | string |  | CSV text with a header row. |
| `format` | string |  | Source format label; reqif is refused. One of: [json, csv, reqif]. Default: `json`. |
| `dry_run` | boolean |  | Preview only (no write). Default: `true`. |
| `allow_write` | boolean |  | Must be true (with dry_run=false) to create elements. Default: `false`. |

### `cameo_export_requirements_preview` (read)

Preview a requirements export without writing anything: probe-only contract (dryRun=true, executed=false, capabilityProbe) plus requirementCount, five sampleRows, the CSV columns and an outputPathCheck. Use cameo_export_requirements to export.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `scope_ids` | string[] |  | Roots to export from (default: primary model). |
| `format` | string |  | Planned export format. One of: [csv, json, reqif]. Default: `csv`. |
| `output_path` | string |  | Planned absolute output path (checked, not written). |

### `cameo_import_requirements_preview` (read)

Probe-only requirements import preview (dryRun=true, executed=false) with the same parsing, validation and target comparison as cameo_preview_requirements_import; writes nothing.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `source_path` | string |  | Absolute path of a .csv or .json file. |
| `source_rows` | object[] |  | Requirement rows as objects. |
| `target_package_id` | string |  | Package the requirements would be created in. |

### `cameo_get_simulation_capabilities` (read)

Check whether Cameo Simulation Toolkit runtime classes (SimulationManager, SimulationResult, SimulationSession) are present. Execution is not enabled through this server (executionEnabled=false). Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_list_simulation_configurations` (read)

Heuristically list simulation configuration candidates in the primary model (elements whose type, name or stereotype mentions simulation/config/parametric; max 500). Returns an 'unsupported' payload when Simulation Toolkit is missing.

### `cameo_run_simulation_preview` (read)

Record a simulation run request without running anything; checks configuration_id exists. Execution itself stays gated (requiresAllowExecute, requiresRuntimeClasses).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `configuration_id` | string |  | SimulationConfig (or executable element) id. |
| `timeout_ms` | integer |  | Intended time limit. Default: `30000`. |

### `cameo_run_simulation` (side-effect)

Guarded simulation execution endpoint. Always refused with UNSUPPORTED in this release (even with allow_execute=true): execution stays disabled until validated against a disposable executable model. Run simulations from the Cameo UI instead.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `configuration_id` | string |  | SimulationConfig id. |
| `target_id` | string |  | Element to execute. |
| `timeout_ms` | integer |  | Intended time limit. Default: `30000`. |
| `allow_execute` | boolean |  | Explicit execution consent (still refused in this release). Default: `false`. |
| `async_run` | boolean |  | Request asynchronous execution. Default: `false`. |

### `cameo_get_simulation_result` (read)

Fetch a simulation run result. No runs are persisted while execution is disabled, so this returns NOT_FOUND.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `run_id` | string | yes | Simulation run id. |

### `cameo_terminate_simulation` (side-effect)

Terminate an active simulation run. No job store exists while execution is disabled, so this returns NOT_FOUND.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `run_id` | string | yes | Simulation run id. |

### `cameo_get_teamwork_capabilities` (read)

Check Teamwork Cloud / Magic Collaboration Studio API availability (EsiUtils). Commit/update writes are not supported (writeSupported=false). Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_get_teamwork_project` (read)

Describe the open project's storage: projectMode (teamwork or local), its ProjectDescriptor and ESI diagnostics (logged user, server info) when available. Read-only.

### `cameo_preview_teamwork_commit` (read)

Record a Teamwork commit request without committing (writeEnabled=false, no approval token). Commit from the Cameo UI.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `message` | string |  | Intended commit message. |

### `cameo_preview_teamwork_update` (read)

Record a Teamwork update request without updating the local project (writeEnabled=false).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `message` | string |  | Optional note. |

### `cameo_list_teamwork_descriptors` (read)

List remote project descriptors via EsiUtils.getRemoteProjectDescriptors when Cameo is logged into a Teamwork Cloud server; status auth-or-server-unavailable otherwise.

### `cameo_list_teamwork_branches` (read)

Teamwork branch readback probe: reports projectMode and status (probe-required for server projects, local-project otherwise). Typed branch lists are not available yet.

### `cameo_get_teamwork_history` (read)

Teamwork history readback probe (projectMode/status only; typed history is not available yet).

### `cameo_get_teamwork_locks` (read)

Teamwork lock readback probe (projectMode/status only; typed lock lists are not available yet).

### `cameo_get_datahub_capabilities` (read)

Check for DataHub / DOORS / ENOVIA integration (DataHubPlugin classes or plugin folders). Source readback and sync writes are not supported. Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_list_datahub_sources` (read)

Diagnostic DataHub source inventory: returns the capability probe and an empty sources list (configured sources are not readable through the API yet).

### `cameo_preview_datahub_sync` (read)

Record a DataHub synchronization request without syncing anything; checks scope_id exists.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `source_id` | string |  | DataHub source identifier. |
| `scope_id` | string |  | Model element to synchronize. |

### `cameo_get_criteria_capabilities` (read)

Describe criteria expression support: bridge-owned templates verified by UI snapshot diff, native expression classes, valid directions and relationship kinds. Criteria writes are gated. Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_list_criteria_templates` (read)

List criteria templates (id such as satisfy.both or refine.targetToSource, relationshipKind, direction, targetKinds, verifiedWithUiDiff). Filter with target = relationMap, matrix or table.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `target` | string |  | Target kind filter: relationMap, matrix, table. |

### `cameo_build_criteria_expression` (read)

Build a criteria expression from relationship_kind + direction (or a template_id), normalizing spelling (e.g. 'derive' -> DeriveReqt, 'forward' -> sourceToTarget) and matching it to a template; verifiedWithUiDiff is true only for UI-verified templates. Writes nothing.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `relationship_kind` | string |  | Relationship kind, e.g. Satisfy, Refine, DeriveReqt, Allocate, Verify, Trace, Dependency. |
| `direction` | string |  | Traversal direction. One of: [both, sourceToTarget, targetToSource]. Default: `both`. |
| `target` | string |  | Where it will be used: relationMap, matrix or table. |
| `template_id` | string |  | Start from a template id from cameo_list_criteria_templates. |

### `cameo_parse_criteria_expression` (read)

Parse a criteria expression into normalized form with diagnostics. Accepts an object ({relationshipKind, direction} or {templateId}, or a build result), a JSON string, a template id ('satisfy.both') or text ('Satisfy:sourceToTarget', 'Refine both'). Returns parsed, criteriaExpression, matchedTemplateId, verifiedWithUiDiff and issues.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `expression` | any | yes | Expression object or string. |

### `cameo_apply_criteria_template` (write)

Gated: applying criteria to a matrix/table/map is refused (UNSUPPORTED) until UI-created native expressions are captured and replayed by snapshot diff. For Relation Maps use cameo_set_relation_map_criteria.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `target_id` | string | yes | Matrix, table or relation map id. |
| `template_id` | string |  | Template id. |
| `expression` | object |  | Expression object. |
| `refresh` | boolean |  | Refresh the target afterwards. Default: `false`. |

### `cameo_capture_criteria_template_from_diff` (read)

Record a criteria template capture from before/after snapshots taken around a UI edit (cameo_create_snapshot before, edit criteria in Cameo, snapshot after). Attaches the snapshot diff as diffEvidence when snapshot tools are available; the template stays unverified.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `before_snapshot_id` | string | yes | Snapshot taken before the UI edit. |
| `after_snapshot_id` | string | yes | Snapshot taken after the UI edit. |
| `target_kind` | string |  | relationMap, matrix or table. |

### `cameo_get_profile_capabilities` (read)

Describe profile/DSL authoring support: summary read is supported, typed profile writes are preview-only (use cameo_apply_profile / cameo_apply_stereotype / cameo_set_tagged_values for writes). Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_export_profile_summary` (read)

List Profile and Stereotype elements owned by the primary model (reference form: id, name, qualifiedName, owner). Profiles from used projects (SysML, UAF...) are not included.

### `cameo_preview_profile_operation` (read)

Record a profile operation request without writing. operation: create-profile, create-stereotype, create-tag, apply-profile or set-tags; payload is echoed as the request.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | yes | Profile operation. One of: [create-profile, create-stereotype, create-tag, apply-profile, set-tags]. |
| `payload` | object |  | Operation details, e.g. {"name":"MyProfile","ownerId":"..."}. |

### `cameo_get_variant_capabilities` (read)

Check for native variability / product-line plugins; bridge-owned preview analysis is always available, native writes are not. Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_analyze_variants_preview` (read)

Preview variant/product-line analysis without writes: resolves configuration_ids and scope_ids (missing ids become warnings) and echoes the scope as includedElements. No variant rules are evaluated natively.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `configuration_ids` | string[] |  | Variant configuration element ids. |
| `scope_ids` | string[] |  | Elements in scope. |

### `cameo_install_variant_pattern_preview` (read)

Record a request to install variant stereotypes/patterns; nothing is installed.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `payload` | object |  | Pattern details. |

### `cameo_export_variant_configuration` (read)

Record a variant configuration export request (evidence payload preview); nothing is written and no model content is hidden or deleted.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `payload` | object |  | Configuration details. |

### `cameo_get_extension_capabilities` (read)

Check for safety/reliability/cyber/security plugins (folder names). Model scans are read-only; pattern writes and compliance claims are not supported. Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_scan_extensions` (read)

Read-only evidence scan for safety/cyber modeling: profiles, stereotypes and elements (max 500) whose type or name contains the target terms (default safety, cyber, security, risk, hazard, failure, fmea, control, threat, classification). Evidence only, never a compliance claim.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `targets` | string[] |  | Terms to match instead of the defaults, e.g. ["hazard","mitigation"]. |
| `scope_id` | string |  | Element/package to scan (default: primary model). |

### `cameo_list_extension_profiles` (read)

List profiles and stereotypes in the primary model whose names match the default safety/cyber terms.

### `cameo_install_extension_pattern_preview` (read)

Record a request to install a safety/cyber extension pattern; nothing is installed.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `payload` | object |  | Pattern details. |

### `cameo_refuse_compliance_claim` (read)

Return the explicit refusal contract (refused=true, code COMPLIANCE_CLAIM_REFUSED) for safety, cyber, regulatory or certification compliance claims: this server reports evidence and gaps only. Use when asked to certify or declare compliance.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `claim_type` | string | yes | Claim requested, e.g. ISO 26262, DO-178C, IEC 62443. |
| `evidence_ids` | string[] |  | Evidence element ids offered for the claim. |

### `cameo_get_typed_diagram_capabilities` (read)

Describe typed diagram support: native inspection plus diagram-type-gated write previews (sequence-message, state-transition, parametric-binding, legend-apply). Probe-first: reports class/plugin evidence (available, status, classProbe, pluginDirectoriesFound, projectState); never changes anything.

### `cameo_list_typed_diagrams` (read)

List all diagrams with id, name and diagramType (e.g. 'SysML Block Definition Diagram'), plus countsByType. Optional diagram_type substring filter.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_type` | string |  | Case-insensitive substring of the diagram type, e.g. 'Sequence'. |

### `cameo_inspect_typed_diagram` (read)

Inspect one diagram's presentation tree: every shape/path with presentationId, shapeType, parentPresentationId, element reference, bounds and childCount, plus countsByShapeType (first limit presentations).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Diagram id from cameo_list_typed_diagrams. |
| `limit` | integer |  | Maximum presentations returned. Default: `500`. |

### `cameo_preview_typed_diagram_operation` (read)

Record a typed diagram write request without drawing anything. operation: sequence-message, state-transition, parametric-binding or legend-apply; payload describes it and is echoed.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | yes | Typed diagram operation. One of: [sequence-message, state-transition, parametric-binding, legend-apply]. |
| `payload` | object | yes | Operation details, e.g. {"diagramId":"...","sourceId":"...","targetId":"..."}. |

## analysis

### `cameo_verify_matrix_consistency` (read)

Run quantitative consistency checks against one native requirement matrix (wraps cameo_get_matrix): expected row/column membership, dependency names in populated cells, populated-cell count and density over the full row x column product. Returns ok, checks (name/ok/details), metrics and the matrix readback. Find matrix ids with cameo_list_matrices.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `matrix_id` | string | yes | Diagram id of the matrix artifact to validate. |
| `expected_row_ids` | string[] |  | Element ids that must appear in the row domain. |
| `expected_column_ids` | string[] |  | Element ids that must appear in the column domain. |
| `expected_dependency_names` | string[] |  | Relationship names that must appear in populated cells. |
| `min_populated_cell_count` | integer |  | Minimum populated-cell count required. Default: `0`. |
| `min_density` | number |  | Minimum populated-cell density (0-1) over the full row/column cross-product. Default: `0.0`. |

### `cameo_verify_diagram_visual` (read)

Run reusable visual verification checks against one diagram: combines cameo_get_diagram_image and cameo_list_diagram_shapes and checks image payload validity, rendered size, non-background content coverage, expected element/path presence, shape counts and coarse sibling-overlap risk. Returns ok, checks, imageMetrics, shapeMetrics plus diagramImage and diagramShapes readback.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | The id of the diagram to validate. |
| `expected_element_ids` | string[] |  | Model element ids that must appear on the diagram canvas. |
| `expected_relationship_ids` | string[] |  | Relationship element ids that must appear as relationship paths. |
| `min_shape_count` | integer |  | Minimum number of presentation elements expected. Default: `0`. |
| `min_relationship_shape_count` | integer |  | Minimum number of relationship paths expected. Default: `0`. |
| `min_width` | integer |  | Minimum rendered image width in pixels. Default: `1`. |
| `min_height` | integer |  | Minimum rendered image height in pixels. Default: `1`. |
| `min_image_bytes` | integer |  | Minimum PNG payload size in bytes. Default: `1`. |
| `min_content_coverage_ratio` | number |  | Minimum non-background pixel coverage ratio over the image area. Default: `0.0`. |
| `max_overlap_ratio` | number |  | Maximum allowed sibling-shape overlap ratio. Default: `1.0`. |

### `cameo_verify_activity_flow_semantics` (read)

Validate whether one activity diagram behaves like a coherent workflow: initial/final nodes, reachable actions, no isolated action islands, partition nesting depth, container-only partitions and stereotype-looking partition labels. Find the diagram with cameo_list_diagrams (inspect it with cameo_list_diagram_shapes). Use it when an activity diagram has the right nouns but may lack a real control/object flow. Returns checks, diagram readback (elements, relationships, diagramShapes) and the flattened flow graph.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `diagram_id` | string | yes | Activity diagram id. |
| `max_partition_depth` | integer |  | Maximum allowed nested activity-partition depth. Default: `1`. |
| `allow_stereotype_partition_labels` | boolean |  | When false, flags partition labels that look like stereotype notation such as «allocate». Default: `false`. |

### `cameo_verify_port_boundary_consistency` (read)

Validate interface-block flow-property ownership and duplication: the same flow property copied onto several interfaces, direction conflicts, missing directions and orphan properties. Find interface blocks with cameo_query_elements(stereotype="InterfaceBlock"). Returns checks plus the interfaceBlocks and flowProperties readback.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `interface_block_ids` | string[] | yes | Interface block ids to inspect together as one boundary set. |
| `allow_shared_flow_property_names` | string[] |  | Flow property names allowed on more than one interface block. |

### `cameo_verify_requirement_quality` (read)

Validate whether SysML requirements contain real requirement content: requirement id present, non-blank text, minimum length, directive (shall/must/will) and measurable wording. Collect ids with cameo_query_elements(stereotype="Requirement"). Returns per-requirement assessments, aggregate checks and the requirement readback.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `requirement_ids` | string[] | yes | Requirement element ids to evaluate. |
| `require_id` | boolean |  | Fail requirements that have no requirement id. Default: `true`. |
| `require_measurement` | boolean |  | Fail requirements that do not look directive and measurable. Default: `true`. |
| `min_text_length` | integer |  | Minimum text length for a non-trivial requirement statement. Default: `20`. |

### `cameo_verify_cross_diagram_traceability` (read)

Validate traceability across activity, port, IBD and requirement views: activity terms covered by interface flow properties and by IBD flows/ports, and requirements traced into the architecture element set. Every input is optional; only applicable checks run. Find diagrams with cameo_list_diagrams, interface blocks / requirements with cameo_query_elements(stereotype="InterfaceBlock" / "Requirement").

| Parameter | Type | Required | Description |
|---|---|---|---|
| `activity_diagram_id` | string |  | Activity diagram to extract behavior-side terms from. |
| `interface_block_ids` | string[] |  | Interface blocks to compare against the activity vocabulary. |
| `ibd_diagram_id` | string |  | IBD to compare against the activity vocabulary. |
| `requirement_ids` | string[] |  | Requirements to trace into the architecture element set. |
| `architecture_element_ids` | string[] |  | Architecture element ids that count as valid requirement trace targets. |

### `cameo_detect_cross_diagram_inconsistencies` (read)

Detect cross-diagram inconsistencies and return a previewable remediation plan. Non-mutating: runs the activity, port-boundary, requirement-quality and traceability validators on the supplied artifacts, then returns structured receipts (with confidence) plus a patchPlan.steps preview to inspect before any change. Pass any subset of the ids.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `activity_diagram_id` | string |  | Activity diagram id. |
| `interface_block_ids` | string[] |  | Interface block ids. |
| `ibd_diagram_id` | string |  | Internal block diagram id. |
| `requirement_ids` | string[] |  | Requirement ids. |
| `architecture_element_ids` | string[] |  | Architecture element ids that count as trace targets. |
| `allow_shared_flow_property_names` | string[] |  | Flow property names allowed on several interfaces. |
| `require_id` | boolean |  | Requirements must carry an id. Default: `true`. |
| `require_measurement` | boolean |  | Requirements must be directive and measurable. Default: `true`. |
| `min_requirement_text_length` | integer |  | Minimum requirement text length. Default: `20`. |
| `max_partition_depth` | integer |  | Maximum nested activity-partition depth. Default: `1`. |
| `allow_stereotype_partition_labels` | boolean |  | Allow stereotype-looking partition labels. Default: `false`. |

### `cameo_build_cross_diagram_remediation_plan` (read)

Build a previewable remediation plan from existing validation payloads (the results of cameo_verify_activity_flow_semantics, cameo_verify_port_boundary_consistency, cameo_verify_requirement_quality and cameo_verify_cross_diagram_traceability) without reading the model again. Returns receipts and patchPlan.steps.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `activity_validation` | object |  | Result of cameo_verify_activity_flow_semantics. |
| `port_validation` | object |  | Result of cameo_verify_port_boundary_consistency. |
| `requirement_validation` | object |  | Result of cameo_verify_requirement_quality. |
| `trace_validation` | object |  | Result of cameo_verify_cross_diagram_traceability. |
| `architecture_elements` | object[] |  | Architecture elements ({id, name}) to suggest as trace targets. |

### `cameo_proof_model_text` (write)

Proof model text across requirements, comments, states, transitions and diagram labels: spelling (built-in engineering lexicon), naming style (Title Case state and diagram-label names, PascalCase transition names, sentence case for requirement/comment text), placeholder text (TODO/TBD/FIXME), whitespace and stray punctuation. Give root_package_id for a package-wide sweep or explicit ids for narrower analysis. Read-only unless auto_apply=true, which applies the safe rename/text edits (one undo step each) and returns apply receipts. Otherwise review patch_plan and pass it to cameo_apply_proofing_patch_plan.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root_package_id` | string |  | Package whose subtree (elements and diagrams) is proofed. |
| `requirement_ids` | string[] |  | Requirement ids to proof. |
| `comment_ids` | string[] |  | Comment ids to proof. |
| `state_ids` | string[] |  | State ids to proof. |
| `transition_ids` | string[] |  | Transition ids to proof. |
| `diagram_ids` | string[] |  | Diagrams whose shape labels are proofed. |
| `auto_apply` | boolean |  | Apply safe fixes immediately. Default: `false`. |

### `cameo_apply_proofing_patch_plan` (write)

Apply a previously generated proofing patch plan (the patch_plan of cameo_proof_model_text) to the live model: name fields through cameo_modify_element, documentation through cameo_modify_element, other text fields through cameo_set_specification. Returns one receipt per operation and failedCount; each edit is its own undo step.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `patch_plan` | object | yes | Patch plan object with an operations list (from cameo_proof_model_text). |

### `cameo_list_methodology_packs` (read)

List the built-in methodology packs (OOSEM viewpoint pack, UAF starter pack) with each pack's phases, recipes, naming rules, mandatory relationships, review sections and evidence expectations. Start here before cameo_get_methodology_guidance.

### `cameo_get_methodology_pack` (read)

Get one methodology pack definition: recipes (with parameters, prerequisites, created artifacts, mandatory relationships, steps), phases, naming rules and review/evidence structure.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |

### `cameo_get_methodology_guidance` (read)

Explain what artifact work is missing next for a methodology pack: blockers, missing artifacts and relationships, completion ratio and recommended next actions. Omit recipe_id to assess the first pending recipe. Pure planning: it does not read the model, so pass completed_artifacts you already know.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |
| `recipe_id` | string |  | Recipe to assess; omit for the first pending recipe. |
| `recipe_parameters` | object |  | Recipe parameter values by name (e.g. {"subject_name": "ATM", "actor_names": ["Customer"]}); they materialize the expected artifact names. Parameters are listed per recipe by cameo_get_methodology_pack. root_package_id here also seeds the workspace. |
| `completed_artifacts` | object[] |  | Artifacts already present. Normalized artifact snapshots, each {"key", "kind", "name", "element_id"} plus optional parent_key, stereotypes, properties, relationships. Keys are the recipe's artifact keys (e.g. "workspace", "use_case_package"). |

### `cameo_execute_methodology_recipe` (write)

Execute a bounded OOSEM/UAF methodology recipe through structured model operations (cameo_create_element, cameo_create_relationship, cameo_create_diagram, cameo_add_to_diagram, cameo_add_diagram_paths, cameo_set_specification, cameo_set_shape_compartments, cameo_route_paths, cameo_set_usecase_subject). Existing artifacts (given or discovered by name under root_package_id) are reused, not duplicated. Returns workflow guidance, the execution plan, receipts, conformance (structural plus semantic validators), an evidence bundle and review-packet markdown. Each operation is its own undo step; if a step fails the error lists the steps already applied. Check required parameters with cameo_get_methodology_pack first.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |
| `recipe_id` | string | yes | Recipe id from the pack, e.g. "use_case_model", "system_requirements_package", "logical_activity_flow", "logical_port_bdd" (see cameo_get_methodology_pack). |
| `root_package_id` | string | yes | Package/model that will own the new artifact set. |
| `recipe_parameters` | object | yes | Parameter values for the recipe (required ones must be present), e.g. {"subject_name": "ATM", "actor_names": ["Customer"], "use_case_names": ["Withdraw Cash"]}. |
| `completed_artifacts` | object[] |  | Artifacts already present and available for trace/allocation linkage. Normalized artifact snapshots, each {"key", "kind", "name", "element_id"} plus optional parent_key, stereotypes, properties, relationships. Keys are the recipe's artifact keys (e.g. "workspace", "use_case_package"). |
| `assumptions` | string[] |  | Assumptions to include in the evidence bundle. |

### `cameo_validate_methodology_recipe` (read)

Run methodology conformance checks for one recipe without changing the model: required artifacts (given or discovered under root_package_id), kinds, names, parents, stereotypes, relationships, plus the recipe's semantic validators (requirement quality, activity flow, port boundary, traceability). Returns workflowGuidance, conformance and semanticValidations.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |
| `recipe_id` | string | yes | Recipe id from the pack, e.g. "use_case_model", "system_requirements_package", "logical_activity_flow", "logical_port_bdd" (see cameo_get_methodology_pack). |
| `recipe_parameters` | object |  | Recipe parameter values by name (e.g. {"subject_name": "ATM", "actor_names": ["Customer"]}); they materialize the expected artifact names. Parameters are listed per recipe by cameo_get_methodology_pack. root_package_id here also seeds the workspace. |
| `current_artifacts` | object[] |  | Artifacts to validate. Normalized artifact snapshots, each {"key", "kind", "name", "element_id"} plus optional parent_key, stereotypes, properties, relationships. Keys are the recipe's artifact keys (e.g. "workspace", "use_case_package"). |

### `cameo_generate_review_packet` (read)

Generate a compact methodology review packet without mutating the model: an evidence bundle (guidance, plan, conformance, semantic validations, live artifacts, diagram snapshot metadata) plus Markdown review-packet text with findings and assumptions.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |
| `recipe_id` | string | yes | Recipe id from the pack, e.g. "use_case_model", "system_requirements_package", "logical_activity_flow", "logical_port_bdd" (see cameo_get_methodology_pack). |
| `recipe_parameters` | object |  | Recipe parameter values by name (e.g. {"subject_name": "ATM", "actor_names": ["Customer"]}); they materialize the expected artifact names. Parameters are listed per recipe by cameo_get_methodology_pack. root_package_id here also seeds the workspace. |
| `current_artifacts` | object[] |  | Artifacts to include. Normalized artifact snapshots, each {"key", "kind", "name", "element_id"} plus optional parent_key, stereotypes, properties, relationships. Keys are the recipe's artifact keys (e.g. "workspace", "use_case_package"). |
| `assumptions` | string[] |  | Assumptions to include in the packet. |
| `notes` | string[] |  | Free-form notes to include in the packet. |

### `cameo_compare_expected_artifact_list` (read)

Compare discovered/current artifacts against an expected methodology artifact list. Dry run: returns per-artifact status (match, missing, kind_mismatch, name_mismatch, parent_mismatch, stereotype_mismatch, property_mismatch, unexpected), suggested actions, a preview patchPlan, missingArtifactKeys, unexpectedArtifactKeys and ready.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `expected_artifacts` | object[] | yes | Expected artifacts. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |
| `current_artifacts` | object[] |  | Current artifacts. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |

### `cameo_validate_methodology_package` (read)

Validate a whole pack or one recipe scope against the methodology definition (dry run). Without current_artifacts the live model is searched under root_package_id. Expected artifacts default to the recipes' required artifacts (names that depend on recipe parameters are not compared); pass expected_artifacts to use your own list. Returns workflowGuidance, expectedArtifacts, artifactComparison, recommendedActions, ready and currentArtifacts.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |
| `recipe_id` | string |  | Recipe scope; omit to validate the whole pack. |
| `root_package_id` | string |  | Package searched for live artifacts when current_artifacts is omitted. |
| `current_artifacts` | object[] |  | Current artifacts; omit to discover them. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |
| `expected_artifacts` | object[] |  | Custom expected artifact list. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |

### `cameo_export_required_diagrams` (side-effect)

Plan or execute export of the diagrams a methodology pack/recipe requires. Omit output_dir for a dry-run export queue (exportItems with status ready/blocked, outputFile names, blockedDiagramKeys). With output_dir, ready diagrams are rendered through cameo_get_diagram_image and written there (directory is created); returns exports, exportedCount and blockedCount.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |
| `recipe_id` | string |  | Recipe scope; omit for the whole pack. |
| `root_package_id` | string |  | Package searched for live artifacts when current_artifacts is omitted. |
| `current_artifacts` | object[] |  | Current artifacts; omit to discover them. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |
| `expected_artifacts` | object[] |  | Custom expected artifact list. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |
| `export_format` | string |  | Image format. One of: [png, jpeg, jpg, webp]. Default: `png`. |
| `output_dir` | string |  | Absolute directory to write images into; omit for a dry run. |

### `cameo_assemble_ppt_pdf` (side-effect)

Plan or assemble a PowerPoint + PDF review package from the methodology-required diagrams. Omit output_dir for a dry-run presentationPlan (title slide, one slide per required diagram, appendix). With output_dir, ready diagrams are exported there and a .pptx (title slide plus one picture slide per diagram) and a .pdf (one page per diagram) are written; returns createdFiles and ready. Use export_format png or jpeg for assembly.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `pack_id` | string | yes | Methodology pack id: "oosem" or "uaf" (see cameo_list_methodology_packs). |
| `recipe_id` | string |  | Recipe scope; omit for the whole pack. |
| `root_package_id` | string |  | Package searched for live artifacts when current_artifacts is omitted. |
| `current_artifacts` | object[] |  | Current artifacts; omit to discover them. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |
| `expected_artifacts` | object[] |  | Custom expected artifact list. Artifact objects {"key" (or element_id/id), "kind" (or type/humanType), "name", "parent_key", "element_id", "stereotypes", "properties"}. |
| `output_dir` | string |  | Absolute directory for images, PPTX and PDF; omit for a dry run. |
| `title` | string |  | Deck title (default: the pack title). |
| `pptx_name` | string |  | PPTX file name (default: <slugified title>.pptx). |
| `pdf_name` | string |  | PDF file name (default: <slugified title>.pdf). |
| `export_format` | string |  | Image format of the exported diagrams. One of: [png, jpeg, jpg, webp]. Default: `png`. |

## sysml2

### `cameo_sysml2_status` (read)

Check SysML v2 support: whether this Cameo has the SysML v2 plugin (2026x and later), whether the open project is a SysML v2 project (projectKind 'sysml2'), how many root namespaces it has, and which SysML v2 operations (model access, text export, text import) are available. Call this before the other cameo_sysml2_* tools.

### `cameo_sysml2_list_roots` (read)

List the root namespaces of the open SysML v2 project with their top-level members (packages, part definitions, ...): id, elementId, name, type (SysML v2 metaclass such as Package, PartDefinition, PartUsage), qualifiedName and childCount. This is the SysML v2 counterpart of cameo_get_project's primaryModelId.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `include_libraries` | boolean |  | Also list the standard library roots (large). Default: `false`. |
| `members_limit` | integer |  | Top-level members listed per root (0-500). Default: `100`. |

### `cameo_sysml2_list_children` (read)

List the elements a SysML v2 element owns (its members), paged, with id, elementId, name, type, qualifiedName and childCount. Walk the model tree with it, starting from the ids cameo_sysml2_list_roots returns.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id from cameo_sysml2_list_roots, cameo_sysml2_list_children or cameo_sysml2_query (the 'id' field; the SysML v2 'elementId' UUID is accepted too). |
| `limit` | integer |  | Page size (1-1000). Default: `200`. |
| `offset` | integer |  | Index of the first child to return. Default: `0`. |

### `cameo_sysml2_get_element` (read)

Get one SysML v2 element: id, elementId, name, declaredName, shortName, type, humanType, qualifiedName, owner, documentation, isLibraryElement, and its first owned members. With include_text=true also returns the element as SysML v2 textual notation.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id from cameo_sysml2_list_roots, cameo_sysml2_list_children or cameo_sysml2_query (the 'id' field; the SysML v2 'elementId' UUID is accepted too). |
| `include_text` | boolean |  | Also return the element's SysML v2 textual notation. Default: `false`. |
| `members_limit` | integer |  | Owned members listed (0-500). Default: `50`. |

### `cameo_sysml2_query` (read)

Search the SysML v2 model by name and/or metaclass, depth-first from the project roots or from scope_id. type matches the SysML v2 metaclass name case-insensitively (for example PartDefinition, PartUsage, RequirementDefinition, RequirementUsage, AttributeUsage, PortDefinition, ActionDefinition, Package). name is a case-insensitive substring. Paged with limit/offset; hasMore tells you to page.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string |  | Case-insensitive substring of the element name. |
| `type` | string |  | SysML v2 metaclass name, e.g. PartDefinition or PartUsage. |
| `scope_id` | string |  | Only search under this element. Element id from cameo_sysml2_list_roots, cameo_sysml2_list_children or cameo_sysml2_query (the 'id' field; the SysML v2 'elementId' UUID is accepted too). |
| `include_libraries` | boolean |  | Also search the standard libraries. Default: `false`. |
| `limit` | integer |  | Page size (1-1000). Default: `100`. |
| `offset` | integer |  | Index of the first match to return. Default: `0`. |

### `cameo_sysml2_get_text` (read)

Export SysML v2 textual notation generated by Cameo, for one element (and everything it owns) or, without element_id, for every root namespace of the project. Use it to read a whole SysML v2 model at once, review changes, or round-trip text through cameo_sysml2_validate_text and cameo_sysml2_create_from_text.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string |  | Element to export. Omit to export the whole project. Element id from cameo_sysml2_list_roots, cameo_sysml2_list_children or cameo_sysml2_query (the 'id' field; the SysML v2 'elementId' UUID is accepted too). |
| `max_chars` | integer |  | Truncate the text after this many characters (1000-2000000). Default: `200000`. |

### `cameo_sysml2_validate_text` (read)

Parse SysML v2 textual notation with Cameo's SysML v2 compiler without changing the model. Returns valid, diagnostics (severity ERROR / WARNING / INFO / HINT, line, column, message) and the top-level elements the text declares. Names resolve against the open project and the standard libraries, so the text may reference existing elements and library types (for example ScalarValues::Real). Run it before cameo_sysml2_create_from_text.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `text` | string | yes | SysML v2 textual notation, e.g. package Vehicle { part def Engine; } |

### `cameo_sysml2_create_from_text` (write)

Create model content from SysML v2 textual notation: Cameo compiles the text and adds it to the open SysML v2 project as a new root namespace, as one undoable step (like importing a .sysml file). Text with ERROR diagnostics is rejected and nothing changes. Returns the new root's id and the created top-level elements with their ids. To change existing content, export it with cameo_sysml2_get_text, edit the text, delete the old element with cameo_sysml2_delete_element and create it again.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `text` | string | yes | SysML v2 textual notation to add. |
| `allow_warnings` | boolean |  | Create even when the compiler reports warnings. Default: `true`. |

### `cameo_sysml2_rename_element` (write)

Rename a SysML v2 element (sets its declared name) as one undoable step. Returns the element with its new name and qualifiedName.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id from cameo_sysml2_list_roots, cameo_sysml2_list_children or cameo_sysml2_query (the 'id' field; the SysML v2 'elementId' UUID is accepted too). |
| `name` | string | yes | New declared name. |

### `cameo_sysml2_delete_element` (destructive)

Delete a SysML v2 element and everything it owns, as one undoable step (cameo_undo restores it). Library elements and read-only elements cannot be deleted. Deleting a root namespace removes everything created from one text.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `element_id` | string | yes | Element id from cameo_sysml2_list_roots, cameo_sysml2_list_children or cameo_sysml2_query (the 'id' field; the SysML v2 'elementId' UUID is accepted too). |

