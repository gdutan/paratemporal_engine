#!/usr/bin/env bash
# Regenerate docs/docroot.tex (absolute file:/// address of docs/, used by the cross-document links) and
# compile every ParaDEVS PDF, so all links point to the current location.
#
# Usage (Git Bash):  bash scripts/build_docs.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DOCS="$HERE/docs"
WIN="$(cd "$DOCS" && pwd -W)"                         # C:/Users/.../docs
ENC="$(printf '%s' "$WIN" | sed 's/ /%20/g')"
cat > "$DOCS/docroot.tex" <<TEX
{\catcode\`\%=12 \xdef\docroot{file:///$ENC}}
TEX
echo "docroot: file:///$ENC"

# -g forces a rebuild: latexmk does not notice that docroot.tex or doclinks.tex changed.
build() {
    (cd "$DOCS/$1" && latexmk -g -pdf -interaction=nonstopmode "$2" >/dev/null && latexmk -c "$2" >/dev/null) \
        || { echo "FAILED: docs/$1/$2"; exit 1; }
    echo "built: docs/$1/${2%.tex}.pdf"
}
build .            ParaDEVS_Start_Here.tex
build report       ParaDEVS_Build_Report.tex
build howto        ParaDEVS_HowTo.tex
build architecture ParaDEVS_Architecture.tex
build rl           ParaDEVS_RL_Plan.tex
build rl           ParaDEVS_Control_Spec.tex
