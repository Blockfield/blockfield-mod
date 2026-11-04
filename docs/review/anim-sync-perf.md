# Anim-sync CPU cost (UOPT)

Owner decision 2026-10-17: the ADR4 animation sync must get cheaper before release, with results
bit-identical to phase23 `18e0d02e`. Only cost changed: no sampling, skipped ticks/frames/victims,
approximation, precision change, weaker validation or wire change. Branch `fulla/as-uopt`.

## 1. Benchmarks

Both run in the transformed production JVMs of the [oracle runner](anim-sync-oracle-runner.md)
(Fabric Loader 0.19.3, intermediary names, every production mixin, the pinned PA 2.0.4, LR,
TaCZ, ParCool, Create and SBW JARs), exact transport on and resources installed:

```sh
# under the workspace heavy-build lock
./gradlew --no-daemon --max-workers=2 :server:oracleTest :client:oracleTest \
    -PanimSyncBench=all -PbenchSeconds=6
```

`-PanimSyncBench` runs the benchmark instead of the oracle fixtures (their gate is unchanged).
Outputs: `{server,client}/build/anim-sync-bench/{server,client}.{json,md}` and one JFR recording
per cohort. Options: `-PanimSyncBench=10,30:9` (players[:targets per observer]),
`-PbenchTicks=300`, `-PbenchWarmup=120`, `-PbenchVerify=true` (below), `-PbenchSeconds=4`
(client seconds per cohort).

**Server (`AnimSyncBench`).** N players as Mockito `ServerPlayer` doubles with a real inventory
(every second player holds a full-auto TaCZ rifle), ParCool attachment and walk state; every
fourth swings an LR bat every 25 ticks, every fourth rolls (ParCool) every 40 ticks, one hangs on
Create. They circle and sprint. Each tick runs, in production order: the observers'
`AnimationApplied` reports, the shot (combat cohorts), the accepted inputs, `ServerBodyPoses`
START and END ticks (END includes capture), `AnimSyncNative.flushAll` and `SnapshotStream.exact`'s
per-observer computation without network sends. Observers are emulated with the shared
`ObserverReducer` as `ClientAnimationJournal` reduces (seed activation, capsules at their bucket,
heartbeats, fold adoption); that client work is not timed. Combat = 20 shots/s: two rifle holders
on full auto alternate ticks (a new pair every 2 s); each shot's receipt is the shooter's honest
complete receipt (one row per remote in its last batch, like `ClientAnimationJournal.row`),
validated by `AnimSyncValidation`, and every victim it returns is evaluated by `AnimSyncEval`.
Cohorts: 2 players (each sees the other), 10 (each sees 9), 30 with 9 targets per observer
(`30x9`: the 256 KiB batch cap, §6, disables an observer that sees more gun holders).
Cost = current-thread CPU and allocation (`com.sun.management.ThreadMXBean`) after 120 warm-up
ticks, over 300 ticks.

**Client (`DrivenPresentationBench`).** 2/10/30 real `RemotePlayer`s in the U10 mixed-renderer
environment, each on its own `MixedRendererCases` case, presented every frame of a 144 FPS
schedule over the cases' ticks: `SnapshotResolver.exact` + `ClientCombatScene.write` +
`DrivenPresentation.install` per remote per frame. The observer reduction a frame presents is
advanced once per bucket outside the timing (the journal's work, the server emulation's reducer).
A report-only MAIN render pass through the production renderer runs after the measured loop.

**Verify mode** (`-PbenchVerify=true`): every reduction also runs on the reference path (fresh
scratch per operation, no resumption) and must be equal after every step, application and fold.

## 2. Results

Machine: AMD Ryzen 5 7535U (12 threads), Linux 7.2.8, Temurin 21.0.12+8; one thread does
everything, as on the server thread. Base = `18e0d02e` code with the same benchmark
(`9ed7c916`); after = this branch merged with phase23 `25bead1b`. Both runs produced the same
workload outcome: per cohort the same receipts, verdicts (30x9 combat: 84 exact, 325 rejected
`seed_activation`, 11 unavailable), folds (4,050), seed activations, applications and
observer-side fold adoptions.

### Server, per tick and per operation (CPU)

"Anim-sync CPU/tick" is the tick without the END-tick evaluation outside capture (the r10 body
pose, which runs with or without animation sync); capture is the production `captureNanos`
counter. Validation rows: one per remote in the shooter's last batch (1 at 2 players, 9 at 10 and
30x9); "validate/row" is per validated row (a rejected receipt stops at its failing row). An
exact 9-row receipt at 30x9 costs 224.8 ms on base and 6.58 ms after (p99 316.9 / 17.7 ms).

| cohort | run | tick CPU mean / p50 / p99 (ms) | anim-sync CPU/tick (ms) | alloc/tick mean (MB) | capture/player (µs) | END tick/player incl. capture (µs) | stream/observer (µs) | validate/receipt (µs) | validate/row mean (µs) | evaluate/victim (µs) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2-idle | base | 12.07 / 8.36 / 85.53 | 9.08 / 5.42 / 81.17 | 11.1 | 2483 / 2439 / 7009 | 3964 / 4008 / 8329 | 2008 / 355 / 61348 | - | - | - |
| 2-idle | after | 7.48 / 6.86 / 18.63 | 4.85 / 4.32 / 15.77 | 2.9 | 1914 / 1847 / 3927 | 3221 / 3100 / 6170 | 472 / 294 / 4677 | - | - | - |
| 2-combat | base | 15.16 / 11.96 / 101.55 | 12.41 / 9.30 / 98.70 | 18.1 | 2037 / 2149 / 5734 | 3382 / 3635 / 6582 | 2264 / 306 / 76376 | 3002 / 3974 / 7648 | 3054 | 739 / 672 / 1457 |
| 2-combat | after | 6.94 / 6.25 / 16.32 | 4.74 / 4.13 / 14.08 | 3.7 | 1435 / 1339 / 3377 | 2528 / 2375 / 5412 | 455 / 260 / 7216 | 280 / 291 / 1083 | 209 | 605 / 567 / 1024 |
| 10-idle | base | 176.85 / 58.56 / 355.23 | 164.45 / 43.83 / 343.65 | 315.3 | 1862 / 1829 / 5291 | 3083 / 3113 / 6354 | 14560 / 2192 / 63009 | - | - | - |
| 10-idle | after | 32.46 / 29.19 / 56.35 | 22.89 / 17.87 / 43.75 | 26.9 | 1129 / 1099 / 2505 | 2074 / 2045 / 4282 | 1147 / 782 / 4082 | - | - | - |
| 10-combat | base | 332.60 / 304.46 / 695.21 | 320.67 / 292.99 / 681.41 | 654.3 | 1740 / 1816 / 4207 | 2920 / 3110 / 5999 | 16746 / 2273 / 92476 | 134367 / 124417 / 326814 | 26223 | 519 / 474 / 1341 |
| 10-combat | after | 36.93 / 32.28 / 68.38 | 27.83 / 22.44 / 56.91 | 35.9 | 1047 / 1046 / 2237 | 1946 / 1968 / 3833 | 1291 / 766 / 5451 | 3501 / 3166 / 17029 | 641 | 388 / 352 / 1125 |
| 30x9-idle | base | 528.68 / 165.77 / 1030.09 | 495.19 / 127.10 / 994.85 | 952.4 | 1913 / 1959 / 4204 | 3018 / 3197 / 5961 | 14579 / 2056 / 63348 | - | - | - |
| 30x9-idle | after | 89.50 / 69.65 / 152.16 | 63.35 / 42.63 / 115.40 | 81.2 | 988 / 990 / 1903 | 1838 / 1880 / 3177 | 1127 / 447 / 3933 | - | - | - |
| 30x9-combat | base | 708.28 / 401.95 / 1414.54 | 673.97 / 370.06 / 1381.97 | 1330.0 | 1947 / 2025 / 3976 | 3083 / 3294 / 5910 | 16336 / 2180 / 80026 | 123643 / 105163 / 302591 | 27257 | 471 / 429 / 1272 |
| 30x9-combat | after | 98.04 / 85.14 / 154.59 | 71.38 / 55.77 / 124.34 | 91.8 | 1021 / 1020 / 2076 | 1886 / 1923 / 3478 | 1220 / 499 / 4756 | 3301 / 2610 / 16630 | 658 | 368 / 316 / 1460 |

Allocation per tick at 30x9 combat: 1,330 MB → 92 MB.

### Client, driven presentation per frame at 144 FPS (CPU)

Per-thread CPU of the client bench is sensitive to background load (the loaded pairs ran while
other desktop applications used several cores), so base and after ran back to back as pairs on
the same 29 mixed-renderer cases (`18e0d02e`'s case set). The quiet pair's after run is an
intermediate state of this unit (between `88afc422` and `c04820e1`); the loaded pairs use
`e7cec705`, the unit's last commit before the phase23 merge.

| remotes | run | per frame mean / p50 / p99 (µs) | per remote mean / p99 (µs) | alloc per frame (KB) |
| --- | --- | --- | --- | --- |
| 2, base (quiet machine) | 497 / 491 / 685 | 248.6 / 356.0 | 131 |
| 2, after (quiet machine) | 106 / 99 / 184 | 53.2 / 101.7 | - |
| 2, base (loaded, pair 1) | 1055 / 666 / 2693 | 527.6 / 1319.6 | 131 |
| 2, after (loaded, pair 1) | 114 / 102 / 231 | 56.9 / 123.0 | 29 |
| 2, base (loaded, pair 2) | 673 / 633 / 1220 | 336.6 / 614.4 | 131 |
| 2, after (loaded, pair 2) | 90 / 80 / 169 | 44.8 / 90.6 | 29 |
| 10, base (quiet machine) | 741 / 756 / 1212 | 74.1 / 119.7 | 596 |
| 10, after (quiet machine) | 120 / 110 / 265 | 12.0 / 32.6 | - |
| 10, base (loaded, pair 1) | 876 / 858 / 1682 | 87.6 / 180.4 | 596 |
| 10, after (loaded, pair 1) | 126 / 118 / 290 | 12.6 / 43.6 | 105 |
| 10, base (loaded, pair 2) | 873 / 807 / 1650 | 87.3 / 178.5 | 596 |
| 10, after (loaded, pair 2) | 142 / 122 / 380 | 14.2 / 57.5 | 105 |
| 30, base (quiet machine) | 2394 / 2346 / 3271 | 79.8 / 549.6 | 2904 |
| 30, after (quiet machine) | 897 / 832 / 1911 | 29.9 / 498.2 | - |
| 30, base (loaded, pair 1) | 2909 / 2781 / 4539 | 97.0 / 681.4 | 2907 |
| 30, after (loaded, pair 1) | 1099 / 938 / 3061 | 36.6 / 621.9 | 1064 |
| 30, base (loaded, pair 2) | 2978 / 2851 / 4813 | 99.3 / 687.0 | 2907 |
| 30, after (loaded, pair 2) | 951 / 776 / 2595 | 31.7 / 514.0 | 1063 |

On the merged branch, phase23's case set (three new cases in the
first 30, shifting out the two combined cases) gives 99 / 109 / 1,350 µs per frame for 2 / 10 / 30 remotes (loaded machine).
The 30-remote frame is dominated by a few heavy cases (per-remote p99 ≈ 0.5 ms): each install
materializes the remote's reduced PA graph twice (the finite probe sample and the installed
natives) and walks each new checkpoint once in `validateFinite` (cached by identity for the
tick's later frames).

### Final code: phase23 `9cf76d2a` (FX-TRUST) merged

FX-TRUST landed while this unit was integrating. It fixes the honest `seed_activation`
rejection (§6), so every combat receipt is now exact and fully validated (9 rows, 9 evaluated
victims per shot), and it adds a per-tick `AnimSyncStream.prune` that recomputed the resource
closure of every retained seed for every observer (66 % of the stream at 30x9). This unit's last
change computes each retained seed's closure once (§3). Back-to-back 30x9 runs on the same
machine state (loaded: about 1.6× the §2 timings), mean CPU:

| commit | 30x9 | tick (ms) | anim-sync/tick (ms) | capture/player (µs) | stream/observer (µs) | validate/receipt (µs) | receipts |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A: before the FX-TRUST merge (`d862063a`) | idle | 160.9 | 114.9 | 1853 | 1973 | - | - |
| A: before the FX-TRUST merge (`d862063a`) | combat | 178.3 | 131.1 | 1904 | 2216 | 5949 | 84 exact, 325 rejected |
| A, repeat | idle | 150.1 | 106.6 | 1716 | 1831 | - | - |
| A, repeat | combat | 157.7 | 115.3 | 1665 | 1946 | 5345 | 84 exact, 325 rejected |
| A, repeat | idle | 152.8 | 108.3 | 1746 | 1857 | - | - |
| A, repeat | combat | 163.6 | 119.5 | 1743 | 1997 | 5500 | 84 exact, 325 rejected |
| B: FX-TRUST merged (`d3f2d340`) | idle | 211.1 | 168.6 | 1692 | 3848 | - | - |
| B: FX-TRUST merged (`d3f2d340`) | combat | 241.5 | 198.2 | 1728 | 4250 | 10232 | 409 exact |
| B, repeat | idle | 209.0 | 165.9 | 1693 | 3752 | - | - |
| B, repeat | combat | 226.6 | 185.3 | 1620 | 3961 | 9711 | 409 exact |
| C: final, seed closures cached (`9572fe3a`) | idle | 165.2 | 122.0 | 1718 | 2282 | - | - |
| C: final, seed closures cached (`9572fe3a`) | combat | 191.3 | 147.3 | 1788 | 2513 | 10196 | 409 exact |

The final code's full run (`-PanimSyncBench=all`, same loaded machine; the benchmark's own table,
µs columns mean / p50 / p99; C above is its 30x9 rows). Receipts stay exact with the cache and
verify mode passes on C.

| cohort | per tick mean / p50 / p99 (ms) | anim-sync per tick (ms) | main wall / other-thread CPU per tick, mean (ms) | alloc/tick (KB) | capture/player (µs) | stream/observer (µs) | validate/receipt (µs, rows) | evaluate/victim (µs) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2-idle | 12.02 / 11.40 / 27.80 | 7.86 / 7.25 / 23.55 | 12.53 / 0.02 | 2882 | 3035 / 2920 / 5355 | 831 / 568 / 8294 | - () | - |
| 2-combat | 11.27 / 10.41 / 26.91 | 7.93 / 7.12 / 22.75 | 11.74 / 0.03 | 3761 | 2216 / 2192 / 4612 | 802 / 513 / 11147 | 590 / 544 / 1452 (1) | 775 / 746 / 1375 |
| 10-idle | 57.15 / 49.09 / 91.25 | 41.89 / 32.22 / 72.33 | 59.78 / 0.12 | 27785 | 1863 / 1851 / 3520 | 2238 / 1633 / 7143 | - () | - |
| 10-combat | 73.85 / 65.39 / 118.96 | 59.24 / 48.99 / 103.84 | 77.35 / 0.18 | 41253 | 1765 / 1761 / 3578 | 2534 / 1652 / 9396 | 9774 / 8240 / 34117 (9) | 514 / 500 / 1114 |
| 30x9-idle | 165.17 / 136.23 / 246.45 | 122.00 / 89.46 / 205.56 | 174.71 / 0.38 | 83679 | 1718 / 1700 / 3021 | 2282 / 1170 / 7029 | - () | - |
| 30x9-combat | 191.30 / 164.20 / 287.43 | 147.33 / 115.90 / 239.89 | 202.80 / 0.56 | 98816 | 1788 / 1756 / 3367 | 2513 / 1295 / 8742 | 10196 / 8779 / 30924 (9) | 528 / 479 / 1225 |

### JFR hot frames, 30x9 combat

Operation sample shares and the hottest frames under each operation (in-process JFR of the
measured ticks; `observerEmulation` is the untimed client-side reducer of the emulated observers).

**base** (119762 samples: observerEmulation 48%, stream 38%, validate 10%, endTick 4%, other 0%, evaluate 0%, appliedReport 0%)

| operation | inclusive frames (share of the operation's samples) | self frames |
| --- | --- | --- |
| endTick | 100.0% ServerBodyPoses.endTick; 99.1% ServerBodyPoses.evaluate; 99.1% ServerBodyPoses.evaluateInner; 77.4% AnimSyncCapture.commit; 59.1% AnimSyncCapture.freeze; 32.9% AnimSyncCapture.stage | 11.4% HashMap.putVal; 5.7% HashMap.getNode; 5.5% String.hashCode; 4.7% Arrays.copyOf; 4.4% HashMap$HashIterator.nextNode |
| stream | 99.1% AnimSyncStream.prepare; 88.2% AnimSyncStream.fold; 83.6% ObserverReducer.reduce; 82.4% ObserverReducer$Reduction.step; 62.1% PaGraph$Stack.freeze; 51.5% PaGraph$Stack.keyframe | 12.2% Arrays.copyOf; 9.4% ByteArrayOutputStream.write; 5.7% AnimationSchema.visit; 5.4% HashMap.getNode; 5.0% PaGraph.canonical |
| validate | 100.0% AnimSyncValidation.validate; 100.0% AnimSyncValidation.check; 100.0% AnimSyncValidation.victim; 95.7% ObserverReducer.reduce; 93.2% ObserverReducer$Reduction.step; 71.8% PaGraph$Stack.freeze | 14.5% Arrays.copyOf; 11.0% ByteArrayOutputStream.write; 6.7% AnimationSchema.visit; 6.1% PaGraph.canonical; 5.9% ArrayList.addAll |
| evaluate | 100.0% AnimSyncEval.body; 100.0% AnimSyncEval.evaluate; 68.8% AnimSyncEval.pose; 53.1% AnimSyncEval.pa; 37.5% PaGraph$Stack.materialize; 34.4% AnimationSchema.visit | 21.9% AnimationSchema.visit; 9.4% DirectMethodHandleAccessor.checkReceiver; 6.3% ArrayList.addAll; 6.3% ConcurrentHashMap.get; 6.3% InvocationMatcher.argumentsMatch |

**after** (5276 samples: observerEmulation 42%, stream 35%, endTick 17%, validate 4%, other 0%, evaluate 0%, appliedReport 0%)

| operation | inclusive frames (share of the operation's samples) | self frames |
| --- | --- | --- |
| endTick | 100.0% ServerBodyPoses.endTick; 97.5% ServerBodyPoses.evaluate; 97.5% ServerBodyPoses.evaluateInner; 76.3% AnimSyncCapture.commit; 45.7% AnimSyncCapture.freeze; 30.3% AnimSyncCapture.retain | 13.5% PublicMethods$Key.matches; 10.6% HashMap.getNode; 9.9% AnimationSchema.readValue; 3.4% ParameterizedTypeImpl.getActualTypeArguments; 3.4% ConcurrentHashMap.get |
| stream | 93.8% AnimSyncStream.prepare; 80.8% AnimSyncStream.fold; 59.7% ObserverReducer.reduce; 56.0% ObserverReducer$Reduction.step; 55.8% ObserverReducer$Reduction.advance; 22.8% PaGraph$Stack.freeze | 10.3% HashMap.getNode; 6.6% HashMap.putVal; 5.2% ImmutableCollections$ListItr.next; 5.1% AnimationSchema.writeBody; 5.0% AnimationSchema.references |
| validate | 100.0% AnimSyncValidation.check; 100.0% AnimSyncValidation.validate; 100.0% AnimSyncValidation.victim; 57.2% AnimSyncValidation.resolve; 56.4% AnimationResolver.resolve; 40.3% ObserverReducer.reduce | 15.7% AnimationSchema.visit; 7.2% AnimationSchema.visitRecord; 6.4% HashMap.getNode; 5.9% AnimationSchema.references; 5.5% AnimationSchema.writeBody |
| evaluate | 100.0% AnimSyncEval.body; 100.0% AnimSyncEval.evaluate; 80.0% AnimSyncEval.pose; 30.0% AnimSyncEval.pa; 20.0% MockMethodAdvice.handle; 20.0% AnimationContainer.get3DTransform | 10.0% InvocationMatcher.argumentsMatch; 10.0% LinkedList$ListItr.checkForComodification; 10.0% class_2960.method_29184; 10.0% HashMap.getNode; 10.0% KeyframeAnimation$StateCollection$State.lockAndVerify |


## 3. What changed

Every change keeps the computed values, bytes, exceptions and their order; the caches hold
immutable values (every registered record copies its lists, audited) or prove their inputs
unchanged by identity.

- **Schema codec** (`AnimationSchema`): accessors, generic types, nullability and enum constants
  resolved once per layout (LambdaMetafactory getters, unboxed primitives) instead of reflection
  per field; components that can hold neither a nonfinite number nor a resource ref are skipped in
  the semantic walks (depth checks kept); encoding into a thread-confined buffer per nesting level
  with the same bound; batches splice the bytes of a target's shared checkpoint components encoded
  by the first observer of the tick (for eight ticks, so receipt validation reuses them), each
  copy repeating the position's type check and the depth bound.
- **Identity caches of immutable values** (bounded, thread-confined): finiteness of checkpoints,
  seeds, graphs, their components and the catalog's shared keyframe part lists;
  `validateComplete` of graph instances; digests and reference sets of the resolver's inputs;
  ordered ParCool actions; the reference closure of a checkpoint per tick; stamped sent endpoints
  per sent identity.
- **Reducer** (`ObserverReducer`, `PaProjection`, `LrProjection`, `ParCoolStages`): one native
  scratch per step for the tick and every PA decision, carried into the next step when it froze
  into that step's input graph; a decision that starts or stops nothing returns its input graph
  instead of freezing an equal one; a ParCool stage that keeps every root and node object returns
  its input graph. `ObserverReducer.Memo`: validation and folds of one observer and target life
  resume from the last reduction when the request extends exactly its inputs (seed, activation,
  assets, policy, applied prefix, boundary checkpoints by identity).
- **PA graph** (`PaGraph`): an actor clip is compared with its registered content field by field
  instead of serializing it and a deep copy (clips whose PA copy is not content-stable keep the
  serializing comparison); copies of copy-stable clips are equal by construction; the generation
  catalog memoizes part, metadata and first-person refs and one immutable part list per clip; the
  body-part inventory check is one pass with the same precedence.
- **Resources and capture**: the store installs in O(additions) instead of copying itself; a
  capture pins the reference set it staged.
- **Stream after FX-TRUST**: each retained seed's resource closure is computed once per seed
  (the seed is immutable and its lease pins every definition the closure reads) instead of per
  observer per tick in `prune`.

Tests forcing both paths: `ObserverReducerScratchTest` (shared vs per-operation scratch, values and
encoded bytes), `ReductionMemoTest` (validation with and without resumption, equal verdicts);
the benchmark's verify mode (above).

## 4. Equality evidence

`dev/anim_sync_perf_gate.py <base-run> <after-run>` compares two `just test` runs (the U10 report
`build/anim-sync-oracle/equality-report.json` and both sides' fragments): every fragment's
status, message and measurements, the report's cohort counts, mixed-renderer results,
differentials and manifest re-runs. Only run-variant fields (durations, report-only costs,
artifact paths, timestamps, Mixin handler ids) are ignored; the report's gating logic is untouched.
Wall-clock cost counters (`*Nanos`, e.g. `wire* Nanos`) are legitimate run-to-run variance and are
ignored by leaf name (presence is still required); the parallel proof and the bench comparison
likewise compare only non-`Nanos` counters. No `Set.of`/`Set.copyOf`/`Map.of`/`Map.copyOf`
iteration may feed bytes: their iterators start at a per-JVM random index (`SALT32L`), so two
runs of one commit serialize different orders. `AnimationSchema.references` therefore returns
walk order in a `LinkedHashSet` (fixed U10 variance: batch definition order differed run to run
while every count matched; pinned by `AnimationSchemaTest.referencesIterateInFirstOccurrenceWalkOrder`).
Fixing byte order changes the wire digest vs any base captured before the fix: re-capture the
gate base once from fixed code; the bench canonical SHAs are unaffected (checkpoint digests only).

| base run | after run | fragments | mixed renderer | result |
| --- | --- | --- | --- | --- |
| `18e0d02e` | `88afc422` (this unit, before the phase23 merge) | 123 pass, 0 unavailable, 0 fail | 4,118 bit-identical; SBW passenger seat the known unavailable case; same sample digests | identical |
| phase23 `25bead1b` | merged branch | 127 pass, 0 unavailable, 0 fail | 6,248 MAIN samples bit-identical, 0 unavailable; the FX-POSE SBW prone diagnostic (142 samples) excluded as on base; native `afc350fe…baefc9`, server `274d09ea…a9865a` on both | identical |

Also: the benchmark's workload outcome is the same on both commits (§2), its verify mode passes
on the final code (10 players, 150 ticks), and `ObserverReducerScratchTest`/`ReductionMemoTest`
force both paths. The wire is unchanged: no schema, manifest or fixture digest changed.

## 5. Targets

| target | after (30x9 combat unless noted) | met |
| --- | --- | --- |
| server anim-sync CPU ≤ 5 ms/tick mean | 71.4 ms (base 674.0); 98.0 ms with the r10 END-tick pose | no |
| server anim-sync CPU ≤ 10 ms/tick p99 | 124.3 ms (base 1,382.0) | no |
| capture ≤ 0.1 ms/player | 1.02 ms (base 1.95) | no |
| validation ≤ 1 ms/receipt | 3.30 ms mean, 6.58 ms per exact 9-row receipt, 0.66 ms/row; 0.28 ms for 1-row receipts at 2 players | no (yes for 1 row) |
| evaluation ≤ 0.1 ms/victim | 0.37 ms (base 0.47); every row of an exact receipt is evaluated (9 at 30x9) | no |
| client ≤ 1 ms/frame driven presentation, 30 remotes at 144 FPS | 0.90 ms quiet (base 2.39); 0.95–1.19 ms loaded (base 2.91–2.98); p99 1.9–3.1 ms | borderline: mean met on a quiet machine only |

The table uses §2's quieter paired runs. On the final merged code (§2 final, loaded machine) every
combat receipt is exact: 30x9 combat anim-sync 147 ms/tick, capture 1.79 ms/player, 10.2 ms per
9-row receipt, 0.53 ms/victim; against the paired pre-FX-TRUST run the extra cost is the full
validation and evaluation of every receipt.

The server targets are not reachable with bit-identical results at this design; the remaining
cost is structural, not overhead:

- **Observer folds (≈ 80 % of the stream, ≈ 30 ms/tick).** Every observer/target pair refreshes its
  seed every 20 ticks by reducing that observer's presentation from its last seed: at 30x9,
  13.5 folds × ~20 reducer steps = ~270 steps per tick. A step is a native PA tick and freeze of
  the whole PA graph (15–17 KB for a gun holder), the ParCool boundary stage, the TaCZ/LR
  decisions and `validateComplete` of the new graph: ~110 µs each after this unit (~1.8 ms on
  base). The inputs are observer-specific (seed, applied capsules), so no step is shared. Cutting
  this needs an ADR4 change (observer-independent fold seeds, a longer fold age or client-proven
  folds; each a wire/manifest change) — an owner decision.
- **Capture (≈ 1 ms/player, ≈ 31 ms/tick).** The native freeze of PA, LR, ParCool and vanilla state
  (46 % of the END tick; 22 % of the freeze samples and 13 % of capture's are Mockito dispatch
  on the doubled players, which real players do not pay) and the decoded retention round trip
  (23 %: encode, decode and digest of a 22–24 KB checkpoint per gun holder per tick). Reusing unchanged decoded components across
  ticks is exact but needs a component-level codec; it would save at most the decode share.
- **Validation (≈ 0.66 ms/row).** A row resumes the observer's reduction to bucket A (usually a
  few steps) and resolves it: stamping, digests and pins are now per instance; what remains is the
  reduction steps and the presentation digest of the reduced graph.
- **Evaluation (0.37 ms/victim)** materializes the victim's reduced PA graph into fresh natives and
  samples them (a fresh native stack per evaluation is ADR4's isolation rule).

Wire format: unchanged (no schema/manifest change was needed or made).

## 6. Findings outside this unit (behaviour, not changed)

- **`REJECTED:seed_activation` on honest receipts** (`AnimSyncValidation.victim`): a row whose
  seed has starts had to carry a `SEED_CONTINUE` start; once the seed's continued nodes left the
  reduced graph, honest receipts were rejected (325 of 409 combat receipts at 30x9 on both
  commits of §2). Fixed on phase23 by FX-TRUST (`f8a0da5c`, "Added UOPT P0" in
  [anim-sync-fx-trust](anim-sync-fx-trust.md)), which also records the remaining
  `prefix_backwards` case for FX-CLIENT.
- **Lost application report on fold adoption** (`ClientAnimationJournal.report`): adopting a
  same-revision fold drops the application reports not yet sent; the server then rejects later
  receipts (`unsent_application_h`) or the fold is unavailable (`seedFoldUnavailable:bounds`).
  The emulation carries them over (a deliberate deviation so receipts stay honest). Owner: U8.
- **256 KiB batch cap** (`AnimationSchema.MAX_PAYLOAD_BYTES`): a gun holder's checkpoint is
  22–24 KB (its graph 15–17 KB), so a FULL burst of 10+ gun holders, or about 14 gun holders in
  steady state, exceeds one batch and `SnapshotStream.exact` disables that observer's stream.
  Hence the `30x9` cohort; 30 observers seeing all 29 remotes do not stay exact today.

## 7. Thread confinement (for UPAR)

This unit adds no threads; everything below runs on the server thread (client: the render thread)
today. Every cache holds immutable values or is keyed by them, so a cache miss only costs time:
a per-worker copy is always exact, a shared one needs the synchronization noted.

| state | owner | today | on worker threads |
| --- | --- | --- | --- |
| `LAYOUTS`, `CONSTANTS`, `FINITE_DEPTH` (`ClassValue`), `PartLists.DEPTH` | `AnimationSchema` | immutable after first computation | safe as is |
| `SCRATCH`, `SCRATCH_DEPTH` encode buffers (per nesting level, ≤ 256 KiB each) | `AnimationSchema` | `ThreadLocal` | per-worker copy (automatic); memory per worker |
| `SPLICES` shared encodings (8 scopes) | `AnimationSchema.shareEncoding` | `ThreadLocal`, registered by `AnimSyncStream.share` on the thread that prepares batches | per-worker copy; a worker encoding a batch must register the tick's shared components itself (else no reuse, same bytes) |
| `FINITE_VALUES`, `DIGESTS`, `REFERENCES`, `PartLists.REFERENCES` (identity, ≤ 1024, cleared when full) | `AnimationSchema` | `ThreadLocal` | per-worker copy (automatic) |
| `VALIDATED` (graph → node map, ≤ 1024) | `AnimationGraphValidator` | `ThreadLocal`; callers share the returned map read-only | per-worker copy (automatic); returned maps must stay unmodified |
| `ORDERED` (ParCool state → ordered actions) | `ParCoolFrame` | `ThreadLocal` | per-worker copy (automatic) |
| catalog memos (`metadata`, `parts`, `bodyParts`, `copyStable`, `firstPerson[16]`) | `PaGraph.Catalog`, shared per resource generation | `ConcurrentHashMap`s; `firstPerson` is a racy cache of immutable records | safe as is |
| `PaGraph.Stack` (`verified`, `lastFrozen`, ids, allocation state) | one native scratch | confined to the reduction, evaluation or capture that owns it | never share a stack between threads |
| `Reduction.carried` step scratch | one `ObserverReducer.Reduction` | confined to that reduction; copies never carry it | one reduction per thread at a time |
| `ObserverReducer.Memo` | `AnimSyncValidation.Intake.memos`, per observer and target life | server thread; validation and folds mutate it | serialize all reductions of one observer/target pair (same worker or a lock); never two concurrently |
| `Intake.memos` map and its pruning | `AnimSyncValidation.memo` | server thread (as all of `INTAKE`) | main thread only, or per-observer confinement with `INTAKE` |
| `STAMPED` (sent identity → stamped endpoint, ≤ 1024) | `AnimSyncValidation` | static `IdentityHashMap`, server thread | per-worker copy (`ThreadLocal`) or per-observer; plain sharing is unsafe |
| `CLOSURES` + `closureTick` (checkpoint → closure, one tick) | `AnimSyncStream.closureOf` | static, server thread | compute the tick's checkpoint closures on the main thread before forking and publish them immutably, or per-worker copy |
| `State.seedClosures` (retained seed → closure) | `AnimSyncStream.State`, per observer | mutated in `prune` | per-observer confinement (the worker that owns the observer) |
| `staged`, `stagedRefs` (last staged checkpoint's references) | `AnimSyncCapture` | static, set in `stage` and consumed in `retain` of the same capture | pass through the capture as locals (or `ThreadLocal`) before captures run in parallel |
| resource `STORE` (`definitions` is now a mutable `HashMap`, `pins`) | `AnimSyncLedger` / `AnimationResources.Store` | server thread; `installAll`, `pin`, lease `close` mutate it | main thread only, or one lock around every store access; read-only lookups are safe only while nothing installs or retires |
| `AnimSync` counters (`validateRows`, `validateRowNanos`, …) | `AnimSync.COUNTERS` | static `LinkedHashMap`, server thread | main thread only, or per-worker counters merged after the join |
| switches `shareStepScratch`, `verifyMemo`, `memoize` | `ObserverReducer`, `AnimSyncValidation` | `volatile`, set only by tests/benchmark | safe as is |
| `EXPIRY` per-observer expiry queues (`dates`, `ticks`, `pending`) | `AnimSyncValidation`, per observer | server thread; `schedule`/`due`/`retired` mutate it | main thread only, or per-observer confinement with `INTAKE` |
| `DEFERRED` deferral FIFO per observer, `deciding` flag, `Deferred.decided` | `AnimSyncValidation` | server thread; `defer`/`decideDeferred`/boundary mutate it | main thread only: `decideDeferred` replays validation inline at boundary record; never drain from a worker |
| `PreparedReceipt.deferred` (pending → decided link) | per receipt | published once by `prepareAction`; `decided` written once by `decideDeferred` | hand to one consumer; `replay` unwraps after the boundary, never concurrently with the drain |

**Measuring offload.** `AnimSyncBench` reports per tick the server thread's CPU in the measured
operations (`perTick.cpuNanos`, `animSyncCpuNanos`), its wall time in them
(`perTick.mainThreadWallNanos`, per operation `wallNanos`) and the CPU of every other thread
during the tick (`perTick.otherThreadsCpuNanos`; today JIT/JFR background only). With parallel
stages the main-thread wall time is the offload target and main-thread CPU plus other-thread CPU
the total cost. The observer emulation stays on the main thread outside the measured operations.

## 8. UOPT completion: honest-receipt regression

The failed `ReductionMemoTest` from the interrupted final check was a **test-helper bug**,
not a production rejection of an honest client. `AnimSyncScenario.row` included the attack's
application at A=11.75 even in its first receipt at A=11.5 (both are in integer bucket 11).
FX-TRUST's `AnimSyncValidation.authorizeProof` correctly returns `future_application` when
`proof.applyA() > receipt.a()`, before replay, as required by ADR4 §3.5. It is not enough for
the application and receipt to share a reducer bucket.

The helper now excludes that capsule from both applications and reduction until its exact
application time, and uses the pre-application frame/revision for earlier receipts. Later
receipts keep the original application proof. `ReductionMemoTest` checks the immediately
preceding, exact and following Q16 samples of A=11.75, as well as the original increasing-A
replay comparison. Its two paths explicitly clear the whole-receipt verdict cache introduced
by FX-TRUST, so both reconstruct rather than returning the first verdict; all value/recipe
equality and minimum-resumption assertions remain. The receipt correction changes no production
animation/validation code or schema; evidence-only timeout settings are restored afterward.

Continuation source: `c47508ac`; clean merge of phase23's transport-only `2eded3a7`:
`e604509b`. Evidence is in `WS/.dev/uopt-fin/` (command logs, return codes and archived
fragments). The four targeted `ReductionMemoTest` cases pass. All original equality and
minimum-resumption assertions remain.

- `just format` and `just check`: pass; no formatting changes.
- Ordinary `just test`: failed solely on `:client:oracleTest`'s five-minute JavaExec timeout
  (6m15s total build). Load average at command start/end: `2.70/3.31/3.53` ->
  `3.77/3.67/3.66`. All 33 server fragments and 97 completed client fragments passed;
  the main mixed-renderer fixture passed before the timeout.
- Evidence-only `gate-long.sh` rerun of the full `just test`: pass. This used the existing
  local five-to-25-minute substitution and restored `dev/oracle-runner.gradle` afterward.
  Actual guarded-run load: `5.79/4.43/3.95`; completion: `19.69/12.30/7.48`.
- Ordinary `just java-check` also reached only that same client oracle timeout (5m49s build,
  load `19.69/12.30/7.48` -> `4.00/7.20/6.82`). `check` depends on `oracleTest`, so the
  complete Java build needs the same evidence-only deadline substitution on this machine.
  No test assertion or equality gate is changed.

The pre-merge extended run compares identical to the archived phase23 `9cf76d2a` run
(`WS/.dev/uopt/base6-long`): 132/132 fragments pass, 6,674 MAIN samples, maximum corner
error 0 m, unchanged native/server sample digests. The existing 142-sample
`sbw_main_hand` diagnostic remains unavailable and excluded from equality.

After merging `2eded3a7`, `just format` / `just check` pass again. The full
`just java-check` with the same temporary oracle deadline passes (7m37s build; load
`3.29/5.16/6.08` -> `4.01/4.25/5.25`): client 529 tests (two existing opt-in skips),
server 1,717 tests plus two allocation tests, zero failures/errors. All 39 cases in the six
classes using `AnimSyncScenario` pass. All 13 required in-process GameTests pass and their
server shuts down. The five-minute runner file is restored and has no diff.

The merged comparison exposed one gate-normalization omission: the transport change removes
two mixins, changing the synthetic UID in the native TaCZ hook's reflected name from
`handler$fnh000$tacz$setRotationAnglesHead` to `handler$fnf000$tacz$setRotationAnglesHead`.
All other fields already match. Ignoring synthetic Mixin IDs is the existing §4 rule;
`anim_sync_perf_gate.py` now normalizes only that six-character UID in
`measurements.injectedHandler`. It still compares the member's owner, modifiers, method,
descriptor and presence, and all numerical fields. Three Python regression tests prove UID
renaming passes while those semantic changes, malformed IDs and changes in other fields fail.
The final merged comparison is identical: 132 pass fragments, the same 6,674 MAIN samples,
0 m maximum error and both unchanged sample digests; the 142-sample diagnostic stays excluded.
After this Python-only gate fix, final `just format` / `just check` pass; the Python portions of
`just test` pass again: 35 rig tests (including the three new gate regressions), 40 isolated-rig
tests and 80 RoomClosePolicy checks. The final perf-gate rerun uses the merged native fragments.

No animation/validation semantic change, schema/wire change or ADR4 deviation was needed.
No new CPU benchmark was run in this continuation; the measured costs and remaining targets
above are unchanged.
No new dependent-unit item was found; the existing FX-CLIENT items in §6 remain outside UOPT.

## 9. UOPT-2 checkpoint 1 (not integrated)

Base: phase23 `fc3b218a` (FX-CLIENT-3), merged into `fulla/as-uopt2`. This is a
tested continuation checkpoint, not completion of the six-option unit or its merge gates.

- A1 stream retention: count references of retained batches and seed closures incrementally;
  remove a sent definition exactly when its retained count becomes zero. Newest-seed indexes
  preserve the original first-insertion tie rule for entity, life and complete generation.
  Lease release, restart, replacement and expiry still use the original boundaries.
- A2: reuse canonical item text only for the same registry and a matching defensive stack
  copy, including count and component patch. Unchanged equipment reuses immutable definitions,
  bytes, digests and references only within the same resource generation and target life.
- A3(a)/(c): retention and seed round trips register immutable components with the existing
  thread-local schema splice cache; seed content uses the existing identity digest memo.
  Every round trip asserts `decode(encode(value)).equals(value)`. No decoder or wire file changed.
- A13: reuse stamped identities while every input is unchanged, reuse a natural sorted eligible
  map, and use the newest-seed indexes in timing-seed and fold selection.

Thread confinement for UPAR-2: reference counts, seed indexes, stamp identities, inventory and
equipment memos are **main-thread only**. Pure tasks receive immutable planned values. Retention
encoding uses only immutable record components and `AnimationSchema`'s existing **ThreadLocal**
splices/digest/buffer caches; it does not read the live ledger or inventory memos.

`WS/.dev/uopt2/checkpoint1/`: targeted Gradle run with `--no-daemon --max-workers=2`, heavy lock
and memory preflight: **54 tests pass**, zero failures/errors/skips (EquipmentMemoTest 2,
AnimSyncStreamTest 11, AnimSyncValidationTest 25, AnimSyncResourcesTest 5, AnimSyncLedgerTest 11).
The transformed `AnimSyncJournalOracleFixture` and new registered `EquipmentMemoOracleFixture`
pass on Fabric Loader 0.19.3 with the production pack. The latter executes 128 native TaCZ
ammo writes and compares memo-on/off equipment, ordered definition bytes and encoded records.
The former retains its native journal/continuation/400-tick retention assertions. Stream unit
tests additionally compare incremental counts and newest seeds with the old full rebuild after
each populated publication. Random inventory tests cover count, components, empty slots,
selected slot, registry identity and life/resource boundaries.

Still required: A1 validation prune/intake, A3(b) component decode reuse (requires a schema hook
owned by WIRE-CODEC), A4, A5, benchmark defaults and before/after measurements at 30x9/30x29,
memo-off/flight differentials, refreshed equality base on the final phase23 head (including
FX-TRUST-2 if landed), all four full gates, and integration. No oracle timeout was changed.

### Checkpoint 2

A1 intake now reads the authorized history and its disjoint additions directly instead of copying
the full retained map per applied report. Authorization, prune, commit and failure order stay in
place; FX-TRUST-2's frozen-bound/admission code is untouched. A4's repeated legacy vector constructor,
stack-transform and component reflection calls use immutable method handles resolved once in
`LrBodyPose.Reflect`; target failures retain the reflection wrapper consumed by the existing caller.
No one-freeze reuse was introduced yet. LEGACY-OUT can remove these handles with `sample/read`;
the canonical PA graph and capture remain independent of the legacy vector read path.

`WS/.dev/uopt2/checkpoint2/`: targeted LrBodyPoseTest, HeldWeaponPoseTest and
AnimSyncValidationTest pass with zero failures/errors/skips. The transformed PA graph,
LR transition and journal fixtures pass. The required in-process GameTest server shuts down
normally. This remains an incomplete UOPT-2 checkpoint, without full-gate or benchmark claims.

### Checkpoint 3

A1 validation uses typed `(life,event,node)` / `(life,event)` keys instead of string creation
and parsing. Retained seed-life and protected-start counts are updated at seed insertion/removal;
`prune` consumes those counts instead of rebuilding all seed start sets every call. Main-thread
confinement is unchanged. `AnimSyncJournalCases` independently rebuilds reference, seed-life and
protected-start counts beside the indexes at every tick of its transformed retention fixture.

`WS/.dev/uopt2/checkpoint3/`: all 36 targeted Stream/Validation tests and transformed
journal/trust fixtures pass. Still pending in A1: tick-ordered expiry queues for history,
evidence, accepted proofs and first-report entries; existing expiry scans and prune call points
are preserved. A5 is also pending: `ObserverReducer.Memo` retains a private `Reduction` and has no
public copy API at this head (`resume` and the reduction copy constructor are private). UPAR-2's
planned memo copy API can supply the isolated receipt-bucket snapshot without sharing mutable
fold/validation state with flight tasks. No private-access workaround or competing reducer was added.

### UOPT-2 continuation implementation

A1 now schedules proof/evidence, accepted-start and first-report expiry by their last-live tick.
Replacement and early capsule removal mark only the affected keys for immediate reconsideration;
seed changes revisit expired proofs protected by a retained seed. `PruneDifferential` executes the
old full-map filters beside the queues on every native retention-fixture tick, including unpair
and the exact last-live boundary. Reference counts and newest indexes retain their independent
full-rebuild checks.

A4 samples and captures one frozen graph when the tick, stack revision, prior graph base and
journal position still match. Verify mode freezes again and asserts graph equality. The two-pass
differential compares all checkpoint bytes and all 36 legacy bone scalar bits, with the same
isolated target identity. LEGACY-OUT may delete `FrozenSample`, the sample-base helper, revision
invalidation, sample switches/counters and the vector method handles along with legacy sampling;
keep canonical `Stack.graph` freezing when no sample precedes it.

A5 publishes a private `Memo.copy()` at the validated receipt bucket. Immutable values are shared;
mutable reduction/native scratch and boundary arrays are copied. Every flight worker owns a
ThreadLocal continuation and resolved-age cache. Boundary availability and generation checks run
before a cache lookup; cache hits additionally require identical checkpoint objects and family
asset/policy identities. Out-of-order ages safely replay if resumption inputs no longer extend.
The published snapshot is never mutated. Main remains parked while workers read target history.

The worker differential found that `AnimationResolver.resolve` temporarily mutates the store's
pin-count HashMap, despite the immutable recipe inputs. Validation now serializes that lease
scope on the store monitor. UPAR-2 must preserve this scope and use the same monitor for any other
worker pin/release operation; the enclosing barrier must keep main-thread store writers parked.
Two-worker same-receipt repetitions compare with fresh serial replay after the live validation
memo has advanced independently.

A3(b) remains explicitly deferred by coordinator instruction. Needed WIRE-CODEC hook: in the
schema decoder, memoize a successfully decoded immutable component by its exact byte span,
declared component type, wire layout/version and decoding context. A hit must retain the same
length, type, position/depth and budget checks as a fresh read. Return the previous immutable
instance, with bounded per-worker memo storage or immutable safely published entries. The outer
ledger cannot bypass construction of nested records without that decoder hook; no parallel field
list or schema change was introduced. A3(a)/(c) and the full round-trip equality assertion remain.

Benchmark defaults now include every native ParCool Action.tick, observer A spread 2.4, three
additional flight ticks, and both 30x9 and 30x29. Flight CPU/allocation/wall time enters the per-tick
sum. Verify mode compares resumed flights with fresh replay (including refusal class/message)
and retains the existing observer reduction differential. Per-operation mock counts and a CPU
calibration yield a production-equivalent END-tick estimate, reported separately from raw cost.
Large cohorts skip the whole-recording JFR table. `-PbenchPayloadCap=16777216` derives a marked
measurement-only Blockfield jar by changing the two payload-limit integer constants; the source,
normal oracle tasks, equality gates and distributable jar retain 256 KiB. The artifact inventory
records the derived jar's hash. A whitelist/byte-level regression verifies no other class changes.

For the refreshed equality BASE, test-only overlays add the same native codec/flight fixtures,
prune differential and benchmark source to unmodified phase23 production. The equipment reference
always runs ItemStack.CODEC freshly; it does not use the memo under test. No missing fixture,
unavailable result or opt-out is treated as an equality pass.

The refreshed equality comparison initially found one nonsemantic difference: FX-CLIENT-3's
`measurements.scene` serializes nested `Map.of`/`Map.copyOf` maps with `Map.toString`; their entry
order is salted per JVM. The retained raw base/after strings have identical keys and scalar
values, including every clock fraction and counter. The gate now parses this one debug-map field
and compares the complete nested key set and exact scalar strings, ignoring only map-entry order.
Duplicate keys, malformed maps and trailing content are rejected from normalization (raw strict
comparison remains). Three regressions prove reorder-only equality and failure for changed clocks,
entity ids, counts, missing keys, branches and empty strings. No fixture/schema/output was changed.
The resulting comparison is identical: **148 pass fragments, zero unavailable/fail**, **7,952 MAIN
samples**, 0 m maximum error; native and server sample SHA-256 both
`30c1c4204c68c15034a899a6980293a77e36a294fbad6a4d4fd8746277b80ac8` on base and after.
Both `just test` runs completed within the normal five-minute JavaExec deadline; no timeout file
was changed. Base production is `fc3b218a`; test overlays and raw evidence are in
`WS/.dev/uopt2/{base-overlay.json,equality-base,equality-after,equality-gate.txt}`.

### UOPT-2 before/after measurements

Full measured runs: base production `fc3b218a`, after production `42ce013f`; 120 warm-up + 150
measured ticks per cohort, combat only, native action counters, A spread 2.4, three additional
flight ticks, 16 MiB measurement cap on both. Raw JSON with operation distributions, encoded-byte
counts, workload outcomes and machine provenance is [anim-sync-perf-uopt2.json](anim-sync-perf-uopt2.json).
Original output/JFR/logs stay in `WS/.dev/uopt2/{bench-base,bench-after}`. This is one serial thread,
Temurin 21.0.12+8, Ryzen 5 7535U; background work remained running. Load averages (1/5/15 min):
base start `3.33/3.48/2.76`, finish `4.06/3.58/2.92`; after start `4.03/3.60/2.95`, finish
`3.43/3.58/3.05`. These costs do not establish a homeserver capacity gate.

| cohort | run | CPU/tick mean / p50 / p99 (ms) | allocation/tick (MB) | bench-quiet projection (ms/tick) | raw END/player (µs) | production-equivalent END/player (µs) |
| --- | --- | --- | --- | --- | --- | --- |
| 30x9 | base | 179.54 / 165.25 / 274.85 | 175.41 | 157.63 | 2,138.43 | 935.29 |
| 30x9 | after | 137.95 / 121.00 / 209.11 | 114.10 | 121.12 | 1,888.76 | 797.03 |
| 30x29 | base | 436.96 / 442.04 / 520.02 | 464.05 | 383.64 | 1,974.43 | 814.63 |
| 30x29 | after | 367.17 / 371.11 / 464.02 | 285.20 | 322.37 | 2,012.32 | 843.13 |

CPU improves 23.2% / 16.0%, allocation 35.0% / 38.5%. Flight resolution plus evaluation per
victim-tick falls 1,971 → 824 µs at 30x9 and 2,094 → 987 µs at 30x29. The larger cohort's END
figure is slightly slower in this loaded pair; this is reported, not hidden by the total saving.
The remaining codec decode work is unchanged (A3(b) deferred).

"Bench-quiet" uses the study's §3.1 normalization method: the mean of the **base** 30x9 END ratio
2,138.43/1,886 and capture ratio 1,168.15/1,021 is 1.138983; divide both runs by this common
factor. It is a projection to UOPT's historical quiet machine, not a second observed quiet run.
Production-equivalent END subtracts measured mock calls × this run's calibrated dispatch CPU:
base 193.475 calls/player, 6,219 / 5,995 ns/call; after 188.475, 5,792 / 6,203 ns/call
(30x9 / 30x29). It estimates the harness cost; it is not a production timing measurement.

Both runs have identical receipts, verdicts, observer applications/activations/adoptions and every
non-timing production counter, including all encoded byte counts. In the 150 measured ticks:
30x9 has 148 exact / 2 prefix-backwards receipts and 2,160 folded seeds; 30x29 has 146 exact /
4 prefix-backwards and 6,660 folded seeds. The benchmark emulator's existing rejections remain
visible. Including warm-up: 6,831 / 19,749 victim flight checks, **zero flight failures** on both.
No admission policy or equality criterion was weakened to improve these figures.

Large-cohort verify mode (120 warm-up + 40 measured ticks each) passes: 3,915 / 10,527 flight
checks, zero flight failures, and 45,702 / 137,853 observer-reduction comparisons. Gradle reports
`BUILD SUCCESSFUL in 4m22s`. The evidence wrapper subsequently failed while archiving because its
script was edited during that run; the untouched benchmark outputs were copied manually before
any later benchmark, and the wrapper failure is recorded separately from Gradle's successful
result in `WS/.dev/uopt2/bench-verify/wrapper-note.txt`.

The additional native four-player mixed-family bit audit passes on original phase23 production
and the optimized code (120 warm-up + 40 measured ticks, verify enabled): **640 complete retained
S[t] byte digests plus 23,040 raw legacy bone float bits** match. Combined SHA-256:
`a0ae5781ca389b9c8b83d4416ba88b4eae806b1bbe9f46514f943e3ae40cfd21`.
All receipt, observer and flight outcomes match as well; both runs have zero flight failures.
The checksum is computed outside timed operations and is now emitted by the benchmark. Neither
an absent checkpoint nor unavailable evaluation can count as a matching native sample.

Full `just java-check` passes: client 534 tests (two existing opt-in skips), server 1,726 tests
plus two allocation tests, zero failures/errors. All 13 in-process GameTests pass; their server
stops normally. The added classes include 7 ReductionMemo, 9 Capture, 12 Ledger and 2 Equipment
cases. `just format` and `just check` pass on the same source, with the original oracle deadlines.

## 10. UOPT-2c merge onto phase23 bb773d7e and integration

Base: phase23 `bb773d7e` (WIRE-CODEC, FX-TRUST-2, WIRE-REPORTS, FX-CLIENT fixture updates) plus
test-only overlays (the UOPT-2 bench source, native codec/flight fixtures, `PruneDifferential`,
bench-key runner lines, the two UOPT-2 server fixtures in session and shard lists); base
production is untouched. After: `fulla/as-uopt2c` merged production. Raw evidence:
`WS/.dev/uopt2c/` (`equality-base1/2`, `equality-after1/2`, `bench-verify-base/after`,
`bench-base/after`).

Merge conflicts (all keep-both): `dev/oracle-runner.gradle` (UOPT-2 bench keys
`benchCombatOnly/benchFlight/benchASpread` beside the sharded runner; one dangling `bench`
reference fixed after), `AnimSyncValidation.forget/clear` (`EXPIRY` beside FX-TRUST-2's
`DEFERRED`), the server oracle session (`FlightContinuationOracleFixture` beside
`WireCodecOracleFixture`), and `dev/oracle-shards.json` (the two UOPT-2 server fixtures were
missing or `verifyOracleShards` fails the gate). No wire/codec/client-intake file was touched.
Adaptation to the deferral FIFO: expiry queues and `DEFERRED` coexist (both cleared in
`forget`/`clear`); prune never touches the FIFO (asserted every retention tick), and the
deferred decision replays the same authorize/prune path with its original receive time, so no
expiry predicate changed. `PruneDifferential` snapshots the FIFO (order, activation, decision
state; vacuous on pre-deferral production) and `ReductionMemoTest` drives an honest receipt
through `start_bound_pending`, decides it at the next boundary, then proves resumed validation
equals full replay. A3(b) stays deferred: it needs the decoder hook documented above.

Equality gate (`dev/anim_sync_perf_gate.py` with the salted-map normalizer): base1 vs base2,
base1 vs after1, base2 vs after2, after1 vs after2 are all **identical: 153 pass fragments
(114 client + 39 server), zero unavailable/fail; 7,952 MAIN samples at 0 m maximum error**;
native and server sample digests equal across all four runs. One base1-vs-base2 fragment
(`fx-client-production-wire` scene) differs only in JVM-salted map-entry order; the normalizer
proves identical keys and scalars.

Bit audit and verify mode (120 warm-up + 40 measured ticks, `4:3,30:9,30:29`, combat only,
verify, 16 MiB measurement cap): all three cohort `canonicalAndLegacySha256` match base
exactly (`4-combat f0106df4…`, `30x9 1151338b…`, `30-combat 1a2d8d41…`); flight checks
1,368 / 3,915 / 10,527 with **zero failures** and identical verdict distributions on both.

Before/after benchmark (120 warm-up + 150 measured ticks, combat only, A spread 2.4, three
flight ticks, 16 MiB cap; Temurin 21.0.12+8, Ryzen 5 7535U; load base `4.97/4.71/5.24`, after
`4.27/4.27/4.89`): identical SHAs, verdicts, flight outcomes, receipt counts and every
non-timing counter (only `*Nanos` timing counters differ).

| cohort | run | CPU/tick mean / p50 / p99 (ms) | allocation/tick (MB) | production-equivalent END/player (µs) |
| --- | --- | --- | --- | --- |
| 30x9 | base | 196.03 / 203.37 / 324.14 | 174.58 | 1,085.93 |
| 30x9 | after | 155.40 / 140.31 / 254.37 | 114.50 | 913.19 |
| 30x29 | base | 485.67 / 489.77 / 657.76 | 461.50 | 1,040.99 |
| 30x29 | after | 388.02 / 384.83 / 504.86 | 286.17 | 805.24 |

CPU improves 20.7% / 20.1%, allocation 34.4% / 38.0%, production-equivalent END 15.9% / 22.7%.
(The earlier −23.2%/−16.0% figures were measured on the older `fc3b218a` base; the merge added
the same FX-TRUST-2 deferral prologue to both sides, so the delta moved slightly with the new
absolute costs. No admission policy or equality criterion was weakened.)

What LEGACY-OUT may delete (unchanged): `FrozenSample`, the sample-base helper, revision
invalidation, sample switches/counters, the `LrBodyPose.Reflect` vector method handles and
legacy sampling, keeping canonical `Stack.graph` freezing when no sample precedes it.

UPAR-2 constraints (in addition to §7): every memo/cache/index added here is main-thread only
(reference counts, seed indexes, stamp identities, inventory/equipment memos, `EXPIRY` queues,
the `DEFERRED` FIFO with its `deciding` flag, `STAMPED`); workers receive immutable planned
values plus a private `Memo.copy()`/`Flight` continuation. `AnimationResolver` pin-lease scope
is serialized on the store monitor — UPAR-2 must preserve that scope and use the same monitor
for any other worker pin/release, with main-thread store writers parked by the enclosing
barrier. `PreparedReceipt.deferred` is handed to one consumer; `replay` unwraps it after the
boundary, never concurrently with the drain.

Open for successors: A3(b) needs the WIRE-CODEC decoder hook (byte-span memo of immutable
components); WIRE-SERVER must not move `decideDeferred` off the boundary-record path without
preserving arrival order and original receive times.

## 11. Integration hold: SnapshotStreamTest byte-count fragility (U7)

`just format`, `just check`, `just test`, `:client:test` (534 tests, two pre-existing opt-in
skips) and all 13 in-process GameTests pass on `fulla/as-uopt2c`. Full `:server:test` has one
failure outside this unit: `SnapshotStreamTest.emptyBatchAndConfigHeaderAreAbsoluteAndOncePerTick`
expects exactly 9 encoded bytes for an empty batch but gets 10. Cause: every
`new SnapshotStream.Observer()` bumps the static `nextStream` epoch, and once 128+ observers
precede the test in the suite JVM the epoch varint grows by one byte. It passes alone and on
base (base full suite included), and passes here with base test files + this production; the
added observer-creating tests tip the counter over the boundary. `SnapshotStream` and its test
belong to U7 (ADR4 §11), so this unit does not edit them: the branch is pushed but NOT
integrated into `fulla/phase23` until U7 hardens the test (e.g. a counter reset hook or a
derived byte expectation). Suggested owner fix must keep the wire-bloat guard, not drop it.

## 12. UPAR-2 retention failure rule (2026-10-19)

The coordinator permits speculative state after an escaping Error only when no callback
continues ticking and no extra capture mutation is saved/sent during crash shutdown. This
exhaustive isolation proof is not established: pinned MinecraftServer.stopServer saves players
and levels and runs chunk shutdown work; native capture reaches live/cache/attachment getters.
The audit is recorded in anim-sync-parallel §4.3/§8 with preserved javap evidence. UPAR-2 therefore
uses the authorized conservative per-player retention joins, preserving the serial Error boundary
as well as caught-exception equality. Folds, validation rows and victim evaluation remain parallel.
This removes deferred retention's projected overlap benefit; §6.3 measurements must report the
actual ordered-join implementation and separate main-thread wall/CPU from total process CPU.

### UPAR-2 preliminary measurement before the identity-cache fix

One run each, 120 warm-up + 150 measured combat ticks, A spread 2.4, flight 3, workers 0/2;
Temurin 21.0.12+8, Ryzen 5 7535U, heavy lock, no stand started. Loads (1/5/15 min): serial
3.66/2.40/1.47; default 2.80/2.60/1.74. Raw logs/results:
WS/.dev/coord-20261011/upar2-cx-bench-w{0,2}.{log,json,md,load}. Both commands passed. The existing
measurement-only 16 MiB cap derivative was used on both sides (no distributable/source cap changed).

| cohort | workers | main CPU ms/tick | main wall ms/tick | process CPU ms/tick | heap after GC MiB |
| --- | --- | --- | --- | --- | --- |
| 30x9 | 0 | 145.97 | 152.74 | 254.73 | 403.50 |
| 30x9 | 2 | 160.69 | 178.16 | 317.33 | 398.71 |
| 30x29 | 0 | 365.35 | 408.77 | 1239.80 | 674.95 |
| 30x29 | 2 | 583.12 | 678.42 | 1797.73 | 689.36 |

Every canonical/legacy SHA, verdict distribution and non-timing counter matches; flight failures
are zero. Process CPU includes JVM GC/JIT concurrent with timed server operations; emulated client
reductions between operations are not timed, but their allocation can affect later GC. This is a
mock-driven comparative workload, not a live-server tick-budget prediction. Default extra heap
is -4.79 / +14.40 MiB (negative is GC/run variation), below the 32 MiB allowance in this preliminary
pair; the automated fresh-JVM memory gate still must run on final code.

This exposed a regression rather than an optimization claim: the deep record Stamp's hash was
recomputed by WeakHashMap on each victim lookup. CombatViews now uses weak identity keys and an
executed confinement regression test. **The table predates that fix and must not be quoted as
final UPAR-2 performance.** Ordered retention joins remain in force; its final cost, lane CPU,
worker allocations, batching choice and post-fix three-run 300-tick numbers remain open.


### UPAR-2 tick-wide batch memory audit (not the final three-run benchmark)

`upar2-cx-extended-smoke.log`: 120 warm-up + 40 measured combat ticks, A spread 2.4,
three flight ticks, the same measurement-only 16 MiB cap on both sides, fresh JVMs for
workers 0/2. Native canonical/legacy hashes match, flight failures are zero. Extra heap
is **21.17 MiB at 30x9 / 17.48 MiB at 30x29**, below the 32 MiB allowance. Raw profiles:
`WS/.dev/coord-20261011/upar2-cx-global-memory-profile/`.

The post-timing scratch audit forces every worker and main through a bounded lane barrier
and reads only its own ThreadLocals. Each worker holds one **65,536-byte codec buffer**,
zero splice entries and identity maps bounded at 1,024 entries (observed maxima across
these two profiles: digest/reference 806, finite 986, part-list 636). Native stacks/reduction
scratch are private task values, not shared worker globals. BodyPose's existing per-thread
constructor scratch is approximately 1.3 KiB (36 floats, six Matrix4f, one Matrix4d and 6x6
doubles plus headers). The VM reports 1,024 KiB stacks. Main's shared-encoding scopes remain
main-owned; WIRE-SERVER's explicit shared encode scope is still downstream and must remeasure
its own worker footprint. No schema, cache bound or production cap was changed.

Per-thread allocation is now measured by ThreadMXBean alongside main allocation: worker
means are 18.54/18.95 MiB per tick at 30x9 and 63.32/63.45 MiB at 30x29. It is allocation
churn, not retained heap. Main CPU/wall/process CPU means in this short diagnostic pair:
30x9 serial 144.92/151.32/235.75 ms, default 116.24/132.31/281.50 ms; 30x29 serial
369.76/411.50/1201.75 ms, default 272.40/353.60/1300.00 ms. This is the mock workload;
process CPU includes concurrent GC/JIT. These 40-tick means do not replace §6.3's three
300-tick runs, load observations or its latency percentiles.

Final UPAR-2 numbers (three 300-tick runs, both Gradle slots held, post identity-cache fix,
ordered retention joins included): `anim-sync-parallel.md` §14.4.

## 13. LEGACY-OUT 2c before/after (2026-10-21)

`AnimSyncBench` combat-only cohorts (`-PanimSyncBench="30:9,30:29" -PbenchCombatOnly=true`,
120 warm-up + 300 measured ticks), 3 runs each, medians, under `gradle-slot --exclusive`.
Before = `42630199` (phase23 before the legacy train); after = `fulla/as-legacy-2c`
`f114805a` (train + dead r10 wire-math deletion). Temurin 21.0.12+8, Ryzen 5 7535U.
Raw JSON/logs/load: `WS/.dev/coord-20261011/legacy-2c-bench/`
(`before-r{1,2,3}.json`, `after-r{1,2,3}.json`). The `30:29` cohort is named `30-combat`
by the bench. Workload outcome is identical on both sides: verdicts, receipts built,
unavailable receipts, victims resolved and flight failures match exactly.

| cohort | side | main wall ms/tick, median (runs) | alloc/tick, median KB (runs) |
| --- | --- | --- | --- |
| 30x9-combat | before 42630199 | 156.29 (199.00 / 156.29 / 156.28) | 69914 (70417 / 69914 / 69828) |
| 30x9-combat | after f114805a | 132.07 (132.07 / 143.21 / 128.88) | 57220 (57660 / 57151 / 57220) |
| 30-combat | before 42630199 | 337.70 (358.15 / 324.75 / 337.70) | 135272 (136327 / 135272 / 134670) |
| 30-combat | after f114805a | 296.94 (299.84 / 296.94 / 293.36) | 120054 (120661 / 120054 / 118966) |

The before→after drop (wall −15 % / −12 %, alloc −18 % / −11 %) is the legacy-train
effect (R10-OUT 3a/3b + LEGACY-CUT 2b: no per-tick legacy body composition); the 2c
helper deletion itself removes only dead code with no production callers.
