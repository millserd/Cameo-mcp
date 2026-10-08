package com.cameomcp.mcp;

/**
 * Hook run around every tool call. The Cameo plugin uses it to refuse calls that need
 * an open project, to enforce the macro switch, and to apply time limits.
 */
public interface CallGuard {

    /** Throw {@link ToolException} to refuse the call. */
    default void before(ToolSpec spec, Args args) {
    }

    /** Run the handler; override to add timeouts or thread hand-off. */
    default Object invoke(ToolSpec spec, ToolHandler handler, Args args, ToolContext ctx) throws Exception {
        return handler.call(args, ctx);
    }
}
