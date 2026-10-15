#!/usr/bin/env bash
# Rebuild and run the working ParaDEVS baseline (Demo Deliverable) outside MS4 Me.
# Requires a JDK (verified with 17.0.15). No external jars are needed.
#
# Usage (Git Bash):  bash scripts/build_and_run.sh
# The PMF CSV is written by the model to Demo Deliverable/ParaDEVS/src/Models/data/output,
# because RunFinanceTree resolves paths relative to the working directory.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
PARADEVS="$HERE/../Demo Deliverable/ParaDEVS"
OUT="$HERE/build/classes"
LOG="$HERE/build/last_run.log"

rm -rf "$OUT" && mkdir -p "$OUT"
cd "$PARADEVS"
# Relative paths: javac @argfiles split on spaces, and "Demo Deliverable" has one.
find src -name "*.java" > "$HERE/build/sources.txt"
javac -nowarn -encoding UTF-8 -d "$OUT" @"$HERE/build/sources.txt"
echo "Compiled $(find "$OUT" -name '*.class' | wc -l) classes."

java -cp "$OUT" Models.java.RunFinanceTree > "$LOG" 2>&1
grep -E "expected value|probability mass|expected net value|Simulation Elapsed" "$LOG"
echo "Newest output: $(ls -t src/Models/data/output | head -1)"
