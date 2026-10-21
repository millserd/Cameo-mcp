---
name: cameo-mcp
description: Connect to the Cameo MCP server running inside Cameo Systems Modeler / CATIA Magic and use its tools to read, create, change, diagram, validate and review SysML / UML models. Use whenever the user asks a question about, or a change to, a model open in Cameo. Always ask the user for the server address and port before connecting.
---

# Cameo MCP

Cameo MCP is an MCP server that runs **inside** Cameo Systems Modeler / CATIA Magic (2024x and
2026x). It works on the live model the user has open, through Cameo's own Java API. Every change
shows up in the user's Cameo window right away and can be undone there.

![How Cameo MCP works](../../docs/how-it-works.svg)

## When to use this skill

Use it whenever answering the user needs the model in Cameo, for example:

- "What blocks are in the power subsystem?" / "Which requirements have no satisfy link?"
- "Add a block definition diagram for ..." / "Create requirements from this list."
- "Validate the model", "export the diagrams", "build a review packet".

## Step 1: ask for the address and port, every time

Before your **first Cameo MCP call for a model question**, ask the user where the server is.
Do not silently assume the default, and do not reuse an address from another conversation.
Ask in one short message, for example:

> To reach your Cameo model I need the Cameo MCP server address. What host and port should I
> use? The default is `127.0.0.1` port `18740` (shown in Cameo under **MCP > MCP Server Status**).
> If you set a bearer token there, please give me that too.

Rules:

- Ask once per conversation. Ask again if the connection fails, if the user mentions another
  machine or Cameo instance, or if the user asks you to switch.
- Accept any of these answers: a host and a port (`10.0.0.12`, `18741`), a `host:port` pair, or a
  full URL (`http://cameo-box:18740/mcp`). Build the endpoint as `http://<host>:<port>/mcp`. If
  the user says "default", use `http://127.0.0.1:18740/mcp`.
- A token is optional. Use it only if the user gives one or the server says `authRequired: true`.
  Never write a token into files the user did not ask you to change, and never echo it back.
- If the user's question does not need the model (general SysML advice, for example), do not ask.

## Step 2: check the server is up

Call the health endpoint. It needs no token and exposes no model data:

```
GET http://<host>:<port>/status
```

Read these fields from the JSON:

| Field | Meaning |
|---|---|
| `running` | The MCP server inside Cameo is listening |
| `endpoint` | The MCP URL the server reports for itself |
| `authRequired` | `true` means every `/mcp` request needs `Authorization: Bearer <token>` |
| `cameo.project` | The open project (`name`, `file`, `dirty`), or `null` if none is open |
| `toolCount`, `version` | Server version and number of tools |

If it fails:

| Symptom | Tell the user |
|---|---|
| Connection refused / timeout | Cameo is not running, the server is stopped, or the port is different. Check **MCP > MCP Server Status** in Cameo (Start Server, Test connection). |
| Works on the Cameo PC but not from another machine | The server binds to `127.0.0.1` by default. In **MCP > MCP Server Status > Settings** set Bind address to `0.0.0.0` (or the PC's address) **and** set a token, then restart the server. Or use an SSH tunnel: `ssh -L 18740:127.0.0.1:18740 user@cameo-pc`, then connect to `127.0.0.1:18740`. Firewalls must allow the port. |
| Connection refused from your shell, but Cameo shows the server running | Your shell probably runs in a sandbox, VM, container or WSL on the Cameo PC, where `127.0.0.1` is the sandbox itself, not Windows. Best fix: have the user register the stdio launcher (option B) in the MCP client's own config on the Cameo PC, so the client starts it on Windows with Cameo's `java.exe`. From WSL2 alone: turn on mirrored networking (`networkingMode=mirrored` under `[wsl2]` in `%UserProfile%\.wslconfig`, then `wsl --shutdown`), or run the launcher through Windows Java (`/mnt/c/.../jre/bin/java.exe -jar 'C:\...\cameo-mcp-stdio.jar'`). Do not ask the user to bind to `0.0.0.0` just for this. |
| `401` from `/mcp` | Missing or wrong token. Ask the user for the token shown in the status window. |
| `403 Origin not allowed` | You sent a browser `Origin` header from a non-local page. Send requests without an `Origin` header. |
| `cameo.project` is `null` | Ask the user to open or create a project in Cameo. |

## Step 3: connect

Pick the first option your environment supports.

### A. Your client can add an MCP server over HTTP (preferred)

Add a server named `cameo` with transport Streamable HTTP (often written `"type": "http"`):

```json
{
  "mcpServers": {
    "cameo": {
      "type": "http",
      "url": "http://<host>:<port>/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Leave out `headers` when there is no token. Some clients need a restart or reload to pick up a
new server. The tools then appear with names like `cameo_status`.

### B. Your client only starts local stdio servers

Run the bundled launcher with Java 11 or newer: Cameo's own `jre/bin/java` if the install has one, otherwise the Java named by `JAVA_HOME` in `<Cameo install>/bin/<product>.properties` (no-install builds have no `jre` folder). The jar is in the plugin
folder: `<Cameo install>/plugins/com.cameomcp.plugin/cameo-mcp-stdio.jar`.

```json
{
  "mcpServers": {
    "cameo": {
      "command": "<Java home>/bin/java",
      "args": ["-jar", "<Cameo install>/plugins/com.cameomcp.plugin/cameo-mcp-stdio.jar",
               "--host", "<host>", "--port", "<port>"],
      "env": { "CAMEO_MCP_TOKEN": "<token>" }
    }
  }
}
```

`--url http://<host>:<port>/mcp` can replace `--host`/`--port`. `--token` or the env
`CAMEO_MCP_TOKEN` passes a token. The same settings are read from `CAMEO_MCP_HOST`,
`CAMEO_MCP_PORT` and `CAMEO_MCP_URL`. `java -jar cameo-mcp-stdio.jar --diagnose --host <host> --port <port>`
checks the connection from a terminal.

### C. You can only make HTTP requests (no MCP client)

Speak MCP's JSON-RPC 2.0 directly. Every request is a `POST` to `http://<host>:<port>/mcp` with
`Content-Type: application/json`, `Accept: application/json, text/event-stream` and, if needed,
`Authorization: Bearer <token>`. Responses are plain JSON.

1. Initialize and keep the `Mcp-Session-Id` response header:

   ```json
   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"my-agent","version":"1.0"}}}
   ```

2. Send `{"jsonrpc":"2.0","method":"notifications/initialized"}` with header `Mcp-Session-Id: <id>`.
   Send that header on every later request.
3. List tools: `{"jsonrpc":"2.0","id":2,"method":"tools/list"}`.
4. Call a tool:

   ```json
   {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"cameo_status","arguments":{}}}
   ```

   The answer is in `result.content[0].text` (JSON text). `result.isError: true` means a tool
   error with a `code` and a `message` that says what to do.
5. A `404` means the session expired (idle more than an hour): initialize again.

Example with curl:

```sh
URL=http://127.0.0.1:18740/mcp
curl -s -D - -X POST "$URL" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"agent","version":"1"}}}'
# copy the Mcp-Session-Id header from the output, then:
curl -s -X POST "$URL" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"cameo_get_project","arguments":{}}}'
```

## Step 4: use the tools

Start every session the same way:

1. `cameo_status`: Cameo version, whether a project is open, recent errors.
2. `cameo_get_project`: project name, `projectKind` and `primaryModelId` (the root package id).
   If `projectKind` is `sysml2`, use only the `cameo_sysml2_*` tools.
3. Orient with `cameo_list_containment_children` (start at the root, page with `limit`/`offset`)
   or search with `cameo_query_elements` (`type`, `name`, `stereotype`, `package_id`).

Core rules:

- **Ids, not names.** Write tools take ids from earlier results. Never invent ids.
- **One write at a time.** Each write is one undo step in Cameo (`MCP: <operation>`). Reads can
  run in parallel; writes must not (`WRITE_IN_PROGRESS`).
- **Build big structures in one call** with `cameo_create_elements` (entries can reference each
  other by `ref`).
- **Compact first.** List tools return pages; ask for `view: "full"` or `cameo_get_element` only
  where needed. Request diagram images small (`max_width`, `format: "jpeg"`) or not at all.
- **Preview before applying.** Use `*_preview` tools and `dry_run` flags; show the user previews
  of large, destructive or file-writing changes first.
- **Saving is explicit.** Nothing is saved until `cameo_save_project`. Ask before saving,
  and always before saving a Teamwork Cloud project.
- **Keep the user informed.** `cameo_log` writes a line into Cameo's Notification window.

Find the right tool with `cameo_list_tools` (or the resource `cameo://tools`). Families: server,
model, diagrams, artifacts, advanced, analysis, sysml2.

Common errors: `NO_PROJECT` (ask the user to open one), `NOT_FOUND` (re-query the id),
`INVALID_PARAMS` (fix the arguments), `TIMEOUT` (a dialog is probably open in Cameo, ask the
user to look), `SESSION_OPEN` (call `cameo_reset_session`), `MACROS_DISABLED` (use structured
tools; scripts are off by default), `SYSML2_PROJECT` / `NOT_SYSML2_PROJECT` (wrong tool family).

## More detail

- [docs/AGENT-GUIDE.md](../../docs/AGENT-GUIDE.md): full agent guide with element and
  relationship types, modeling recipes (BDD, IBD, requirements, state machines, activities,
  review), macros and SysML v2. Also served by the server as the resource `cameo://guide`;
  read it once connected.
- [docs/TOOLS.md](../../docs/TOOLS.md): every tool with its parameters.
