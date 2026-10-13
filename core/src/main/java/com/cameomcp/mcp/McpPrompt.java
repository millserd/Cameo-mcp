package com.cameomcp.mcp;

import com.cameomcp.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A reusable MCP prompt template (a ready-made modeling workflow). */
public final class McpPrompt {

    private final String name;
    private final String title;
    private final String description;
    private final List<Map<String, Object>> arguments = new ArrayList<>();
    private final Function<Map<String, Object>, String> template;

    /**
     * @param template turns the arguments the client supplied (missing optional ones are absent)
     *                 into the prompt text returned by prompts/get
     */
    public McpPrompt(String name, String title, String description, Function<Map<String, Object>, String> template) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.template = template;
    }

    /** Declares one argument, in the order clients should show them. Returns this for chaining. */
    public McpPrompt arg(String argName, String desc, boolean required) {
        arguments.add(Json.obj("name", argName, "description", desc, "required", required));
        return this;
    }

    public String name() {
        return name;
    }

    public List<Map<String, Object>> arguments() {
        return arguments;
    }

    /** Fills the template with the client's arguments. */
    public String render(Map<String, Object> args) {
        return template.apply(args);
    }

    /** The entry this prompt contributes to a prompts/list response. */
    public Map<String, Object> toMcp() {
        return Json.obj("name", name, "title", title, "description", description, "arguments", arguments);
    }
}
