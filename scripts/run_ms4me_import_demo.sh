#!/usr/bin/env bash
# Compile and run an MS4 Me-generated model (JobProcessorExample) against the MS4 Me jars and
# the ParaDEVS library. Generated MS4 Me code imports SimViewer and SimulationOptionsImpl, which
# are only in the MS4 Me jars, not in the core bundled with Demo Deliverable.
#
# Usage (Git Bash):  bash scripts/run_ms4me_import_demo.sh
#   MS4ME_PLUGINS can override the default plugin folder.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
PLUGINS="${MS4ME_PLUGINS:-C:/Program Files/MS4 Systems/MS4 Me/plugins}"
OUT="$HERE/build/ms4me_import_demo"
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

rm -rf "$OUT" && mkdir -p "$OUT/classes"
find "$HERE/src/main/java" "$HERE/src/ms4me_import_demo/java" -name "*.java" \
  | while read -r f; do printf '"%s"\n' "$(win "$f")"; done > "$OUT/sources.txt"
CP="$PLUGINS/*"
if ! javac -nowarn -encoding UTF-8 -cp "$CP" -d "$(win "$OUT/classes")" @"$(win "$OUT/sources.txt")" > "$OUT/javac.log" 2>&1; then
  grep -v '^Note:' "$OUT/javac.log"; echo "Build failed"; exit 1
fi
java -cp "$(win "$OUT/classes");$CP" demo.RunJobShop 2>&1 | grep -v '^\[' | grep -v '^\s*$'
