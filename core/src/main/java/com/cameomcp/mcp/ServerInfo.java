package com.cameomcp.mcp;

/** Name, version and agent instructions advertised in the MCP initialize result. */
public final class ServerInfo {

    /** Server name reported in serverInfo.name. */
    private final String name;
    /** Server version reported in serverInfo.version. */
    private final String version;
    /** Instructions for the agent, or null to omit them. */
    private final String instructions;

    /**
     * Creates the server identity.
     *
     * @param name server name, for example "cameo-mcp"
     * @param version the release version
     * @param instructions text clients may add to the agent's system prompt; the plugin passes
     *                     {@code Catalog.INSTRUCTIONS}, a short "how to start" summary
     */
    public ServerInfo(String name, String version, String instructions) {
        this.name = name;
        this.version = version;
        this.instructions = instructions;
    }

    /**
     * Returns the server name.
     *
     * @return the server name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the server version.
     *
     * @return the server version
     */
    public String version() {
        return version;
    }

    /**
     * Returns the agent instructions, or null.
     *
     * @return the agent instructions, or null
     */
    public String instructions() {
        return instructions;
    }
}
