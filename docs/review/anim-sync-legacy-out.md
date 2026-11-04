# Anim-sync LEGACY-OUT

Removal of the per-tick legacy r10 body (owner decision B3). Plan: workspace
`.dev/coord-20261011/plans/anim-sync/legacy-out-plan.md`.

## LEGACY-OUT step 1

`ServerBodyPoses.current(player)` serves the canonical body `Eval(S[t], R = A = t)` of the
player's latest retained S[t] (`CanonicalBody`, `AnimSyncEval.canonical`, the pure seed builder
`AnimSyncStream.seed`). The legacy END-tick pass still runs; `legacyCurrent` exposes it for the r10
branch of `CombatViews.resolve` and for the A/B test.

- Memo: one evaluation per retained value (`State.canonicalOf` = identity of the
  `AnimSyncLedger.Retained`), so N rays x M victims cost one evaluation per player per tick; a
  same-tick replacement retains a new value and re-evaluates. Counter `canonicalEvals`.
- Consumers: `CombatHitbox` (`Body.current`, the null-body clip path), `AreaExposure` (body
  overload), `CombatViews.fallback`, `FamilyStamps` (no-stamp and `server == null` branches),
  `ConservativeCoverage`, `KnifeCombat.body` and the SBW mixins all read `current()`, so they flip
  without call-site changes. `CombatViews.candidate` keeps an unavailable exact evaluation
  conservative with the canonical pose bound (`PoseBound.reaches` over the canonical recipe)
  instead of the legacy frame bounds. `CombatViews.fallback` without a body returns a null-body
  `view:current:<reason>` resolution (subcause `no_state`), a MISS.
- Snapshot committed check: dropped for the exact transport (the exact path omits a player without
  S[t] itself, `snapshotUnavailable:player`); r10 keeps it; `snapshotSkipped` stays for unpaired
  subscriptions.
- `/bodypose` dumps the canonical body with schema `blockfield.bodypose.v3`: `sampleUnixMs`,
  `sampleMonoNs`, `state.animator`, `animatorAge`, `doingTick`, `notDoingTick`, `weapon`,
  `display`, `lower`/`upper`/`once` (+`Age`) are absent.
- Capture off (`-Dblockfield.animSync.exact=false`, the r10 transport, and the unit-test default):
  no S[t] exists, so `current()` returns the legacy body until R10-OUT removes the switch.

### A/B: canonical vs legacy (`CanonicalBodyTest`)

Full END-tick pass (`ServerBodyPoses.endTick`, capture on, pinned PA/LR rig), every tick compared:
feet (distance < 1e-6), crouch (equal), eye height (1e-6), the 36 part parameters and the root
matrix. Tolerance ≤ 1e-5 outside the explained arm terms; every family asserts per-tick, and
the run logs `A/B <case> maxDiff=<worst non-arm difference>`.

| Case | Ticks | maxDiff | Verdict |
|---|---|---|---|
| standing | 4 | 0.0 | equal outside the bob term |
| walking, yaw 30, pitch -20, head yaw 45 | 6 | 0.0 | equal outside the bob term |
| crouch (pose, `isCrouching`, shift) | 4 | 0.0 | equal outside the bob term |
| rider-vanilla | 4 | 0.0 | equal outside the bob term |
| rider-sbw (Pilot seat) | 4 | 0.26 | DIVERGE, pinned both sides (below) |
| tacz-prone (swim pose, no weapon) | 4 | 0.0 | equal, weapon layers null both sides |
| tacz-reload (accepted, no weapon) | 6 | 0.0 | equal, acceptance is a no-op both sides |
| lr-swing (accepted attack, drain) | 6 | 0.0 | equal; attack-active arm terms ≤ 5e-3 |
| medkit (open channel) | 4 | 0.0 | equal |
| parcool crawl / fastrun | 4 each | 0.0 | equal |
| parcool hang / cling (unloaded neighbourhood) | 4 each | 0.0 | equal |
| parcool roll | 4 | 1.82 | DIVERGE, pinned by bound (below) |
| create-hanging (journal-fed) | 4 | 5.24 | DIVERGE, pinned by bound (below) |

Explained difference, every equality case: the idle arm bob (`VanillaBodyPose.bob`,
RIGHT_ARM/LEFT_ARM xRot and zRot, parameters 15, 17, 21, 23). The canonical evaluates at
A = t, whose native endpoint is `(t, partial 1)` (`AnimationTiming.endpoint`), so
`ageInTicks = tickCount + 1`, the age the remote client renders at that instant. The legacy
END pass samples `tickCount + 0`. The test checks each bob difference against
`0.05 |sin(0.067 (a + 1)) - sin(0.067 a)|` (xRot) and `0.05 |cos(0.09 (a + 1)) - cos(0.09 a)|`
(zRot) at varying ages: at most 0.0034 rad and 0.0045 rad. The canonical value is the one
the observer's client shows.

Explained divergences (asserted, each with its cause; step 2 must resolve them before
deleting the legacy pass):

- rider-sbw: the canonical side applies the Pilot seat (pinned: right-arm xRot -55 deg,
  zRot -30 deg), the legacy side keeps vanilla arms. Cause: the legacy
  `SbwBodyPose.freeze` degrades the whole seat to null when the parachute API is
  unloadable in unit tests (`NoClassDefFoundError` geckolib GeoItem ->
  `missing:sbw_api`), while the canonical `SbwBodyPose.capture` degrades only the
  parachute flag and keeps the seat. With the full pack both sides apply Pilot; the
  unit harness cannot show that convergence.
- lr-swing: on attack-active ticks the UPPER layer plays the accepted clip on both sides,
  replayed from S[t] progress canonically versus live in the legacy pass. Arm terms agree
  within 5e-3 (measured 1.1e-4, about one Mth table step: same progress, float-path
  noise); every other param is exact.
- parcool-roll: the legacy pass reads the live `doingTick`, which the unit harness never
  advances (no native ParCool tick runs on the mock action), while the canonical replay
  advances with the accepted-event age. Tumbling arms desync progressively (1.1 to 2.0
  rad over the case), tucked legs hold constant offsets. In production the action ticks
  natively and both sides share the progress; the onset fade still needs step-2 review.
- create-hanging: feeding the accepted-membership journal hangs the canonical body
  only (measured 5.24), because Create is absent from the unit-test classpath and the
  legacy `CreateBodyPose.freeze` never hangs. If Create ever lands on the unit-test
  classpath this must become an equality case.

Not producible in `:server:test` (harness walls, with file:line):

- TaCZ held gun and finite aim: a mock stack breaks the equipment canonicalization both
  sides share (`ItemStack.CODEC` in `VanillaAnimSync.canonicalUncached`, orElseThrow ->
  vanilla unavailable), an unregistered real item fails the same codec for want of a
  registry holder, and finite aim needs `IGunOperator`, which the pose player never
  implements (`ServerBodyPoses` reads it at instanceof). Covered instead: prone pose
  and reload acceptance with no weapon present.
- LR throwable held: same registry-holder wall for the unregistered mod item.
- Create contraption seat and Zapper/PotatoCannon arm pose: no Create classes in unit
  tests (`CreateBodyPose` returns graceful nulls); seat riding itself goes through the
  vanilla passenger path covered by rider-vanilla.
- SBW-seat equality and roll-progress equality: classpath (geckolib/config) and
  native-tick walls above; their oracle coverage stays pinned by
  `CreateSbwServerOracleFixture` and the `ParCool*PortOracle` fixtures.

### Without S[t] (plan section 3)

`CanonicalBody` returns null and counts `canonicalUnavailable:<cause>`: `no_state`, `no_target`,
`missing_tick` (the latest tick has no retained value), `latched` (capture unavailable latch),
`eval` (the evaluator refused). No older tick's value and no native box stands in. Tests:

- spectator: null, no counter changes;
- a tick whose capture fails (ParCool intent withdrawn): null although the previous tick had S
  and the legacy pass still produced a body; `CombatHitbox.clip` misses with a counted
  `missingFrames` defect; `AreaExposure` returns 0;
- one evaluation per retained value (`canonicalEvals` + 1 for six reads), a new one next tick;
- capture off: `current()` is the legacy body, no evaluation;
- a player outside the END loop (stale latest S): one synchronous restart per tick, counted
  `canonicalStale`, then the body of the restart tick.

### Defects fixed in the first commit (ca764fdf)

- With capture off every player had no body (null from `current()`), so no player could be hit
  under `-Dblockfield.animSync.exact=false`; 39 unit tests (which run with capture off) failed.
  `current()` now serves the legacy body while `AnimSync.capturing()` is false.
- The virtual activation proof of `AnimSyncEval.canonical` was invalid (`applyH = 0` below
  A = t, prefix below the activation id): every S[t] with an active start (an LR swing) refused
  with `eval:Invalid application clocks/prefix`, so the attacker had no body during the attack.
  Now `applyH = t`, prefix = the activation id, as an applied seed proof.
- The canonical evaluation ran under the exact path's worker stage `"evaluate"`, so a fault or
  timing keyed to a victim's exact evaluation also hit its fallback body. It has its own stage
  `"canonical"`.
- Three files were not `just format` clean.

### Migrated tests

- `PoseBoundGateTest.unavailableExactKeepsCurrentBodyHits`: the far fallback body was a synthetic
  legacy frame at x = 100; it is now the victim's own S[14] captured at x = 100. The
  missing-flight-boundary variant ticks 19 and 20: after the 14..18 gap S[19] is unavailable
  (LR reseed, counted `canonicalUnavailable:missing_tick`), and no older S stands in. Assertions
  unchanged.
- `PoseBoundGateTest.stubBox` names the counters when a victim has no current body.

### Counters

`canonicalEvals`, `canonicalStale`, `canonicalUnavailable:{no_state,no_target,missing_tick,latched,eval}`
(in `AnimSync.counters()`, surfaced by `bf bodypose status` with the other AnimSync counters).

## LEGACY-OUT step 2b (LEGACY-CUT)

Deleted the per-tick legacy body composition: `evaluateInner` keeps only S[t] advancement
(skin/shape/dimension resolution, TaCZ journal drain and loop/stop/fallback selection,
LR advance and selection, the graph freeze) plus capture; `frame()`, the legacy `State`
fields (`animator/action/starts/doing/...`, bones/body), `flatness`, the `LrBodyPose.sample`
materialize half, `stack.inputs` with its adjustment sources, the legacy ParCool intake
(`parcoolStarted`, `applyActions`, `freeze`, `Start`/`decode`, `synchronizedState`) and the
`CombatPoseNormalStateMixin` that fed it, `fallbackWeapon`, visual-yaw/skydive/mask/rotation
selection, and `fallbackDefects`. `AnimSync.capturing()` and every capture-off branch are
gone (capture is mandatory). `current()` serves the canonical body, reseeding synchronously
on a missing life and restarting stale fake players once per tick, as before.

Slim inputs: `ServerBodyPoses.Inputs`/`RootInputs` and `VanillaBodyPose.Inputs`/`freeze`
keep their shape — the canonical evaluator builds the full inputs from decoded values —
and the END tick populates only the fields the surviving advancement reads (vanilla
walk/ride state; the TaCZ selector `tacz, climbing, waterSwimming, pose, creativeFlying,
sprint, aim`); the TaCZ selector stays on the `lower`/`upper(in)` overload.

Tests: lifecycle assertions run on the ledger and the canonical body (join/respawn/
dimension-change/teleport/clock-gap/quit forget and reseed); the legacy-vs-canonical A/B
tests, the materialize and intake-unit fixtures, and the legacy mask/decoder/visual-yaw
tests are deleted with their subject; fallback suites commit real S[t] instead of fake
frames. S[t] bytes, ledger digests and wire fixtures are unchanged.
