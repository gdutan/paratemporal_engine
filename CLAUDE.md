# ParaDEVS Library

A Java library that runs stochastic MS4 DEVS models as paratemporal computations: when a model makes
a random choice through `Para.choose(label, law)`, the engine follows the outcomes (all, sampled, or
pruned), merges identical continuations, and returns the distribution of what is observed. There is
also an RL track that uses the engine to compute n-step targets.

## Orient first (read before doing anything substantial)
1. `README.md`: layout and build commands.
2. `docs/report/ParaDEVS_Build_Report.tex`: what exists, how it was verified, problems fixed, and next steps. Section 1 is a one-page summary.
3. `notes/03_scope_and_todo.md`: decision log and current to-do list. Keep it updated.
4. `docs/rl/ParaDEVS_RL_Plan.tex` and `docs/rl/r1_results.tex`: the RL plan and its results.
5. Latest work: `src/examples/java/examples/rl/` and `rl/experiments/results/java_nstep_compare.csv`.

Theory authority: `C:\Users\cabre\OneDrive\Documents\Do s\documents\paratemporal\`
(`main_paratemporal.tex`, `Paratemporal_Adaptation.tex`). `Main_Sol_Base` is out of scope.

## Build and test
- `bash scripts/build_library.sh` builds the library and runs all acceptance tests (147 checks, all must pass).
- `bash scripts/build_library.sh <test>` runs one test: tutorial | reward | finance | baseball | nstep | control | nstep-compare | sandbox | plain.
- `bash scripts/build_docs.sh` rebuilds every PDF and refreshes the cross-document links.
- Python: `rl/.venv/Scripts/python.exe` (pinned in `rl/requirements.txt`; the venv is gitignored).
- Use Git Bash, JDK 17+, and MiKTeX (`latexmk`). The MS4 core comes from `../Demo Deliverable` when present, and from `lib/ms4-core.jar` otherwise.

## Decisions (from the user)
- Java on the MS4 core. Models make their draws inside transitions; the engine branches at each draw.
- Safe merging (full continuation key) is the default. An expectation-only (accumulator) merge is approved as an
  explicit opt-in, off by default (decided 2026-10-16). It is exact only for linear objectives with additive rewards,
  so it must never be the default and must refuse distribution queries.
- Small, plug-and-play scope. Re-run all tests after any engine change.

## Conventions
- Write documents (plans, guides, reports) as LaTeX with a compiled PDF, not Markdown. Add each new document to `docs/doclinks.tex` and give it a card in `docs/ParaDEVS_Start_Here.tex`.
- Make documents easy to read: put a summary first, open each section with an "In short" box, and include a glossary.
- Edit `.tex` and other backslash-heavy files with the Edit tool, not sed or inline Python, and rebuild once at the end.
- Run long experiments in the foreground, or resumably (`rl/experiments/_resume.py`). Memory pressure has killed long background runs before.
- Seed replications with any integers; the engine scrambles seeds (SplitMix64).
- Remote: https://github.com/gdutan/paratemporal_engine (branch `main`).
