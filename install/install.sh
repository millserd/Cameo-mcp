#!/bin/sh
# Installs (or removes) the Cameo MCP plugin into Cameo Systems Modeler / CATIA Magic 2024x or 2026x.
#   sh install/install.sh [--cameo-home DIR]... [--all] [--uninstall] [--no-claude] [--port N]
set -eu
PLUGIN_ID=com.cameomcp.plugin
PKG_ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$PKG_ROOT/plugin/$PLUGIN_ID"
HOMES=""
ALL=0
UNINSTALL=0
NO_CLAUDE=0
PORT=18740

while [ $# -gt 0 ]; do
  case "$1" in
    --cameo-home) HOMES="$HOMES
$2"; shift 2 ;;
    --all) ALL=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    --no-claude) NO_CLAUDE=1; shift ;;
    --port) PORT=$2; shift 2 ;;
    -h|--help) sed -n '2,3p' "$0"; exit 0 ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
done

is_cameo() { [ -d "$1/plugins" ] && [ -d "$1/lib" ]; }

find_homes() {
  for root in /Applications "$HOME/Applications" /opt /usr/local "$HOME"; do
    [ -d "$root" ] || continue
    for d in "$root"/*; do
      case "$(basename "$d")" in
        *Cameo*|*CATIA*Magic*|*Magic*Systems*|*MagicDraw*|*Magic*Cyber*|*Magic*Software*|*cameo*|*magicdraw*)
          if is_cameo "$d"; then echo "$d"; fi
          for s in "$d"/* "$d"/Contents/Resources "$d"/Contents/Java; do
            [ -d "$s" ] && is_cameo "$s" && echo "$s"
          done ;;
      esac
    done
  done | sort -u
}

if [ "$UNINSTALL" -eq 0 ] && [ ! -f "$SRC/plugin.xml" ]; then
  echo "Plugin files not found at $SRC. Run this from the unzipped cameo-mcp package." >&2; exit 1
fi

HOMES=$(printf '%s\n' "$HOMES" | sed '/^$/d')
if [ -z "$HOMES" ]; then
  FOUND=$(find_homes)
  COUNT=$(printf '%s\n' "$FOUND" | sed '/^$/d' | wc -l | tr -d ' ')
  if [ "$COUNT" -eq 0 ]; then
    echo "No Cameo installation found. Pass --cameo-home <folder containing plugins and lib>." >&2; exit 1
  elif [ "$COUNT" -eq 1 ] || [ "$ALL" -eq 1 ]; then
    HOMES=$FOUND
  else
    echo "Several Cameo installations were found:"
    printf '%s\n' "$FOUND" | awk '{print "  [" NR "] " $0}'
    printf 'Install into which one? Number, or A for all: '
    read -r ANSWER
    case "$ANSWER" in
      A|a) HOMES=$FOUND ;;
      *) HOMES=$(printf '%s\n' "$FOUND" | sed -n "${ANSWER}p") ;;
    esac
  fi
fi

while IFS= read -r CAMEO; do
  [ -n "$CAMEO" ] || continue
  if ! is_cameo "$CAMEO"; then echo "Skipping $CAMEO: not a Cameo installation (no plugins/ and lib/)." >&2; continue; fi
  TARGET="$CAMEO/plugins/$PLUGIN_ID"
  if [ ! -w "$CAMEO/plugins" ]; then
    echo "No write access to $CAMEO/plugins. Re-run with sudo." >&2; exit 1
  fi
  rm -rf "$TARGET"
  if [ "$UNINSTALL" -eq 1 ]; then
    echo "Removed Cameo MCP from $CAMEO"
  else
    cp -R "$SRC" "$TARGET"
    echo "Installed Cameo MCP into $TARGET"
  fi
done <<EOF_HOMES
$HOMES
EOF_HOMES

URL="http://127.0.0.1:$PORT/mcp"
if [ "$NO_CLAUDE" -eq 0 ] && command -v claude >/dev/null 2>&1; then
  if [ "$UNINSTALL" -eq 1 ]; then
    claude mcp remove --scope user cameo >/dev/null 2>&1 || true
  elif claude mcp add --scope user --transport http cameo "$URL" >/dev/null 2>&1; then
    echo "Registered with Claude Code as 'cameo' -> $URL"
  else
    echo "Claude Code already has a 'cameo' server or registration failed; check with: claude mcp list"
  fi
fi

if [ "$UNINSTALL" -eq 1 ]; then
  echo "Done. Settings remain in ~/.cameo-mcp (delete that folder to remove them)."
  exit 0
fi
echo
echo "Next steps:"
echo "  1. Start Cameo. An MCP menu appears; the Notification window shows \"[Cameo MCP] MCP server started\"."
echo "  2. MCP > MCP Server Status > Test connection."
echo "  3. Point your MCP client at $URL (see the 'Connect a client' tab for ready-made configs)."
