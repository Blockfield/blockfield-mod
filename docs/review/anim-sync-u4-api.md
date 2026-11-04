# U4 LR family API and downstream handoff

Contract: ADR4.1 §§2.3, 3.2/3.6, 4.3, 5.1, 6, 10–11 and its 2026-10-16 errata. U1
schema manifest 3 is consumed unchanged (nullable TaCZ, U3F). U4e fix-session base:
`7aeec56d`; branch/worktree `fulla/as-u4e`, `WS/.dev/worktrees/as-u4e`. Review
P2-7 remains unresolved; the family must stay unavailable until it and the
existing downstream gates close. This API
does not activate an exact cohort: transport, timing, authorization, resources
activation and renderer-wide gates stay with U7b/U8/U9/U10 and must keep LR
unavailable until they wire it. Family oracles do not certify final six-box
equality or appearance.

## Effective resources (`common.combat.LrAssets`)

A generation-owned view of LR bf2's effective melee resolution, retained with
every seed/lease that names one of its refs. No live resource manager is read
while selecting or sampling.

- `register(id, config, animation, upper, lower, clips)` freezes one display:
  native `getThirdPersonAnimation()` presence, the Gson config (`animation`,
  `upper`, `lower` keys; list slots kept, non-string slots null, empty lists and
  non-string scalars absent) and the path's clip file under its native keys
  (native keys: `serializeTextToString(name).toLowerCase(ENGLISH)`, U3F). `config(...).clip(upper, key, count)` is native
  `getAnimation` (`count % size`; a negative count with a multi-slot list has no
  native result and is refused). `Display.clip(name)` is the native exact-key
  `getAnimations(path, name)`; a scale/bend clip is refused with
  `PaGraph.Unsupported` (`LrAssets.NON_RIGID`) on both sides.
- `held(displayId, meleeId)` is native `getMeleeDisplay(stack)` (override, then
  melee id); `display(id)` is `getMeleeDisplay(id)` for attack events.
- `require(ref)` refuses another generation or an unknown display. (U3F: every
  life shares its generation's catalog, so `scoped` is gone; clips are stored under
  TaCZ's native key without LR's mutable metadata; starts use Stack-private copies.)
- `definitions()` = catalog definitions plus every typed `ConfigRef`; install
  these in U9's store (config/display/manifest refs resolve and lease).
- `manifest(rotationWinner)` is the family contribution (the caller must pass
  the actual native associated-data winner, PA_42 or PA_96): transition algorithm/pins
  (`LrPose.manifest`, `lr.transitions.v3`), physical stack order and effective
  rotation winner, lookup rules and every display/config/
  clip/non-rigid entry of the generation. U9 still hashes raw bytes, pack
  precedence, missing ids and native display-creation failures; the server parse
  and the client's native effective view must produce equal manifests.

Server: `LrBodyPose.reload` retains one `LrAssets` per HeldWeaponPose resource
epoch, sharing TaCZ's generation catalog (`assets(generation)`,
`retireAssets(generation)` for U9 only). Client: U9 calls
`RemoteLrAssets.retain(assets, id, meleeDisplayInstance)` on each staged native
display; it reads the native Gson config and the shared TaCZ clip cache.

## Native selector and transitions (`common.combat.LrPose`)

All operations act on U3's one mixed `PaGraph.Stack`, bind new nodes through
`started` and never tick, sample or dispatch side effects.

- `tick(stack, display, suppressed, movement, sequence)` is one player's native
  `IdleAnimationHandler.onClientTick` body (POST_NATIVE_TICK): suppressed
  (sleeping/fall-flying pose, climbing, swimming) or no config → `stopAll`;
  otherwise upper idle unless an attack is active, lower idle, rotation latch.
  A config without an animation path changes nothing. `select(..., layer, ...)`
  is the same call for one layer (the observer's per-layer decision).
- `attack(stack, display, action, count, sequence)` is the native attack
  listener (INTER_TICK_ACCEPT): missing config/path → `MISSING_CONFIG`, missing
  clip or Gson null slot → `MISSING_CLIP`, both keep the incumbent.
- `suppressed`, `movement` (strict double `.001`/`.05`), `attackActive`
  (typed Boolean, strict `currentTick < endTick`) are the native predicates.
- `idle`/`attack`/`rotation`/`stop` reproduce PA's two-argument replace
  exactly: a stop fades only an active animation and drops an inactive one
  without a node; repeated stops while fading are no-ops; the rotation latch
  never installs a fade; idle always adds the forced fade with a frozen keyframe
  source. Each returns the native `Outcome`; `Step` adds the kind and journal id
  (-1: no node created).
- Driven actors carry private metadata overlays (`name`, `lr_attack_action`).
  **Known incomplete P2-7:** idle currently clears the attack flag, including
  that player's own prior attack on the same clip. ADR4 §5.1 authorizes only
  cross-entity history removal. Schema 3 retains metadata on reachable actor
  nodes, not a per-player/per-clip history after the actor and fade leave the
  graph. Fixing this across checkpoint/materialization/seed continuation needs
  a U1-owned typed retained-history contract. No extra field, hidden mutable
  map or new deterministic replacement is introduced by U4e. Pinned bf2's
  disjoint idle/attack clips do not exercise this gap; custom overlapping
  clips must not receive equality credit. Local native observations are
  preserved by the client view below.

## Canonical capture (`server.combat.LrBodyPose`)

Per END-tick apply: accepted attacks (sequenced at acceptance by the journal
allocator, `Stack.sequence(LongSupplier)`) are applied first, then the shared
stack's one native advance, then the selector once per tick. Package APIs for
U7b:

- `Stack.lr(assets)` exports U1 `AnimationState.Lr`: config/display null
  together on a missing-config tick (never reused bytes), actual incumbent
  selections, accepted `attackAction`/`actionCount`/`attackDisplay` (null when
  idle), attack-active outcome, rotation latch, the pinned rotation fade (4),
  fixed-hand/passenger and immutable `PendingOperation`s
  `(tick, sequence, action, count, lr.melee-display ref)`.
- `Stack.graph(base)` freezes the shared graph; `Stack.ops(assets)` returns the
  tick's journal entries in application order: `Op(eventSequence, stage,
  Patch)` with the tick's `Lr` in each patch. Selector outcomes without a node
  have sequence -1 (U7b sequences them); an accepted attack keeps its
  acceptance id even when it created nothing.
- A missing tick, a missing retained generation, a non-rigid clip or an
  exception or pending-queue overflow (65th attack) makes the checkpoint
  unavailable until `Stack.reseed()`; reseed drops actors, fades and their
  sources on every physical root, preserving only registered adjustments.
  It invalidates selector state so a same-tick selection can run again. A refused
  non-rigid layer keeps its incumbent and the tick's other layers continue.

## Observer projection (`common.combat.LrProjection`)

- `attack/idle/rotation/stop(base, ..., proof, ...)` apply one authorized
  OBSERVER_APPLY/stop operation in the current integer bucket (no retick).
- `decision(base, decision, placement, assets, policy, sources)` applies one LR
  selection at boundary `decision.serverTick` after that boundary's tick. It
  reevaluates native eligibility on the observer graph from `patch.vanilla`
  (pose/climbing/swimming/ground/crouch/sprint), the `Placement` the InputRef
  names (velocity) and `patch.lr` config/display (retained assets). The upper
  idle that an observer-anchored attack blocked starts as PROJECTED_SUCCESSOR at
  the first boundary the attack is inactive (`inputRef`, ordinal, predecessor);
  everything else is CANONICAL_TICK at the decision tick in the layer's policy
  domain (R for 41 under the R lower policy).
- `project(base, domain, from, to, journal, inputs, tacz, lr, policy, sources)`
  is the mixed TaCZ/LR bounded tail (≤ 40 boundaries): one domain tick per
  boundary, then that boundary's decisions in journal order dispatched to
  `decision` or U3's `taczDecision`. Every boundary must exist; nothing borrows a
  later H. Both families must share one catalog.

The server must publish all three LR decisions (upper, lower, rotation) for
every tick, including NO_CHANGE/blocked/stop outcomes: the observer's
eligibility can differ from the server's (its attack ends later), so it needs
each boundary's inputs. Accepted attack capsules are applied by U8 with
`attack` in their own bucket after that boundary's selections.

## Client native compatibility view (`client.animation.RemoteLrCompat`)

For a driven remote, LR's selector and attack listener keep running at their
native schedule on private shadow layers (`RemoteLrLayerMixin` redirects
`getLayer` and the rotation lookup; `RemoteLrCompatTickMixin` ticks the view at
`Player.tick` HEAD like PA's stack). They never touch the installed driven
geometry. Their only lasting effect is LR's writes to the shared clip cache,
which the local player's selector observes exactly as without driving. The
local player is never given a view.

U8 must call `attach(remote)` before the first `RemoteLrCompat.install`
and `detach(remote)` after `RemotePaPresentation.clear` (and `clearAll` on
JOIN/disconnect): the
native LR state moves into and back out of the view, so an undriven remote
continues natively. The LR install adapter refuses a missing view before
transplanting any geometry. A driven remote reaching an LR callback without
its view throws `PaGraph.Unsupported`; `unseeded(remote)` is true immediately,
even before the callback. `attach` cannot recover history from already-driven
scratch. U8 must label this unavailable and clear the presentation; it cannot
retry with an empty view. The low-level U3 install API does not perform this LR
lifecycle check: U8 must use the LR adapter for LR-compatible players.

## Evidence

Transformed (production Loader/mixins, registered in both session lists):

- client `lr-selector-native`: pinned `onClientTick`/`MeleeAnimationListener` on
  factory-registered players with the real shared TaCZ clip cache versus
  `LrPose` with `RemoteLrAssets`-retained assets, four players, 49 ticks, every
  selector/stopAll/no-path/Gson/missing-clip branch (asserted reached). This
  fixture only samples PA setup between ticks; it does not prove mixed
  renderer history independence.
- client `lr-compat-view`: an all-native world versus one whose remotes are
  driven through the view (one attached mid-run, both detached at tick 41): the
  local player's layers, `isAttackAnimationActive`, shared-clip metadata and
  remotes' native state are identical every tick, including a remote attack
  flagging the clip the local player idles with; driven real layers stay empty;
  local actors keep first-person mode DISABLED.
- client/server `lr-selector-projection`: projection consistency against a
  second **LrPose** replay on real PA, with the observer attack two ticks late,
  A/R lower policies, missing/no-path config and one first-eligible successor
  per policy. It is not an independent LR native-selector oracle; use
  `lr-selector-native` for the native leg.
- client `lr-mixed-render-history`: transformed PlayerModel preparation/setup,
  PA and TaCZ's per-frame no-gun stopAll run between ticks at 0/30/60/144/240
  FPS. It measures native rotation re-latching (and geometry with both pinned
  identity and deliberately non-identity `empty`), versus installed driven
  scratch at each frame. A native topology difference is expected, never
  reported as zero; driven canonical state must remain immutable and final
  model bits equal across histories. This is model-pipeline evidence, not a
  GL/root/final-corner or renderer-wide pass gate.
- client `lr-own-metadata-history-native`: actual native attack on `x_shared`,
  leave that clip at expiry, idle re-entry, then attempt to walk. Native attack
  metadata survives and blocks the walk selector. This is evidence for open
  P2-7, not an equality pass for the current port.
- client `lr-compat-lifecycle`: missing attach refuses the LR install adapter
  before mutation; losing an attached view is immediately unseeded and refuses
  the real LR layer callback. The real capsule dispatch/timing test remains U8.
- existing `lr-transition-continuation`, `lr-observer-continuation`,
  `lr-native-integration-leaves` (native play methods now use the same shared
  fixture clip instances on repeated lookups; they no longer copy on lookup).

Plain JUnit: `LrBodyPoseTest` (47: legacy fixtures on the canonical path plus
stage order/acceptance ids, missing-config null refs and single stops, no-path
config, exact-key lookup, Gson slots, gap/reseed, per-layer non-rigid refusal,
manifest determinism and store leases), `LrAnimationReadLayoutTest` (selector,
listener, transition and shared-cache read inventories against the pinned
jars), `RemoteLrInjectionTargetTest` (production injection targets; every LR
layer access passes the two redirected sites).

## Downstream (not U4)

- **U7b/U8:** wire `sequence`, `ops`, `lr`, `graph`, Vanilla/InputRef decision
  closure, capsule wrapping of the native attack broadcast (its native listener
  must still run once on the client so the compatibility view sees it), causal
  dedupe, `attach`/LR `install`/`clear`/`detach`, and the receive/task/zero-tick timing
  oracle (including an attack while the driven capsule waits on a predecessor). Keep
  LR unavailable until then.
- **U1:** add/review retained per-player clip metadata history and its
  checkpoint/observer-seed projection API for P2-7 before enabling LR exact
  presentation. U3F already supplied nullable TaCZ and generation refs.
- **U9:** install `LrAssets.definitions()`, include `manifest(actualRotationWinner)`, compare server
  and client manifests at READY, retain/retire generations.
- **U10/U11:** renderer-wide mixed pass/capture/48-corner, Iris/culling and
  live/owner A/B gates, using `unseeded` and the compatibility view in mixed
  pass-order regressions.

No stand, deployment, release or owner visual acceptance is claimed.

## U4e review closure (2026-10-16)

Verified at base `7aeec56d` against the U4 review in
`WS/.dev/coord-20261011/reviews/p3-anim-sync-u4-review.md`.

| Finding | Current result / regression evidence |
| --- | --- |
| P1-1/P1-2, P2-3/P2-4 | Already fixed by U3F; generation catalog, Stack-private copies, native keys and stripped resource metadata retained. The two respawned-life capture/projection tests remain the regressions. The gun test pre-registers its gun identity before measuring starts/projections, so it also works alone. |
| P2-1 | Former zero-mismatch claim removed; `lr-mixed-render-history` executes actual transformed model setup and exposes the TaCZ rotation writer. See latch policy below. |
| P2-2 | `lr-selector-projection` honestly labelled LrPose replay consistency in code/measurements; native integration leaves use shared clips. |
| P2-5 | `Stack.reseed` uses U3 `reset` on all seven roots, clearing fades/frozen sources; gap/reseed regression inspects the graph before selecting again and exercises same-tick recovery. |
| P2-6 | 65th pending attack latches checkpoint-unavailable; graph/lr/ops still refuse after the queue drains, until reseed. |
| P2-7 | **Open, not a declared ADR replacement:** per-player persistent metadata after a clip leaves the graph needs a U1 contract. No schema change made here. |
| P2-8 | Stale spectator comment corrected. Fourth legacy-server behavior change explicitly recorded below; existing canonical stage-order/acceptance-ID test pins it. |
| P2-9 | LR install adapter requires attach; no lazy empty view; lost view and late attach fail closed. Transformed lifecycle regression covers these APIs; real capsule dispatch remains U8. |
| P2-10 | `manifest(actualRotationWinner)` requires 42 or 96 and hashes physical stack order plus the chosen winner; the digest regression distinguishes both and rejects a non-rotation root. U9 passes the actual registered associated-data value. |

Rotation latch: native LR checks only `getAnimation()!=null`. Every TaCZ model
render of a non-gun holder sets that same associated layer's animation to null
under a four-tick stop fade, so the next LR client tick re-installs `empty`.
With no intervening renders it latches once. The driven model cancels this
native TaCZ entry through U3's production mixin, and canonical packetless
selection/stop decisions own the retained graph (ADR4 §§2.3/5.1). Consequently
all actual frames use the same integer latch state plus fresh scratch; real
render calls never feed the next tick. Pinned LR bf2 `empty` is identity, so
this topology history does not change geometry. A non-identity `empty` exposes
the native history difference; it is also deterministic in the driven path,
which both server and client evaluate from the same canonical graph. This is
the scoped history replacement, not a claim of native topology equality. The
owner A/B still covers the native-versus-deterministic difference.

Fourth change to the legacy server path: accepted attacks are applied **before**
the tick advance (native packet → PA Player.tick HEAD → END_CLIENT_TICK LR
selector order). The old END-tick implementation advanced before draining;
its exported attack phase is shifted by one tick by this correction. Together
with exact-name lookup, no spectator suppression and no-path configs leaving
layers alone, this makes four explicitly recorded server behavior changes.

No schema, fork, release, deployment or stand was changed. P2-7 and the
renderer-wide/live/owner gates are not completed by these family fixtures.

Regression sensitivity: `verification/u4e/mutations.log` records three failed
server assertions when old reseed/overflow behavior and an ignored rotation
winner are restored, and two failed transformed fixtures when the real model
render step or the LR install guard is bypassed. All mutated source is restored
before the final four-command gate.

Fixture isolation: LR's test resource installation now shares the production
TaCZ generation catalog. Each fixture snapshots and restores the catalog's resource indexes (including
identity-keyed native clip refs), so LR-only typed config dependencies do not
leak into a later TaCZ-only store fixture. It keeps the production shared
catalog during the test and preserves the pre-test global generation afterward;
it does not reload assets or change TaCZ's tests. The first full gate exposed the leak in two unchanged
`HeldWeaponPoseGraphTest` store-closure assertions; those assertions and the
store's validation are preserved. Mixed-render fragments also retain the
actual compared six-part raw float bits and post-render rotation topology.
