package com.cameomcp.mcp;

/** Name, version and agent instructions advertised in the MCP initialize result. */
public final class ServerInfo {

    private final String name;
    private final String version;
    private final String instructions;

    public ServerInfo(String name, String version, String instructions) {
        this.name = name;
        this.version = version;
        this.instructions = instructions;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String instructions() {
        return instructions;
    }
}
