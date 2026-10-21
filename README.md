# Cameo MCP

An [MCP](https://modelcontextprotocol.io/) server that lets AI agents work inside
**Cameo Systems Modeler / CATIA Magic** (2024x and 2026x): query, create, modify, validate,
diagram, trace and review SysML/UML models in the Cameo instance you have open.

Everything is Java and ships as one package. The Cameo plugin hosts the MCP server itself and
calls Cameo's Java API directly, in Cameo's own JVM: no Python, no scripts, no helper process and
no third-party libraries. The only thing it needs is the Java that comes with Cameo.

![How Cameo MCP works: MCP clients reach the plugin inside Cameo over HTTP, directly or through the stdio launcher; the plugin runs each tool on Cameo's UI thread through the Cameo Java API](docs/how-it-works.svg)

> **If you are an AI agent:** read [AGENTS.md](AGENTS.md), then follow
> [skills/cameo-mcp/SKILL.md](skills/cameo-mcp/SKILL.md). Both are also in the installed plugin folder.

## What you get

- **183 tools** in seven families: server (status, diagnostics, undo/redo), model (elements,
  stereotypes, relationships, specifications, state-machine semantics, bulk create, optional script macros),
  diagrams (create, populate, layout, images, labels, compartments, routing), artifacts (native
  dependency matrices, generic tables, relation maps, traceability graphs, snapshots), advanced
  (native validation, Report Wizard, requirements import/export, simulation, Teamwork Cloud,
  DataHub, criteria, profiles, variants, typed diagrams) and analysis (semantic verification,
  remediation plans, proofing, OOSEM methodology, review packets, PPTX/PDF assembly) and sysml2
  (SysML v2 projects in 2026x: browse the model, export and import SysML v2 textual notation).
  Feature parity with [ajhcs/cameo-mcp-bridge](https://github.com/ajhcs/cameo-mcp-bridge), plus
  diagram delete/rename, file export, element move, stereotype removal, bulk creation, undo/redo.
- **An MCP menu in Cameo** with a status window: server state, endpoint and port, connected
  clients, every tool call, a one-click connection self-test, settings (port, bind address,
  token, macros, logging) and copy-paste client configurations.
- **Status in Cameo's log**: server start/stop, client connections and tool calls go to the
  Notification window and to `md.log`, prefixed `[Cameo MCP]`.
- **Safe defaults**: localhost-only binding, browser-origin checks, optional bearer token, every
  change is one undoable step, writes are serialized, preview-first tools for risky operations,
  and a switch to turn script execution off.

## Requirements

- Cameo Systems Modeler or another CATIA Magic / MagicDraw-based product, **2024x or 2026x**
  (SysML features need the SysML plugin, which Cameo Systems Modeler includes).
- Nothing else. The plugin runs on Cameo's own Java; the stdio launcher can use it too.
- To build from source: JDK 17+ and Gradle 8 (optional; release zips are prebuilt).

## Install

1. Download or build `cameo-mcp-<version>.zip` and unzip it.
2. Close Cameo, then run the installer from the unzipped folder. It is part of the launcher jar,
   so it needs only Java (Cameo's bundled Java works, e.g. `"<Cameo>\jre\bin\java"`):

   ```
   java -jar launcher/cameo-mcp-stdio.jar --install
   ```

   It finds your Cameo installation (or pass `--cameo-home "<Cameo install folder>"`, repeatable;
   `--all` takes every one it finds) and copies `plugin/com.cameomcp.plugin` into
   `<Cameo>/plugins/`. Run it as administrator (Windows) or with sudo if Cameo is in a
   protected folder. Manual install is the same copy: put the folder `plugin/com.cameomcp.plugin`
   into `<Cameo install>/plugins/`.

   **From inside Cameo instead:** take `cameo-mcp-<version>-rdzip.zip` (prebuilt next to the release
   zip, or build it with `gradle :plugin:rdzip`, which writes `build/dist/`), then in Cameo open
   **Help > Resource/Plugin Manager**, click **Import**, pick that zip and restart Cameo when asked.

   Either way you end up with this folder:

   ```
   <Cameo install>/plugins/com.cameomcp.plugin/
     plugin.xml                 tells Cameo to load the plugin
     cameo-mcp-plugin.jar       the MCP server, menu and status window
     cameo-mcp-optional.jar     tools for optional Cameo plugins (matrices, tables, relation maps)
     cameo-mcp-stdio.jar        stdio launcher and installer
     AGENTS.md                  start here, for AI agents: points at the skill
     README.md                  this file
     skills/cameo-mcp/          the agent skill: SKILL.md, AGENT-GUIDE.md, TOOLS.md
     docs/how-it-works.svg      the graphic above
   ```

   If the MCP menu does not appear, check that `plugin.xml` sits directly in that folder and not
   in a nested `com.cameomcp.plugin/com.cameomcp.plugin/` folder.
3. Start Cameo. The **MCP** menu appears in the main menu bar and the Notification window shows
   `[Cameo MCP] MCP server started. Endpoint http://127.0.0.1:18740/mcp ...`.
4. Open **MCP > MCP Server Status** and press **Test connection**. Every step should say PASS.

Uninstall: `java -jar launcher/cameo-mcp-stdio.jar --uninstall`, or delete
`<Cameo>/plugins/com.cameomcp.plugin`. Settings live in `~/.cameo-mcp/`.

## Connect an AI agent

1. In Cameo open **MCP > MCP Server Status** and press **Copy for AI agent** (or use the menu item
   **MCP > Copy Connection for AI Agent**). It copies the endpoint (e.g. `http://127.0.0.1:18740/mcp`),
   the token if one is set, and where the agent instructions are.
2. Paste that into your agent's chat. An agent that does not know Cameo MCP yet should be pointed at
   the plugin folder or given [skills/cameo-mcp/SKILL.md](skills/cameo-mcp/SKILL.md); agents opened in
   the plugin folder find it through `AGENTS.md`. Agents with skill support can also copy the
   `skills/cameo-mcp` folder into their skills folder.
3. The agent asks for the endpoint if you have not given it, then calls tools. It needs nothing but
   HTTP: one `POST` of a JSON-RPC `tools/call` to the endpoint, no handshake. Agents that only have
   a shell with Java can use `java -jar cameo-mcp-stdio.jar --url <endpoint> --call <tool> key=value`.

### Register Cameo MCP in an MCP client (optional, permanent)

The **Connect a client** tab of the status window shows these with your real port, token and paths.

**Clients with Streamable HTTP support** (Cursor, VS Code, Windsurf, ...):

```json
{ "mcpServers": { "cameo": { "type": "http", "url": "http://127.0.0.1:18740/mcp" } } }
```

**stdio-only clients** (desktop chat apps): copy the **stdio** block from that tab, or run
`"<java>" -jar "<plugin folder>\cameo-mcp-stdio.jar" --print-config` and paste what it prints. Use
this also when the agent's shell runs in a sandbox, VM, container or WSL: there `127.0.0.1` is not
the Cameo PC, so the client must start the launcher on the Cameo PC itself. The launcher answers
`tools/list` while Cameo is closed and reconnects when Cameo starts.
`java -jar cameo-mcp-stdio.jar --diagnose` checks the connection from a terminal.

## Configuration

Set in **MCP > MCP Server Status > Settings** (saved to `~/.cameo-mcp/settings.properties`):

| Setting | Default | Notes |
|---|---|---|
| Port | `18740` | Overridden by `-Dcameo.mcp.port=N` in Cameo's `.vmoptions`, then by env `CAMEO_MCP_PORT` |
| Bind address | `127.0.0.1` | `0.0.0.0` exposes Cameo to the network; only with a token |
| Bearer token | off | Clients send `Authorization: Bearer <token>`; launcher: `--token` or `CAMEO_MCP_TOKEN` |
| Auto-start | on | Start the server when Cameo starts |
| Allow macro scripts | off | Gates `cameo_execute_macro` and `cameo_execute_probe` |
| Notification window | errors | Which tool calls are echoed to the Notification window (`off`, `errors`, `all`); md.log gets all |

The launcher accepts `--port`, `--host`, `--url`, `--token`, `--timeout` and the matching
`CAMEO_MCP_*` environment variables.

## Endpoints

| Path | Purpose |
|---|---|
| `POST /mcp` | MCP Streamable HTTP (JSON responses; protocol 2025-06-18, 2025-03-26, 2024-11-05) |
| `DELETE /mcp` | End an MCP session |
| `GET /status` | Health and status JSON (also `/health`) |

## Troubleshooting

| Symptom | Fix |
|---|---|
| No MCP menu in Cameo | The folder must be `<Cameo>/plugins/com.cameomcp.plugin` containing `plugin.xml`. Check md.log for `[Cameo MCP]`. |
| "Port 18740 is already in use" | Another Cameo or program holds the port. Pick another port in Settings and update your client. |
| Client cannot connect | Status window > Test connection. Check the client uses the same port and, if set, the token. |
| Tool calls time out | Cameo is busy or showing a modal dialog. Look at the Cameo window. |
| `SESSION_OPEN` errors | A script left an editing session open: call `cameo_reset_session`. |
| `NO_PROJECT` | Open a project in Cameo. |

## Build from source

```sh
gradle test dist                                  # compiles against bundled API stubs
gradle test dist -PcameoHome="/path/to/Cameo"     # compiles against your Cameo install
gradle deploy -PcameoHome="/path/to/Cameo"        # copy the plugin into that Cameo
```

The release zip is `build/dist/cameo-mcp-<version>.zip`. See [docs/DEVELOPING.md](docs/DEVELOPING.md)
for the code layout and how to add tools.

## Security

The server can read and change your model and, with macros on, run arbitrary code inside Cameo.
Macros are off by default: on a machine shared by several users, anyone logged in can reach
127.0.0.1, so turn them on only on your own machine or together with a token.
Keep the default `127.0.0.1` binding; requests from non-local browser origins are rejected.
If you bind to another address, set a token. Leave macros off if your agents do not need them.

## License and credits

MIT, see [LICENSE](LICENSE). The tool set and much of the Cameo API usage are adapted from
[cameo-mcp-bridge](https://github.com/ajhcs/cameo-mcp-bridge) by Cole Lyons (MIT); see [NOTICE](NOTICE).
