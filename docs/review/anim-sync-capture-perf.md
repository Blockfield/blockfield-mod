# Animation capture generation-index cache

The 25% CPU target was not met. At 15 open players, median main-thread END CPU fell
2.99%, while full main-thread wall rose 8.03%. The measured gain is less allocation:
END allocation fell 38.5% at 15 players and 38.6% at 30 players. This is an offline
mock benchmark; no live improvement, release or deployment is claimed.

## Method and results

Base: `7b2117080c6c91469d1cabec48d883679026f649` (`origin/main`). Candidate: the
same base plus this cache and its focused tests. Temurin 21.0.12+8, Linux amd64,
workers 2, combat-only, 120 warmup / 300 measured ticks, three fresh JVMs per side
per cohort. `15:14` models 15 open players; `30:9` is the default comparison.
All timed runs held `WS/.dev/coord-20261009/gradle-slot --exclusive`; no competing Gradle
build or stand overlapped a measurement. Other sessions ran between some pairs.
No run was discarded or repeated to improve the numbers.

Command (both sides):

```sh
just gradle --no-daemon --max-workers=2 :server:oracleTest \
  -PanimSyncBench=15:14 -PbenchTicks=300 -PbenchWarmup=120 \
  -PbenchCombatOnly=true -PbenchWorkers=2
```

Replace `15:14` with `30:9` for the other cohort. No lifted-cap JAR is needed.
Order per cohort: before/after, after/before, before/after. Values below are
medians of three per-run means; each arrow is before → after.

CPU uses `operations.endTickPerPlayer.cpuNanos`: despite the old field name this
is one END over all players, divided by the cohort size for the player value.
It includes main-thread live reads, generation preparation and owner-lane capture;
it excludes worker CPU. Wall uses the same END operation. Mockito getter dispatch
is included, without an inferred production correction.

| W2 cohort | END CPU ms/player | END CPU ms/tick | END wall ms/player | END wall ms/tick | runs B / A |
| --- | --- | --- | --- | --- | --- |
| 15x14 | 1.616 → 1.567 | 24.234 → 23.510 | 1.766 → 1.713 | 26.497 → 25.688 | 3 / 3 |
| 30x9 | 1.529 → 1.321 | 45.864 → 39.622 | 1.625 → 1.396 | 48.746 → 41.883 | 3 / 3 |

| W2 cohort | full main wall ms/tick | END allocation MiB/tick | all-lane allocation MiB/tick | capture counter wall ms/player |
| --- | --- | --- | --- | --- |
| 15x14 | 105.213 → 113.667 | 18.305 → 11.259 | 113.896 → 107.010 | 0.611 → 0.679 |
| 30x9 | 115.728 → 109.376 | 36.151 → 22.209 | 127.628 → 113.282 | 0.516 → 0.531 |

The old `capturePerPlayer.cpuNanos` field contains summed capture **wall** time,
not CPU. Its values are reported separately above. Full main wall covers the
bench's measured operations, excluding observer emulation; all-lane allocation
covers main and workers across those operations.

Raw END CPU/player values, in repeat order:

- 15x14 before: 1.646082, 1.588609, 1.615578 ms/player.
- 15x14 after: 1.762834, 1.567344, 1.441779 ms/player.
- 30x9 before: 2.005588, 1.467006, 1.528803 ms/player.
- 30x9 after: 1.601760, 1.320745, 1.308977 ms/player.

## Cut and invalidation

`server/src/main/java/com/lunares/blockfield/server/combat/AnimSyncCapture.java:1347`
reuses an immutable definition map while the generation's
`LrAssets` identity and `definitionVersion()` are unchanged
(`common/src/main/java/com/lunares/blockfield/common/combat/LrAssets.java:237`). The version sums the
existing append-only catalog size and typed-config count; a config may be added
to a view without growing an already-populated shared catalog. New names, clips,
content definitions, typed configs, replacement views and new generations rebuild
the map. Existing `forget` and test reset clear the cache. Player lifecycle changes
do not invalidate generation-owned definitions.

No player data, closure result, native stack, install effect or wire value is
cached. Closure retry, serial install/counter order and worker boundaries remain
unchanged. No B5 pipeline code, client interpolation code or game-mode code changed.

Exact JFR END-inclusive counts, first run of each side (overlapping parents,
not additive CPU shares):

| cohort | preparation before → after | index before → after | captureSegment before → after |
| --- | --- | --- | --- |
| 15×14 | 230/538 (42.75%) → 0/399 | 172/538 (31.97%) → 0/399 | 175/538 → 180/399 |
| 30×9 | 693/1359 (50.99%) → 0/546 | 531/1359 (39.07%) → 0/546 | 373/1359 → 347/546 |

Zero samples is a sampling-resolution statement, not zero execution cost. The
remaining per-player capture/round-trip/validation and mock-dispatch work is not
generation-invariant. Sharing those values across players would need a different
proof; this change keeps their validation and evaluation intact. The combined
live stand must determine the production benefit.

## Verification

| check | result |
| --- | --- |
| `just format-check`, `just check` | PASS |
| `just test` (`BF_SKIP_ORACLE` unset) | PASS: 62 rig tests, 40 isolated tests, room-close checks, 176 oracle fragments, U10 and wire gates |
| `just java-check --no-daemon` | PASS: client 582 tests (2 existing skips), server 1,898 tests, 13 GameTests; all 71 tests in the five capture/resource classes pass |
| `:server:oracleParallelGate -PfullGateFresh=true`, exclusive, `--no-daemon --max-workers=10` | PASS: all five fresh matrix/stress/failure stages; workers 0/1/2/4 exact |
| candidate vs fresh pristine `7b211708` | **0 differences** across all 176 oracle fragments, U10 and wire measurements; repeated after the full Java check |
| requested train-5 comparator | **FAIL: 7,720 differences**, exactly the same paths on pristine main and candidate |

The supplied `upar3-baseline-train5/` predates main. Its 175 fragments lack
`remote-locomotion-per-frame`; later stance measurements and the missing-music
artifact URL also differ. The existing comparator was run unchanged against both
pristine main and candidate. Its complete difference lists are identical; mixed
renderer native/server sample digests remain `30c1c420…80ac8` (7,952 MAIN samples,
0 m corner error). The old archive was not modified or silently replaced. Strict
zero-diff acceptance against that archive needs a coordinator decision on the
reference; zero output change against the actual requested production base is
proved separately.

The existing reflection-based `AnimSyncClosureRetryTest` was adapted after the
bench matrix to inject the new cache entry type with a stale version. It still
forces the same missing dependency, keeps every equality/no-repeat/no-early-seal
assertion, and passes all four cases. The measured production diff and original
focused tests are unchanged (patch SHA-256 `4e0077c9668ce4234d77008f47663809f87ea97b984f1df36a1e4d674709e091`).
An initial Java-check wrapper launch exited 127 before running checks because its
script path was relative; the corrected absolute-path invocation and full
successful check are retained as `java-check-gate.*`.

Focused cache coverage: immutable reuse; new catalog names; typed-config growth
without catalog growth; replacement view; generation change/reload; retirement;
join/leave/respawn/teleport reuse. Existing parallel capture, abort-prefix and
resource-retirement tests were also exercised.

Two new lifecycle-fixture setup failures were diagnosed before the focused tests
passed: null `GameProfile` at `TrustedPlayerSkin.texturesKey:96`, then Mockito
`UnfinishedStubbingException` while building the stub's return value. The test now
supplies a profile and dimension and constructs the profile before stubbing.
Production code did not change for those failures; both failure XMLs and logs are
retained. Full required checks are listed above, including any failure.

## Evidence

`WS/.dev/coord-20261011/plans/anim-sync/capture-perf-logs/`: all 12 JSON/Markdown
results, source/load records and logs; `summary.json`, `summarize.py`, `matrix.sh`,
`run-bench.sh`; the four cited r1 JFRs; `CaptureProfile.java` and `profile.json`;
oracle snapshots/comparison logs and gate exit records. No JAR was copied there.
Progress: `WS/.dev/coord-20261011/plans/anim-sync/capture-perf-progress.md`.
