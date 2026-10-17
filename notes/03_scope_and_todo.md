# 03: Scope and To-Do

## Decision log
- **2026-10-09:** Theory basis is `Do s/documents/paratemporal` (main_paratemporal, Paratemporal_Adaptation);
  `Main_Sol_Base` is out of scope. Java on the MS4 core. The paper's conventions apply (draws inside models, the
  engine branches). **Safe merging only**: the IH shares-only merge is dropped. Start small with plug-and-play
  support for the simple examples. Verification set: timed reward, finance, baseball.

- **2026-10-09:** VS Code (opened on `ParaDEVS_Library`) is the staging environment for testing for now; new
  experiments go in `src/sandbox`. MS4 Me integration is deferred.

## Scope

**In scope**
- A Java library (on top of the MS4 DEVS core) that runs any compliant MS4 model (atomic or coupled) as a paratemporal tree.
- Model-side contract: draws through `Para.choose`; departures deterministic given state and the values chosen.
- Finance, baseball and the paper's timed reward example as acceptance tests against fixed baselines.

**Out of scope (for now)**
- Changes to the MS4 Me IDE or the SES/PES/DNL grammar.
- The RL / Q-learning integration (`ms4me.para(...)` in the Demo README); it will consume the library later.
- IH or other reduced-key merging (would need its own proof; see `02 §D`).
- Parallel or distributed tree expansion.

## Phase 0: Ground truth (done)
- [x] Identify the working baseline → `Demo Deliverable/ParaDEVS`
- [x] Rebuild and run it outside MS4 Me; record the output (`runs/2026-10-09_demo_baseline`)
- [x] Map ordinary DEVS wiring and the paratemporal layer (`01`)
- [x] Read the theory (`main_paratemporal.tex`, `Paratemporal_Adaptation.tex` §1–7, `pt_reward_enumeration.py`)
- [x] Rebuild and run Baseball v7 for a second baseline (`runs/2026-10-09_baseball_baseline`)
- [ ] Read the rest of `Paratemporal_Adaptation.tex` (§8 onwards: DSDEVS realization, stopping, inputs) and `DEVS_PT_V12 (2).pdf`

## Phase 1: Library v0 (done, see `05`)
- [x] Model API: `Para.choose(label, Law)`; runs unchanged under plain MS4 simulation
- [x] Engine: main / Γ_PT / Advance / doExpansion, one global clock, StopStatus, collector, residual
- [x] Selection rules: exhaustive, Monte Carlo (N roots), partial exhaustive, hybrid by label
- [x] Safe merging on the complete continuation key
- [x] MS4 integration: deterministic coupled coordinator, serialization snapshots, model built once (no SES re-parse)

## Phase 2: Port and verify (done)
- [x] Timed reward → exact paper law, 64 leaves, 63 choice nodes
- [x] Finance → matches the 2026-10-09 Demo baseline
- [x] Baseball → matches the original engine at 5 and 6 plate appearances

## Documentation (done 2026-10-09)
- [x] How-to guide and tutorial: `docs/howto/ParaDEVS_HowTo.pdf` (setup, scripted-model tutorial, MS4 Me import tutorial, API, troubleshooting)
- [x] Tutorial model `examples/tutorial` (service line) checked against a hand answer: P(3 served) = 0.36
- [x] MS4 Me import verified on the unmodified JobProcessorExample (two marked edits), compiled against the MS4 Me 1.5 jars
- [x] VS Code workspace: `.vscode/` (source paths, `lib/ms4-core.jar`, launch configs, Git Bash tasks), sandbox template
- [ ] Try the DNL-side `Para.choose` and copy `paradevs/` into an MS4 Me project inside the IDE (the guide marks this as untested)

## Reports
- [x] 2026-10-11: build report `docs/report/ParaDEVS_Build_Report.pdf` and entry point `docs/ParaDEVS_Start_Here.pdf`; suite rerun 77/77

## RL track (planning, 2026-10-10)
- [x] Plan written: `docs/rl/ParaDEVS_RL_Plan.tex` and PDF (phases R0–R5); R0 done, R1 in progress
- [x] Merge-vs-variance experiment: `rl/experiments/merge_variance.py`. Merging with budget reallocation ties with plain
      MC per unit of work; exhaustive + merge is exact and cheapest where outcome sets are small, so use hybrid selection.
- [x] R0 (2026-10-10): `rl/.venv` + pinned requirements; JPype bridge verified (Python runs the Java engine);
      Q-table notebook ported headless (`rl/baselines/q_table_constructor.py`), reproduces the Demo table within ~1%.
      Finding: the notebook's reward amounts to "buy", independent of prices; redesign it before T5.
- [x] R1 prediction studies on T1 and T2 (`docs/rl` PDF, Section 8). The exact target halves the error per real episode;
      merge+budget ties with rollouts; compute-competitiveness depends on frontier size.
- [x] 2026-10-14: n-step targets in the **Java engine** (`examples.rl`): `SelectionRule.monteCarlo(k)` and `budget(B)`,
      tabular-MDP walker ("merge, then sample"), `VerifyNStep` (22 checks), `CompareNStep` study
      (`rl/experiments/results/java_nstep_compare.csv`). Fixed: java.util.Random seeds are now scrambled (consecutive seeds
      were correlated and biased averages over replications). Repo: github.com/gdutan/paratemporal_engine.
- [ ] 2026-10-16 user selections for the next phase:
      (1) parallelize the comparison driver — approved; the engine fast-copy change awaits the user after explanation;
      (2) expectation-only merge as an opt-in — approved;
      (3) add the Java results to the RL Plan PDF — approved;
      (4) GOAL: tie the engine into a full RL pipeline that assists action selection in the value- and policy-iteration
          steps. This is the main planning effort;
      (5) hybrid selection and the cost-growth study — the user asked for more detail;
      (6) `ParaEnv` plus baselines — approved, needed for full integration.
- [x] 2026-10-16 phases 4a-4c (goal 4, engine-assisted action selection): spec `docs/rl/ParaDEVS_Control_Spec.tex`;
      `Para.decide(label, obs, options)` (+ `Para.NOOP`, `Para.Policy`, default uniform under plain MS4); graph-mode
      planner `paradevs.engine.{Planner, Plan, PlanSpec, LeafValues, DecisionRule}` (merge on state + decision depth,
      rewards on edges, max / policy backups, batched leaf hook, `LookaheadPolicy` for acting); `StateKeys.relative`
      (opt-in relative time key); `Snapshots.saveDetached`; model `examples.rl.MdpAgent`; test `VerifyControl`
      ("control", 48 checks: n-step VI, Q, PI/VI to V*, semi-MDP, no-op, sampled bias, online FrozenLake demo).
- [ ] 4d learning comparisons (Python, tabular); 4e Python wrapper over the planner; 4f DEVS-native problem (T4).
- [ ] Open questions from the control spec, Section 9 (time-key default, reward timing, e(a) estimator, leaf input,
      default policy, merging across event counts).
- [x] 2026-10-16: **expectation-only merge** (`Merge.EXPECTATION`, `Transducer.Additive`, result refuses distribution
      queries; `expectation(slope, offset)`); test `expectation` (38 checks).
- [x] 2026-10-16: **hybrid + cost-growth study** (`docs/rl/ParaDEVS_Hybrid_Study.tex`): sample the action, enumerate the
      slip, with budget + merge wins (1.3-2.9x over the best one-draw sampler); the slip carries 88% of the variance;
      merged exhaustive work becomes linear in n once the reachable set is covered; observation-lag finding
      (`LagFreeWalker`, up to 2.8x less work); test `hybrid` (12 checks).
- [ ] **Engine fast-copy**: proposal `docs/engine/ParaDEVS_FastCopy_Proposal.tex` (measured 15-40x options). Waiting on
      the user to read it and decide (Phase 1 default or opt-in; `ParaCopyable` public; new MC random stream; verify mode
      in tests).
- [x] 2026-10-17: all five branches merged into main; documents registered in `docs/doclinks.tex` and Start Here.
- [ ] R1 remaining: T2 control (now via the planner, phase 4d). Then R2 (Java features).
- [x] 2026-10-16 decision: the accumulator (expectation-only) merge goes into the Java library as an **opt-in**, off by
      default. JPype-first and the toy order are being followed as recommended.
- [x] 2026-10-16: **ParaEnv** (R3 first cut): `examples.rl.env.MdpSession` (Java bridge: reset/step with SplitMix64,
      snapshot, `NStepTargets` targets, batch methods, `ActionPlanner` extension point with `PolicyLookaheadPlanner`);
      `rl/paratemporal_py/para_env.py` (Gymnasium env, `para_eval`, `plan`); `rl/tests/test_para_env.py`;
      `rl/experiments/paraenv_ntd_demo.py`. OpenEnv/TRL adapter: path described in `rl/README.md`, not built.
- [ ] Read Dominici et al., SIMULTECH 2026 (broker-based multi-framework DEVS, doi:10.5220/0014846100004094)

## Phase 3: Next
- [ ] One **new** small model written from scratch by someone other than the library author, following the guide
- [ ] Deferred-output representation (ps) and its terminality check
- [ ] Results export (PMF CSV with the same columns as the Demo output) for the RL side
- [ ] Performance: avoid re-serializing on every replay; parallel expansion of unmerged branches
- [ ] Optional: generate the `Para.choose` boilerplate from DNL (e.g. a `choose ... with probabilities` clause)
- [ ] Decide whether the Demo's `.dnl` files should be recreated or the Java treated as source

## Open questions for the advisor / team
1. Is a branch step tied to DEVS simulation time (as in the paper, and now in the library) or to decision epochs (as in the old code)?
2. Is there a need for reduced-key (IH-style) merging, or is safe merging plus linear marginals enough?
   v0 shows that the old IH numbers are recoverable as marginals of the exact law.
3. Target users: MS4 Me modellers (DNL/SES extension) or Java users (library API)?
