package com.cameomcp.catalog;

import com.cameomcp.mcp.McpPrompt;
import com.cameomcp.mcp.McpServer;

import java.util.Map;

/** Ready-made modeling workflows offered as MCP prompts. */
public final class CatalogPrompts {

    private CatalogPrompts() {
    }

    private static String arg(Map<String, Object> args, String name, String dflt) {
        Object v = args.get(name);
        return v == null || String.valueOf(v).trim().isEmpty() ? dflt : String.valueOf(v);
    }

    public static void register(McpServer server) {
        server.addPrompt(new McpPrompt("model_system_structure", "Model a system structure",
                "Build a SysML block hierarchy and BDD for a system from a description.",
                a -> "Using the Cameo MCP tools, model the structure of this system in the open Cameo project:\n\n"
                        + arg(a, "description", "(describe the system)") + "\n\n"
                        + "Steps: cameo_status and cameo_get_project; create a package named '"
                        + arg(a, "package", "System Structure") + "' under the root model; create the blocks and "
                        + "composite part properties in one cameo_create_elements call; create a BDD, add the blocks and "
                        + "relationships, run cameo_auto_layout, then check the result with cameo_get_diagram_image "
                        + "(max_width 1200). Summarize what you created with element ids.")
                .arg("description", "What the system is and its main parts", true)
                .arg("package", "Package name to create", false));

        server.addPrompt(new McpPrompt("trace_requirements", "Trace requirements",
                "Create or import requirements and connect them to the design with satisfy/verify links.",
                a -> "Using the Cameo MCP tools, establish requirements traceability in the open project.\n\n"
                        + "Scope: " + arg(a, "scope", "the whole model") + "\n\n"
                        + "Find requirements (cameo_query_elements type Requirement) and design elements, propose "
                        + "Satisfy/Verify/Derive relationships and show me the list before creating them. Then create "
                        + "them, build a satisfy matrix with cameo_create_matrix, and report coverage gaps using "
                        + "cameo_verify_requirement_quality and cameo_verify_matrix_consistency.")
                .arg("scope", "Package name or id to limit the work to", false));

        server.addPrompt(new McpPrompt("review_model_quality", "Review model quality",
                "Run validation, semantic checks and proofing and produce a prioritized findings list.",
                a -> "Review the quality of " + arg(a, "scope", "the open Cameo model") + " using the Cameo MCP tools: "
                        + "cameo_run_validation, the cameo_verify_* semantic checks that apply, "
                        + "cameo_detect_cross_diagram_inconsistencies and cameo_proof_model_text (preview only). "
                        + "Do not change the model. Report findings grouped by severity with element ids and the "
                        + "fix you recommend for each.")
                .arg("scope", "Package/diagram name or id", false));

        server.addPrompt(new McpPrompt("build_state_machine", "Build a state machine",
                "Create a state machine with states, transitions, triggers and behaviors plus its diagram.",
                a -> "Using the Cameo MCP tools, create a state machine for " + arg(a, "subject", "(subject)")
                        + " in the open project.\n\nBehavior to capture:\n" + arg(a, "behavior", "(states and events)")
                        + "\n\nCreate the StateMachine, initial pseudostate, states and transitions; set triggers with "
                        + "cameo_set_transition_trigger and entry/do/exit with cameo_set_state_behaviors; create the "
                        + "state machine diagram, add states and transitions, lay it out and show me the image.")
                .arg("subject", "Block or system the state machine belongs to", true)
                .arg("behavior", "States, events and actions in plain language", true));
    }
}
