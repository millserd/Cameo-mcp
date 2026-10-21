---
name: cameo-mcp
description: Read and change the SysML / UML model open in Cameo Systems Modeler / CATIA Magic / MagicDraw through the Cameo MCP server. Use whenever the user asks about or wants a change to their Cameo model. First ask for the MCP endpoint URL from Cameo's MCP Server Status window.
---

# Cameo MCP

Cameo MCP is an MCP server running **inside** Cameo (2024x, 2026x). Its tools act on the model
the user has open. Every change appears in Cameo right away and can be undone there.

Follow these steps in order. Do not skip step 1, and do not guess another address.

## Step 1: ask for the endpoint

Unless the user already gave it in this conversation, ask exactly this:

> Please open **MCP > MCP Server Status** in Cameo and paste the **MCP endpoint** shown there
> (it looks like `http://127.0.0.1:18740/mcp`). The **Copy for AI agent** button copies everything I need.

- Use the URL exactly as given. If the user gives only `host:port` or says "default", the URL is
  `http://<host>:<port>/mcp` (default `http://127.0.0.1:18740/mcp`).
- If the user also gives a **token**, send it on every request as `Authorization: Bearer <token>`.
  Never repeat the token back or write it into files.

Below, `URL` means that endpoint.

## Step 2: call a tool

Use the first option that works for you. All three give the same results.

**A. You already have tools named `cameo_status`, `cameo_get_project`, ...** Your MCP client is
connected. Call them directly and skip to step 3.

**B. You can send HTTP requests** (curl, PowerShell, Python, a fetch tool). One `POST` per call.
No handshake and no session header are needed:

```sh
curl -s -X POST URL -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"cameo_status","arguments":{}}}'
```

```powershell
$body = @{ jsonrpc = '2.0'; id = 1; method = 'tools/call'
           params = @{ name = 'cameo_query_elements'; arguments = @{ type = 'Block'; limit = 20 } } } |
        ConvertTo-Json -Depth 50
$r = Invoke-RestMethod -Uri 'URL' -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($body))
$r.result.content[0].text
```

- Put the tool name in `params.name` and its arguments in `params.arguments`.
- The answer is JSON text in `result.content[0].text`. If `result.isError` is `true`, that text
  has a `code` and a `message` that says what to do.
- In Windows `cmd`, inline JSON breaks on quotes: write the body to a file and use `curl.exe -d @body.json`.
- List all tools with `{"jsonrpc":"2.0","id":1,"method":"tools/list"}`.

**C. You have a shell with Java but HTTP is awkward.** The plugin folder holds
`cameo-mcp-stdio.jar` (next to this `skills` folder). Use the Java path the user pasted, or `java`:

```sh
java -jar cameo-mcp-stdio.jar --url URL --call cameo_status
java -jar cameo-mcp-stdio.jar --url URL --call cameo_query_elements type=Block limit=20
java -jar cameo-mcp-stdio.jar --url URL --call cameo_create_elements @request.json
java -jar cameo-mcp-stdio.jar --list            # every tool, works while Cameo is closed
java -jar cameo-mcp-stdio.jar --describe cameo_query_elements
```

Arguments are `key=value` (values are read as JSON when possible), one JSON object, `@file.json`
or `-` for stdin. Add `--token <token>` if there is one. Output is JSON; exit code 0 means success.

## Step 3: check the connection

Call `cameo_status`, then `cameo_get_project`.

| What you see | What to do |
|---|---|
| `cameo_status` works, `projectOpen: false` | Ask the user to open a project in Cameo. |
| `cameo_get_project` says `projectKind: "sysml2"` | Use only the `cameo_sysml2_*` tools. |
| Connection refused or timeout | Ask the user to check that the status window says **Running** and that the URL matches. If it does and you still cannot connect, your shell runs somewhere else (sandbox, VM, container, WSL, another PC) and cannot reach that address. Stop retrying and tell the user: in the status window, open **Connect a client**, copy the **stdio** config and add it to your MCP client's settings, then restart the client (option A). |
| HTTP `401` | A token is set. Ask the user for the token shown in the status window **Settings** tab. |
| HTTP `404` | You sent an old `Mcp-Session-Id` header. Leave the header out. |
| HTTP `403` | Do not send an `Origin` header. |

## Step 4: work with the model

- **Ids, not names.** Find elements with `cameo_query_elements` (`type`, `name`, `stereotype`,
  `package_id`) or `cameo_list_containment_children` (start at `primaryModelId`). Write tools take
  those ids. Never invent ids.
- **One write at a time.** Each write is one undo step in Cameo. Reads may run in parallel.
- **Build big structures in one call** with `cameo_create_elements` (entries reference each other by `ref`).
- **Page and stay compact.** Use `limit`/`offset`; ask for full detail only where needed.
  Request diagram images small (`max_width`, `format: "jpeg"`) or not at all.
- **Preview first.** Use `*_preview` tools and `dry_run` before large or destructive changes.
- **Saving is explicit.** Nothing is saved until `cameo_save_project`. Ask the user first.

Common error codes: `NO_PROJECT` (ask the user to open one), `NOT_FOUND` (query the id again),
`INVALID_PARAMS` (fix the arguments; `cameo_list_tools` or `--describe` shows the schema),
`TIMEOUT` (a dialog is probably open in Cameo), `SESSION_OPEN` (call `cameo_reset_session`),
`MACROS_DISABLED` (use the structured tools).

## More detail, in this folder

- [AGENT-GUIDE.md](AGENT-GUIDE.md): element and relationship types, recipes (BDD, IBD,
  requirements, state machines, activities, reviews), SysML v2. Read it before modeling work.
- [TOOLS.md](TOOLS.md): every tool and its parameters.
