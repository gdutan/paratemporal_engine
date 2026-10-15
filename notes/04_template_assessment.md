# 04: Do we have enough of a template to formalize a library?

**Short answer: yes for the engine, not yet for the formal model contract.** We have enough to design and
build a working library v0 and port the existing examples onto it. We do not yet have enough to say what a
*general* DEVS model must look like to be paratemporal-executable. The current examples avoid that question
by restricting themselves to one very specific shape.

## What we do have (strong evidence)

**Three implementations of the same skeleton, in two domains.** Baseball v7 → Finance v4-IH → Demo Deliverable share
identical engine code (`GeneralTree`, `GeneralNode`, `Run<EF>`, `SimulateModel`). Comparing them separates the
invariant parts from the variable parts:

| Invariant (becomes the library) | Varies by domain (becomes the model contract) |
|---|---|
| Frontier expansion loop | State fields in the node |
| Multiply probabilities along a path, add them on merge | Outcome set |
| Merge into a leaf set, add `Cont` linearly | Equivalence key (`shares` / `shares + profit` / `outs + runs + bases`) |
| Snapshot → inject → simulate → read back | How the outcome is forced (`CallType` / `HitType`) |
| PMF / expectation output | Value increment (`Cont` formula, expected runs) |
| Check that probability mass ≈ 1 | Data sources (Q-table, lineup probabilities) |

The invariant column is about 200 lines of Java and is already domain-free except for leaks we can name. The
variable column is short and maps to 7 hooks (M1–M7 in `02`). That is a good basis for a library API.

**A working, reproducible baseline** (`runs/2026-10-09_demo_baseline`) to use as a regression target.

**Rough evidence for what IH requires.** Finance merges on the share vector, which is exactly what
indexes the Q-table, so the merge key equals the state that determines the next transition. That is the
condition a library can state and check (`02 §D`).

## What we don't have (gaps that block a *general* library)

1. **Only one kind of model has been tested.** Every example is *one generator feeding one state machine, with
   one choice per step, made at time 0+1, in a known component*. Real DEVS models put randomness inside any
   atomic model, at any time, possibly several times per step. The current template cannot tell us how to
   branch on those. This is the biggest gap: **choice points have to be generalized** (a `choose(dist)`
   primitive inside atomic models that the engine forks on), and nothing in the examples does that yet.

2. **No formal definition of a step.** In the code, one tree level is one fresh simulation run until the model goes
   passive (max time 7; the real run ends at t=1). Whether "paratemporal" means branching in DEVS time or at
   decision epochs is a semantic choice that the code makes implicitly. The dissertation and the
   *Advanced ParaDEVS Controls* PDF probably answer this. They have not been read yet.

3. **The state contract is informal.** Snapshots are taken by field name through reflection; `phase` is written directly;
   `sigma` and elapsed time are never captured. That is only correct because every example restarts from a
   passive, time-0 state. A general library needs full DEVS-state save and restore, (phase, σ, e, variables), for every atomic model.

4. **The workarounds sit in the communication layer.** Probability packed into the message value, and state
   enumeration inside the atomic model, both show that **the SES/DNL grammar has no way to say "this output is a
   branch"**. A library can supply an API for this, but if it is meant to be *declared* in MS4 Me, the grammar
   has to change. That is outside what the examples can teach us.

5. **IH validity has only been tested on additive value.** `Cont` works because the value function is linear. It is unclear whether
   IH extends to non-additive metrics (variance, max drawdown, probability of ruin). The proof material in
   `Paper/DEVS_IH` needs to be checked.

6. **No independent third example.** Both domains were written by the same people, using the same template. Generality is not
   demonstrated until a model **not derived from GeneralTree** is run on the library.

## Recommendation

Proceed, in this order:
1. **Read the formal sources first** (dissertation, Advanced Controls PDF, DEVS_IH). They may already settle
   gaps 2, 4 and 5, so we avoid reinventing them.
2. **Build library v0 around the invariant column**, with the model contract limited to the current shape
   (one forced choice per step). Port finance and baseball and match their baselines. This is low-risk and gives a
   usable artifact quickly.
3. **Then generalize choice points** (gap 1) and full state save/restore (gap 3) as v1, validated by a new third model.

In short, the existing code is a good template for **how the engine runs**, but it only hints at **what a model must
declare**. That second part is the actual formalization work.
