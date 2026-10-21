package com.cameomcp.mcp;

/**
 * Hook run around every tool call. The Cameo plugin uses it to refuse calls that need
 * an open project, to enforce the macro switch, and to apply time limits.
 */
public interface CallGuard {

    /**
     * Throw {@link ToolException} to refuse the call.
     *
     * @param spec the tool being called
     * @param args the call's arguments
     * @throws ToolException to refuse the call; the message is sent to the client as a tool error
     */
    default void before(ToolSpec spec, Args args) {
    }

    /**
     * Run the handler; override to add timeouts or thread hand-off.
     *
     * @param spec the tool being called
     * @param handler the tool's implementation
     * @param args the call's arguments
     * @param ctx the call's context
     * @return the handler's result
     * @throws Exception anything the handler throws; the server turns it into a tool error
     */
    default Object invoke(ToolSpec spec, ToolHandler handler, Args args, ToolContext ctx) throws Exception {
        return handler.call(args, ctx);
    }
}
