#!/usr/bin/env bash
# Package the MS4 core sources bundled in Demo Deliverable as lib/ms4-core.jar, so that VS Code
# (and anything else) can use the core without reaching outside this folder.
# Re-run only if the MS4 core sources change.
#
# Usage (Git Bash):  bash scripts/package_ms4_core.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MS4_SRC="$HERE/../Demo Deliverable/ParaDEVS/src/com"
OUT="$HERE/build/ms4-core"
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

mkdir -p "$OUT" "$HERE/lib"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
find "$MS4_SRC" -name "*.java" | while read -r f; do printf '"%s"\n' "$(win "$f")"; done > "$OUT/sources.txt"
if ! javac -nowarn -encoding UTF-8 -d "$(win "$OUT/classes")" @"$(win "$OUT/sources.txt")" > "$OUT/javac.log" 2>&1; then
  grep -v '^Note:' "$OUT/javac.log"; echo "MS4 core build failed"; exit 1
fi
# Keep non-Java resources (icons, text templates) that the core loads at run time.
(cd "$MS4_SRC/.." && find com -type f ! -name "*.java" ! -path "*/.svn/*" | while read -r r; do
  mkdir -p "$OUT/classes/$(dirname "$r")"; cp "$r" "$OUT/classes/$r"; done)
jar --create --file "$(win "$HERE/lib/ms4-core.jar")" -C "$(win "$OUT/classes")" .
echo "Wrote lib/ms4-core.jar ($(find "$OUT/classes" -name '*.class' | wc -l) classes)"
