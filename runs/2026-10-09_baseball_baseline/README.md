# Baseball baseline (2026-10-09)

Produced by the **original, unmodified** `MS4ME/Archive/BaseballDEVS_v7` sources (legacy GeneralTree engine),
compiled on a scratch copy against the MS4 Me 1.5 plugin jars (`C:\Program Files\MS4 Systems\MS4 Me\plugins\*`)
with JDK 17 and run as `Models.java.RunHitToGameTree`.

Configuration as committed in the sources: default lineup (p1..p9, RunHitToGame), walk is an outcome,
equivalence merging on, `GeneralTree.depth = 5`. The loop runs `depth + 1` times, so the CSV holds tree levels 1..7
(level L = state after L-1 plate appearances).

- `RunHitToGameTree2026-10-09.csv`: every tree node (`GeneralNode{...}`), then `ExpRuns` and the elapsed time
- `ExpRuns = 0.31552985850240994`: expected runs after 5 plate appearances (level 6)
- Level 6: 42 merged states, mass 1.000130966725728. Level 7: 62 states, same mass.
  The mass exceeds 1 because Machado's scoring-position row in the source data sums to 1.0004.

The saved CSVs in BaseballDEVS_v7/src/Models/txt predate the last source edits (2025-04-14, 2025-08-12), so
they were not used as the baseline.
