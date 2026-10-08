# Developing Cameo MCP

## Layout

```
core/     Pure Java, no Cameo: JSON (com.cameomcp.json), MCP protocol + HTTP transport
          (com.cameomcp.mcp), and the tool catalog (com.cameomcp.catalog). Unit-testable anywhere.
plugin/   The Cameo plugin. src/main/java: runtime, UI, Cameo helpers, tool implementations.
          src/optional/java: code that needs APIs of optional Cameo plugins (Dependency Matrix,
          Generic Table, Relation Map); shipped as cameo-mcp-optional.jar and loaded through
          OptionalModules so the plugin still loads when those plugins are missing.
stdio/    cameo-mcp-stdio.jar: stdio <-> HTTP launcher for stdio-only MCP clients.
stubs/    Compile-only stand-ins for the Cameo OpenAPI, verified against the 2024x and 2026x
          javadoc. Never packaged. See stubs/README.md and stubs/API-DIFFERENCES.md.
devtools/ compile-check.sh and jars for fast checks without Gradle.
docs/     AGENT-GUIDE.md (served to agents as cameo://guide), this file.
install/  Installers for Windows / macOS / Linux.
```

## How a tool is wired

1. **Spec** (the public contract) in `core/src/main/java/com/cameomcp/catalog/<Family>Tools.java`,
   built with the `ToolSpec` DSL and returned from `public static List<ToolSpec> specs()`.
2. **Binding** in `plugin/src/main/java/com/cameomcp/plugin/tools/<family>/<Family>Bindings.java`,
   `public static void bind(ToolRegistry r)` binds each spec name to a handler.
3. **Implementation**: services in the same package (or `src/optional/java/com/cameomcp/optional/...`
   for optional-plugin APIs, reached via `OptionalModules.call(serviceClass, op, params)`).

Families: `server`, `model`, `diagrams`, `artifacts`, `advanced`, `analysis`, `sysml2`
(`Catalog.FAMILIES`, `ToolBindings.BINDERS`). Families never import each other's packages.
A composite tool that needs another tool's result calls it through
`Internal.call("cameo_other_tool", args)` - the same public contract an MCP client sees.

## Contract rules

- Tool names, parameter names (snake_case), defaults and result field names follow the
  reference bridge (github.com/ajhcs/cameo-mcp-bridge) so agents and prompts written for it
  keep working. `Args` also accepts the camelCase spelling of any parameter.
- Descriptions are the agent's manual: say what the tool does, valid values, an example where
  useful, common mistakes, and which tool to call before/after. One paragraph, plain text.
- Mark every spec with its kind: `readOnly()`, `write()`, `destructive()` (removes data), or
  `sideEffect()` (files/UI, not the model). `noProject()` for tools that work without a project.
- Errors: throw `ToolException(code, message)` with an actionable message
  ("Element 'x' not found. Use cameo_query_elements to find valid IDs."). Codes in use:
  INVALID_PARAMS, NOT_FOUND, NO_PROJECT, UNSUPPORTED, WRITE_IN_PROGRESS, SESSION_OPEN, TIMEOUT,
  NOT_IMPLEMENTED, PRECONDITION_FAILED, CONFLICT, TOOL_FAILED.
- Results are JSON objects. Keep them compact by default and page large lists
  (limit/offset/hasMore/nextOffset).

## Threading

- `Edt.read(project -> ...)` for model reads (runs on the calling HTTP thread).
- `Edt.write("Create element", project -> ...)` for model changes: runs on the Swing EDT inside a
  SessionManager session (one undo step, cancelled on exception). Never open sessions yourself
  except inside macros.
- `Edt.onEdt("Export image", project -> ...)` for UI/presentation work without a session.

## Cameo 2024x and 2026x

- Compile against the stubs (default) or a real install (`-PcameoHome=...`).
- Check `stubs/API-DIFFERENCES.md` before using an API. If something differs between releases,
  call it reflectively or guard it with `catch (LinkageError e)` and degrade with UNSUPPORTED.
- Need an API member that is not stubbed yet? Verify its exact signature in the javadoc for both
  releases (https://jdocs.nomagic.com/2024x/... and /2026x/...), then add it to the existing stub
  file with a minimal edit (other people edit stubs too; never rewrite a whole stub file).

## Checks

```
devtools/compile-check.sh <family>            # compile core + stubs + shared + that family
devtools/compile-check.sh <family> --tests    # ...and run its tests (JUnit 5)
devtools/compile-check.sh all --tests         # everything
gradle test dist                              # full build: build/dist/cameo-mcp-<version>.zip
```

Tests live in `core/src/test/java` or `plugin/src/test/java/com/cameomcp/plugin/tools/<family>/`.
Logic that does not touch Cameo (analysis, parsing, planning) must have unit tests.
To check a build inside a running Cameo, use the scenarios in `devtools/livetest` (see its README);
`gradle dist -PcameoHome=<Cameo install>` compiles against that install's real jars first.
