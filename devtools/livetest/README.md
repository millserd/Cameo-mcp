# Live test against a running Cameo

Scenario scripts that call the MCP server inside a running Cameo and check the results. Use them
after installing the plugin into a real Cameo 2024x or 2026x.

| File | What it does |
|---|---|
| `LiveTest.java` | Runs a scenario over HTTP (`http://127.0.0.1:18740/mcp`) and writes `results*.txt` and `results*.json`. Single-file Java program; needs only Gson. |
| `scenario.json` | UML / SysML v1 scenario: elements, relationships, diagrams, matrices, tables, relation maps, validation, reports, macros, undo/redo. Open a SysML v1 project first. |
| `scenario-sysml2.json` | SysML v2 scenario (Cameo 2026x): validate text, create from text, query, export text, rename, undo/redo, delete, error cases. Open a SysML v2 project first. It removes what it creates. |
| `run-live-test.bat`, `run-sysml2-test.bat` | Windows runners. |

## Running

1. Copy this folder into the Cameo install folder as `cameo-mcp-livetest`, next to `plugins`
   (the runners use `..\plugins\com.cameomcp.plugin\lib\gson-2.10.1.jar`).
2. Start Cameo with the plugin installed and open the right kind of project. Turn on
   MCP > MCP Server Status > Settings > Allow macro scripts (off by default), or the macro steps fail.
3. Run `run-live-test.bat` (SysML v1) or `run-sysml2-test.bat` (SysML v2), or directly:

   ```
   java -cp <path>/gson-2.10.1.jar LiveTest.java <this folder> [scenario-file]
   ```

`scenario.json` writes `results.txt`/`results.json`; `scenario-NAME.json` writes
`results-NAME.txt`/`results-NAME.json`. The first line of the text file is the summary.

## Scenario format

A JSON array of steps:

```json
{"name": "create_from_text", "tool": "cameo_sysml2_create_from_text",
 "args": {"text": "package P { part def A; }"},
 "expect": {"created": true},
 "expectContains": {"elements.0.name": "P"},
 "save": {"pkg": "elements.0.id"}}
```

- `args` may use `${var}` for values saved by earlier steps; a step whose variable is missing is skipped.
- `save` stores a value from the result (dotted path, falling back to the first matching key anywhere).
- A step passes when the tool's `isError` equals `expectError` (default false) and every `expect`
  (path to exact value) and `expectContains` (path to substring) check holds. `allowError: true`
  accepts either outcome.

## Results so far

- Cameo 2026x HF2 (Magic Systems of Systems Architect), SysML v2 project: 39/39 steps passed
  (2026-10-08). The current SysML v2 scenario has 38 steps; a cleanup step for that machine was removed.
- SysML v1 scenario on 2026x: not run yet. Cameo 2024x: not run yet.
