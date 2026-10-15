# ParaDEVS Library: Formalization Workspace

Goal: turn the paratemporal-engine workarounds in the finance and baseball DEVS projects into a reusable
library. Any DEVS model should be able to declare its branch points and run under the engine without
hand-written tree code.

Started 2026-10-09. **Status: library v0 built and verified on all three acceptance examples** (`notes/05`).

## Quick start

**Start here: `docs/ParaDEVS_Start_Here.pdf`**, a one-page reading guide that links to every document.
**Project status: `docs/report/ParaDEVS_Build_Report.pdf`** (what was built, how it was verified, what is next).
**New users: read `docs/howto/ParaDEVS_HowTo.pdf`** (setup, two tutorials, API reference, troubleshooting).
**How it is built and how to read results: `docs/architecture/ParaDEVS_Architecture.pdf`** (layers, MS4 integration,
execution model, result interpretation, guarantees, extension points).

```bash
bash scripts/build_library.sh                      # build library + examples, run all tests
bash scripts/build_library.sh tutorial             # one test: tutorial | reward | finance | baseball | plain
bash scripts/run_my_model.sh <src-dir> <Main>      # compile and run your own model (add --ms4me for MS4 Me code)
bash scripts/build_docs.sh                         # rebuild Start Here + build report PDFs (refreshes cross-links)
bash scripts/run_ms4me_import_demo.sh              # MS4 Me-generated JobProcessorExample under the engine
```

Requires JDK 17+. It compiles against the MS4 core sources bundled in `../Demo Deliverable/ParaDEVS/src/com`.

## Working in VS Code (current testing environment)

Open **this folder** (`ParaDEVS_Library`) in VS Code, with the Java Extension Pack installed. It is preconfigured:

- **Sources:** `src/main/java`, `src/examples/java`, `src/sandbox/java`. MS4 core from `lib/ms4-core.jar`
  (rebuild it with the task "package MS4 core jar" if the Demo Deliverable core ever changes).
- **Run and Debug:** "Sandbox: RunSandbox", "Run current file", the tutorial, each `Verify*` test, and a plain MS4 run.
  All of them run with the library root as the working directory.
- **Tasks** (Terminal → Run Task): build and run all tests, run one test, package the core jar, MS4 Me import demo,
  compile the how-to PDF. They run in Git Bash.
- **New experiments go in `src/sandbox/java/sandbox/`** (see `src/sandbox/README.md`).

The MS4 Me import demo (`src/ms4me_import_demo`) is deliberately not on the VS Code source path. It needs the
MS4 Me jars and is run by its own task; MS4 Me integration comes later.

## Layout

| Path | Contents |
|---|---|
| `src/main/java/paradevs/` | Model API: `Para.choose(label, Law)`, `Law` |
| `src/main/java/paradevs/engine/` | Engine (main / Γ_PT / Advance / doExpansion), `ParaSpec`, `ParaResult`, selection rules, trace, transducer |
| `src/main/java/paradevs/ms4/` | MS4 integration: deterministic coordinator, snapshots, complete-state merge keys |
| `src/examples/java/examples/` | `tutorial/` (service line), `reward/`, `finance/`, `baseball/` models with their tests; `PlainMs4Run` |
| `src/sandbox/java/sandbox/` | **Staging area for experiments** (template: `RandomWalk` + `RunSandbox`) |
| `lib/ms4-core.jar` | MS4 core packaged from Demo Deliverable (`scripts/package_ms4_core.sh`), used by VS Code |
| `.vscode/` | VS Code settings, launch configurations, and tasks |
| `src/ms4me_import_demo/java/` | MS4 Me-generated JobProcessorExample with two marked edits, plus its driver |
| `docs/ParaDEVS_Start_Here.tex` (+ PDF) | **Entry point**: reading guide linking every document |
| `docs/report/` | **Build report**: status, verification, problems fixed, RL results, next steps (`ParaDEVS_Build_Report.tex` and PDF) |
| `docs/howto/` | **How-to guide and tutorial**: how to *use* the library (`ParaDEVS_HowTo.tex` and PDF) |
| `docs/rl/ParaDEVS_RL_Plan.tex` (+ PDF) | **RL applications plan and progress**: use cases, merge-vs-variance analysis, toy problems, Python baselines, Java↔Python integration options |
| `rl/paratemporal_py/` | Python reference prototypes: tabular MRP/MDP (incl. exact FrozenLake model), n-step target estimators, learners |
| `rl/experiments/` | `merge_variance.py`, `t1_nstep_td.py`, `t2_frozenlake_prediction.py` (resumable), `make_tex_tables.py`; results in `results/` |
| `rl/baselines/` | `q_table_constructor.py`: headless port of the Demo's Q-learning notebook |
| `rl/tests/` | `test_targets.py`: estimator checks (Bellman consistency, unbiasedness) |
| `rl/.venv/`, `rl/requirements.txt` | Python environment (gymnasium, stable-baselines3, sb3-contrib, jpype1, torch, matplotlib, pandas; pinned) |
| `docs/architecture/` | **Architecture and interpretation guide**: how it is *built* and how to *read results* (`ParaDEVS_Architecture.tex` and PDF) |
| `notes/01_baseline_and_findings.md` | What exists today, how ordinary DEVS wiring works, and what the paratemporal layer adds |
| `notes/02_engine_requirements.md` | What a model must provide and what the engine must do (draft requirements) |
| `notes/03_scope_and_todo.md` | Scope, phased plan and status, open questions |
| `notes/04_template_assessment.md` | Do we have enough of a template to formalize this into a library? |
| `notes/05_library_v0.md` | **What was built**: API, theory→code map, design choices, verification, limits |
| `scripts/build_library.sh` | Build and test the library |
| `scripts/run_my_model.sh` | Compile and run your own model folder (bundled core, or `--ms4me` for the MS4 Me jars) |
| `scripts/run_ms4me_import_demo.sh` | Run the MS4 Me import demo against the MS4 Me jars |
| `scripts/build_and_run.sh` | Rebuild and run the original Demo Deliverable |
| `runs/2026-10-09_demo_baseline/` | Finance baseline (Demo Deliverable, verified run) |
| `runs/2026-10-09_baseball_baseline/` | Baseball baseline (original BaseballDEVS_v7 engine, fresh run; see its README) |

## Source projects (referenced in place, not copied)

| Project | Path (relative to `Binghamton/`) | Status |
|---|---|---|
| **ParaDEVS (Demo Deliverable)** | `Demo Deliverable/ParaDEVS` | Compiles and runs (JDK 17, no jars); also supplies the MS4 core sources |
| FinanceDEVS_v4-IH | `MS4ME/Archive/FinanceDEVS_v4-IH` | Does not compile: `CallGenerator.java` is an empty stub |
| BaseballDEVS_v7 | `MS4ME/Archive/BaseballDEVS_v7` | Compiles against the MS4 Me 1.5 plugin jars (`C:\Program Files\MS4 Systems\MS4 Me\plugins`) |
| Plain DEVS reference | `C:\Users\cabre\Documents\MS4 Me Workspace\JobProcessorExample` | Ordinary SES/PES/DNL coupling |

## Theory

Authoritative: `C:\Users\cabre\OneDrive\Documents\Do s\documents\paratemporal\`, specifically `main_paratemporal.tex`
(engine), `Paratemporal_Adaptation.tex` (derandomized departures, merge soundness) and `pt_reward_enumeration.py`
(exact check). `Main_Sol_Base` is out of scope.
