#!/usr/bin/env bash
# Compiles the production-tested reference plugin (cameo-mcp-bridge) against the
# compile-only stubs in ../src/main/java. Zero errors means every Cameo API the
# reference uses is present in the stubs with a compatible signature.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STUBS="$HERE/../src/main/java"
SCRATCH=/tmp/claude-0/-home-claude/9ff1fdcb-1a22-5473-b04d-2881b3c0f74b/scratchpad
REF="${REF_SRC:-$SCRATCH/ref/cameo-mcp-bridge/plugin/src}"
GSON="${GSON_JAR:-$SCRATCH/libs/gson-2.10.1.jar}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
find "$STUBS" -name '*.java' > "$OUT/sources.txt"
find "$REF/com" -name '*.java' >> "$OUT/sources.txt"
javac --release 17 -nowarn -Xmaxerrs 10000 -d "$OUT/classes" -cp "$GSON" @"$OUT/sources.txt"
echo "Reference compile OK: $(grep -c "$STUBS" "$OUT/sources.txt") stub sources, $(grep -vc "$STUBS" "$OUT/sources.txt") reference sources, 0 errors"
