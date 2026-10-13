# Cameo OpenAPI stubs (compile-only)

Hand-written stand-ins for the Cameo Systems Modeler / MagicDraw OpenAPI classes this project
calls. They let the plugin compile where no Cameo installation exists (CI, cloud dev).

- **Never packaged.** Use the module as `compileOnly`. At runtime the real classes come from
  Cameo. Every method body throws `UnsupportedOperationException("stub")`.
- Each file starts with `// Compile-only stub of the Cameo OpenAPI. Not packaged.`
- Only members that are actually used are stubbed. If you need a new one, add just that member.
- Target: Cameo 2024x, and it must also run on 2026x. Read `API-DIFFERENCES.md` before you use
  anything in its sections 1 or 5.

## Signatures must match the real API

A wrong erased return or parameter type compiles fine here but fails in Cameo with
`NoSuchMethodError`. Before you add or change a member:

1. Open `https://jdocs.nomagic.com/2024x/<package/path>/<Class>.html` and copy the exact
   declaration: class / interface / abstract / final, superclass, static or instance, return type,
   parameter types and checked exceptions.
2. Open the same path under `/2026x/` and add any difference to `API-DIFFERENCES.md`.
3. If neither version documents it (for example, plugin-jar APIs like `dependencymatrix.MatrixManager`
   or relationship-map settings), infer it from production code and add `// UNVERIFIED` to the line.
4. Copy a constant's value only if you verified it. Otherwise make the field non-constant
   (for example `= Integer.valueOf(0)`) so that callers read the real value at runtime instead of
   inlining a guess.

Some things trip people up:

- UML enums such as `AggregationKindEnum` are final classes, not Java enums, and their constants are
  typed as the `*Kind` interface.
- `ElementsFactory` is a class.
- `AMConfigurator` lives in `com.nomagic.actions`.
- `MainFrame` is final.

## Reference check

`reference-check/compile-reference.sh` compiles the production-tested reference plugin
(cameo-mcp-bridge, built against real 2024x) against these stubs with `javac --release 17`. It
should report 0 errors:

```
REF_SRC=/path/to/cameo-mcp-bridge/plugin/src stubs/reference-check/compile-reference.sh
# Reference compile OK: <n> stub sources, 41 reference sources, 0 errors
```

To point it at other copies, set `REF_SRC` (the reference `plugin/src` directory) and `GSON_JAR`.
