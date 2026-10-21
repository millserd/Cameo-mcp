package com.cameomcp.mcp;

import java.util.Map;

/**
 * A tool failure with a stable machine-readable code and an actionable message.
 * <p>
 * The message is what the agent reads, so say what went wrong and what to do next
 * ("Element 'x' not found. Use cameo_query_elements to find valid IDs.").
 */
public class ToolException extends RuntimeException {

    /**
     * Stable code such as NOT_FOUND or INVALID_PARAMS; agents and tests branch on it, so never rename one.
     */
    private final String code;
    /**
     * Optional extra fields (candidate IDs, limits, ...) added to the error result as "details"; may be null.
     */
    private final Map<String, Object> details;

    /**
     * Creates a failure without details.
     *
     * @param code stable error code, for example NOT_FOUND
     * @param message actionable message for the agent
     */
    public ToolException(String code, String message) {
        this(code, message, null, null);
    }

    /**
     * Creates a failure with details.
     *
     * @param code stable error code
     * @param message actionable message for the agent
     * @param details extra fields for the error result, or null
     */
    public ToolException(String code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    /**
     * Creates a failure with details and a cause.
     *
     * @param code stable error code
     * @param message actionable message for the agent
     * @param details extra fields for the error result, or null
     * @param cause the underlying exception, kept for logs; not sent to the client
     */
    public ToolException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    /**
     * Returns the stable error code.
     *
     * @return the stable error code
     */
    public String code() {
        return code;
    }

    /**
     * Returns the extra fields, or null.
     *
     * @return the extra fields, or null
     */
    public Map<String, Object> details() {
        return details;
    }

    // Common factories -------------------------------------------------------

    /**
     * Bad or missing arguments. The message should name the argument and what is expected.
     *
     * @param message what is wrong and what is expected
     * @return an INVALID_PARAMS failure
     */
    public static ToolException invalid(String message) {
        return new ToolException("INVALID_PARAMS", message);
    }

    /**
     * An element, diagram or other object with the given id does not exist.
     *
     * @param what the kind of object, for example "Element"
     * @param id the id that was looked up
     * @return a NOT_FOUND failure with the message "What 'id' not found."
     */
    public static ToolException notFound(String what, String id) {
        return new ToolException("NOT_FOUND", what + " '" + id + "' not found.");
    }

    /**
     * The tool needs an open project and Cameo has none.
     *
     * @return a NO_PROJECT failure telling the user to open a project
     */
    public static ToolException noProject() {
        return new ToolException("NO_PROJECT",
                "No project is open in Cameo. Open or create a project, then retry.");
    }

    /**
     * The feature is missing in this Cameo edition or version (for example no Teamwork Cloud).
     *
     * @param message what is missing and, if possible, an alternative
     * @return an UNSUPPORTED failure
     */
    public static ToolException unsupported(String message) {
        return new ToolException("UNSUPPORTED", message);
    }
}
