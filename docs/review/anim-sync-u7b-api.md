# U7b server orchestration API and handoff

Contract: ADR4.1 §§2–3, 4.5, 6–8, 10–11 and both errata sections. Branch/worktree
`fulla/as-u7b`, `WS/.dev/worktrees/as-u7b`. U1 schema manifest 3 is consumed unchanged.
This does not activate an exact cohort: the ADR4 wire, the probe evidence, the manifest and
ParCool stay unavailable until their owners install them, and every such path fails closed
with a labelled, counted reason.

## Canonical capture (`AnimSyncLedger`, `AnimSyncCapture`)

- One journal sequence space per target life (`ServerBodyPoses.State.epoch`): events and
  decisions, starting at 1. The allocator bound to both PA families hands out ids 256 apart;
  the families report no-node outcomes as -1 and U7b fills them in application order between
  the neighbouring node ids. Every decision list is therefore strictly increasing in the order
  the server applied it (LR upper, lower, rotation; TaCZ lower, upper, rotation), which
  `LrProjection.project`, `AnimationResolver.replay` and `ObserverReducer` require. A
  family-local id from before binding is adopted. A gap overflow latches unavailable.
- Each END tick `ServerBodyPoses` calls `begin` (binds allocators), `lr` after
  `LrBodyPose.apply`, `taczSelection`/`taczEvent` inside the weapon step, `create`, and
  `commit` after the legacy body. `commit` freezes vanilla/stance/equipment (U2), TaCZ and LR
  checkpoints and the shared graph (U3/U4), Create and SBW (U6) into one `AnimationState`,
  encodes and decodes it, installs and pins its whole reference closure, and retains the
  decoded value for 40 ticks. A same-tick re-evaluation replaces an unsent value only.
- Decisions: all three LR selections and every TaCZ selection each tick, POST_NATIVE_TICK,
  with the tick's authoritative `Vanilla`, the TaCZ checkpoint on TaCZ layers (null = no gun)
  and an `InputRef` naming the tick's placement by content (`contentId` = tick, digest =
  SHA-256 of its encoding). Both sides compute that ref the same way.
- R pairs name the previous committed endpoint: a dedicated server resets `xo/yo/zo` and
  `yRotO/xRotO` in its connection tick before `doTick`, so live END-tick fields equal the
  current ones. Continuous ticks take S[t-1]'s current values; a life/seat/teleport change
  snaps (old = current). `VanillaAnimSync.notePreTick` runs at START_SERVER_TICK.
- Unavailability: a producer latch (missing tick, refused clip, overflow) reseeds both PA
  producers with `Animation.reseed()` / `LrBodyPose.Stack.reseed()` and records the reseed
  tick (next record FULL|RESET). Other causes (vanilla capture, ParCool, a missing definition)
  only drop that tick's checkpoint; the canonical graph and the legacy pose are untouched.
- Capsules: accepted LR attacks (acceptance ids), TaCZ fire/reload/melee/draw transitions
  (ids at application) and Create enter/exit (server tick clock) are sealed each END tick in
  journal order with the life's predecessor chain and the previous checkpoint as seed ref.
- Create: the U6 journal's stamps count Create's own `ServerChainConveyorHandler.ticks`, so
  U7b keeps the presentation-enter tick on the server clock and builds `Create` itself with
  `CreateBodyPose.capture`'s anchor/u0 semantics.

- Cost (report only, warm test JVM, pinned PA/LR stack, two targets, 582 captures):
  capture 2.48 ms per player per END tick, decoded checkpoint 2.3 KB, batch 5.9 KB per
  two-player tick, validation 4.1 ms per two-victim receipt, evaluation 0.43 ms per victim.
  Capture runs unconditionally since R10-OUT (`AnimSync.capturing()` is constant true; LEGACY-CUT
  removes the gate with the legacy composition).

## Delivery (`AnimSyncStream`, `SnapshotStream.exact`)

- Observer identity: `connectionId` per JOIN, `observerStreamEpoch`, `sceneGeneration`
  (+1 on stream restart), `subscriptionGeneration` minted per pairing and target life, never
  reused. Canonical identity fields are zero; every record/capsule/seed is stamped.
- One batch per observer END tick: absolute lossless placements for players and kind-1/2
  vehicles (vehicle previous endpoints from the retained vehicle tick; U6's `captureVehicle`
  previous velocity is replaced by the retained one), decoded checkpoints, FULL|RESET on a new
  incarnation, FULL on a gap, FULL|SEAT, FULL|SNAP, FULL|RESET on a producer reseed. A player
  without S[t] has no record: never a stand-in.
- Definitions: generation-scoped refs (`targetEpoch` 0: clips, metadata, configs, resource
  ids, the unwrapped-payload marker) are preloaded content resolved through U9's validated
  manifest and never travel. Life-scoped refs travel once per stream and with every FULL.
- Seeds: every FULL player record carries an `ObserverSeed`: the stamped S[t], its graph,
  CANONICAL_TICK starts at the seed tick (END_COMMIT; Create membership start with S[t]'s
  anchor/u0), prefixes = the watermark, reducer `ObserverReducer.DIGEST`, and
  `ref.contentId` = a fresh journal id of that life above every start it names. The observer
  activates it with one SEED_CONTINUE OBSERVER_APPLY proof using that id as event sequence
  (`PaProjection.seedContinue`). A zero-start (neutral) seed needs no activation proof.
- Capsules are sent before the batch, only to observers with an incarnation from an earlier
  tick (a FULL seed already folds them), stamped per observer, and recorded as delivered.
  The server always sends the ADR4 wire (R10-OUT removed the `AnimSync.exactTransport` switch).
  It requires the ANIMATION_CODEC payload registration in `NetworkHandler` and the client
  intake.

## Shared observer reducer (`common.combat.ObserverReducer`, U8 must use it)

`reduce(seed, activation, to, checkpoints, applied, tacz, lr, policy)`: rebase the seed's PA
starts at the activation, then for every bucket from the seed tick to `to`: one A-domain tick
and that boundary's decisions in journal order (from the received S[n], whose placement must
match each decision's input ref), then the capsules applied in that bucket in `ordered(...)`
order (bucket, application A, frame, revision, id), each a patch at fraction zero. TaCZ uses
`PaProjection.apply`, LR `LrProjection.attack` with the fragment actor's clip, Create a
membership start. `prefixDigest(seedRef, ordered)` is the victim row's `eventPrefixDigest`.
The R lower-body alternative and ParCool are refused (unavailable).

## Receipt validation and two-time rewind

- `CombatViews.accept` routes an ADR4 view (non-null receipt) to the exact sent ledger;
  `action` validates it at the consuming attack (`AnimSyncValidation`): connection/stream/
  scene and generations, transport and manifest installed, nondecreasing A, the complete
  driven catalogue of the batch at H (no client choice), victim generations, a server-issued
  seed of that life, every OBSERVER_APPLY start naming a capsule actually delivered and an
  actually sent application H, its sW inside the frozen send/probe bound (`StartBounds`),
  immutable accepted anchors, the applied prefix digest, exact equality of the reduced start
  list, the presentation digest and the common resolver over the stamped sent history.
- No probe echo packet exists yet (U1/U8). `AnimSyncValidation.probe(...)` and the recorded
  START-tick boundary map are ready; until probes arrive every observer-applied start is
  `start_bound_unavailable`, hence no receipt is exact in production.
- `AnimSyncEval` evaluates a validated victim from decoded values: R placement/facing/walk/
  velocity at p_R, A vanilla inputs at p_A (model age keeps its fraction), the reduced graph
  sampled once at frac(A) with the R-resolved adjustment ports, SBW seat root from the root
  vehicle's retained R placement, TaCZ native fallback, medkit, Create phase since
  presentation enter, then the END-tick port's root and model transforms.
- One report serves every victim of the action, blasts and secondary rays included. Delayed
  projectiles and fuses reduce the same retained closure to the advanced bucket
  (`exact_advancing`, a labelled extrapolation, no invented observer start). Everything else
  is the labelled `exact_unavailable` fallback.

## Cross-unit changes made here (with tests)

- `VanillaBodyPose.reset(Bone[])`: the unused `parcool=false` leg z 0.1 branch is gone;
  1.21.1 `HumanoidModel.createMesh` places legs at z 0 (`resetRestoresTheNativeMeshWithZeroLegZ`).
- `VanillaBodyPose.Inputs.age` is a float: native `ageInTicks` keeps the A fraction (the arm
  bob moved up to ~2.6 mm per tick); integer END-tick callers keep their values.
- `VanillaAnimSync` attachment refs are content-addressed (attachment points through the native
  getter) and defined; `EntityAttachments` has no `toString`, so the identity string was
  unstable and undefined, and the store could not pin any checkpoint.
- `SnapshotRestartC2SPacket.Reason` has explicit stable ids equal to the shipped bytes (U1
  review P2-1, assigned to U7/U8).

## ParCool hook (U5, later U7c-ParCool slice)

Replaced by U7c: real canonical capture, composed reducer, wrapped batch and port evaluation
([handoff](anim-sync-u7c-parcool.md)). The text below records the pre-U7c state.

`AnimSyncCapture.parcool` is the single plug point: `capture(player, state, inert, base)`
returns the ParCool checkpoint and the graph with ParCool nodes, or a cause. Until U5 replaces
it, an inert player (no animator, no passive fall, no visual action) gets `INERT_PARCOOL`, and
any active ParCool producer is `parcool_unavailable:u5_pending`. `ObserverReducer` and
`AnimSyncEval` refuse ParCool animators. Batch capsule wrapping of
`ActionSynchronizationBroadcaster.send` belongs to that slice.

## Evidence

Plain JUnit (server): `AnimSyncLedgerTest`, `AnimSyncCaptureTest` (real pinned PA/LR stack:
three LR decisions every tick, attack capsules before the tick's decisions, same-tick
replacement and sent immutability, gap latch and reseed, ParCool unavailable, Create
exit-then-enter), `AnimSyncStreamTest`, `AnimSyncValidationTest` (honest two-victim receipt
replays exactly; victim set, identity, seed, unsent event, omitted start/ancestor, digest,
prefix, changed anchor, decreasing A, probe/transport/manifest/connection/clock vectors),
`AnimSyncEvalTest` (bit-stable pose at R, A moves the observer attack, every victim and an
instant/fuse blast through one report, labelled fallback).

Transformed (production server Knot, intermediary, all pack mixins):
`AnimSyncJournalOracleFixture` / `lr.u7b-journal-observer-replay`: the canonical LR stack
sequenced by the ledger with decoded checkpoints; the shared reducer from a server seed with
the attack applied in the bucket before its tick reproduces every canonical sample bit for
bit, and a later observer equals a native LrPose replay of its own schedule.

## Open items for other units

- **U8:** switch payload registration, sender and intake to the ADR4 codecs together with
  `AnimSync.exactTransport`; use `ObserverReducer` for presentation; activate seeds with the
  seed id; report `prefixDigest`; dispatch native payloads exactly once and treat the
  `native.unwrapped` marker as "native packet delivered separately"; LR compat view lifecycle
  under real capsule delivery (U4e P2-9, client half); A clock and frame revisions.
- **U1/U8:** a capsule applied without creating a node (missing clip, stop on an idle layer,
  Create exit) has no start and so no reported bucket; the server refuses such receipts as
  `unreported_application` until the receipt names every applied capsule's application.
- **U1/U8:** the probe echo packet feeding `AnimSyncValidation.probe` (server send/receive,
  client monotonic receive/send) and the client mapping report; registering
  `AnimationAppliedC2SPacket` (first-report times only tighten bounds).
- **U9:** install `AnimSync.manifest(...)` at activation, extend the compatibility digest,
  lease-based generation retirement (`AnimSyncLedger.retire`), preloaded generation content.
- **U6:** `CreateBodyPose.mesh` still sets leg z 0.1 (native baked 0.0; ~5.9 mm at the
  hanging feet); `CreateBodyPose.capture` mixes Create's tick counter with the server tick;
  `SbwBodyPose.captureVehicle` passes the previous origin as the previous velocity.
- **U2:** `VanillaAnimSync.project` interpolates pitch with `rotLerp` (native `Mth.lerp`
  differs only at a ±180° delta); fractional death/elytra/autospin ages now come from the
  root inputs U7b builds.
- **U3:** TaCZ accepted events are applied after the tick's selection (U4e moved LR attacks
  before the advance); both sides import S[t] so equality holds, but the canonical order
  differs from ADR4 §2.3 INTER_TICK_ACCEPT for TaCZ.
- Done by U8b ([handoff](anim-sync-u8b.md)). Capsules were sealed at END tick (broadcast stage END_COMMIT) instead of inside the native
  broadcast; wrapping TaCZ `ServerMessageGun*` and LR `SMeleeAnimationSync` needs U8's client
  dispatch and is gated off with the transport.

## U8 integration (server side)

U8 switched the transport on (`AnimSync.exactTransport` default true, dual snapshot/ticket wire)
and, inside U7 files: applications replace the per-start capsule loop in validation (no-node
applications are now reportable), the applied-prefix high-water is keyed by target life, probes
and echoes feed `StartBounds`, AnimationApplied reports record first-report times and the
observer's seed/activation/applications/A, and `AnimSyncStream.fold` issues successor seeds
(ADR4 §3.6). See [U8 API](anim-sync-u8-api.md).

## Cross-family equality fixes (UFX, `fulla/as-ufx`)

Each open U2/U3/U6 item above was checked against the pinned bytecode (`javap -p -c`, Temurin
21.0.12+8) before changing code; all five were real. The U6/U2/U3 open-item bullets above are
closed by this table.

| Finding | Native evidence | Fix | Evidence that fails on the old code |
| --- | --- | --- | --- |
| U6 `CreateBodyPose.mesh` leg z 0.1 (~5.9 mm at the hanging feet) | 1.21.1 `PlayerModel.createMesh` bakes legs at z 0.0 and `setHangingPose` never writes z; the transformed `create/hanging-pose-deterministic-equality` fixture asserts baked `initial.z == 0` | `mesh` resets z to 0 | `CreateBodyPoseTest.sampleBitsMatchesNativeGoldensBitwise` now holds the native bits at every index (26/32 were `0x3dcccccd`); `slimMeshPivotsShiftTheHang` pins raw z 0 |
| U6 `CreateBodyPose.capture` mixes Create's counter with the server tick | bf4 `ServerChainConveyorHandler.ticks` is a JVM-static `ticks++` in `tick()` (Create `CommonEvents.onServerTick`, END_SERVER_TICK), used only for the `%10` sync; packets drain before the tick and stamped the previous one, so a packet enter captured age 1 at its first END and a tick enter 0 | `CreateBodyPose.acceptTick(server, packet)`: the hook stamps packets with the tick being entered and `tick()` with the current one; `serverTicks()` is gone | transformed `create/membership-journal-hook` drives the real handler with Create `ticks` 1000 and server tick 9: enter stamp 10, first capture `u0` 0 (old: stamp 1000, its age clamped from −990); `CreateBodyPoseTest.packetAndTickEntersShareTheServerClock` |
| U6 `SbwBodyPose.captureVehicle` previous velocity = previous origin | bf24 `VehicleEntity` keeps a real `deltaMovementO` pair; an origin is never a velocity | `captureVehicle(vehicle, previousOrigin, previousDelta)`; `AnimSyncStream.vehicle` passes the retained previous velocity it already used (stream bytes unchanged) | transformed `sbw/capture-vehicle-values` asserts `deltaMovementOnPreviousTick` equals the supplied velocity |
| U2 `VanillaAnimSync.project` pitch via `rotLerp` | `LivingEntityRenderer.render` offset 194–201: `getfield xRotO`, `getXRot`, `Mth.lerp(FFF)F` (the `rotLerp` calls there are body/head yaw) | `SnapshotWireMath.lerp`, the same float order as `Mth.lerp` | `VanillaAnimSyncTest.projectLerpsPitchLikeTheNativeRenderer`: −90→90 at p 0.25/0.5/0.75 bit-equals `Mth.lerp` (old: −135/−180/−225) |
| U3 TaCZ accepted events applied after the tick's selection | ADR4 §2.3 INTER_TICK_ACCEPT precedes PRE/NATIVE/POST; the native remote applies drained TaCZ packets before its client tick (PA advance) and selects in the following render | `ServerBodyPoses.weaponEvents` runs after `AnimSyncCapture.begin` and before `LrBodyPose.apply` ticks the shared stack; `HeldWeaponPose.Animation.begin` opens the tick, `advance` ticks after the events; a draw no longer re-selects by itself. `enabled` (play-then-stop, U3 P2-2) is the previous selection, which is what the native render before the next tick runs | `HeldWeaponPoseTest.weaponTickConsumesMeleeVariantWithoutRebuildingTheStack`: the accepted melee's op leads tick 6's journal and its actor is one tick old (old: selection first, age 0); `HeldWeaponPoseGraphTest` script/driver/observer replay use events → tick → selection and stay bit-identical |

Limits: LR attacks still drain after the TaCZ events although their acceptance ids sort first
(disjoint layers, never the rotation alias, so the graph is equal; `ponytail:` in
`weaponEvents`). A fire on the very first tick of a fresh stack now meets `enabled` false and is
played-then-stopped, as for any stopped incumbent. No stand or game check was run.

