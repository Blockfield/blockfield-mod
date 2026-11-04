# Anim-sync USHARE (A6 STEP, A7 SHARE)

Owner rule: exact server = client six-box equality; accuracy first; no visual change.
A6 is bit-identical (same values, bytes, verdicts, exceptions); A7/B1 need a coordinator decision.

## A6 STEP — cheaper reducer steps

One scratch and one freeze per observer-reducer step (`ObserverReducer.Reduction.advance`).
The PA tick and every TaCZ/LR decision of the bucket run on the single step scratch; each
decision only records a `PaProjection.Pending` (proof template plus layer predecessor).
The step freezes once into the boundary's composed graph and `PaProjection.settle` binds
every pending decision's new nodes from that final graph by event sequence — the same
attribution `after` did per decision. Intermediate graphs are not part of any result.
The ParCool boundary still runs at its journal position through its validated stage (it
touches only ParCool state, so the scratch stays valid across it); the family-isolation
gate is kept, so a step validates twice (final freeze plus the stage), not once.
A decision that re-selects the incumbent installs nothing by native parity (TaCZ same-name
loop, active-once, LR idle/rotation/attack guards — audited, no behavior change), so it
also copies no clip; the pending is bookkeeping-only. The per-operation reference path
(`shareStepScratch(false)`: fresh scratch and one freeze per operation) is kept verbatim.

Touched: `common/combat/ObserverReducer.java` (advance split into shared/settle path plus
`reference`), `PaProjection.java` (`Pending`, `priorAnchor`, `settle`, `taczStep`),
`LrProjection.java` (`decisionStep`, single-path `run`), `PaGraph.java` (deleted the
now-unused no-op identity check; per-thread freeze counter for the test).
Capsule installs keep their own freeze (outside the step).

Proof: `ObserverReducerScratchTest` compares shared vs reference over every bucket (values
plus wire bytes) and asserts shared freezes < reference freezes; the bench verify mode
(`-PbenchVerify=true`) replays every reduction on the reference path and must stay silent;
`just format/check/test/java-check` and `:server:oracleParallelGate` green.

Bench (same machine, `gradle-slot --exclusive`, 3 runs, medians; combat cohorts):

| cohort | main-thread wall before → after | anim-sync CPU before → after | alloc/tick before → after |
| --- | --- | --- | --- |
| 30x9 | 122.05 → 121.46 ms (−0.5 %) | 76.76 → 74.75 ms (−2.6 %) | 66.11 → 62.21 MB (−5.9 %) |
| 30x29 | 265.38 → 260.21 ms (−1.9 %) | 172.28 → 161.47 ms (−6.3 %) | 131.48 → 118.78 MB (−9.7 %) |

JFR (30x9 observer emulation): `freeze` 44.3 % → 31.3 % (−29 %, as estimated); `after`
11.1 % disappears into the settle; `validateComplete` stays 17.7 % → 17.5 %: the validated
ParCool stage is kept deliberately, and capsule/fold steps still re-materialize (the carried
scratch goes stale outside the step), so the "one validateComplete per step" target is not
fully reached. Net step-level win is smaller than the estimated −30 % for that reason.

Open: route capsule installs through the carried scratch (kills the re-materialize on
capsule steps); re-measure after UOPT-2/UPAR-2 land around this code.

## A7 SHARE — reverted

A7 (one reducer step per presentation class, a step cache in `ObserverReducer`) was integrated
as `e2e5f589..165d2d9e` and reverted. It was byte-identical, but slower in every measured mix
(`AnimSyncBench`, combat-only, medians of 3, `shareSteps` off → on):

| Mix | 30x9 wall, ms | 30x29 wall, ms | Hits / fallbacks |
|---|---|---|---|
| scattered | 120.17 → 122.29 | 256.92 → 270.02 | 10 % / 34 % |
| 2 classes | 120.33 → 121.35 | 263.10 → 268.57 | 22–24 % / 32 % |
| 3 classes | 121.54 → 123.49 | 257.76 → 269.54 | 21–24 % / 32 % |

Seeds still scatter through per-observer FULL/link timing, so classes rarely form, and about a
third of lookups fall back to a native recompute. Bench class-mix option and raw numbers:
branch `fulla/as-us2-measure` 8b25dd47. Revisit only with a workload where observers share seeds.
