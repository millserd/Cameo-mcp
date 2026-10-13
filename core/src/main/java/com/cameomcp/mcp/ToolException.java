package com.cameomcp.mcp;

import java.util.Map;

/**
 * A tool failure with a stable machine-readable code and an actionable message.
 * <p>
 * The message is what the agent reads, so say what went wrong and what to do next
 * ("Element 'x' not found. Use cameo_query_elements to find valid IDs.").
 */
public class ToolException extends RuntimeException {

    // Stable code such as NOT_FOUND or INVALID_PARAMS; agents and tests branch on it, so never rename one.
    private final String code;
    // Optional extra fields (candidate IDs, limits, ...) merged into the error result.
    private final Map<String, Object> details;

    public ToolException(String code, String message) {
        this(code, message, null, null);
    }

    public ToolException(String code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ToolException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    // Common factories -------------------------------------------------------

    /** Bad or missing arguments. The message should name the argument and what is expected. */
    public static ToolException invalid(String message) {
        return new ToolException("INVALID_PARAMS", message);
    }

    /** An element, diagram or other object with the given id does not exist. */
    public static ToolException notFound(String what, String id) {
        return new ToolException("NOT_FOUND", what + " '" + id + "' not found.");
    }

    /** The tool needs an open project and Cameo has none. */
    public static ToolException noProject() {
        return new ToolException("NO_PROJECT",
                "No project is open in Cameo. Open or create a project, then retry.");
    }

    /** The feature is missing in this Cameo edition or version (for example no Teamwork Cloud). */
    public static ToolException unsupported(String message) {
        return new ToolException("UNSUPPORTED", message);
    }
}
