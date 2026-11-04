# Anim-sync performance options (ADR4.3 study, UPERF)

Status: options study for the coordinator and the owner. Design and scratch prototypes only; no
production code changed. Base: `origin/fulla/phase23` `9cf76d2a` with `origin/fulla/as-uopt`
`38bfbacf` (UOPT, [anim-sync-perf](anim-sync-perf.md)) and `origin/fulla/as-upar` `aaa9fb94`
(UPAR-1, [anim-sync-parallel](anim-sync-parallel.md)) merged. Contracts: ADR4.1 + errata
(`p3-anim-sync-adr4.md`), ADR4.2 v3 (`p3-anim-sync-wire-amendment.md`), the owner rules of
2026-10-13…18: exact server = client six-box equality, no visual degradation, accuracy first,
optimization required before release without any accuracy or visual trade-off, multithreading
allowed and wanted.

`K/`, `S/`, `C/` are the mod's `common/…/common/`, `server/…/server/`, `client/…/client/` roots.
**[P]** marks a number this unit measured with its prototype (§3); every other number is cited or
derived and says so. "Bench" is UOPT's `AnimSyncBench` on the AMD Ryzen 5 7535U. "Bench-quiet"
rescales this unit's runs to UOPT's quiet machine (§3.1). The homeserver (i7-4710MQ, Haswell
2.5 GHz, 4C/8T, load ≈ 2 from other services) is projected as bench-quiet × 1.5–1.8 per thread
(UPAR-1 §5.2) with a 3-lane fork-join speedup of 2.4–2.0 instead of the bench's 2.6 [P, UPAR-1].
Cohorts are combat at 20 shots/s: 30x9 (30 players, each sees 9) and 30x29 (all mutually visible,
256 KiB cap lifted for measurement).

## 0. Result

**Budget (§1, assumed):** anim-sync + r10 pose on the homeserver main thread ≤ **15 ms/tick mean**
(≤ 25 ms p99) = ≤ 8.5 ms bench-quiet, after 20 ms for the rest of a 30-player game and 15 ms
headroom; workers ≤ ≈ 70 ms CPU/tick on the homeserver (2 workers at 70 %).

**Today, serial (§2):** bench 110 ms/tick at 30x9 and 222 ms at 30x29 — matching UOPT/UPAR-1. Four
findings change that picture:

1. **The bench overstates the END tick.** Its players, level and gun item are Mockito inline mocks:
   6.3 µs per call [P], 185 calls per player-tick [P]. Of the r10 pose's 832 µs/player, 538 µs are
   mock dispatch; of capture's 1,031 µs, 413 µs. Production-equivalent: **r10 ≈ 290 µs, capture ≈
   620 µs per player** (8.8 + 18.6 ms/tick at 30), and the whole serial tick ≈ 82 ms (30x9) /
   194 ms (30x29) bench-quiet.
2. **Bullets in flight are the largest real combat cost and are not in UOPT's bench.** Every flight
   tick of every TaCZ bullet re-reduces every validated victim from its seed without the memo and
   evaluates it: **1.8 ms per victim per flight tick** bench-quiet [P] (2.18 / 2.26 ms run units at
   30x9 / 30x29). With 3 bullets in flight: +48 ms/tick at 30x9, +161 ms at 30x29.
3. **Observers mostly see the same presentation.** With production-rate ParCool counters and a
   3-bucket spread of observer clocks, **71 % (30x9) / 85 % (30x29) of server reducer steps repeat
   another observer's step** for the same target and bucket [P] (2.7 / 4.2 distinct presentations
   for 9 / 29 observers); relabelled repeats always produced identical results (190,495 repeats,
   0 mismatches) [P]. Per-pair reduction work can become per-class work without any rule change.
4. **Bookkeeping is a large, pure overhead**: ledger prune and applied-report intake rebuild
   O(retained) sets per observer per tick: 9.5 ms (30x9) / 33.7 ms (30x29) bench-quiet [P];
   equipment canonicalization re-encodes 41 inventory slots through the DFU codec per player-tick.

| Option | Class | Saving 30x9 main / total, bench-quiet → homeserver (ms/tick) | Saving 30x29 main / total, bench-quiet → homeserver | Basis |
| --- | --- | --- | --- | --- |
| A1 LEDGER incremental prune/intake | A | 8.1 / 8.1 → 12–15 | 28.9 / 28.9 → 43–52 | [P] stage cost, estimated factor |
| A2 EQUIP equipment memo | A | 1.7 / 1.7 → 3 | 1.4 / 1.4 → 2–3 | [P] (t6, s6), partial prototype |
| A3 CODEC component round-trip reuse | A | 6.9 / 6.9 → 10–12 | 16.6 / 16.6 → 25–30 | estimate |
| A4 R10-MICRO one PA freeze, no reflection | A | 1.1 / 1.1 → 2 | 1.1 / 1.1 → 2 | estimate |
| A5 FLIGHT resume in-flight victims | A | 31.1 / 31.1 → 47–56 | 100 / 100 → 150–180 | [P] cost; estimated after-cost |
| A13 PREPARE per-pair fast path | A | 2.2 / 2.2 → 3–4 | 5.7 / 5.7 → 9–10 | estimate |
| A9 UPAR-2 fork-join + WIRE-SERVER encode | A | 36.7 / −2.8 → 55–66 / −4 | 106.9 / −8.9 → 160–192 / −13 to −16 | [P] UPAR-1 scaling |
| A6 STEP one freeze per reducer step | A | 2.5 / 7.1 → 4 / 11–13 | 7.7 / 22.0 → 12–14 / 33–40 | estimate (JFR shares) |
| A7 SHARE one step per presentation class (+B1) | A | 5.5 / 13.3 → 8–10 / 20–24 | 20.8 / 50.0 → 31–37 / 75–90 | [P] repeated share |
| A8 BOUND proved broad phase (ADR4 §7) | A | 6.5 / 18.6 → 10–12 / 28–33 | 24.7 / 70.6 → 37–44 / 106–127 | assumption: 2 candidates/segment |
| A10+A11 UPAR-3 two-phase capture, stream tasks | A | 8.9 / −4.9 → 13–16 / −8 | 8.8 / −5.6 → 13–16 / −9 | estimate |
| B1 CLASS-SEEDS aligned folds, one seed per class | B | inside A7 (fold commits per class) | inside A7 | — |
| B3 LEGACY r10 body on demand from S[t] | B | 1.9 / 5.4 → 3 / 8–10 | 1.6 / 4.5 → 2–3 / 7–8 | estimate |
| B4 LAZY-ROWS reconstruct candidate rows only | B | 1.0 / 2.7 → 1–2 / 4–5 | 3.3 / 9.4 → 5–6 / 14–17 | assumption |
| B5 NEXT-TICK send at start of t+1 | B | homeserver main 28–39 → 12–26 | homeserver main 43–58 → 35–56 | model §7.2 |
| B2 longer fold age / B6 receipt refs / B7 client-proven folds | B | ≤ 2 / small / 0 | — | not recommended |

Savings are incremental in package order (§7.2), production-equivalent, 3 bullets in flight;
"main" is the server thread (it waits inside fork-joins), "total" all threads (parallel stages add
≈ 10 % CPU). Class C (excluded) in §6.

**Package (§7):** UOPT-2 (A1–A5, A13; bit-identical, single thread) → UPAR-2 ∥ WIRE-CODEC →
WIRE-SERVER → USHARE (A6, A7, B1) ∥ UBOUND (A8, B4) → UPAR-3 (A10, A11, B3, B5). Cumulative
projection (homeserver main ms/tick): today 198–237 (30x9) / 532–638 (30x29) → after UOPT-2
121–145 / 301–362 → +UPAR-2/WIRE 69–91 / 149–203 → +USHARE 56–73 / 103–139 → +UBOUND 45–58 /
63–81 → +UPAR-3 33–46 / 51–69 → +B3/B4 28–39 / 43–58 → +B5 **12–26 / 35–56**.

**Fit (§9):** **30x9 can fit on the homeserver only with the whole package including B5, and
then only at the fast end of the projection** (12–26 ms against 15 ms; total CPU 62–74 ms/tick,
≈ 50 ms of it on workers, at the worker limit). Without B5 it does not (28–39 ms). **30x29 does not
fit** on this host with any option set (35–56 ms main with B5, total CPU 88–106 ms/tick). Otherwise the package needs a host
with **≥ 1.45–1.8× the i7-4710MQ's per-thread speed and 2 free cores (≥ 1.25–1.45× with 4 free
cores) for 30x9, and ≥ 1.9–2.35× with 2 free cores (≥ 1.6–1.95× with 4, ≥ 1.4–1.65× with 8) for
30x29**, without B5.

**Owner questions (§8):** B3 (legacy body on demand — recommend yes), B4 (candidate-row
reconstruction — yes after A8 is proved), B5 (snapshot at the start of the next tick, +15–30 ms
remote delay — decide after UPAR-3 is measured on a 30-bot stand), and the release target cohort
on this host (recommend 30x9-class; 30x29 only with the hardware of §9). B1 is a server-only
coordinator decision.

## 1. Budget (assumed; replace with a 30-bot staging measurement before release)

The tick is 50 ms. Three shares of the homeserver main thread:

- **G, the rest of the game at 30 players** (vanilla, mods, TaCZ bullets, ParCool, tracking,
  chunk sends). No 30-player measurement exists, and production MSPT was outside this unit. On the
  bench machine's own stand, eight armed DM bots firing ≈ 52 shots/s (mod 1.47.2, before the r10
  pose and anim-sync) gave steady-window MSPT p50 3.3 ms, p95 5.05 ms, p99 8.85 ms (median of three
  runs, `.dev/bf-integration-20261009/cases/acceptance-summary.json`); one player on a map
  3.3–5.0 ms (`server/docs/CODRE-MAPS.md`). Assumption: bench G(30) ≈ 3–5 ms + 30 × 0.2–0.35 ms ≈
  9–15 ms → homeserver ≈ 14–27 ms; **G = 20 ms**.
- **Headroom** for p95/p99 (GC pauses, autosave I/O — iowait and swap on the homeserver,
  `.dev/coord-20261009/workshop-stalls.md` —, contention with Velocity, Workshop and the site,
  MSPT variance): **15 ms**.
- **Anim-sync + r10 main thread: 50 − 20 − 15 = 15 ms mean (≤ 25 ms p99) on the homeserver,
  i.e. ≤ 8.5 ms bench-quiet** (÷ 1.8; ≤ 10 ms with ÷ 1.5).
- **Workers**: 8 hardware threads; other services ≈ 2, the game JVM's netty/GC/JIT/chunk I/O
  ≈ 1–1.5, main 1 ⇒ ≈ 2–2.5 free core-equivalents. **≈ 2 × 50 ms × 70 % = 70 ms worker CPU per
  tick ≈ 40 ms bench-quiet.** The game container's CPU quota is unknown (UPAR-1 §4.10 open item);
  a quota below 3 CPUs lowers this.

## 2. Today's cost (serial, final code)

### 2.1 Decomposition (bench-quiet ms/tick) [P]

"Bench" is what the benchmark measures (t2 30x9, t5 30x29). "Prod-eq" subtracts the Mockito
dispatch of the bench doubles: calls per stage counted with an invocation listener on every double
(t3), × 6.3 µs per call (t3 microbench in run units: `thenAnswer` 6.26, `thenReturn` 5.62,
unstubbed 7.04 µs; t2 without the listener 6.32 / 6.21 / 6.74 µs). The bench's `ServerPlayer`,
`ServerLevel`, connection and TaCZ item are Mockito inline mocks; each call does a reflective
method lookup and a stack walk (the UOPT JFR under-samples this native/VM time). A real player pays
a field read. 184.5 dispatches per player-tick: r10 inputs 73.5, PA advance 17, legacy frame 9,
history 4, legacy sample 1, vanilla capture 61, ParCool capture 14, Create/SBW 4, identity 1.

| component | 30x9 bench | 30x9 prod-eq | 30x29 bench | 30x29 prod-eq |
| --- | --- | --- | --- | --- |
| r10: live inputs (applyActions, ParCool/vanilla/root/SBW freeze, `Inputs`) | 14.8 | 3.4 | 13.9 | 2.5 |
| r10: canonical PA advancement + legacy bone application (`LrBodyPose.apply`, `weapon`) | 4.3 | 1.7 | 4.1 | 1.5 |
| r10: legacy sample (`LrBodyPose.sample`: freeze, materialize, sample, reflective reads) | 2.4 | 2.2 | 2.3 | 2.1 |
| r10: legacy frame + `CombatPoseHistory.commit` | 3.4 | 1.4 | 3.3 | 1.3 |
| **r10 pose** | **25.0** | **8.8** | **23.6** | **7.4** |
| capture: vanilla (equipment canonicalization 83 % of its samples) | 13.6 | 4.1 | 12.8 | 3.3 |
| capture: PA/LR/TaCZ freeze | 0.9 | 0.9 | 0.9 | 0.9 |
| capture: Create + SBW | 1.0 | 0.4 | 1.0 | 0.4 |
| capture: ParCool canonical stages | 5.0 | 2.8 | 4.9 | 2.7 |
| capture: validate + stage (closure, install) | 2.0 | 2.0 | 2.0 | 2.0 |
| capture: retention round trip | 7.1 | 7.1 | 7.0 | 7.0 |
| capture: identity, seal, pin, commit | 1.3 | 1.2 | 1.3 | 1.1 |
| **capture** | **31.0** | **18.6** | **29.9** | **17.5** |
| stream: per-pair prepare records | 3.6 | 3.6 | 9.6 | 9.6 |
| stream: fold reductions | 19.1 | 19.1 | 59.4 | 59.4 |
| stream: fold commits (13.5 / 44.4 per tick) | 5.8 | 5.8 | 19.7 | 19.7 |
| stream: size-check encode | 6.8 | 6.8 | 18.2 | 18.2 |
| stream: ledger prune | 7.3 | 7.3 | 26.1 | 26.1 |
| stream: publish rest | 0.2 | 0.2 | 0.7 | 0.7 |
| **stream** | **42.9** | **42.9** | **133.7** | **133.7** |
| receipt validation (1 receipt/tick, 9 / 29 rows) | 6.0 | 6.0 | 18.5 | 18.5 |
| evaluation (9 / 29 victims per shot) | 2.8 | 2.8 | 8.0 | 8.0 |
| applied-report intake | 2.1 | 2.1 | 7.6 | 7.6 |
| START tick, accepted inputs, native flush | 0.7 | 0.7 | 0.9 | 0.9 |
| **total** | **110.5** | **81.9** | **222.2** | **193.6** |
| + bullets in flight, 3 per tick (§2.2) | +48 | +48 | +161 | +161 |

Per-unit costs (bench-quiet): reducer step ≈ 70 µs [P]; fold 1.41 ms (≈ 20 steps) + commit
0.42 ms; receipt row 0.66 ms; evaluation 0.31 ms/victim; prune 0.25 ms/observer at 30x9 and
0.87 ms at 30x29 (super-linear: ×3.6 for ×3.2 pairs); applied-report intake 79 / 255 µs per report.
Allocation 101 / 237 MB per tick (≈ 2 / 4.7 GB/s at 20 TPS) [P].

**Why the r10 pose costs 865 µs/player** (UOPT quiet): 832 µs bench-quiet here, of which 538 µs are
mock dispatch (65 %). The remaining ≈ 290 µs: live input reads ≈ 115 µs (applyActions, ParCool and
vanilla input freezing, visual yaw, root inputs, SBW seats; mostly feeding the legacy bones),
canonical PA advancement with legacy bone application ≈ 57 µs (the only part S[t] needs), legacy
sample ≈ 75 µs (a second freeze + materialization of the same PA stack that capture freezes again,
and 36 reflective `Method.invoke` per player-tick) and legacy frame + history ≈ 47 µs. Inherent:
the PA advancement and the reads it needs. Native overhead: the double freeze and the reflection
(A4). Movable to workers: everything but the live reads and the ordered `CombatPoseHistory`/
target commits (A10); removable while capturing: the legacy bones (B3).

### 2.2 Not in UOPT's bench: bullets in flight [P]

`BulletHitboxMixin` wraps TaCZ's per-tick `findEntitiesOnPath`: `ConservativeCoverage.merge` adds
every level player, and `getHitResult` resolves each of them through
`FamilyStamps.impactBody(…, advancing=true)` → `CombatViews.exact` → `AnimSyncValidation.advance`
(a memo-less `ObserverReducer.reduce` from the row's seed — up to 40 buckets —, a presentation
digest, the stamped history, `AnimationResolver.resolve`) → `AnimSyncEval.evaluate`. **Every bullet
in flight costs one full victim resolution per validated victim per tick.** The pinned TaCZ AK-47
has `speed` 250 (12.5 blocks/tick) and `life` 0.8 s (16 ticks): a shot that does not hit within a
tick or two stays in flight for up to 16 ticks.

Prototype (`-Puperf.flight=N`: each exact shot of the bench stays in flight N more ticks; every
validated victim is advanced and evaluated at each age, timed outside the tick totals):
**2.18 ms CPU per victim per flight tick at 30x9 (p50 1.36, p99 5.91; t4, 5,400 resolutions) and
2.26 ms at 30x29 (p50 1.58, p99 5.45; t5, 8,700), 0 failures** — ≈ 1.8 ms bench-quiet, of which
the evaluation is ≈ 0.29 ms and the replay + resolve ≈ 1.5 ms. With 20 shots/s and 3 bullets in
flight (assumption): **+48 ms/tick at 30x9 and +161 ms/tick at 30x29 bench-quiet**, linear in fire
rate and flight time.

### 2.3 Scaling laws

| work | unit | 30x9 → 30x29 [P] |
| --- | --- | --- |
| r10 pose, capture, round trip | per player | ×1 |
| folds, prepare records, encode | per observer × targets seen | ×2.7–3.2 |
| prune, applied intake | per observer × retained refs/seeds | ×3.4–3.6 |
| receipt rows, evaluation | per shot × victims | ×2.8–3.1 |
| flight resolutions | per bullet in flight × victims × tick | ×3.2 |

30x9 is dominated by per-player capture and per-pair folds; 30x29 by per-pair folds, prune and
encode; combat at either size by per-victim flight work.

## 3. Prototype measurements [P]

### 3.1 Method

Scratch patch (§11): stage timers inside the production END tick, capture and stream code
(`UperfProbe`, `System.nanoTime` laps, off by default), an optional Mockito invocation listener on
every bench double, a reducer-step probe (`ObserverReducer.STEP_PROBE`), an optional per-observer A
spread, native ParCool counter ticks, multi-tick bullet flight and an equipment memo. Runs on
2026-10-18 under the heavy lock, one at a time, combat cohorts, 120 warm-up ticks; machine load
average 3–6 from other sessions.

Normalization: the clean runs (t2, t3) measure END tick 2,289 µs/player and capture 1,259
µs/player where UOPT's quiet run measured 1,886 / 1,021 µs: **bench-quiet = run / 1.22**. The
listener (t3) did not change stage times (within 2 % of t2).

| run | cohort, ticks | switches | purpose |
| --- | --- | --- | --- |
| t2 | 30x9, 300 | — | clean stage timings |
| t3 | 30x9, 200 | `countMocks` | Mockito dispatches per stage |
| t4 | 30x9, 200 | `flight=3` | in-flight victim resolution |
| t5 | 30x29, 150 | `flight=2`, cap lifted, no JFR table | 30x29 stages |
| s1 / s2 | 30x9, 300 | `share`, A spread 2.4 / none | presentation classes, bench as is |
| d1 | 10x9, 100 | `share`, `shareDiag` | what differs between observers |
| s4 / s5 | 30x9, 300 | `share`, `parcoolTick`, spread 2.4 / none | classes with production-rate ParCool counters |
| s6 | 30x29, 150 | `share`, `parcoolTick`, spread 2.4, `equipMemo` | 30x29 classes; A2 reading |
| t6 | 30x9, 300 | `equipMemo`, `parcoolTick` | A2 at 30x9 |

### 3.2 Presentation classes

`UperfShare` keys every server-side reducer step (folds and receipt rows; the observer emulation
excluded) by (target life, bucket, composed graph with the reduction's own seed id relabelled,
ParCool value) and, on a repeat, compares the relabelled post-step graph with the first one.
`-Puperf.aSpread=2.4` puts each observer's A at its own offset 0.55–2.95 ticks behind H (spread by
observer index, drifting ±0.2 ticks with a 50-tick period; nondecreasing; R ≤ A ≤ H), so a capsule's
`sA = floor(applyA)` falls into three adjacent buckets across observers, as differing jitter, `oA`
and arrival phase do.

| run | server steps/tick | step µs (run) | repeated share | classes per (target, bucket), mean / max | repeats compared, mismatches |
| --- | --- | --- | --- | --- | --- |
| s1 30x9, spread, bench counters | 302 | 86 | 9.3 % | 8.6 / 13 | 8,468, 0 |
| s2 30x9, equal A, bench counters | 303 | 97 | 9.5 % | 8.7 / 13 | 8,589, 0 |
| **s4 30x9, spread, native counters** | 302 | 88 | **71.1 %** | **2.74** / 9 | 64,464, 0 |
| s5 30x9, equal A, native counters | 303 | 85 | 77.5 % | 2.16 / 7 | 70,384, 0 |
| **s6 30x29, spread, native counters** | 989 | 76 | **85.0 %** | **4.18** / 16 | 126,031, 0 |

d1 found why s1/s2 do not share: graphs, node ids, roots and aliases were identical; only the
ParCool `ActionState.notDoingTick` of idle actions (e.g. `BREAKFALL_READY` 112 vs 115) differed.
bf7 `ActionProcessor.processAction` calls `Action.tick()` for every player every tick on the server
too, so canonical counters advance in step with the observer mirror (`ParCoolStages.counters`);
the bench's mock players are never ticked by ParCool, their canonical counters freeze, and each
observer's mirror drifts by its own seed tick. With `-Puperf.parcoolTick` (the native per-tick
`Action.tick()` on each double) the remaining differences in s4 are genuine timing ones (a roll
started one bucket apart, a fire clip two ticks apart). The bench should tick ParCool natively by
default (§10); verdicts are unchanged by it (s5: 409 exact, as t2). The spread itself adds 4 / 9
`prefix_backwards` rejections at 30x9 / 30x29 (the known FX-CLIENT case, UOPT §6) and 1
unavailable endpoint.

### 3.3 Other prototype results

- Mockito: 6.3 µs/call, 184.5 calls per player-tick (§2.1).
- Flight: §2.2.
- Equipment memo (`VanillaAnimSync.canonicalString` keyed by stack identity, reused iff
  `ItemStack.matches` with a defensive copy; the joined bytes/digest memo was not prototyped):
  vanilla capture 520 → 444 µs/player at 30x29 (s6 vs t5; other stages 1–6 % faster in s6), i.e.
  production-equivalent 136 → ≈ 78 µs (−43 %) after removing the 61 mock calls; at 30x9 (t6 vs t2,
  other stages within ±6 %): 551 → 481 µs/player, production-equivalent 168 → 97 µs (−42 %),
  verdicts unchanged (409 exact). The projection uses −42 %.

## 4. Class A: bit-identical options (no semantic change, no owner decision)

Every A option keeps the values, bytes, verdicts, exceptions and visible effects of today's code
(UPAR-1 §0); its gate is UOPT's (`dev/anim_sync_perf_gate.py` over the U10 report, the bench's
workload outcome and verify mode) plus the option's own differential (§10). Savings: §0 table and
§7.2.

### A1 LEDGER — incremental ledger bookkeeping

`AnimSyncStream.publish → prune` rebuilds, per observer per tick, the union of the reference sets of
all 40 retained `SentBatch`es and of every retained seed's closure into a fresh `HashSet` and runs
`sentRefs.retainAll` over it, and `latest` scans all seeds for every seed (O(seeds²));
`AnimSyncValidation.prune` rebuilds string keys (`key(life, StartKey)`) for every retained seed's
starts, parses `firstReport` keys back with `Long.parseLong(key.substring(…))` and scans `history`,
`EVIDENCE` and `accepted`; `AnimSyncValidation.applied` runs that `prune` for every C2S applied
report and copies `intake.history`. New: a reference count per `ResourceRef` over retained batches
and seed closures (a ref leaves `sentRefs` exactly when its count reaches zero, which is today's
`sentRefs ∩ ⋃retained`), typed record keys, tick-ordered expiry queues for `history`, `EVIDENCE`,
`accepted`, `firstReport`, a newest-seed index per life, and `applied` validating against the live
map plus its additions instead of a copy. Same sets after every call. Bench-quiet today: prune 7.3 /
26.1 ms, intake 2.1 / 7.6 ms (30x9 / 30x29) [P]; assumed −90 % / −70 %. Main-thread bookkeeping:
saving is main and total. Small (two files), independent of UPAR/WIRE.

### A2 EQUIP — equipment canonicalization memo [P]

`VanillaAnimSync.equipment` encodes all 41 inventory slots plus main/off/use items through
`ItemStack.CODEC` → NBT → canonical string, joins and SHA-256-hashes them, per player per tick
(83 % of `VanillaAnimSync.capture` samples in the UOPT JFR). The text is a pure function of what the
codec encodes: item, count and component patch. A per-stack memo holding a defensive `copy()` and
its text, reused iff `ItemStack.matches(copy, stack)`, and the joined bytes, digest and ref reused
when every slot text is unchanged, give identical bytes. TaCZ ammo writes change the gun's
`CUSTOM_DATA` on every shot, so the gun slot re-encodes on firing ticks only. [P] §3.3.

### A3 CODEC — component-level round-trip reuse

The retention round trip (`AnimSyncLedger.roundTrip`: encode, decode, digest, `equals`) costs
7.1 ms/tick for 30 checkpoints (22.4 KB per gun holder: graph 15.5 KB, decisions 4.5 KB) [P]; every
fold/issued seed round-trips again a value whose checkpoint is a stamped copy of an already
round-tripped S[b] (fold commits 5.8 / 19.7 ms/tick). New: (a) encode copies the cached bytes of
components shared by identity (the stream's `SPLICES`, extended to the retention and seed round
trips with the same per-position type and depth checks); (b) decode returns the previously decoded
immutable instance for byte-identical component input (decode is a pure function of its bytes);
(c) seed content digests use the existing identity digest memo. `decode(encode(x)).equals(x)` stays
asserted for every value. Estimates: seed round trips −70 %, checkpoint round trips −40 % (graph and
placement change every tick; more with node-level reuse, §7.3).

### A4 R10-MICRO — one PA freeze per player per tick

`LrBodyPose.sample` freezes the live PA stack with a neutral base, materializes it into a private
scratch and samples it; capture freezes the same stack again (`LrBodyPose.Stack.graph`). Between
the two no code mutates the PA stack (postStages, medkit, Create and the frame touch bones only —
asserted by a stack-revision check). New: freeze once at the sample point; the legacy sample
materializes that graph and capture reuses it (sampling is a pure function of graph value; node ids
differ — neutral vs prior base — but are not read by sampling; the differential compares bones and
S[t] bytes). `LrBodyPose.read`'s 36 reflective `Method.invoke` per player-tick become method handles
bound once.

### A5 FLIGHT — resume in-flight victims instead of replaying them [P]

The validated `Victim` keeps an immutable copy of its row's `Reduction` at the receipt bucket;
`AnimSyncValidation.advance` continues that copy by `age` steps (UOPT's `Memo` argument: the reducer
is a deterministic function of immutable inputs, so continuing equals replaying) and caches the
advanced, resolved victim per (victim, age) inside the stamp, so each further flight tick adds one
step and pellets/bullets of one stamp share it. Cost today [P] §2.2 (1.8 ms per victim-tick);
after: ≈ 0.7 ms (one step, digest, stamped history, resolve, evaluation; estimate). With A8 most
victims are not resolved at all.

### A6 STEP — cheaper reducer steps

Per step (≈ 70 µs bench-quiet) the UOPT JFR shows 44 % in `PaGraph.Stack.freeze` (one freeze after
the PA tick and one after each TaCZ/LR decision), 27 % in the ParCool boundary stage and 15 % in
`validateComplete` of intermediate graphs; the native PA tick is 4 %. New: one scratch and one
freeze per step (each decision's start bookkeeping derived from the final graph and the decision's
event sequence, as `after` does now per decision), no clip copy (`Catalog.clip` →
`KeyframeAnimation.copy`) when a decision re-selects the incumbent, one `validateComplete` per step.
Intermediate graphs are not part of any result. Estimate −30 % of step cost; applies to folds, rows
and flight. Differential: UOPT's `shareStepScratch(false)` reference path.

### A7 SHARE — one reducer step per presentation class [P]

The server reduces each (observer, target) pair ≈ once per tick (folds every 20 ticks replay ≈ 20
buckets; receipt rows resume to A): 302 / 989 server steps per tick at 30x9 / 30x29 [P]. A step is a
pure function of the composed graph, the ParCool value, the canonical S[n+1], assets and policy; the
per-observer start bookkeeping does not feed it. Observers of one target whose graph and ParCool
value are equal at bucket n (up to the one observer-minted label, their own seed id in node start
keys) take identical steps: 71 % / 85 % of steps [P, §3.2].

Design: a per-target-life step cache (bucket, canonical pre-state) → (post-state, derived-start
templates, or the thrown `Throwable`), hash-consed so equal states become one instance and later
lookups are identity hits; each observer's `Reduction` relabels the shared graph with its own seed
id and recomputes its start list (`retain` + the step's derived starts; a derived start's anchor
class reads the observer's own predecessor start, exactly as `taczDecision` does today). Entries live
≤ 40 ticks. Folds, receipt rows and flight steps all hit it. With UPAR-2 the main thread plans the
classes, one task per class steps, bookkeeping per pair follows. Exactness rests on (1) the
reducer's purity (its contract; UPAR-1 §3.8 audit); (2) equivariance under renaming the seed label —
190,495 relabelled repeats, 0 mismatches [P] is evidence, not proof; an audit of every comparison or
ordering on start-key values in `PaGraph`, `PaProjection`, `LrProjection`, `ParCoolStages` is
required, and B1 removes this obligation; (3) a serial-vs-shared differential. The client keeps
one reduction per remote and is unaffected. Medium-large (common reducer).

### A8 BOUND — ADR4 §7 analytic broad phase

ADR4 §7 evaluates every eligible player before geometric rejection and allows "an analytic
enclosing bound for the whole allowed time/start/dependency domain" once proved. New: per resource
generation, a radius ρ(gen) such that every corner of the six boxes of every reachable pose lies
within ρ·scale of the R placement origin — rig pivots and box extents, the maximum translation of
every PA clip channel over its keyframes with the easing overshoot bound, the body/root transforms
of every family port (TaCZ Tweaks crawl, ParCool roots, vanilla swim/sleep/elytra/death), seat
offsets of SBW/Create relative to the passenger origin, generated from the manifest. A victim is
resolved and evaluated iff the shot segment / projectile segment / explosion sphere comes within
ρ·scale of the segment between its two R endpoints; the others cannot be hit by any pose, so
outcomes are identical. Gate: ADR4 §10's candidate gate (exhaustive vs prefiltered, every family)
and a runtime "box beyond ρ" defect counter. Assumption for the numbers: 2 candidates per hitscan or
projectile segment (explosions keep their radius set). Risk: a missed transform (a new SBW seat)
turns a hit into a miss, hence the generated bound and the runtime check.

### A9 UPAR-2 — fork-join of the pure stages (UPAR-1 as designed)

Folds, retention round trips, receipt rows, evaluation, flight resolutions and (with WIRE-SERVER,
which also drops the size-check encode) snapshot encodes on 2 workers + main, outcomes replayed in
serial order. 2.62× folds / 2.35× round trips on 3 lanes [P, UPAR-1].

### A10 UPAR-3a — two-phase capture, r10 PA advancement in the same task

Main reads every live input of player P into an immutable raw record (vanilla fields, defensive
`ItemStack` copies, ParCool input rows with their block reads, SBW seat reads, TaCZ operator fields,
Create membership, the tick's queued weapon/LR/ParCool events); a per-player task runs P's
canonical PA advancement (P's `LrBodyPose.Stack` and TaCZ layers are touched by no other player),
the ParCool canonical stages, freeze, `validateComplete/Finite`, closure and the round trip; main
commits in player order (journal ids are per target life and minted only inside P's task while no
fold runs; Store installs, pins, `target.commit`, counters, `CombatPoseHistory`). Prerequisites
(UPAR-1 §4.7): catalog registration concurrent-safe or pre-registered (content-addressed,
idempotent), `staged/stagedRefs` per player, Store installs deferred to the commit,
`GENERATION_DEFINITIONS` built before the fork, LR's reflective integration audited for statics.
Main residue (estimate): 185 live getter calls (ns each without mocks), record building and commit
≈ 60 µs/player.

### A11 UPAR-3b — per-observer stream tasks with pre-minted ids

Per-pair bookkeeping, fold commits and the encode run as one task per observer; main plans in serial
order (which pairs issue/fold, minting `target.next()` ids and subscription generations in today's
order), commits each observer in player-list order (Store install/pin, `publish`, sent ledger) and
enqueues with D as ADR4.2 §7.2. `needsTimingSeed` reads `System.nanoTime()` per observer inside
`prepare`; the plan reads it at the plan point instead, at most the stream phase earlier in the same
END tick — bit-identical with UPAR-1's injected clock, contract-neutral otherwise. Main residue
≈ 30 µs/observer + 1 µs/pair (estimate).

### A12 OVERLAP — speculative folds during the world tick (UPAR-1 §4.9)

Fold inputs exist at tick start; with immutable per-generation catalog/store snapshots (after
UPAR-3) folds can run while the main thread ticks the world. Small after A7; not in the numbers.

### A13 PREPARE — per-pair prepare without scans

Per pair `prepare` copies the eligible map into a `TreeMap`, stamps a fresh `AnimationState` and
identity, and `needsTimingSeed` and `fold` each scan all retained seeds of the observer for the
newest one of the life. New: the newest-seed index of A1, the stamped identity reused while its
fields are unchanged, the sorted order kept by the caller. Estimate −60 % of 3.6 / 9.6 ms.

### Allocation and GC

101 / 237 MB per tick [P] (endTick 39 MB, stream 52 MB, receipt 6 MB, evaluation 2.5 MB at 30x9).
A1, A3, A5–A7 remove most of it (set rebuilds, round-trip copies, replays, intermediate graphs).
Parallel stages do not reduce GC work; the UPAR-1 §4.10 heap budget stays.

## 5. Class B: semantic changes that keep exact equality and visuals

Each B option changes a rule today's code fixes, so results are not bit-identical to today; each
keeps server = client six-box equality (both sides follow the same rule, or the rule is
server-internal) and changes nothing a player sees except where noted (B5: latency).

### B1 CLASS-SEEDS — one fold seed per presentation class, folds aligned per target

Today each pair folds when its newest seed is `FOLD_AGE` = 20 ticks old and gets a fresh
`target.next()` id, so ids and node start keys differ per observer even for equal presentations.
New rule (server only): pairs of target T fold at the ticks where `(tick + φ_T) mod 20 = 0`
(φ_T = entityId mod 20) if the newest seed is ≥ 10 ticks old; the server groups T's folding
observers by reduced presentation (A7's key) and mints one successor id and one seed content per
group; each observer still gets its own stamped seed record (identity, own starts and proofs).
FULL/timing seeds of observers subscribing in the same tick are grouped the same way.
Equality/visuals: unchanged — a fold is adopted without a visual change by construction (ADR4
§3.6), ids are opaque labels, the client and its reducer are unchanged. Trust: unchanged
(server-derived seeds). Bytes: unchanged per observer; folds of one target bunch into one tick per
20 while φ spreads targets over ticks (removing today's fold bursts, ADR4.2 §3.3). Latency: none.
Window: seed age at fold 10–29 (mean 20); replay < 40 buckets. Effect: A7 needs no relabel proof for
grouped observers, and fold commits (content, digest, round trip, install, pin) run once per group.
Small (`S/combat/AnimSyncStream.fold/issue` + plan grouping). Decision: coordinator (server-only id
and schedule policy, no wire format or client change).

### B2 FOLD-AGE — longer fold age

`FOLD_AGE` 20 → 30. Reducer work per pair stays ≈ 1 step per tick (a fold replays its whole age), so
only fold commits drop by ⅓ (≈ 1.9 ms bench-quiet at 30x9 today, ≈ 0 after A3/B1), while the margin
to the 40-tick history shrinks (more `seedFoldUnavailable` at high RTT). Not recommended.

### B3 LEGACY — no per-tick legacy r10 body while capturing

Today every END tick builds the legacy r10 bones and `BodyPose` frame for every player (§2.1:
legacy sample, frame, history and most of the input reads), although exact hits use
`AnimSyncEval`. Consumers: labelled current-body fallbacks (`CombatViews.fallback`,
`AreaExposure` without a stamp, `CombatHitbox.Body.current`), the nonplayer broad-phase inflation
`CombatPoseHistory.reach`, `SnapshotStream`'s "committed" check, `/bodypose` and F3+B diagnostics.
New rule (server only, only while `AnimSync.capturing()`): canonical PA advancement and S[t] capture
as today; the legacy body of tick t is computed on demand by the ADR4 evaluator from S[t] at integer
time (`Eval(S[t], R = A = t)`, canonical starts) and memoized per tick; `reach` comes from A8's ρ;
the committed check becomes "S[t] exists". Equality: fallbacks never count as equality (ADR4 §3.5
item 7); exact hits unchanged. Visuals: none. Accuracy: fallback hits use the reference evaluator's
canonical pose instead of the legacy port. Trust: unchanged. Owner question (fallback geometry
source).

### B4 LAZY-ROWS — full reconstruction only for candidate victims

Today a receipt is EXACT only if every row reconstructs (reduce to A, resolve, presentation digest);
the first failing row makes every victim fall back. New rule: identity, victim-set, seed, prefix
and start-bound checks for every row as today (cheap); reduction, resolution and digest only for
the victims A8 keeps as candidates of this shot (a row that becomes a candidate later — another
segment, a later flight tick — is reconstructed then). Requires A8. Equality/visuals: unchanged for
every evaluated victim. Trust: a modified client can submit an inconsistent row for a victim that
this shot cannot hit without voiding the receipt for the others; it gains no hit (non-candidates
are unhittable by that shot), but the server detects fewer inconsistencies. Owner question
(validation scope).

### B5 NEXT-TICK — send tick t's batch at the start of tick t+1

Today S[t] capture, prepare, encode and enqueue all happen inside END tick t, and the main thread
waits for every fork-join. New rule: END tick t reads the raw inputs (A10) and returns; workers
capture, fold, validate and encode while the server finishes the tick and idles; at the start of tick
t+1, before the task drain, the main thread commits in order and enqueues tick t's probe, capsules
and snapshot (D at the actual enqueue, as now). Per-connection order (probe → capsules → snapshot of
t before any t+1 capsule) holds because t+1's capsules arise in its task drain and entity ticks,
after the enqueue. Equality: unchanged (D, freeze evidence and every ledger are taken at the real
send). Visuals: animation quality and immediate starts unchanged; remote bodies and END-tick
capsules arrive later by the server's idle gap (≤ 1 tick, ≈ 15–30 ms on the homeserver), absorbed
by the jitter buffer and seen only as that much more remote-player delay; native-wrapped
(mid-tick) capsules are not delayed. Trust: unchanged. Owner question (latency).

### B6 RECEIPT-REF — digest-referenced receipts (ADR4 §10 "next iteration")

Shots of one presentation revision reference one validated receipt by digest. CPU gain only for
several shots per frame (pellets, bursts within a frame); a 10 rounds/s rifle changes frame between
shots. Bytes −10–25 KB per extra shot. Defer (already parked by the owner).

### B7 CLIENT-PROVEN FOLDS — considered, no gain

The client folds and reports a digest; the server verifies lazily. Validation still needs the
server's reduction of every row, and a lazily verified chain longer than the 40-tick history needs
longer retention or an unbounded replay spike; after A7 the steady fold cost is per class. Not
recommended.

### B8 OBSERVER-INDEPENDENT FOLD SEEDS — subsumed

Canonical seeds instead of observer folds are exact only where the observer's presentation equals
the canonical one; elsewhere they would snap observer-relative phases (class C). The exact part is
A7/B1 (the canonical presentation is one class among others).

## 6. Class C: excluded (accuracy or visual loss)

- Sampling observers, targets, rows or victims, or a candidate cap without A8's proof: misses hits (ADR4 §1 item 7, §7).
- Capturing S[t] or folding every second tick, or anim-sync below 20 Hz: breaks equality at the skipped buckets.
- Approximate folds or validation (tolerances, skipped digests): equality unproven.
- Baked-bone or whole-tick pose streams: rejected by the owner (2026-10-15).
- Aligning starts to the movement timeline or to coarser/shared A buckets to increase sharing: delays starts (owner decision 3, option A rejected).
- Canonical seeds for pairs with observer-anchored nodes: snaps phases at the fold.
- Fewer native layers, a simplified rig, f16/quantized graph scalars: visual and equality loss.
- Skipping non-candidates without a proved bound (`CombatPoseHistory.reach` is explicitly not a proof).
- Dropping the retention round trip or its equality assertion: weaker codec validation (UOPT rule).

## 7. Recommended package, order and projection

### 7.1 Units and order

| Unit | Options | Main files | After | Size |
| --- | --- | --- | --- | --- |
| **UOPT-2** (bit-identical, single thread) | A1, A2, A3, A4, A5, A13; bench defaults `parcoolTick`, `aSpread`, `flight` (§10) | `S/combat/AnimSyncStream` (prune, prepare), `S/combat/AnimSyncValidation` (prune, applied, advance, `Victim`), `S/combat/VanillaAnimSync`, `S/combat/AnimSyncLedger` + `K/combat/AnimationSchema` (decode memo), `S/combat/LrBodyPose`, `S/combat/AnimSyncCapture`, bench | UOPT integrated | M |
| **UPAR-2** (UPAR-1 §7) | A9 | as UPAR-1 §7 | UOPT-2 (both edit `AnimSyncValidation`/`AnimSyncStream`; UOPT-2 is smaller, land it first) | L |
| **WIRE-CODEC → WIRE-SERVER** | encode once per observer on the pool, size-check encode removed | ADR4.2 §10 | UPAR-2 (pool) | as planned |
| **USHARE** | A6, A7, B1 (if approved) | `K/combat/ObserverReducer`, `PaProjection`, `LrProjection`, `ParCoolProjection`/`ParCoolStages` (graph/bookkeeping split, step cache), `S/combat/AnimSyncStream.fold/issue` (grouping) | UOPT-2, UPAR-2 | L |
| **UBOUND** | A8, then B4 (if approved) | new `S/combat/PoseBound` (ρ per generation from the manifest), `ConservativeCoverage`, `CombatViews.exact`, `FamilyStamps`, `AnimSyncValidation.replay` (B4) | UOPT-2 (A5) | M, proof-heavy |
| **UPAR-3** | A10, A11, B3 and B5 (if approved) | `S/combat/ServerBodyPoses.evaluateInner/endTick`, `AnimSyncCapture`, `LrBodyPose`, `HeldWeaponPose`, `ParCoolPoseInputs`, `AnimSyncStream.prepare/publish`, `SnapshotStream.exact`, catalog/store concurrency | UPAR-2, WIRE-SERVER, USHARE | XL |

Order: UOPT → **UOPT-2** → UPAR-2 ∥ WIRE-CODEC → WIRE-SERVER → **USHARE** ∥ **UBOUND** → **UPAR-3** →
30-bot staging measurement (owner-approved stand) → B5 decision. One branch/PR per unit into
`fulla/phase23`. UOPT-2 goes first: small, bit-identical, and on its own it removes 39 % (30x9) /
43 % (30x29) of today's production-equivalent cost including flight, most of it the flight replay
the UOPT bench never exercised.

### 7.2 Projection, cumulative in package order

Production-equivalent costs (§2.1), combat 20 shots/s, 3 bullets in flight; A7 shares and A2
measured [P], other factors estimates (§4–§5). Bench-quiet main / total ms per tick; homeserver
main = 1.5–1.8 × (serial + parallel / 2.4–2.0).

| step | 30x9 bench main / total | 30x9 homeserver main | 30x29 bench main / total | 30x29 homeserver main |
| --- | --- | --- | --- | --- |
| today (serial) | 131.8 / 131.8 | 198–237 | 354.6 / 354.6 | 532–638 |
| + A1 LEDGER | 123.7 / 123.7 | 186–223 | 325.7 / 325.7 | 489–586 |
| + A2 EQUIP | 122.0 / 122.0 | 183–220 | 324.3 / 324.3 | 486–584 |
| + A3 CODEC | 115.1 / 115.1 | 173–207 | 307.7 / 307.7 | 462–554 |
| + A4 R10-MICRO | 114.0 / 114.0 | 171–205 | 306.7 / 306.7 | 460–552 |
| + A5 FLIGHT | 82.9 / 82.9 | 124–149 | 206.6 / 206.6 | 310–372 |
| + A13 PREPARE (= UOPT-2) | 80.8 / 80.8 | 121–145 | 200.9 / 200.9 | 301–362 |
| + A9 UPAR-2 + WIRE-SERVER | 44.1 / 83.6 | 69–91 | 93.9 / 209.8 | 149–203 |
| + A6 STEP | 41.6 / 76.5 | 65–85 | 86.2 / 187.7 | 136–185 |
| + A7 SHARE (+B1) (= USHARE) | 36.2 / 63.1 | 56–73 | 65.5 / 137.7 | 103–139 |
| + A8 BOUND (= UBOUND) | 29.7 / 44.6 | 45–58 | 40.8 / 67.2 | 63–81 |
| + A10/A11 UPAR-3 | 20.7 / 49.5 | 33–46 | 32.0 / 72.7 | 51–69 |
| + B3 LEGACY | 18.8 / 44.0 | 30–41 | 30.4 / 68.2 | 48–66 |
| + B4 LAZY-ROWS | 17.9 / 41.3 | 28–39 | 27.1 / 58.9 | 43–58 |
| + B5 NEXT-TICK | — | **12–26** | — | 35–56 |

With everything up to B4 the main thread runs 5.3 / 10.1 ms of serial residue (ordered commits,
enqueue, bookkeeping, live reads) and waits for 32.7 / 44.4 ms of parallel work (bench-quiet;
shots and flight 6.1 / 7.0 of it during the tick, END-tick work 26.6 / 37.4). B5 moves the END-tick
work into the idle gap: on the homeserver the gap is 15–18 ms (30x9) and 6–11 ms (30x29) wide for
44–53 / 62–74 ms of END-tick CPU on 2.4–2.0 effective lanes, so 30x9 waits 0–11 ms and 30x29
15–31 ms.

### 7.3 Further reductions not in the numbers

Folds overlapped with the world tick (A12), node-level graph reuse in freeze and the round trip
(deeper A3), a ParCool canonical fast path for unchanged action rows, identity memos for
`validateComplete/Finite` and `stage` on unchanged components, a third worker where cores are free.
Each is bit-identical in principle; none is measured. Together they plausibly remove a further
5–8 ms bench-quiet of END-tick work at 30x9, which is what separates the fast and slow ends of the
30x9 B5 projection.

## 8. Owner questions (B options) and recommendations

1. **B3 — legacy body while animation sync is on.** «Пока включена синхронизация анимаций, можно
   ли не считать на сервере каждый тик старое (r10) тело, а строить его по запросу из того же
   чекпойнта точным вычислителем? Точные попадания и то, что видят игроки, не меняются; меняется
   только поза для помеченных fallback-попаданий (вместо старого порта — эталонный вычислитель).»
   Recommendation: **yes** (≈ −3 ms main and −7–10 ms CPU per tick on the homeserver, no visible
   change, fallback accuracy at least as good).
2. **B4 — row reconstruction only for reachable victims.** «Можно ли для каждого выстрела полностью
   восстанавливать только строки тех жертв, которых этот выстрел может задеть по доказанной
   границе (A8), а для остальных оставить дешёвые проверки идентичности? Исходы попаданий честных
   клиентов не меняются, читер попаданий не получает; но испорченная строка у недосягаемой жертвы
   больше не делает весь отчёт неточным.» Recommendation: **yes, once A8 is proved**.
3. **B5 — snapshot at the start of the next tick.** «Чтобы 30 игроков уложились на этом сервере,
   можно ли отправлять снимок анимаций тика t в начале тика t+1 (удалённые игроки на ≈ 15–30 мс
   дальше в прошлом; качество анимации, мгновенные старты и равенство хитбоксов не меняются)?»
   Recommendation: **decide after UPAR-3 is measured on a 30-bot stand**; by this projection it is
   needed for 30x9 combat on the homeserver and not needed on the hardware of §9.
4. **Release target on this host.** «Цель релиза на этом сервере — 30 игроков, каждый из которых
   видит до ≈ 9–12 других (30x9), или 30 взаимно видимых (30x29)?» Recommendation: **30x9-class on
   the homeserver; 30x29 only with the hardware of §9.**
5. **B1 — class seeds and aligned folds** (server-only id and schedule policy, no wire format or
   client change): coordinator decision, reported to the owner for information. Recommendation:
   **yes**, with A7.

## 9. Fit on the homeserver, and the hardware needed otherwise

- **30x9 combat: fits only with the whole package (A1–A11, A13, B1, B3, B4) and B5**: main thread
  12–26 ms/tick against the 15 ms budget (tick 32–46 ms with G = 20 ms), total CPU 62–74 ms/tick
  (≈ 50 ms on workers) against ≈ 70 ms — inside the budget at the fast end of the projection, over
  it at the slow end; confirm on a 30-bot stand before relying on it. Without B5: 28–39 ms main
  (tick 48–59 ms): does not fit.
- **30x29 combat: does not fit** on the i7-4710MQ with any option set: 43–58 ms main without B5,
  35–56 ms with it; total CPU 88–106 ms/tick.
- **Hardware for the package without B5** (busy main thread incl. G ≤ 35 ms, i.e. 15 ms headroom;
  G scales with the host; lane speedups assumed 2.4–2.0, 3.4–3.0 and 5.5–4.6 for 2, 4 and 8
  workers): per-thread speed relative to the i7-4710MQ (the bench's 7535U is ≈ 1.5–1.8×; current
  desktop Zen 4/5 or Raptor Lake parts are roughly 2.2–2.5× by published single-thread benchmarks,
  not measured here) and free cores for workers:

| cohort | 2 workers (2 free cores) | 4 workers (4 free cores) | 8 workers (8 free cores) |
| --- | --- | --- | --- |
| 30x9 | ≥ 1.45–1.8× | ≥ 1.25–1.45× | ≥ 1.1–1.25× |
| 30x29 | ≥ 1.9–2.35× | ≥ 1.6–1.95× | ≥ 1.4–1.65× |

  Total CPU per tick on such a host = 62–74 (30x9) / 88–106 (30x29) homeserver-ms ÷ the speed
  factor, plus G. Any of these requires the A package; no hardware within these ranges fits
  today's serial code (198–237 / 532–638 homeserver-ms with flight).
- **Sensitivity**: G (assumed 20 ms) shifts every verdict 1:1; fire rate and bullet flight time
  scale the flight term (A5 + A8 make it small); the 30x9 fit needs A7's measured sharing to hold
  in real traffic (bench: 71 % with a 3-bucket spread, 77.5 % with none).

## 10. Verification plan for the package

- **Every A unit**: UOPT's equality gate (`dev/anim_sync_perf_gate.py base after` over two `just test`
  runs: fragments, mixed-renderer MAIN samples, native/server digests) plus the bench's workload
  outcome (same receipts, verdicts, folds, adoptions) and verify mode; A1 a per-tick ledger
  differential (old full rebuild beside the incremental one in tests); A2 a memo-vs-codec check
  under random inventory mutations (count, components, TaCZ ammo writes); A3 the
  `decode(encode(x)).equals(x)` assertion plus a memo-off differential; A5/A6/A7 a
  serial-vs-resumed/shared differential on the bench and the fixtures (as `ReductionMemoTest`),
  with A7's relabel comparison kept as a test-mode assertion; A8 ADR4 §10's candidate gate
  (exhaustive vs prefiltered, every family, a victim whose pose intersects while its current box does
  not) plus the runtime "box beyond ρ" defect counter.
- **UPAR-2/3**: UPAR-1 §6 (injected-clock bench differential, workers 0/1/2/4, failure injection,
  race stress, reachability guard), extended to per-player capture tasks and per-observer stream
  tasks.
- **B options**: B1 per-frame presentation digests of every observer and `seedFoldUnavailable` = 0 in
  steady state; B3 fallback-body comparison legacy port vs `Eval(S[t])` on the U10 cohorts
  (differences reported); B4 honest-cohort verdicts unchanged and a malformed-row test per family;
  B5 the ADR4 §9 timing oracle and WIRE-GATE latency/backlog tests with the shifted send.
- **Bench**: native ParCool counter ticks and an observer A spread become defaults (without them the
  bench under-reports sharing by 8×), plus multi-tick projectile flight, a 30x29 cohort (lifted cap,
  no JFR table — the table needs the whole recording in heap) and a production-equivalent END-tick
  figure (mock calls × measured per-call cost).
- **Before release**: a 30-bot staging run on the homeserver (owner-approved stand) measures G, the
  main-thread time and the worker CPU, replacing §1's assumptions and §7's factors.

## 11. Evidence and reproduction

- Prototype: [`anim-sync-perf-options-proto.patch`](anim-sync-perf-options-proto.patch) applies to
  this branch's parent (`origin/fulla/phase23` `9cf76d2a` + `origin/fulla/as-uopt` `38bfbacf` +
  `origin/fulla/as-upar` `aaa9fb94`). Benchmark-only probes, all off by default: `S/combat/UperfProbe`
  (stage laps in `ServerBodyPoses.evaluateInner`, `AnimSyncCapture.commit/freeze/retain`,
  `AnimSyncStream.prepare/fold/publish`), `ObserverReducer.STEP_PROBE`, the oracle-test `UperfShare`,
  an `AnimationSchema.MAX_PAYLOAD_BYTES` system-property override, the `VanillaAnimSync` equipment
  memo, and bench switches (`-Puperf.combatOnly`, `countMocks`, `share`, `aSpread`, `parcoolTick`,
  `flight`, `equipMemo`, `maxPayload`, `heap`, `noJfr`, `shareDiag`).
- Raw results: [`anim-sync-perf-options-proto.json`](anim-sync-perf-options-proto.json) (per run: the
  exact Gradle command, start/end with memory and load, per-tick totals, operations, stage laps, share,
  flight and mock blocks, verdicts).
- Runs: under the workspace heavy lock, one at a time, `--no-daemon --max-workers=2`, MemAvailable
  ≥ 3 GB checked before each; JDK Temurin 21.0.12+8. Example:
  `./gradlew --no-daemon --max-workers=2 :server:oracleTest -PanimSyncBench=30:9 -Puperf.combatOnly=true -Puperf.share=true -Puperf.aSpread=2.4 -Puperf.parcoolTick=true -PbenchTicks=300 -PbenchWarmup=120`.
  The 30x29 run with the sharing probe and JFR (s3) ran out of its 1.5 GB heap in the JFR table after
  measuring; the JFR table is skipped for large cohorts since.
- JFR: the pinned JDK's `jfr` and a scratch `jdk.jfr.consumer` reader (inclusive/self/caller chains)
  on UOPT's final-code recording (`.dev/worktrees/as-uopt/server/build/anim-sync-bench/server-30x9-combat.jfr`)
  and this unit's own.
- Code reading at the base for §2.2 (bullet flight), §4–§5 (cited methods).
- No production code, stand, server, client, release, deploy or production host was touched; no
  Gradle daemon or JavaExec was left running.
