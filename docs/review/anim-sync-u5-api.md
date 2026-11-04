# U5 ParCool family API

Contract: ADR4.1 §§4.4/5.2 and U1 schema manifest 3. Branch `fulla/as-u5c`,
worktree `WS/.dev/worktrees/as-u5c`. Fork `fulla/as-u5c` at
`402eaa65251fa902ada674ed5d499eb21bef31a3` (pin promotion is U9b's). The family is
complete on the server/common side; U7c/U8 wire it (see "Wiring" below). No exact
cohort is enabled until that wiring and the U10/U11 gates pass.

## Production reducer (fourth resume)

`ParCoolStages(mode, samples, eventStarts, policy)` implements `ParCoolProjection.Stages`
and is the only production stage function; fixture callbacks are not substitutes.
`samples` maps each `InputRef` to a complete decoded canonical `AnimationState`;
`eventStarts` gives the start templates of accepted events by event sequence in fragment
order (host, Dive child, SkyDive child). The reducer binds node and predecessor IDs.

Journal contract per boundary `n` (ParCool layers; other families' records are skipped):

- `NATIVE_TICK`: exactly one inert input record, `ParCoolStages.input(EventId, InputRef)`.
  Its sample is S[n] or an explicitly recorded hold (an older sample). A later sample, a
  missing sample or a missing record fails closed. CANONICAL mode requires S[n] itself.
- `INTER_TICK_ACCEPT`/`PRE_NATIVE_TICK`/`END_COMMIT`: accepted patches from
  `ParCoolAccepted` (server: `ParCoolPoseInputs.accepted`), applied in sequence order by
  `event(...)`. An OBSERVER_APPLY event in bucket `n` is an `END_COMMIT` record of `n` with
  its own sequence; its `input` names the sample supplying equipment for options.
  `event(...)` is also the direct application API (U8 at packet application, U7 at
  acceptance). SELECTED replaces the incumbent (bf7 setAnimator); NO_CHANGE updates rows.
  Options follow immediately; removal waits for the next integer boundary.
- `POST_NATIVE_TICK`: optional recorded packetless outcomes, verified, never applied.

Boundary order: (1) rows: CANONICAL takes native counters and server inputs from S[n];
OBSERVER keeps event-driven counters/start data and takes synced/world values only while
S[n]'s canonical action is doing (otherwise held); charge sync, intent and gravity are
ungated. (2) bf7 tick order: passive (target intent, canonical flags, mirror Cling), animator
(Kong over the sample's R body endpoints, zipline integer step, Dive host/children and
SkyDive intent counters, transition counter), then all 27 action counters with Dive
`fallingTick` and Hang swing damping. (3) Hang R grid: CANONICAL advances three 60 Hz
steps over the interval ending at `n` (seeded from the sample's `yBodyRotO`, held while no
bar is found); OBSERVER carries S[n]'s grid. (4) removal: all 26 bf7 `shouldRemoved`
predicates on the integer mirror. (5) Dive: grid for the child's age (predecessor point
retained), child `oldFactor`/`pitchAngle` = last grid factor/root function at fraction 0,
then bf7 checkTransition, SkyDive child creation (start pitch = current Dive root pitch),
exit pitch capture and drop after the six-tick fade. (6) the five hasAnimator-gated
selections in registration order (JumpCharging, Crawl, FastRunning, FastSwim, WallSlide).
(7) options from the animator and `tacz != null`. (8) passive phase key when it starts
contributing; reducer-owned keys leave with the pose.

Derived starts: CANONICAL mode mints CANONICAL_TICK(n, POST_NATIVE_TICK). OBSERVER mode mints
PROJECTED_SUCCESSOR(n, POST_NATIVE_TICK, inputRef, ordinal) when a predecessor node exists
(removed animator, or host for the SkyDive child) and CANONICAL_TICK otherwise (U1 requires
a projected successor to name a predecessor). Ordinals: selection 0, SkyDive child 1,
passive 2. The event sequence is the boundary record's. U7 validates observer
CANONICAL_TICK starts for ParCool by replaying this reducer over the same seed/journal,
because an observer-shifted unblocked selection need not coincide with an S[t] decision.

`canonical(previous, n, decisions)` is the server END-tick product over the pre-ParCool
capture: events, boundary, `ParCoolYaw.apply` at zero partial (Hang commits its grid
endpoint), then Kong over the folded endpoints. It returns the result and the committed R
angles. Canonical history is imported as complete checkpoints, not re-derived from folded
samples. `hangAge(grid, p_R)` gives the R-domain Hang coordinate for placement sampling.

## Producers and adapters

- `ParCoolPoseInputs.sample(player, authoritative)`: END-tick rows for CANONICAL mode —
  native counters, Normal-synced and common-callback fields (`ParCoolActions.synced`),
  server world reads of the other-client callbacks (Hang bar/orientation, Cling wall/facing)
  and accepted intent. Until the first intent packet, U11 uses server-selected neutral
  directional lean; native server action/counter/world inputs are unchanged. A client that
  withholds intent stays hittable, while `intent(player)` remains null.
- `ParCoolPoseInputs.accepted(player, action, start, data, current)`: the accepted entry as a
  U1 patch after native start/finish, via `ParCoolAccepted`. `started()` toggles the server
  QuickTurn `turnRightward` first, exactly like bf7's local start.
- Target intent: `ParCoolIntentC2SPacket` (four key bits) is sent by `ParCoolIntentSender`
  after each client tick when it changes; the server keeps the latest per connection
  (`ParCoolPoseInputs.intent(player)`, null until the first packet).
- Client presentation: inside `RemoteParCoolPresentation.scope`, FastRunning/HWR scratch gets
  the current renderer limb input at p_R (`limb`), and `ParCoolDiveLegMixin` replaces the
  Dive leg term with `ParCoolReducers.diveLegDegrees(grid, age)`. The server port takes the
  same term through `ParCoolPoseInputs.Inputs.diveLeg`.

## Wiring left to U7c/U8 (exact calls)

Done by U7c ([handoff](anim-sync-u7c-parcool.md)); the calls below are what it wires.

U7c replaces `AnimSyncCapture.parcool` (it may not stay a capture-only hook, because the
fold changes placement): per player and tick, keep the previous ParCool `Result`, journal
each accepted entry as `ParCoolPoseInputs.accepted(...)` with its event identity and start
template, build S[n]'s pre-ParCool sample with `ParCoolPoseInputs.sample`, call
`ParCoolStages.canonical`, and commit its `angles` into S[n] placement before freezing.
Give the reducer the complete composed graph: like PA, ParCool allocates node IDs above
every live node and phase key in the graph it receives, so a family-only base collides with
PA IDs. Replace the legacy lazy zipline caller in the phase-2 `ServerBodyPoses` path; the
reducer schedules the zipline step. U8: apply observer events with `event(...)`, project
with `ParCoolProjection.observer` and these stages, install frames with
`RemoteParCoolPresentation`, and present the same p_R walk to the model call.


## Typed native restoration

`common.combat.ParCoolGraph` binds the reviewed U1 `AnimatorTag` registry to
one of two fixed implementations: native
`com.alrex.parcool.client.animation.impl.` or the server formula ports
`com.lunares.blockfield.server.combat.`. Class names never come from a packet.
The common adapter has no static dependency on native client classes.

- `freeze(base, animator, ids, starts)` replaces ParCool animator nodes and the
  `PARCOOL_ACTIVE` root, retaining other-family nodes, roots and aliases. The
  caller supplies disjoint positive node IDs and authoritative phase keys for
  the host and each child. Null removes the family subtree without allocation.
  It performs no native tick or render callback. All 26 registered roots, both
  Dive children, native constructor values and inherited ticks use U1 records.
- `materialize(graph, ref)` constructs new native/port objects and restores all
  scalars and Dive child topology. It does not tick the restored bucket. It
  rejects unknown types, duplicate/missing/cyclic child IDs, wrong child types,
  nonfinite inputs, tag/state mismatches and changed constructor constants.
  It is a subtree primitive: the caller still runs U1's complete-graph,
  identity, resource, start and resolver validation before installation.
- `passive(native)` / `passive(type, state)` capture/restore the three native
  passive counters. `option(native)` / `option(type, state)` preserve all seven
  raw allowed bits and the canceled flag, including the hidden mask of a
  canceled option. The returned native objects are invocation scratch.

Retain the immutable graph/U1 values. Materialize fresh scratch for every
root/model invocation and pass. Native callbacks may change scratch; never
freeze sampled scratch as the next canonical state. This adapter preserves
native history fields; it does not by itself apply ADR4's deterministic
history replacements.

`JumpFromBarAnimator` demonstrates why the native pin is the authority: bf7
has the instance constant `MAX_TICK=8`, while the server formula port spells
that constant `maxTick`. It is not a U1 field. The transformed fixture checks
the pinned native constant; the adapter does not invent a serialized field.

## Action inputs and counters

`ParCoolActions.capture(action, authoritative, player)` exports native mutable
visual fields and all four inherited action counters into U1 `ActionState`.
The authoritative input declaration supplies already accepted constructor
outcomes, duration/config refs, target intent, stop outcomes, Hang yaw seed,
resource refs and policy context. Capture never guesses those from a live
observer or a later state. Required input families, duplicate inputs, native
registration order and mismatched action/input types fail closed.

`materialize(state)` returns a newly constructed native action with counters
and visual fields assigned directly, without `start`, `finish`, physics,
selectors, sounds or effects. Use it only in a separate driven animation
scratch attachment, never to replace a live physics attachment. Complete
attachment construction and scoped installation are described below.

Special boundaries:

- QuickTurn's authoritative `AcceptedStart.direction` and `flag` restore native
  `startAngle` and `turnRightward`. Capture preserves the server-owned accepted
  outcome; native server defaults cannot replace it. `ParCoolAccepted` produces
  both at acceptance (the flag toggles in `ParCoolPoseInputs.started`).
- Dive retains the native A-domain double speed pair. Its stop outcome triple
  is supplied by the accepted journal, not recomputed during materialization.
- Zipline captures gravity from the exact player attribute getter. Scratch
  restores offset/acceleration/slope; rope physics is never reconstructed. The
  authoritative `ZiplineInput` (including valid/rope/gravity) remains retained
  beside scratch. Recapturing a scratch action is not a replacement for those
  authoritative dependency fields.
- Duration/config, passive flags, accepted intent and compatibility inputs stay
  immutable U1 context for the reducer; they are not writes to native physics.

## Reducer primitives available now

`ParCoolReducers` includes the earlier rational Hang/Dive grid sampling,
zipline double step, passive step and option reducer. This increment adds:

- `skyDiveStep(SkyDiveChild, IntentInput)`: all native counter limits,
  contradictory-key and release behavior, using accepted target intent.
- `kongStep(KongVault, previousBodyYaw, bodyYaw)`: the native atan(cross/dot)
  recurrence from consecutive authoritative R endpoints, preserving the old
  difference. The server Kong port calls this primitive.

These primitives do not produce intent packets, choose input history, advance
A/R, mint start proofs, or fold an observer seed.

## Canonical capture and invocation scratch

`ParCoolPoseInputs.capture(player, canonicalAnimator, baseGraph, canonical,
ids, starts)` is the server END-boundary capture surface. It checks the server
thread, captures the four native action counters, and preserves the complete
immutable visual input/accepted/config/grid/passive/option context supplied by
the canonical reducer. Client-only native server fields must not replace that
context. The transformed server fixture deliberately plants a different native
Dive speed and proves the retained authoritative speed wins across twelve ticks.
Calling capture never ticks an action or animator.

`ParCoolFrame.freeze` binds the captured native/port animator graph to the U1
ParCool checkpoint. Its result is a family operation result, not a new wire
schema. `scratchAttachment()` constructs all 27 actions and native lookup tables
fresh without touching a live entity attachment. Missing/duplicate input rows
fail closed. Only the three policy-forced geometry ClientSetting getters are
available; a new unclassified config/physics read fails rather than consulting
the observer's settings.

`RemoteParCoolPresentation.Frame` contains that value, the U1 policy, and A/R
fractions. U8 validates the complete resolver/receipt/generation context before
`install(remote, frame)`. For each native root/model invocation or pass:

```java
try (var scope = RemoteParCoolPresentation.scope(remote)) {
    // Run the native renderer's normal stages with U2's pinned entity/R inputs.
}
```

`scope` fully materializes a new Animation and Parkourability before publishing
its thread-local view. Production mixins redirect only the matching player's
native getters; native physics/lifecycle continues against the real attachment
outside the scope. Transformer/rotator partial getters use the frame's A
fraction for that player. Fresh parts, entity input scoping, and the outer
receipt/pass identity remain U2/U8 responsibilities. R-specific deterministic
family adapters remain part of the next reducer increment.

Native mirror animation ticks, frame option events, native packetless selection
and action render callbacks are suppressed only for installed driven remotes.
Root/model render-time removal cannot change resolved topology. Calls to a
registered driven render without an invocation scope fail explicitly. Nested
scopes restore in LIFO order and unwind on exceptions; a clear/reset cannot run
through an active scope, including a suspended parent scope. `clear`/`clearAll`
release the installation and reset mirror-only native animation fields; they
never replace or advance live physics actions. The local player is refused.
U8 must call them for Remove, unavailable/reseed, JOIN and disconnect.

These hooks execute with pinned native bf7 through the transformed oracle. They
do not require a fork pin change; the earlier fork live ClientInfo policy fix
still needs U9b promotion. Input production, integer topology/removal/selection
and projection are `ParCoolStages` (fourth resume).

## Restored zipline sampling and canonical yaw

Typed server `RideZiplineAnimator` restoration marks its integer bucket already
advanced. Its stored old/current double angles are the complete oscillator
checkpoint; neither root nor model sampling may integrate them again. This is
derived scratch bookkeeping, not another wire field. `ziplineStep` remains the
pure native integer recurrence; the complete ordered projector must schedule it
once per accepted input boundary. The legacy direct-construction path retains
its existing lazy advancement until that separate caller is replaced.

`ParCoolYaw.apply(preParCoolAngles, frame, actionPartial, hangAgeR)` returns U1
`Angles` with geometry consequences in native order: Cling, Hang, HWR, QuickTurn,
SkyDive, VerticalWallRun, then the resolved Roll/Dodge/Zipline animator. It
preserves old/current distinctions, including Zipline's old body = old entity
yaw. `hangAgeR` is the event-local retained grid coordinate, not absolute R.
Hang consumes its resolved grid endpoint/interpolation without reading a yaw
already overwritten by another real render. QuickTurn uses the accepted native
normalized vector, counter and direction flag. Missing required outcomes fail.

The canonical placement producer calls the fold at its ordered boundary (zero
partial for an integer action boundary), commits the resulting endpoints and
later samples placement on R. U8 must not apply it again using a later A over
already adjusted placement. No producer or transport is activated by this API.
Kong scheduling must consume the final consecutive canonical R body endpoints.

The transformed zipline fixtures emit identical U1 cases in the separate client
and SERVER JVMs. `python3 dev/check_parcool_oracles.py`, after both oracle tasks,
requires successful fragments, actual applied mixins and matching ParCool/
Loader/JDK artifacts, then compares 5,460 raw root/six-part float bits. Missing,
failed or unavailable artifacts cannot pass. Thirteen integer checkpoints cover
changing acceleration/slope/gravity, signed zero, idle hold and null/zero rope
offsets. Each side checks 30/60/144/240 FPS, reverse fractions, both root/model
orders, repeats and unchanged retained state.

`ParCoolYawOracleFixture` compares 7,680 native ordered calls with the fold:
all 64 subsets of six action writers, three cling facings/turn configurations,
ten animator variants and four fractions. It supplies Hang's endpoint using an
actual native Cling/Hang prefix to isolate ordered writes. It does not assert
that the selected retained Hang trajectory equals the native frame recurrence;
interval production, resolver input selection and composed trajectory A/B are
still separate unfinished gates.

## Earlier resumes

### Third resume: bounded replay and seed primitives (partial)

Merged Phase 23 `7aeec56d80ef0d91adc89dc7cd7be7410242f46a` before code changes.
U5 consumes U1 schema manifest 3 (nullable TaCZ); it has no two-argument
`PaGraph.Catalog` call to migrate. The shared generation catalog is unchanged.

`ParCoolProjection.Result` holds a `ParCoolFrame` and its complete U1 start
closure. This is a family operation result, not a wire type. A contributing
passive pose requires its start; a neutral row may have zero starts. Unused
typed passive keys round-trip unchanged, without inventing a sentinel or start.
Input/transition anchors are kept;
the API does not silently prune them based only on graph reachability.

- `canonical(base, from, to, complete, journal, policy, stages)` calls the shared
  `AnimationResolver.replay`. The newest complete checkpoint replaces derivation;
  its own tick is never repeated. Every missing subsequent boundary needs an
  explicit journal entry, including an empty one. The shared 40-tick continuation
  bound applies after the latest checkpoint. Journal lists are copied before
  invoking any stage. Restored checkpoints and each stage result are validated.
- `observer(seed, from, to, journal, policy, stages)` uses the same resolver with
  **no canonical-checkpoint import map**. `from` is the seed's projected integer
  A bucket from U7b/U8's immutable recipe/application ledger. Never infer it from
  `seed.checkpoint.serverTick`; retain R/A coordinates separately. Missing input
  or a missing reducer step must throw/be unavailable, not use a later H.
- `Stages.apply` is the explicit boundary between this scheduler and U5's still
  unfinished deterministic geometry reducer. It receives each native stage and
  its ordered decisions. It must consume the retained input/hold closure, tick
  only at the proper stage, and perform the §5.2 transitions/removal/selection.
  The production implementation is `ParCoolStages` (fourth resume, above). The
  native tick/counter callbacks in the third-resume oracle only test scheduling.
- A ParCool stage cannot change another family's nodes, roots, aliases or start
  proofs. Compose other families separately. `seedContinue` rebases only ParCool
  active/passive starts to a server-issued activation, preserving integer ages,
  native scalars and grids; other-family proofs stay unchanged.
- `fold` writes both the projected graph and ParCool checkpoint into the new U1
  seed. Identity, policy, reference scope, predecessor, nondecreasing prefixes,
  start prefix and reducer digest are checked. `withFrame` is the explicit
  checkpoint copy operation; all other checkpoint fields are preserved. U7b/U8
  still authorize prefixes, supply generation-compatible input/resource closure,
  lease definitions and retain old seeds until acknowledgement.

New transformed fixtures execute real native action counters in both runtimes
and all 26 native animators plus both Dive children on the client. They test
bounded folds versus unfurled integer callbacks, replacement without reticking,
passive and foreign-anchor preservation, missing-step/bound refusal, immutable
journal capture and the nullable no-gun checkpoint. These are scheduler/seed
fixtures, not server-port or deterministic-topology equality.

The third-resume list (canonical input production, holds, Hang/Dive intervals, Dive
topology/pitch/limits, yaw fold scheduling, removal, selections, successors, intent
transport, zipline scheduling) is implemented by the fourth resume above; only the
wiring named there remains.

U7b/U8 own event/snapshot delivery, actual native batch capsules, start proofs,
installation/revision lifecycle and presentation wiring. U9b owns the pre-JOIN ClientInfo timing fix
and verified fork pin promotion. The fork live-getter change cannot rewrite a
packet already sent before policy installation. Server lean remains policy-on
rather than trusting a stale stored ClientInfo value; U5's existing transformed
getter/ClientInfo fixture records the pre-JOIN hazard explicitly.

Native restoration evidence covers 50 constructor combinations, immediate
root/model samples, twelve continuation ticks and Dive interruptions. The
fixture retains raw encoded U1 constructor/continuation state, action input bytes
and native root/six-part scalar bits. It runs in the registered transformed
session with the production bf7 pin. See `anim-sync-u5-fixtures.json` for source,
log and fragment hashes and the exact validation scope.

Mixed-renderer, runtime matrix, live equality, owner A/B and per-shot cost gates
remain with U10/U11. No release, tag, deployment or live stand was performed.
