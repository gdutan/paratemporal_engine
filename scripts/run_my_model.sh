#!/usr/bin/env bash
# Compile your own model sources together with the ParaDEVS library and run a main class.
#
# Usage (Git Bash):
#   bash scripts/run_my_model.sh <your-src-dir> <main.Class> [--ms4me]
#
#   <your-src-dir>  folder holding your .java files (any package layout)
#   <main.Class>    fully qualified class with main(), e.g. mymodels.RunMyModel
#   --ms4me         compile against the MS4 Me jars instead of the bundled MS4 core
#                   (needed when sources import SimViewer / SimulationOptionsImpl, as generated
#                   MS4 Me code does). MS4ME_PLUGINS overrides the plugin folder.
set -euo pipefail
[ $# -lt 2 ] && { sed -n 2,12p "$0"; exit 1; }

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$(cd "$1" && pwd)"; MAIN="$2"; MODE="${3:-}"
OUT="$HERE/build/user"
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

rm -rf "$OUT" && mkdir -p "$OUT/classes"
{
  if [ "$MODE" != "--ms4me" ]; then find "$HERE/../Demo Deliverable/ParaDEVS/src/com" -name "*.java"; fi
  find "$HERE/src/main/java" "$SRC" -name "*.java"
} | while read -r f; do printf '"%s"\n' "$(win "$f")"; done > "$OUT/sources.txt"

CP="$(win "$OUT/classes")"
JCP=""
if [ "$MODE" = "--ms4me" ]; then
  PLUGINS="${MS4ME_PLUGINS:-C:/Program Files/MS4 Systems/MS4 Me/plugins}"
  JCP="$PLUGINS/*"; CP="$CP;$PLUGINS/*"
fi
if ! javac -nowarn -encoding UTF-8 ${JCP:+-cp "$JCP"} -d "$(win "$OUT/classes")" @"$(win "$OUT/sources.txt")" > "$OUT/javac.log" 2>&1; then
  grep -v '^Note:' "$OUT/javac.log"; echo "Build failed"; exit 1
fi
java -cp "$CP" "$MAIN"
