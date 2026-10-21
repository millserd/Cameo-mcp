package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.util.Arrays;
import java.util.List;

import static com.cameomcp.mcp.ToolSpec.REQUIRED;
import static com.cameomcp.mcp.ToolSpec.tool;

/**
 * SysML v2 projects (Cameo 2026x with the SysML v2 plugin): browse the KerML model, export it as
 * SysML v2 textual notation, and create model content from textual notation.
 */
public final class Sysml2Tools {

    /** Family name used in docs and in cameo_list_tools filters. */
    public static final String FAMILY = "sysml2";

    /**
     * Help text for element id parameters. SysML v2 elements have both a Cameo id and a SysML v2
     * elementId (UUID); the tools accept either.
     */
    private static final String ID_HELP = "Element id from cameo_sysml2_list_roots, cameo_sysml2_list_children or "
            + "cameo_sysml2_query (the 'id' field; the SysML v2 'elementId' UUID is accepted too).";

    /** Static methods only. */
    private Sysml2Tools() {
    }

    /**
     * The family's specs, in the order clients see them. Descriptions and parameter help are the
     * text agents read when choosing a tool, so they say what the tool returns and which tool to call next.
     *
     * @return the specs, a fixed-size list built on every call
     */
    public static List<ToolSpec> specs() {
        return Arrays.asList(
                tool("cameo_sysml2_status", FAMILY).noProject().readOnly()
                        .desc("Check SysML v2 support: whether this Cameo has the SysML v2 plugin (2026x and later), "
                                + "whether the open project is a SysML v2 project (projectKind 'sysml2'), how many root "
                                + "namespaces it has, and which SysML v2 operations (model access, text export, text "
                                + "import) are available. Call this before the other cameo_sysml2_* tools."),
                tool("cameo_sysml2_list_roots", FAMILY).readOnly()
                        .desc("List the root namespaces of the open SysML v2 project with their top-level members "
                                + "(packages, part definitions, ...): id, elementId, name, type (SysML v2 metaclass such "
                                + "as Package, PartDefinition, PartUsage), qualifiedName and childCount. This is the "
                                + "SysML v2 counterpart of cameo_get_project's primaryModelId.")
                        .bool("include_libraries", "Also list the standard library roots (large).", false)
                        .integer("members_limit", "Top-level members listed per root (0-500).", 100, 0, 500),
                tool("cameo_sysml2_list_children", FAMILY).readOnly()
                        .desc("List the elements a SysML v2 element owns (its members), paged, with id, elementId, "
                                + "name, type, qualifiedName and childCount. Walk the model tree with it, starting from "
                                + "the ids cameo_sysml2_list_roots returns.")
                        .str("element_id", ID_HELP, REQUIRED)
                        .integer("limit", "Page size (1-1000).", 200, 1, 1000)
                        .integer("offset", "Index of the first child to return.", 0, 0, Integer.MAX_VALUE),
                tool("cameo_sysml2_get_element", FAMILY).readOnly()
                        .desc("Get one SysML v2 element: id, elementId, name, declaredName, shortName, type, "
                                + "humanType, qualifiedName, owner, documentation, isLibraryElement, and its first "
                                + "owned members. With include_text=true also returns the element as SysML v2 textual "
                                + "notation.")
                        .str("element_id", ID_HELP, REQUIRED)
                        .bool("include_text", "Also return the element's SysML v2 textual notation.", false)
                        .integer("members_limit", "Owned members listed (0-500).", 50, 0, 500),
                tool("cameo_sysml2_query", FAMILY).readOnly()
                        .desc("Search the SysML v2 model by name and/or metaclass, depth-first from the project roots "
                                + "or from scope_id. type matches the SysML v2 metaclass name case-insensitively (for "
                                + "example PartDefinition, PartUsage, RequirementDefinition, RequirementUsage, "
                                + "AttributeUsage, PortDefinition, ActionDefinition, Package). name is a "
                                + "case-insensitive substring. Paged with limit/offset; hasMore tells you to page.")
                        .str("name", "Case-insensitive substring of the element name.")
                        .str("type", "SysML v2 metaclass name, e.g. PartDefinition or PartUsage.")
                        .str("scope_id", "Only search under this element. " + ID_HELP)
                        .bool("include_libraries", "Also search the standard libraries.", false)
                        .integer("limit", "Page size (1-1000).", 100, 1, 1000)
                        .integer("offset", "Index of the first match to return.", 0, 0, Integer.MAX_VALUE),
                tool("cameo_sysml2_get_text", FAMILY).readOnly().timeout(180)
                        .desc("Export SysML v2 textual notation generated by Cameo, for one element (and everything "
                                + "it owns) or, without element_id, for every root namespace of the project. Use it to "
                                + "read a whole SysML v2 model at once, review changes, or round-trip text through "
                                + "cameo_sysml2_validate_text and cameo_sysml2_create_from_text.")
                        .str("element_id", "Element to export. Omit to export the whole project. " + ID_HELP)
                        .integer("max_chars", "Truncate the text after this many characters (1000-2000000).", 200000,
                                1000, 2000000),
                tool("cameo_sysml2_validate_text", FAMILY).readOnly().timeout(180)
                        .desc("Parse SysML v2 textual notation with Cameo's SysML v2 compiler without changing the "
                                + "model. Returns valid, diagnostics (severity ERROR / WARNING / INFO / HINT, line, "
                                + "column, message) and the top-level elements the text declares. Names resolve "
                                + "against the open project and the standard libraries, so the text may reference "
                                + "existing elements and library types (for example ScalarValues::Real). Run it before "
                                + "cameo_sysml2_create_from_text.")
                        .str("text", "SysML v2 textual notation, e.g. package Vehicle { part def Engine; }", REQUIRED),
                tool("cameo_sysml2_create_from_text", FAMILY).write().timeout(300)
                        .desc("Create model content from SysML v2 textual notation: Cameo compiles the text and adds "
                                + "it to the open SysML v2 project as a new root namespace, as one undoable step (like "
                                + "importing a .sysml file). Text with ERROR diagnostics is rejected and nothing "
                                + "changes. Returns the new root's id and the created top-level elements with their "
                                + "ids. To change existing content, export it with cameo_sysml2_get_text, edit the text, "
                                + "delete the old element with cameo_sysml2_delete_element and create it again.")
                        .str("text", "SysML v2 textual notation to add.", REQUIRED)
                        .bool("allow_warnings", "Create even when the compiler reports warnings.", true),
                tool("cameo_sysml2_rename_element", FAMILY).write()
                        .desc("Rename a SysML v2 element (sets its declared name) as one undoable step. Returns the "
                                + "element with its new name and qualifiedName.")
                        .str("element_id", ID_HELP, REQUIRED)
                        .str("name", "New declared name.", REQUIRED),
                tool("cameo_sysml2_delete_element", FAMILY).destructive()
                        .desc("Delete a SysML v2 element and everything it owns, as one undoable step "
                                + "(cameo_undo restores it). Library elements and read-only elements cannot be deleted. "
                                + "Deleting a root namespace removes everything created from one text.")
                        .str("element_id", ID_HELP, REQUIRED));
    }
}
