#!/usr/bin/env bash
# Fast compile check without Gradle, for one tool family at a time.
#   devtools/compile-check.sh <family> [--tests]
# family: server | model | diagrams | artifacts | advanced | analysis | sysml2 | all
# Compiles core + stubs + shared plugin code + the family's catalog and plugin package
# (and the optional source set for 'artifacts'), into /tmp/cameo-mcp-check-<family>.
# With --tests, also compiles that family's tests and runs them with the JUnit console.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAMILY="${1:?family required}"
LIB="$ROOT/devtools/lib"
OUT="/tmp/cameo-mcp-check-$FAMILY"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/test-classes"

declare -A CATALOG=( [server]=ServerTools [model]=ModelTools [diagrams]=DiagramTools [artifacts]=ArtifactTools [advanced]=AdvancedTools [analysis]=AnalysisTools [sysml2]=Sysml2Tools )
ALL_FAMILIES=(server model diagrams artifacts advanced analysis sysml2)

srcs=()
while IFS= read -r f; do srcs+=("$f"); done < <(find "$ROOT/stubs/src/main/java" -name '*.java')
while IFS= read -r f; do
  base="$(basename "$f" .java)"
  skip=0
  for fam in "${ALL_FAMILIES[@]}"; do
    if [[ "$base" == "${CATALOG[$fam]}" && "$FAMILY" != "all" && "$fam" != "$FAMILY" && "$fam" != server ]]; then skip=1; fi
  done
  [[ $skip == 0 ]] && srcs+=("$f")
done < <(find "$ROOT/core/src/main/java" -name '*.java')
while IFS= read -r f; do
  rel="${f#$ROOT/plugin/src/main/java/com/cameomcp/plugin/}"
  if [[ "$rel" == tools/*/* ]]; then
    fam="${rel#tools/}"; fam="${fam%%/*}"
    if [[ "$FAMILY" != "all" && "$fam" != "$FAMILY" && "$fam" != server ]]; then continue; fi
  fi
  srcs+=("$f")
done < <(find "$ROOT/plugin/src/main/java" -name '*.java')
if [[ "$FAMILY" == "artifacts" || "$FAMILY" == "all" ]] && [[ -d "$ROOT/plugin/src/optional/java" ]]; then
  while IFS= read -r f; do srcs+=("$f"); done < <(find "$ROOT/plugin/src/optional/java" -name '*.java')
fi

echo "Compiling ${#srcs[@]} files for family '$FAMILY'..."
if ! javac --release 17 -nowarn -encoding UTF-8 -cp "$LIB/gson-2.10.1.jar" -d "$OUT/classes" "${srcs[@]}" > "$OUT/javac.log" 2>&1; then
  grep -v '^Picked up JAVA_TOOL_OPTIONS' "$OUT/javac.log" | head -150
  echo "COMPILE FAILED"; exit 1
fi
echo "COMPILE OK"

if [[ "${2:-}" == "--tests" ]]; then
  tsrcs=()
  for dir in "$ROOT/core/src/test/java" "$ROOT/plugin/src/test/java"; do
    [[ -d "$dir" ]] || continue
    while IFS= read -r f; do
      if [[ "$FAMILY" == "all" ]] || grep -q "family: *$FAMILY\b\|/tools/$FAMILY/" <<<"$(head -5 "$f"; echo "$f")"; then tsrcs+=("$f"); fi
    done < <(find "$dir" -name '*.java')
  done
  if [[ ${#tsrcs[@]} -eq 0 ]]; then echo "No tests for $FAMILY"; exit 0; fi
  CP="$OUT/classes:$LIB/gson-2.10.1.jar:$LIB/junit-platform-console-standalone-1.10.3.jar"
  javac --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT/test-classes" "${tsrcs[@]}" 2>&1 | grep -v '^Picked up' || true
  cp -r "$ROOT/core/src/test/resources/." "$OUT/test-classes/" 2>/dev/null || true
  cp -r "$ROOT/plugin/src/test/resources/." "$OUT/test-classes/" 2>/dev/null || true
  java -jar "$LIB/junit-platform-console-standalone-1.10.3.jar" execute -cp "$OUT/test-classes:$CP" --scan-classpath "$OUT/test-classes" --disable-banner --details=summary 2>&1 | grep -v '^Picked up' | tail -40
fi
