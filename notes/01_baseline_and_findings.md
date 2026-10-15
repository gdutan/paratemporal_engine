# 01: Baseline and Findings

## 1. Ordinary DEVS wiring in MS4 Me (reference: JobProcessorExample)

| Layer | File | Role |
|---|---|---|
| Behaviour and ports | `dnl/*.dnl` | `accepts input on X`, `generates output on Y`, phases, `hold in … for time …`, event code blocks |
| Structure | `ses/*.ses` | `S is made of A, B!` and `A sends X to B [as inZ]!` |
| Pruning and build | `pes/*.pes` | `pruned from …ses`, `write class files` |
| Generated code | `java/<Coupled>.java` | `addChildModel(...)` and `addCoupling(A.outX, B.inX)` |

Every connection is declarative. One `sends` line in the SES becomes one `addCoupling`.

## 2. The DEVS layer under the paratemporal engine is ordinary

Finance (`EFFinance.ses`): `EFFinance = CallGenerator + Portfolio`, coupled on `Buy`, `Sell` and `Holding`.
- `CallGenerator`: `Activate` (0 time units) → `GenerateCall` (1 time unit) → emits the outcome's probability → `passive`.
- `Portfolio`: phase machine (`passive`, `BuyingStock`, `SellingStock`, `HoldingStock`) that updates shares, cash and profit.

Baseball (`HitToGame.ses`): `HitToGame = HitMaker + BallGame`, coupled on `YrOut`, `Single`, `Double`, `Triple`, `Homer`, `Walk`.
The structure is the same: a **generator** that emits a chosen outcome plus its probability, and a **state machine** that absorbs it.

On its own, the coupled model makes exactly one decision per run and never branches.

## 3. The paratemporal layer (all hand-written Java, nothing declared in SES)

| Piece | File(s) | What it does |
|---|---|---|
| Simulation-as-function | `SimulateModel`, `Run<EF>.java` | `defineModel()` builds the model from SES/PES. `setParams()` injects a snapshot by reflection and writes `phase` directly. `execute()` runs to max time 7. `getAndAnalyzeResults()` reads the state back into a `SimResult`. |
| State snapshot | `GeneralNode` | Domain fields plus `newProb` (path probability), `Cont` (probability-weighted accumulated value), `level`, `f` (children) |
| Frontier expansion | `GeneralTree.AdvanceLeafFront` | For every leaf and every outcome: clone → inject → simulate → snapshot |
| Probability algebra | `GeneralTree.simulateFrom` | Child probability = parent probability × branch probability. On merge, probabilities and `Cont` values are **added**. |
| Equivalence (H / IH) | `GeneralTree.equivalent` | Finance IH: same share vector. Finance H: same shares and same profit. Baseball: same level, cState, totalOuts and runs. |
| Driver | `Run<EF>Tree.java` | Sets depth, outcome set and data files, and writes the leaf PMF to CSV |

### Key idea behind IH (inferred from the code; to be confirmed against `Paper/DEVS_IH`)
Split the state into a **future-relevant** part (finance: the share vector, which selects the Q-table row and therefore
the next transition distribution) and a **past-only** part (realised profit). Past value is carried as the
probability-weighted accumulator `Cont`. Because that accumulator is linear, two nodes that agree on the
future-relevant part can be merged by adding their probabilities and accumulators, and no expectation is lost. H keeps
profit in the equivalence key, so it merges less.

## 4. Version comparison

| Area | FinanceDEVS_v4-IH | Demo Deliverable ParaDEVS |
|---|---|---|
| Compiles | No (CallGenerator stub) | **Yes** |
| Branch probabilities | Hard-coded `WorkDays` | Q-table CSV, row = encoded portfolio state |
| Prices | Hard-coded `if (Index < n)` chain | Stock CSV |
| Actions | `Buy`/`Sell`/`Holding` strings | `"<stock><action>"` codes (`10` hold, `11` buy, `12` sell); N stocks |
| Shares | `int` | `int[]` |
| Merging | Shares only | Selected by `merging_flag`: `IH`, `H` or none |
| Node isolation | Shared and mutated | Each node is deep-copied (`GeneralNode.clone()`) before simulating |
| `.dnl` sources | Present | **Missing**. The Java was edited directly after generation. |

## 5. Workarounds that reveal what is missing from DEVS/SES

1. **The probability travels inside the action code.** `CallGenerator` sends `Prob + s_action` (e.g. `12.1028`) on `outBuy`, and
   `Portfolio` separates the integer part (action) from the fraction (probability). The three SES couplings are
   effectively one. There is no way to declare "this output carries a branch label and a probability".
2. **The model computes its own state index.** `index = Σ shares[r]·(depth+1)^r` selects the Q-table row. This is
   engine-level state enumeration, buried inside an atomic model.
3. **The branch is chosen from outside.** `CallType` is injected by reflection. The model never makes the choice itself.
4. **Snapshots are taken by field name through reflection**, and the phase is written directly (`Portfolio.phase = cState`).
5. **One tree level means one fresh simulation**, and `execute()` re-parses the SES/PES and rebuilds the model for every
   branch (9 rebuilds in the depth-2 demo).
6. **Domain logic leaks into the engine.** `GeneralTree.simulateFrom` decodes finance actions, reads prices and
   computes the `Cont` formula.

## 6. Defects noted (for whoever ports the examples)

- String comparison with `==` (`CallType == "Buy"`, `merging_flag == "IH"`). It only works because Java reuses identical string literals.
- Finance v4-IH: some `Portfolio` branches subtract `100000` instead of `1000000`; the Sell branches set `Profit = Cash`.
- Demo: `Portfolio` updates `Profit` differently in `passive` (running sum) and `BuyingStock` (cash plus holdings minus 1e6).
- `globalStateTransition` builds `Run<EF>` once and then mutates its fields. The constructor's argument order does not match its parameters in v4-IH.
- `equivalentTo` scans every leaf linearly, so merging is O(n²) per level. It should use a hash key.

## 7. Verified baseline run (2026-10-09)

`Demo Deliverable/ParaDEVS`, JDK 17.0.15, AAPL validation data, `Q_table_AAPL_stoch_1_15.csv`, IH, depth 2.
Probability mass 1.00000007. Expected net value −38.91. Three merged leaves (shares 0, 1, 2).
See `runs/2026-10-09_demo_baseline/`.
