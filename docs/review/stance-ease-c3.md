# STANCE-EASE c3b: held transitions closed

Baseline: `fulla/as-stance-ease` at `3be09619`. Stance duration is eight ticks.
Crawl enter/exit, TaCZ timing, the LR hold probe and the Crawl held exit are
complete on client and server. No stand, client window, fork release or
production deployment is involved.

## Timing audit

| Layer | Client before c3 | Phase-3 server before c3 | Change |
| --- | --- | --- | --- |
| ParCool Crawl limbs | Instant on/off; body lean already follows `flat` | Rigid native port, instant overlay; same graph removal predicate | Blend the existing native overlay by shared `flat`; keep the Crawl node through normal and held exits (fresh-stop provenance). |
| TaCZ 3P crouch/lie | Eight-tick `INOUTSINE` PA fade | Replays that same native PA graph, including the fade | Keep authored animations and eight-tick fades. |
| TaCZ 1P RPK crawl start/end | 0.4333 s = 8.666 ticks | No first-person layer on server | Keep authored timing (8 ± 1). |
| TaCZ 1P FN Evolys crawl start/end | 0.6 s = 12 ticks | No first-person layer on server | Scale the runner's progress delta by 12/8; curves and sound progress remain on the same clock. The rule applies to any crawl clip outside 8 ± 1. |
| LR sneak | Upper four ticks / lower eight, native forced sine fade | The same native forced fades in the mixed PA graph | Stance selections use one shared linear fade factory, length eight, seeded from stance age and re-sampled from the shared crouch coefficient every tick (held freezes, interruption re-seeds). Non-stance idle/attack selection stays native. |
| Held-weapon frame | Client already uses live PA graph | Canonical `AnimSyncEval.pa` samples the live PA graph. Instant frame selection survives only in legacy `HeldWeaponPose.Profile.capture`. | Leave the legacy frame untouched for LEGACY-OUT. |

The audit read ParCool's pinned `CrawlAnimator` and `Animation`, TaCZ bf8's
`AnimationManager` and `ObjectAnimationRunner`, and LR bf2's
`PlayerAnimatorIntegration`/`ForcedFadeModifier` bytecode. The latter applies the
sine alpha twice when its predecessor is active; merely changing its duration
would not reproduce the linear stance coefficient. The stance path therefore
uses PA's standard linear fade through `StanceLayers.lrFade` on both runtimes.

## Implementation and proof boundary

`StanceLayers.blend` preserves native endpoint bits and supplies the same scalar
operation to the native Crawl mixin and the phase-3 evaluator. Exit retention is
also shared between the native removal hook and the canonical/observer reducer.
No new state field or wire layout was introduced. The legacy per-tick Crawl
port remains unchanged for LEGACY-OUT, like the legacy held-weapon frame. A fresh renderer scratch cannot
remove the authoritative Crawl node early.

During crouch transitions, LR body-layer decisions carry the existing typed
stance block as part of their input closure; canonical selection and observer replay use the same fade factory.
This is an input-closure addition, not a schema-layout change. The LR transition
algorithm resource fingerprint is versioned accordingly.

The remaining timing fixtures exercise native ParCool `CrawlAnimator`, native LR
`playIdleAnimation`, native TaCZ `AnimationManager.playLoopAnimation`, and the
production server paths. Every timeline crosses the stance codec; PA timelines
use pinned rifle clips, cover enter/exit and interrupted changes, and sample at
0, 1/2 and 65535/65536 tick. Repeated samples must be inert. The aggregate oracle
report compares each raw scalar bit across the separately transformed JVMs.
The server LR timeline also replays its stance-bearing decisions through the
production `LrProjection.decision` and compares every sampled bit to the
canonical stack.

First-person timing calls the actual transformed `ObjectAnimationRunner`
`updateProgress` method with eight 50 ms steps and resource-declared RPK/Evolys
lengths. It verifies that RPK remains unchanged and Evolys advances through its
full authored duration in eight ticks. This is runner-clock evidence, not a live
first-person visual acceptance run.

The c3 layer timeline compares layer parameters; existing mixed-renderer shards
remain responsible for root/six-box/corner equality. No gate or tolerance changes.

## Coefficient work (closed by c3b)

The c3 fade initialized a native eight-tick fade from the shared stance age but did
not sample `crouch` continuously: a ceiling hold froze the stance coefficient while
PA kept ticking the fade (isolated hold probe: expected exit alpha 0.25, actual
0.625). The probe source, failed XML and command log are kept in the coordinator
evidence directory named at the end of this doc.

c3b binds the sampled LR sneak layer to the shared crouch coefficient with one
shared implementation (`StanceLayers.syncLrLayer`, per tick, after the layer tick
on every runtime):

- Native: `RemoteLrLayerMixin` re-samples the installed stance fades from the live
  stance at every `playIdleAnimation` call (own, undriven and driven-shadow layers),
  before the native same-clip early return. Only PA standard 8-tick linear fades
  are touched; native forced/attack/stop fades are never re-seeded.
- Canonical and observer: `LrPose.select` re-samples from the decision stance
  snapshot on every selection, so the server stack and every replayed observer
  decision track the coefficient (held and interrupted changes included). The needed
  source pose stays in the existing U1 closure (patch stance plus frozen begin);
  no schema change, no workaround. The LR transition fingerprint is versioned
  accordingly (`lr.transitions.v5`).
- The freeze needs a frozen clock: `StanceTransition.hold` preserves the transition
  age instead of settling it, ticks stay suppressed while the endpoints are
  collapsed, and the sync pins a collapsed stance to its frozen age. Resuming
  re-targets from the frozen coefficient with no jump.

Crawl held exit: `hold()` copies the current `flat` into the endpoints, so the old
`toFlat == 0` predicate dropped the node too early. `StanceLayers.retainCrawl` now
also retains a collapsed (`toFlat == flat == fromFlat`) exit while the Crawl stop
is fresh (`notDoingTick < 8`, from the existing action provenance on both runtimes:
native `Parkourability`, canonical/evaluator action rows). A settled TaCZ prone
rests collapsed too, but its Crawl stop is old, so its stale overlay still drops.
Retention lapses 8 ticks after the Crawl stop even if the hold persists.

Both cases are promoted into both stance oracle fixtures (client + server): a
`sneak-held-exit` LR scenario and a `crawl-held-exit` Crawl scenario, every tick
bit-identical. This checkpoint carries no integration block.

## Files

- `common/.../StanceTransition`: frozen hold age, suppressed ticks while held.
- `common/.../combat/StanceLayers`, `ParCoolStages`, `LrPose`, `LrProjection`:
  shared math, Crawl lifetime, per-tick LR sampling and replay input.
- Client `ParCoolCrawlStanceMixin`, `CrawlTimingMixin`, `RemoteLrLayerMixin`
  and their mixin registrations: native overlay and timing hooks.
- Server `AnimSyncParCoolEval`, `AnimSyncEval`, `LrBodyPose`, `AnimSyncCapture`:
  evaluator integration and stance-bearing LR decisions.
- Shared `StanceEaseCases`, both `StanceEaseOracleFixture` classes,
  `StanceEaseServerCases` and the existing native/port fixture helpers:
  scenario execution, native samples and replay comparisons.
- `dev/check_parcool_oracles.py`, `dev/anim_sync_report.py`: mandatory cross-JVM
  comparison in the aggregate gate.

Hold-probe evidence: `WS/.dev/coord-20261011/evidence/stance-c3-20261020-3w061szt/`.

## Verification

- `just format`, `just check`, `just test`: pass on the implementation source.
  Both aggregate oracle tasks ran all shards. The stance differential compared
  11,808 raw scalar bits with zero differences (crawl 55, LR 165, TaCZ 108 rows,
  up from 31/93/108 with the two held scenarios). Existing mixed-renderer gates
  retained their bit-identical / zero-beyond-1mm results.
- Targeted server JUnit (`StanceEaseTest`, `StanceHitboxTest`,
  `VanillaAnimSyncTest`, `LrBodyPoseTest`, `ParCoolReducersTest`): pass; the
  dependent in-process GameTest server shut down. New unit cover: the frozen hold
  age and suppressed ticks, the sampled LR fade under a hold (exit alpha stays
  0.25), and the fresh/stale Crawl retention cases.
- Follow-up fixture-only check: explicit TaCZ gun in hand, prone/crouch entity
  pose and the LR mixin-config application assertion; both focused oracle tasks
  pass and the differential still compares equal raw bits.
- The isolated LR hold probe from c3 is superseded by the `sneak-held-exit`
  oracle scenario (same shape: exit two ticks, hold four, resume and settle).
- The wire performance gate uses checked-in evidence after reporting that fresh
  runs are from an older commit. No new bandwidth/CPU benchmark or live game
  acceptance is claimed. No merge, release or deployment was performed.
