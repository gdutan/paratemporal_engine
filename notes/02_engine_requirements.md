# 02: Engine Requirements (draft)

Derived from three implementations (Baseball v7, Finance v4-IH, Demo Deliverable). Each requirement lists where the current code meets it with a workaround.

## A. What a participating DEVS model must provide

| # | Requirement | Current workaround |
|---|---|---|
| M1 | **Snapshot / restore** of the full global state, including phase, sigma and elapsed time | Reflection `setValue` by name; direct `phase` write; sigma and elapsed time are not captured |
| M2 | **Choice points**: where branching happens and which outcomes are possible | Outcome list hard-coded in the driver; one choice per step, always in the generator |
| M3 | **Branch probability** for each outcome at a choice point | Encoded in the message value (`Prob + action`) |
| M4 | **Forced outcome**: the engine selects the branch and the model follows it | `CallType` injected before the run |
| M5 | **Step boundary**: when one paratemporal step is complete | `setMaxSimulationTime(7)` and the model going passive |
| M6 | **Future-relevant state key** for merging | Hand-written `equivalent()` per domain |
| M7 | **Value increment** for each step (reward or contribution) | `Cont` formula inside `GeneralTree` (finance-specific) |

## B. What the engine must do

| # | Requirement |
|---|---|
| E1 | Expand the frontier: for each leaf and each outcome, restore → force → simulate to the step boundary → snapshot |
| E2 | Probability algebra: multiply along a path; add on merge; check that probability mass is conserved (≈1) |
| E3 | Merging policies: none, H (full-state equality) and IH (future-relevant key plus a linear accumulator). Merging should use hash lookup, not a linear scan |
| E4 | Accumulate value linearly (probability-weighted) so that merging stays valid |
| E5 | Stop at a depth or horizon, with optional pruning (probability threshold, beam width) |
| E6 | Outputs: leaf PMF, expected value, node and merge counts, timing; one CSV schema shared by all domains |
| E7 | Build the model once and reuse it across branches; do not re-parse SES/PES for each branch |
| E8 | Stay independent of the domain: no domain fields in the tree or node classes; generic state map plus a typed accessor |

## C. Specification layer (how a modeller declares it)

Open design question. Candidates, from least to most invasive:
1. **Java interface / annotations** on atomic models (`@ChoicePoint`, `@StateKey`, `Branchable`). Easiest to build first.
2. **DNL extension**, e.g. `choose outcome from Buy, Sell, Holding with probabilities <% … %>!`
3. **SES/PES extension**, e.g. `From the para perspective, CallGenerator branches on Call!` and `merge on Portfolio.Shares!`

## D. Validity condition to formalize (IH)

Merging nodes s and t is exact for expected value if their keys are equal, k(s) = k(t), and k determines:
(i) the next-step outcome distribution, and (ii) all future value increments.
The library should **check** this, at least as a runtime assertion: any state variable that the probability or value
functions read must be included in the key.
