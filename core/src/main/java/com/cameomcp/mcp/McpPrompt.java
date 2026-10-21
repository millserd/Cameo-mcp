package com.cameomcp.mcp;

import com.cameomcp.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A reusable MCP prompt template (a ready-made modeling workflow). */
public final class McpPrompt {

    /** Unique prompt name, used by prompts/get. */
    private final String name;
    /** Short display title. */
    private final String title;
    /** One-paragraph description shown to the user. */
    private final String description;
    /** Argument declarations (name, description, required), in display order. */
    private final List<Map<String, Object>> arguments = new ArrayList<>();
    /** Builds the prompt text from the supplied arguments. */
    private final Function<Map<String, Object>, String> template;

    /**
     * Creates a prompt with no arguments; add them with {@link #arg}.
     *
     * @param name unique prompt name
     * @param title short display title
     * @param description what the workflow does
     * @param template turns the arguments the client supplied (missing optional ones are absent)
     *                 into the prompt text returned by prompts/get
     *
     */
    public McpPrompt(String name, String title, String description, Function<Map<String, Object>, String> template) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.template = template;
    }

    /**
     * Declares one argument, in the order clients should show them. Returns this for chaining.
     *
     * @param argName argument name
     * @param desc what the argument means
     * @param required whether prompts/get must reject a request without it
     * @return this prompt
     */
    public McpPrompt arg(String argName, String desc, boolean required) {
        arguments.add(Json.obj("name", argName, "description", desc, "required", required));
        return this;
    }

    /**
     * Returns the unique prompt name.
     *
     * @return the unique prompt name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the live list of argument declarations, as JSON-shaped maps.
     *
     * @return the live list of argument declarations, as JSON-shaped maps
     */
    public List<Map<String, Object>> arguments() {
        return arguments;
    }

    /**
     * Fills the template with the client's arguments.
     *
     * @param args the client's arguments; required ones have been checked by the server
     * @return the prompt text
     */
    public String render(Map<String, Object> args) {
        return template.apply(args);
    }

    /**
     * The entry this prompt contributes to a prompts/list response.
     *
     * @return the prompts/list entry
     */
    public Map<String, Object> toMcp() {
        return Json.obj("name", name, "title", title, "description", description, "arguments", arguments);
    }
}
