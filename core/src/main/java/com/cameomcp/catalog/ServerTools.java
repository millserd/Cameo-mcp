package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.util.Arrays;
import java.util.List;

import static com.cameomcp.mcp.ToolSpec.REQUIRED;
import static com.cameomcp.mcp.ToolSpec.tool;

/** Connection health, capability discovery and recovery tools. */
public final class ServerTools {

    // Family name used in docs and in cameo_list_tools filters.
    public static final String FAMILY = "server";

    private ServerTools() {
    }

    /** The server family's specs, in the order clients see them. */
    public static List<ToolSpec> specs() {
        return Arrays.asList(
                tool("cameo_status", FAMILY).noProject().readOnly()
                        .desc("Check that Cameo and the MCP plugin are up. Returns plugin version, Cameo product/version, "
                                + "endpoint and port, connected clients, whether a project is open, and recent errors. "
                                + "Call this first in every session."),
                tool("cameo_get_capabilities", FAMILY).noProject().readOnly()
                        .desc("Machine-readable capability report: tool families and counts, which optional Cameo "
                                + "plugins (Dependency Matrix, Generic Table, Relation Map, Simulation, Teamwork, Report Wizard, "
                                + "DataHub) are present, whether macros are allowed, and supported MCP protocol versions.")
                        .bool("include_tools", "Also list every tool name grouped by family.", false),
                tool("cameo_probe_bridge", FAMILY).noProject().readOnly()
                        .desc("Self-test of the MCP connection from inside Cameo: checks the HTTP endpoint answers, the "
                                + "/status health path, the bound port and host, the Swing event thread responsiveness and "
                                + "the editing-session state. Use when calls hang or fail unexpectedly."),
                tool("cameo_list_tools", FAMILY).noProject().readOnly()
                        .desc("Search this server's tool catalog by family and/or keyword. Returns name, family, kind "
                                + "(read/write/destructive) and the first sentence of each description.")
                        .str("family", "Family filter, e.g. server, model, diagrams, artifacts, advanced, analysis.")
                        .str("query", "Case-insensitive keyword matched against tool names and descriptions."),
                tool("cameo_reset_session", FAMILY).destructive()
                        .desc("Recovery: cancel (roll back) a Cameo editing session left open by a failed macro or "
                                + "interrupted write, so new writes can proceed. Partial changes from that session are discarded."),
                tool("cameo_undo", FAMILY).write()
                        .desc("Undo the most recent model change in Cameo (same as Edit > Undo). Each MCP write tool "
                                + "is one undoable step.")
                        .integer("steps", "How many steps to undo (1-50).", 1, 1, 50),
                tool("cameo_redo", FAMILY).write()
                        .desc("Redo the most recently undone model change (same as Edit > Redo).")
                        .integer("steps", "How many steps to redo (1-50).", 1, 1, 50),
                tool("cameo_log", FAMILY).noProject().sideEffect()
                        .desc("Write a message to Cameo's Notification window and log file, so the human watching "
                                + "Cameo can follow what the agent is doing.")
                        .str("message", "Text to show.", REQUIRED)
                        .enumStrDefault("level", "Severity.", "info", "info", "warn", "error"));
    }
}
