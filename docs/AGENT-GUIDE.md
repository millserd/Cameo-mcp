# Cameo MCP: guide for AI agents

You are connected to **Cameo MCP**, an MCP server running *inside* Cameo Systems Modeler /
CATIA Magic (2024x or 2026x). Every tool acts on the live model that the user has open in
Cameo, through Cameo's own Java API. Changes appear in the user's Cameo window immediately and
can be undone there.

This guide is also served by the server as the resource `cameo://guide`. A full,
always-current parameter reference is the resource `cameo://tools` (or call `cameo_list_tools`).

## 1. Start every session the same way

1. `cameo_status` — is Cameo up, which version, is a project open, any recent errors?
   - `projectOpen: false` → ask the user to open or create a project. Model tools return
     `NO_PROJECT` until they do.
   - The call fails with `CAMEO_NOT_RUNNING` → Cameo is closed or the MCP server inside it is
     stopped. Ask the user to start Cameo and check **MCP > MCP Server Status**.
2. `cameo_get_project` — project name, file, and `primaryModelId` (the root package id).
3. Orient yourself with `cameo_list_containment_children` (start at the root, page with
   `limit`/`offset`) or search with `cameo_query_elements` (`type`, `name`, `stereotype`,
   `package_id`).

`cameo_get_capabilities` tells you which optional Cameo features exist in this installation
(Dependency Matrix, Generic Table, Relation Map, Simulation, Teamwork, Report Wizard, DataHub)
and whether macros are allowed.

## 2. Core rules

- **IDs, not names.** Every element, diagram and relationship has an `id`. Write tools take ids
  (`parent_id`, `element_id`, `source_id`, `diagram_id`). Get them from query/list/create results.
  Never invent ids.
- **One write at a time.** Each write tool is one undoable step in Cameo (Edit > Undo shows
  `MCP: <operation>`). Writes are serialized; parallel writes fail with `WRITE_IN_PROGRESS`.
  Run reads in parallel if you like, writes sequentially.
- **Build big structures in one call** with `cameo_create_elements`: a list of entries, each may
  name a `ref` that later entries use instead of an id. It is one undo step.
- **Compact first, full on demand.** List tools default to compact views and pages
  (`limit`, `offset`, `hasMore`, `nextOffset`). Use `view: "full"` or `cameo_get_element` only for
  the elements you need. Avoid `cameo_get_containment_tree` on large models; use
  `cameo_list_containment_children`.
- **Images are expensive.** Call `cameo_get_diagram_image` with `include_image: false` to get
  metadata, or set `max_width`/`max_height` and `format: "jpeg"` before asking for pixels.
  `cameo_export_diagram_image` writes straight to a file instead.
- **Preview before applying.** Tools named `*_preview`, flags like `dry_run` (default true where
  present) and gates like `allow_write` exist on purpose. Show the user the preview for anything
  large, destructive, or file-writing, then apply.
- **Save is explicit.** Changes live in memory until `cameo_save_project` (or the user saves).
  Ask before saving shared/remote (Teamwork Cloud) projects.
- **Tell the human what you are doing.** `cameo_log` writes a line into Cameo's Notification
  window for the person watching.

## 3. Element and relationship types

`cameo_create_element` `type` values (SysML ones need the SysML profile, present in Cameo
Systems Modeler):

Package, Profile, Stereotype, Class, **Block**, Property, **FlowProperty**, Port, Interface,
**InterfaceBlock**, **ConstraintBlock**, **ValueType**, DataType, Signal, Enumeration, Component,
Activity, CallBehaviorAction, OpaqueAction, ActivityPartition, InitialNode, ActivityFinalNode,
FlowFinalNode, DecisionNode, MergeNode, ForkNode, JoinNode, InputPin, OutputPin, Operation,
UseCase, Actor, StateMachine, State, Pseudostate/InitialState, **Requirement**, Comment, Constraint.

Useful options: `stereotype`, `documentation`, `type_id` (typed properties/ports),
`lower`/`upper` multiplicity (`upper: "*"`), `aggregation` (`composite` for part properties),
`direction` (FlowProperty: in/out/inout), `behavior_id` (CallBehaviorAction),
`represents_id` (ActivityPartition), `metaclasses` (Stereotype).

`cameo_create_relationship` `type` values: Association, DirectedAssociation, Composition,
Generalization, Dependency, ControlFlow, ObjectFlow, Transition, Connector, InformationFlow,
ItemFlow, Allocate, Satisfy, Derive, Refine, Trace, Verify, Include, Extend.
Connectors need `owner_id` (the block that owns the IBD) and, for nested ports,
`source_part_with_port_id`/`target_part_with_port_id`. Transitions and flows accept `guard`.
Item flows accept `conveyed_ids`, `realizing_connector_id` and `item_property_id`.

Stereotypes and tagged values: `cameo_apply_stereotype`, `cameo_remove_stereotype`,
`cameo_set_tagged_values`. Any other UML property: `cameo_get_specification` /
`cameo_set_specification`. Move elements with `cameo_move_element`.

## 4. Recipes

**Block structure (BDD).** Create a Package → Blocks (`type: Block`) → part properties
(`type: Property`, `type_id: <part block>`, `aggregation: composite`) or Composition
relationships → `cameo_create_diagram` (`type: BDD`, `parent_id: <package>`) →
`cameo_add_to_diagram` for each block → `cameo_add_diagram_paths` for the relationships →
`cameo_auto_layout` → check with `cameo_get_diagram_image` (`max_width: 1200`).

**Internal structure (IBD).** In the context block: part properties typed by blocks, Ports typed
by InterfaceBlocks with FlowProperties → `cameo_create_diagram` (`type: IBD`,
`parent_id: <context block>`) → add parts, then ports with `container_presentation_id` of their
part shape → Connector relationships (`owner_id: <context block>`) → ItemFlows for what flows.
Verify with `cameo_verify_port_boundary_consistency`.

**Requirements and traceability.** Requirements (`type: Requirement`, set `Id`/`Text` with
`cameo_set_tagged_values`) → Satisfy (design → requirement), Verify (test case → requirement),
Derive/Refine/Trace → `cameo_create_matrix` (`kind: satisfy`) → `cameo_get_matrix` →
`cameo_verify_requirement_quality` and `cameo_verify_matrix_consistency`.
Bulk exchange: `cameo_export_requirements`, `cameo_preview_requirements_import` →
`cameo_apply_requirements_import` (JSON/CSV).

**State machine.** StateMachine → InitialState + States (inside the state machine's region) →
Transitions (`type: Transition`, optional `guard`) → `cameo_set_transition_trigger`
(signal/change events) and `cameo_set_state_behaviors` (entry/do/exit) →
`cameo_create_diagram` (`type: StateMachine`) → add states → `cameo_add_diagram_paths`.

**Activity.** Activity → nodes (InitialNode, actions, Decision/Merge/Fork/Join,
ActivityFinalNode), ActivityPartitions with `represents_id` → ControlFlow/ObjectFlow
relationships → Activity diagram → `cameo_verify_activity_flow_semantics`.

**Review and quality.** `cameo_run_validation` / `cameo_run_native_validation` (Cameo's own
rule suites), the `cameo_verify_*` semantic checks, `cameo_detect_cross_diagram_inconsistencies`
(returns a non-mutating remediation plan), `cameo_proof_model_text` (spelling/wording, preview
patch plan, then `cameo_apply_proofing_patch_plan`).

**Methodology (OOSEM).** `cameo_list_methodology_packs` → `cameo_get_methodology_guidance`
(what artifact is missing next) → `cameo_execute_methodology_recipe` → `cameo_generate_review_packet`;
`cameo_export_required_diagrams` and `cameo_assemble_ppt_pdf` build a review deck/PDF.

**Diagram cleanup.** `cameo_list_diagram_shapes` (presentation ids) → `cameo_move_shapes`,
`cameo_route_paths`, `cameo_set_shape_properties`, compartment and label presets
(`cameo_set_*_presentation`, `cameo_repair_*`, `cameo_normalize_compartment_presets`),
`cameo_prune_diagram_presentations`.

## 5. Errors and recovery

Tool errors come back as a result with `isError: true` and `{code, message}`. The message says
what to do next. Common codes:

| Code | Meaning | What to do |
|---|---|---|
| `NO_PROJECT` | No project open | Ask the user to open one |
| `CAMEO_NOT_RUNNING` | (stdio launcher) Cameo unreachable | Ask the user to start Cameo / the MCP server |
| `NOT_FOUND` | Bad id | Re-query; ids change if elements are deleted/recreated |
| `INVALID_PARAMS` | Missing/invalid argument | Fix the call; read the tool description |
| `WRITE_IN_PROGRESS` | Another write is running | Wait and retry, do not parallelize writes |
| `SESSION_OPEN` | A failed macro left a session open | `cameo_reset_session`, then retry |
| `TIMEOUT` | Cameo did not answer in time | Often a modal dialog in Cameo; ask the user to look |
| `UNSUPPORTED` | Feature/plugin not in this Cameo | Use another route or tell the user |
| `MACROS_DISABLED` | User turned scripts off | Use structured tools or ask the user |
| `API_INCOMPATIBLE` | This Cameo version lacks an API | Report it; use another route |

`cameo_probe_bridge` runs a connection self-test (HTTP endpoint, UI thread responsiveness,
open sessions). `cameo_undo` / `cameo_redo` step through Cameo's undo history.

## 6. Macros (escape hatch)

`cameo_execute_macro` runs Groovy inside Cameo with `project`, `application`, `primaryModel`
and `ef` (ElementsFactory) predefined. Prefer structured tools; use macros for gaps.
Scripts that change the model must manage their own session:

```groovy
import com.nomagic.magicdraw.openapi.uml.SessionManager
SessionManager.getInstance().createSession(project, "My change")
try {
    // ... change the model ...
    SessionManager.getInstance().closeSession(project)
} catch (e) {
    SessionManager.getInstance().cancelSession(project)
    throw e
}
```

If a macro dies mid-session, call `cameo_reset_session`. The user can disable macros in
**MCP > MCP Server Status > Settings**.

## 7. Tool families

| Family | What it covers |
|---|---|
| server | status, capabilities, self-test, tool search, session reset, undo/redo, log |
| model | project, UI selection, elements, stereotypes, tagged values, profiles, relationships, specifications, state-machine semantics, bulk create, macros |
| diagrams | diagram types, create/list/rename/delete, add elements and paths, images/export, layout, shapes, labels, compartments, routing, property dumps |
| artifacts | native matrices, generic tables, relation maps, traceability graphs, snapshots and diffs, read-only probes |
| advanced | native validation, Report Wizard, requirements import/export, simulation, Teamwork Cloud, DataHub, criteria expressions, profiles, variants, safety/cyber extensions, typed diagrams |
| analysis | diagram/matrix verification, semantic checks, cross-diagram remediation plans, proofing, OOSEM methodology packs, review packets, PPTX/PDF assembly |
