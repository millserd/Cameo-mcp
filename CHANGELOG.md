# Changelog

## Unreleased

- `java -jar cameo-mcp-stdio.jar --print-config` prints a ready-to-paste `mcpServers` block for
  this machine, with the running Java and the jar's own path filled in. Docs cover agents in
  sandboxes, VMs and WSL that cannot reach the host's `127.0.0.1`.
- Docs: an architecture graphic (`docs/how-it-works.svg`) in the README, and an agent skill
  (`skills/cameo-mcp/SKILL.md`, also in the release zip) that tells any AI agent to ask for the
  server address and port, then connect to any host and port and use the tools.
- Pure Java: the plugin no longer bundles Gson. It builds results with its own JSON classes
  (`com.cameomcp.json.tree`), so it runs on the JDK and Cameo's API alone.
- The installer is now part of the launcher jar (`java -jar launcher/cameo-mcp-stdio.jar --install`);
  the PowerShell and shell scripts are gone.
- The live-test runners use `cameo-mcp-stdio.jar` for JSON instead of the Gson jar.
- Fixed: `cameo_repair_conveyed_item_labels` now shows conveyed-item labels (it was hiding them).
- Fixed: `cameo_detect_remediation` now suggests best-match fixes (the term lists were dropped).
- Fixed: a `cameo_run_validation` TIMEOUT now names the `run_id` to fetch the result later.
- Fixed: `cameo_export_requirements_preview` honors `root_id` / `package_id` like the export.
- Port verification also reports owner ids (`duplicateFlowPropertyOwnerIds`); remediation receipts use them.
- Idle client sessions (1 hour without a request) are dropped; the client re-initializes on its next call.
- A read that collides with a running change is retried once, then fails with `MODEL_CHANGED`.
- Macro scripts are now off by default; turn them on in MCP > MCP Server Status > Settings.
- Saving settings no longer writes port, host or token values that come from environment variables or JVM options.
- The installers no longer register the server with a specific client CLI.
- CI: GitHub Actions runs `devtools/compile-check.sh all --tests` on every push and pull request.

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
