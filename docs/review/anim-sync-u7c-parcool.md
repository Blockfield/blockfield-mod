# U7c ParCool wiring (U7cP) and handoff

Contract: ADR4.1 §§2.2–2.3, 3.2–3.6, 4.4, 5.2, 6, 10 and both errata; U1 schema manifest 4
(unchanged). Branch/worktree `fulla/as-u7cp`, `WS/.dev/worktrees/as-u7cp`, base phase23
`d4972b9e`. ParCool is wired end to end in the shared reducer, both evaluators and the transport;
no exact cohort is claimed live (U10/U11 gates, owner A/B and pin promotion remain).

## Server canonical capture (`AnimSyncParCool`, `AnimSyncCapture`)

- Acceptance: `ParCoolPoseInputs.started/finished` (after native start/finish, after QuickTurn's
  server-owned toggle) journal every accepted entry with `AnimSyncParCool.accepted`: patch from
  `ParCoolPoseInputs.accepted` over the retained S[t-1] value with the tick's earlier entries
  overlaid, a journal id, the tick being entered (`INTER_TICK_ACCEPT`; an in-tick API start is
  `END_COMMIT` of the current tick) and the acceptance time. Bounded (256) per life.
- END tick (`freeze`): PA is frozen onto S[t-1]'s composed graph (`stack.graph(prior.graph())`),
  then `ParCoolStages.canonical` runs over that composed graph with the tick's entries, the
  NATIVE_TICK boundary over the pre-ParCool sample (`ParCoolPoseInputs.sample`) and the ordered yaw
  fold; the folded angles are committed into S[n] placement **before** the input ref, decisions and
  freeze. ParCool allocates above every PA node; PA allocates above ParCool on the next tick.
- Every S[n] journal ends with the ParCool boundary marker (`ParCoolCheckpoints.marker`,
  POST_NATIVE_TICK, PARCOOL_PASSIVE, input = S[n]'s committed placement ref). Its sequence is the
  canonical boundary's; observers turn it into the family's NATIVE_TICK record, so derived starts
  carry one server-allocated identity on every evaluator.
- Reseed: a missing S[t-1], a resource/policy generation change, a lost acceptance or capacity
  restarts the family from `ParCoolCheckpoints.neutral` (counted `parcoolReseeds`); the incumbent
  animator is then not presented (explicit, never guessed).
- Seeds (`AnimSyncStream.starts`) add the contributing passive phase key; folds carry the
  observer-reduced ParCool value with its graph (`ParCoolProjection.withFrame`).
- Legacy phase-2 path: the RideZipline port integrates once per integer `tick(input, pc)` like
  bf7, never lazily from a render callback; restored typed scratch needs no `advancedTick`.

## Shared observer reducer (`ObserverReducer`, DIGEST v2)

`Reduction` composes ParCool with PA in one graph: per bucket the PA tick, then S[n]'s decisions
in journal order (the marker runs `ParCoolStages` OBSERVER boundary over S[n] in place); a ParCool
capsule is `ParCoolStages.event` at fraction zero of its bucket with the bucket's checkpoint as its
options sample and the observer proof bound to each fragment node (action-only stops add no start;
their application row is the proof). Every stage is validated by `ParCoolProjection.stage`
(complete closure, other families untouched). `Result` carries the ParCool value and the passive
key; `parcoolFrame()` is what both evaluators sample. Validation therefore checks observer
CANONICAL_TICK selections and passive keys by replay (`reduced.starts().equals(row.starts())`).

## Transport (ADR4 §2.2 batched exception)

`AnimSyncParCoolBatchMixin` holds ParCool's one END-tick `ActionStateBroadcastPayload` (entries and
order untouched) on the exact transport. In the same tick's snapshot phase each observer gets it
exactly once: `SnapshotStream.exact` wraps every capsule of the tick in one delivery whose first
ParCool capsule carries the encoded batch (`native.parcool.batch`, others `native.parcool.batched`)
when it names ParCool capsules, otherwise sends the batch unchanged ahead of the per-life capsules;
`flush` serves everyone else (disabled/r10 observers, no stream). A capacity overflow falls back
to unchanged batch + per-life capsules (counted). The client (`ClientCombatScene.parcoolBatch`)
runs the batch's native `processPlayer` once per delivery (bf7 handleClient order) before the
journal; a driven remote's native mirror cannot select geometry (`ParCoolAnimationMixin`).

## Client presentation

`DrivenPresentation.install` installs `RemoteParCoolPresentation.Frame(reduced.parcoolFrame(),
policy, frac(A), frac(R))` for **every** driven remote (inert included); `clear`/`clearAll` release
it. `ParCoolRenderScopeMixin` opens the invocation scope around every
`EntityRenderDispatcher.render` pass of a driven remote (exception-safe); FastRun/HWR limbs read the
R walk the walk scope presents, the model call gets the same R walk. The local player stays native.
`ClientAnimationJournal.row` raises the applied prefix over the reduced starts exactly as the
server reconstruction does (post-seed canonical decisions name later journal ids; previously such
rows failed `AnimationStartReport.validate`); fold adoption also compares the ParCool value.

## Server evaluation (`AnimSyncParCoolEval`, `AnimSyncEval`)

The observer-reduced frame is materialized into the server ports (`ParCoolGraph` server package),
inputs from the typed rows (native counters via the frame's scratch attachment), Dive leg from the
retained grid, lean policy-forced. Stage order and gates are bf7 `Animation`: model pre (cancel
skips vanilla setup and model post), root pre (replacement skips the native root, the PA body root
and root post), option part mask, animation cancel, ROTATION cancel, passive falling without an
animator. Kong's difference pair is sampled at frac(A) on both evaluators (native reads the
transformer partial in the driven scope).

## U9b manifest

`compat.family.parcool` = `ParCoolCheckpoints.familyIdentity()`: reducer versions, native action
registration, native and mirrored duration/removal constants, forced geometry settings, computed by
both peers from loaded classes. The JAR bytes remain `compat.native.parcool` (bf7 `9a0ec4e2…` today).
Fork `parcool-fabric@402eaa65251fa902ada674ed5d499eb21bef31a3` (live ClientInfo getters, replay
hooks) is **not** pinned or built here: promotion into the paired client/server pins is the
owner-gated release step; driven geometry does not depend on it (scratch uses forced getters).

## Evidence

Plain JUnit (server): `AnimSyncParCoolTest` (Roll journal/capsule/install/fold/removal; honest
receipt with an observer-applied Roll EXACT and evaluated through the ports; observer FastRunning
selection CANONICAL_TICK without predecessor validated by replay, tampered anchor rejected; native
batch wrapped once per observer, flushed once to others), updated `AnimSyncCaptureTest`
(marker, complete family value, unavailable without target intent), `AnimSyncStreamTest`,
`AnimationManifestTest`, legacy zipline tests (tick integration).

Transformed (production Knot, intermediary, full pack): client
`parcool.presentation-six-box-native` and server `parcool.presentation-six-box-server` evaluate 207
typed frames (all 26 registered animators incl. variants at two ages, Dive host with Dive child and
SkyDive child entering/exiting from ground/air, passive falling, gun option masks incl. canceled
Crawl, and every boundary of U5's scripted canonical timeline plus its two-bucket-early observer
projection) at the 30/60/144/240 FPS fraction schedules, each sample twice: native driven scope
(ParCool root stages + transformed `PlayerModel` with every production mixin) versus the server
ports with the vanilla port. `python3 dev/check_parcool_oracles.py` compares root, six parts and
48 corners: **14,490 samples bit-identical**. The differential found and fixed two port defects:
`ParCoolBodyPose.Root` used `Matrix4f.rotateX/Y/Z` where bf7 multiplies a quaternion (ULPs), and
`JumpChargingAnimator` ignored bf7's signed partial. The roots compare ParCool's contribution on an
identity matrix; the full mixed renderer (vanilla root, PA, Iris, culling, capture identity) is U10.

## Deviations from ADR4 (recorded, not silent)

- §2.2: the held batch leaves in the snapshot phase of the same END tick (after the commit phase),
  not ParCool's default phase, so capsules seal in journal order; no tick or frame is added.
- §5.2 Kong: difference pair at frac(A), not p_R, on both evaluators (equal; U5 R adapter open).
- Action-only ParCool capsules add no observer start; the U8 application row is their proof.
- Owner A/B items unchanged: SkyDive start pitch, grid-based Dive leg, removal before selection at
  one boundary, observer holds.

## Open items

- U10: mixed renderer/pass order with Iris/EntityCulling, capture identity of driven frames, the
  full AnimSyncEval root versus native renderer; U11 live cohorts (all 26 animators) and A/B.
- U8: TaCZ/LR native broadcasts: done by U8b (sealed and carried at the native broadcast).
- U5: R-domain Kong adapter if the owner wants p_R; Hang/Cling world reads stay server-side.
- Coordinator/owner: fork `402eaa65` build + paired pin promotion (owner-gated); this unit
  changed no workspace pin.
