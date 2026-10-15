# 05: Library v0 (built 2026-10-09)

> Superseded by `docs/report/ParaDEVS_Build_Report.pdf` (2026-10-11). Check counts here are from 2026-10-09 (66); the suite now has 77.

Theory source (authoritative): `OneDrive/Documents/Do s/documents/paratemporal/main_paratemporal.tex` (Oct 3 draft)
and `Paratemporal_Adaptation.tex`. `Main_Sol_Base` is out of scope.

## Decisions (from the user, 2026-10-09)
1. Java, integrated with the MS4 core.
2. The paper's conventions: models make draws inside their transitions; the engine branches at each draw.
3. **Safe merging only.** The Demo's IH shares-only merge is not in the library.
4. Verification set: timed reward (paper), finance single stock (Demo baseline), baseball (legacy engine).
5. v0 scope: fixed input trace, horizon in persistent entries, exhaustive / Monte Carlo / partial selection,
   eager representation (psλ) only.

## How a model plugs in

Write an ordinary MS4 atomic or coupled model, and obtain every random value with

```java
int qn = Para.choose("phase", Law.of(0, 0.25, 1, 0.75));
```

That is the whole model-side contract. Under plain MS4 simulation, `choose()` samples, so the model still runs
in MS4 Me (checked with `examples.PlainMs4Run`). Under the engine, `choose()` is the engine-controlled random
generator. Requirement: a departure must be deterministic given its state and the values chosen, and must use no
other randomness.

Run it:

```java
ParaResult<X> r = ParaEngine.run(
    ParaSpec.of(() -> new MyModel(...), myTransducer)   // M and the EF transducer
        .trace(InputTrace.builder().at(0.0, "action", "a").build())
        .horizon(5)                                    // persistent entries; optional
        .selection(SelectionRule.exhaustive())         // or monteCarlo(), partialExhaustive(p), hybrid(...)
        .merge(ParaSpec.Merge.SAFE));                  // optional
r.distribution(); r.expectation(f); r.marginal(g); r.residual(); r.stats();
```

## Theory → code map

| main_paratemporal | Code |
|---|---|
| PTS = (M, rep, EF, AP, Sel, MM, Φ) | `ParaSpec` (Φ is applied by the caller to `ParaResult`) |
| branch record k = (η, t, θ, j, n, ξ, w) | `ParaEngine.Branch` (η is the serialized MS4 model graph) |
| main / Γ_PT / Advance / doExpansion | `ParaEngine.Run.execute / gamma / advance / doExpansion` |
| RunLocal, Resume | `runLocal`: restore η, replay the departure with the values already chosen (*derandomized departure*, Adaptation Def. 5.1) |
| choice request payload ρ | `ParaEngine.Continuation` (parent η, departure type, values chosen, pending law) |
| SELECTOptions / TransOptions, mass w·α | `SelectionRule` (exhaustive, Monte Carlo, partial exhaustive, hybrid by draw label) |
| collector P̂, omitted mass r | `ParaResult.distribution()`, `residual()` |
| StopStatus ∈ {horizon, accept, passive} | `StopStatus`, applied in the paper's order, to roots and to every successor |
| safe merge key (complete continuation) | `StateKeys` (every model field plus MS4 event times) together with (θ, j, n, ξ) |
| transducer / acceptor | `Transducer<X>` (ξ immutable, with value equality), `ParaSpec.Acceptor` |
| one global clock, chronological AP | earliest `NextTime = min(tN, next input)`; all due branches advance |

## Engineering choices to review
- **Replay instead of pausing.** Java cannot suspend a method midway, so a suspended departure is restarted from its
  persistent state with the chosen values supplied. This follows the derandomized-departure definition. It costs one
  re-run per choice (see `departures` in the stats).
- **Own coordinator for coupled models.** MS4's `CoordinatorImpl` routes through identity-keyed `HashMap`s, so the
  order in which it processes children can change between copies, and a replay would then make draws in a different
  order. `ModelNode.Coupled` is a Parallel DEVS coordinator that visits children and couplings in declared list order.
  Atomic models still run through MS4's own `SimulatorImpl`.
- **Copies by Java serialization.** This is generic (any Serializable MS4 model) and gives an independent graph per
  replay. Fine for these sizes, but it will be the cost to optimise later.
- **Merge key by reflection.** It covers every model field, including bookkeeping such as `currentTime` and
  `previousPhase`, minus purely structural references. Where a field has no value equality, the key falls back to
  serialized bytes and then to identity. A missed equality only prevents a merge, so this never produces an unsafe one.
- **Zero-probability outcomes are dropped** (the paper: discard zero-weight choices). The legacy baseball tree kept them.
- **`Law.asGiven(..., tol)`** reproduces slightly improper source data. Baseball needs it because Machado's
  scoring-position row sums to 1.0004. The default `Law.of` rejects |mass − 1| > 1e-6.
- Initialization runs at t0 = 0, because MS4's `SimulatorImpl` only calls `initialize()` at time 0.
- **Monte Carlo roots (N > 1) combined with safe merging is refused** (added 2026-10-09). The roots start identical, so
  merging collapsed 2000 samples into one path: P(+6) came out 0.0 instead of about 0.016 on the sandbox random walk. The
  expectation is preserved, as the theory says, but the estimate is useless. Covered by a check in `VerifyTimedReward`.

## Verification (`bash scripts/build_library.sh`; 66 checks, all pass)

| Test | Reference | Result |
|---|---|---|
| Timed reward | `pt_reward_enumeration.py`: law (6352, 10948, 9793, 12907)/40000, 64 leaves, 63 choice nodes | exact match. Safe merge: 8 live branches, same law. MC N=4000 within 4 s.e. Partial: bounds hold |
| Finance (depth 2) | Demo Deliverable baseline (`runs/2026-10-09_demo_baseline`) | P(shares) to 1e-12, cont_value and profit to 1e-6, E[net] −38.9056847633 vs −38.9056847626 |
| Baseball (5 and 6 PA) | Fresh run of the original BaseballDEVS_v7 engine (`runs/2026-10-09_baseball_baseline`) | all 39 / 55 positive-probability states to 1e-12, E[runs\|5 PA] = 0.315529858502410 exactly |

Cost with and without safe merging:

| Case | No merge | Safe merge |
|---|---|---|
| Finance depth 6 | 729 leaves, 0.58 s | 288 leaves, 0.24 s |
| Baseball 6 PA | 24,450 leaves, 12.6 s | 195 outcomes, 0.45 s (legacy engine: 17 s) |

The finance and baseball ports are now plain DEVS coupled models (policy ↔ state machine with a feedback coupling).
They inject no state, pack no probability into an action code, and contain no domain code in the engine. Each test
reconstructs the old engine's merged numbers as linear marginals of the exact law. That is why the old IH results are
reproducible without IH merging.

## Known limits of v0 (next steps)
1. Deferred-output representation (ps) is not implemented. Draws inside `getOutput()` work mechanically, but the
   guarantees for ps also need output terminality.
2. Inputs are a fixed trace only. Reactive or adaptive inputs (the RL use case) are future work.
3. Sequential, single-threaded. Unmerged branches are independent, so parallelism is a natural next step.
4. No continuous laws for exhaustive selection (sampling only), as the paper prescribes.
5. Merge only acts within one Γ update (paper-conformant). Global hash merging over L is possible but not needed yet.
6. Possible optimisation: cache a branch's restored model between Advance and its first replay.
