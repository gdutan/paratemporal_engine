#!/usr/bin/env bash
# Build the ParaDEVS library and its examples against the MS4 core, then run the acceptance tests.
# The core comes from the sources bundled in ../Demo Deliverable when that folder exists, and
# otherwise from lib/ms4-core.jar (e.g. in a fresh clone of the repository).
#
# Usage (Git Bash):  bash scripts/build_library.sh            # build and run every Verify*
#                    bash scripts/build_library.sh reward     # build and run one test
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MS4_SRC="$HERE/../Demo Deliverable/ParaDEVS/src/com"
MS4_JAR="$HERE/lib/ms4-core.jar"
OUT="$HERE/build/library"
mkdir -p "$OUT"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"

# javac @argfiles split on spaces, so quote each path ("Demo Deliverable" has one).
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
CORE_CP=""
if [ -d "$MS4_SRC" ]; then
  CORE_SOURCES="$MS4_SRC"
else
  [ -f "$MS4_JAR" ] || { echo "Neither $MS4_SRC nor $MS4_JAR found"; exit 1; }
  CORE_SOURCES=""; CORE_CP="$(win "$MS4_JAR")"
  echo "MS4 core: $MS4_JAR"
fi
{
  [ -n "$CORE_SOURCES" ] && find "$CORE_SOURCES" -name "*.java"
  find "$HERE/src/main/java" "$HERE/src/examples/java" "$HERE/src/sandbox/java" -name "*.java"
} | while read -r f; do printf '"%s"\n' "$(win "$f")"; done > "$OUT/sources.txt"
RUN_CP="$(win "$OUT/classes")${CORE_CP:+$SEP$CORE_CP}"

if ! javac -nowarn -encoding UTF-8 ${CORE_CP:+-cp "$CORE_CP"} -d "$(win "$OUT/classes")" @"$(win "$OUT/sources.txt")" > "$OUT/javac.log" 2>&1; then
  grep -v '^Note:' "$OUT/javac.log"; echo "Build failed"; exit 1
fi
echo "Built $(find "$OUT/classes/paradevs" "$OUT/classes/examples" -name '*.class' | wc -l) library/example classes."

declare -A TESTS=(
  [reward]=examples.reward.VerifyTimedReward
  [finance]=examples.finance.VerifyFinance
  [baseball]=examples.baseball.VerifyBaseball
  [plain]=examples.PlainMs4Run
  [tutorial]=examples.tutorial.RunServiceLine
  [sandbox]=sandbox.RunSandbox
  [nstep]=examples.rl.VerifyNStep
  [nstep-compare]=examples.rl.CompareNStep
  [hybrid]=examples.rl.VerifyHybrid
  [hybrid-compare]=examples.rl.CompareHybrid
  [cost-growth]=examples.rl.CostGrowth
)
ORDER=(tutorial reward finance baseball nstep hybrid)
[ $# -gt 0 ] && ORDER=("$@")

cd "$HERE"
status=0
for t in "${ORDER[@]}"; do
  cls="${TESTS[$t]:-}"
  if [ -z "$cls" ]; then echo "(unknown test '$t'; choose from: ${!TESTS[*]})"; continue; fi
  if [ ! -f "$OUT/classes/$(echo "$cls" | tr . /).class" ]; then echo "(skip $t: not built)"; continue; fi
  java -cp "$RUN_CP" "$cls" || status=1
done
exit $status
