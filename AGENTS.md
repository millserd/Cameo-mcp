# Instructions for AI agents

This folder is **Cameo MCP**: an MCP server that runs inside Cameo Systems Modeler / CATIA Magic
and lets you read and change the SysML / UML model the user has open.

Whenever the user asks about Cameo, MagicDraw, CATIA Magic or their SysML / UML model, read and
follow **[skills/cameo-mcp/SKILL.md](skills/cameo-mcp/SKILL.md)** before doing anything else.
In short: ask the user for the MCP endpoint shown in Cameo under **MCP > MCP Server Status**
(it looks like `http://127.0.0.1:18740/mcp`), then call tools on it as the skill shows.

Working on the Cameo MCP source code instead? See `docs/DEVELOPING.md` in the source repository.
