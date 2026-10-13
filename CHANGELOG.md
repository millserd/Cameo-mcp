# Changelog

## 1.0.0

First release.

- All-Java MCP server hosted inside the Cameo plugin (Streamable HTTP at `/mcp`), plus a
  dependency-free stdio launcher with an offline catalog and automatic reconnect.
- MCP menu and status window in Cameo: state, endpoint, port, clients, activity, connection
  self-test, settings and client configuration snippets. Status published to the Cameo log.
- Tool set at parity with cameo-mcp-bridge 2.3.5, ported to Java, plus diagram delete/rename,
  diagram export to file, element move, stereotype removal, bulk creation, undo/redo and agent log.
- Supports Cameo / CATIA Magic 2024x and 2026x; optional-plugin APIs load lazily so a missing
  plugin disables only the tools that need it.
