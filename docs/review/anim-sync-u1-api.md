# Animation sync U1 API (ADR4.1)

The common schema is in `common.combat`. This is the contract consumed by U2–U9;
family code must use these records rather than introduce another wire field list.
The checked-in `anim-sync-u1-schema.json` records every field and stable tag.
`AnimationSchema.layoutDigest()` contributes to compatibility identity; family
pins, registration, numeric options and resource closure still belong in U9's
complete compatibility/manifest digests.

- `AnimationState` is a complete decoded checkpoint. Its `Identity` includes
  connection, stream, scene, subscription, life, seat, teleport, resource, policy
  and both digests. `sameGeneration` compares the whole identity. `Placement`
  contains raw previous/current doubles, float angles, walk and both velocities;
  it also carries typed vehicle read slots. For vehicle records (kind 1/2) the
  base yaw/pitch pairs reuse the angle slots (`yRotO/yRot` = yawOld/yaw,
  `xRotO/xRot` = pitchOld/pitch); roll/turret/gun pairs stay in `VehicleAngles`.
  No live entity/native object belongs in these values.
- `AnimationGraphState` preserves ordered roots, node IDs and aliases. Nullable
  `NodeRef` means native null; IDs are positive. `AnimationGraphValidator`
  checks composition cycles, dangling refs, reachability, host backlinks,
  animator subtypes and policy-fixed domains. Host backlinks are not child edges.
  `validateComplete` additionally requires all 17 root slots, including empty
  roots. `neutral()` provides that inventory. A frozen copy may reference its
  ancestor phase key without reusing the physical node ID. PA 42 and 96 remain separate roots. Native materialization/projectors belong
  to U3/U4/U5, and must restore all fields, including source graphs.
- `AnimationStartReport.Start` separates `StartKind`, `AnchorClass` and
  `PhaseDomain`. `ObserverApply` has sW and application frame/revision/R/A/H;
  canonical/projected proofs have no observer timestamps. Whole `phaseAnchor`
  and integer u0 give `age = u0 + clock - phaseAnchor`. INPUT_UPDATE does not
  replace an actor's earlier anchor. `Victim` includes a zero-start neutral row.
  `validate` checks the wire structure, **not** server authorization; U7 must
  check sent ledgers, complete eligible victim catalogue, immutable accepted
  starts, graph digests and decision/seed closure.
- `AnimationEvent` wraps the native logical delivery and its side-effect bytes.
  Per-target-life event sequences start at 1 with predecessor 0 (genesis);
  sequence 0 is unencodable. Keep acceptance ID/stage separate from broadcast
  stage/time. `Patch` contains authoritative typed outcomes and topology;
  `Decision` records packetless outcomes. `ObserverSeed` holds bounded
  continuation state, retained starts, input/event/decision prefixes, predecessor
  seed and reducer digest. `SnapshotRecord.flags` uses `FLAG_FULL/SNAP/SEAT/
  RESET` (same bit mapping as the legacy record flags).
- `AnimationResources.Store.installAll` validates a staged complete dependency
  closure before publication. References are typed, generation/life-scoped and
  digest-bearing. `pin` leases include dependencies. Keep leases for pending
  records, active seeds, rings, send logs and valid tickets; retirement only
  removes unpinned values. An explicit older-generation dependency remains
  valid and pinned across activation; the same ID never resolves via a newer cache. `Bytes` copies at both boundaries. Preloaded asset values can exceed a wire
  payload; encoding still enforces the packet limit. Native ItemStack
  copying and parsing resource blobs remain adapter responsibilities.
- `AnimationPolicy` forces geometry settings and the exact 26-entry registry.
  Only roots 41/93 and their whole subtrees may use the R alternative.
- `AnimationTiming` implements the pure A law, snapshot endpoint convention,
  fixed send/probe deadline, causal mapping and recorded tick-boundary mapping.
  `advance` is called **once** at main-frame entry; its result is frozen for all
  passes/revisions. Timing readiness/hold/snap/timeout results are explicit.
  `freeze` uses only the latest 32 fresh completed probes; `bound` may tighten,
  never widen. `Mapping` uses elapsed client monotonic time and never directly
  compares the two machines' nanoTime values.
- `AnimationResolver.resolve` selects complete endpoints and checks boundaries
  across R–A, seed/victim identity, contributing starts, graph and references.
  Family projection supplies an immutable projected graph. Missing state does
  not stretch animation over a gap. `replay` restores checkpoints by replacement
  and executes only missing subsequent complete journal steps, in native stages.
  Result status is never silently converted into an equality pass/fallback.

## Wire migration and integration

`CombatViewC2SPacket.ANIMATION_CODEC` implements ADR4 §3.4 exactly (version 1).
`PlayerSnapshotS2CPacket.ANIMATION_CODEC` carries `AnimationRecipe.Batch`:
absolute lossless placements on every record, complete player checkpoints,
immutable definitions and observer seeds. `AnimationEventS2CPacket.CODEC` wraps
one native delivery/batch. `AnimationAppliedC2SPacket.CODEC` carries only new
OBSERVER_APPLY starts for its revision and must flush before an attack.

The existing `CODEC` constants and five/eight-argument constructors are **r10
migration APIs only**. They reject ADR4 objects; the ADR4 codecs reject missing
complete state. U7/U8 must switch payload registration/sender/buffer/intake to
`ANIMATION_CODEC` together. No exact cohort may be enabled through the r10 codec.
U7/U8 own handler registration and logical native side-effect dispatch. No event
is delayed until A/H reaches its event tick, and snapshot watermark import must
acknowledge, not restart, an already applied event.

All counts share `AnimationSchema`: 4096 victims/records, 4096 graph/start nodes,
depth 64, 256 KiB payload. Oversize is `report_capacity`; never truncate victims
or graph ancestry. Stable enum IDs are explicit and unknown tags fail closed.
Raw float/double codecs preserve all bits; `validateFinite` rejects invalid
physical inputs before native evaluation. `SnapshotWireMath` supplies matching
native-order origin/velocity/walk/angle interpolation; old mm/u16 helpers remain
only for the isolated r10 migration path.

## U1 validation restriction

The unit instruction forbids starting any server. Fabric's existing
`configureTests` wiring makes `:server:testClasses` depend on `:server:runGameTest`.
The first targeted Gradle invocation unexpectedly ran that Workshop test server;
it completed and shut down. Further validation explicitly excludes that task.
The unmodified full `just java-check` gate and integration push therefore remain
blocked until the coordinator resolves the no-server restriction for this task.
This exclusion is a reported validation gap, not a passed or weakened gate.

[Fixture results and pure traces](anim-sync-u1-fixtures.json) record the checked source hashes and remaining gate.

## U1R freeze review

Independent review: `../.dev/coord-20261011/reviews/p3-anim-sync-u1-review.md`
(workspace-relative `WS/.dev/coord-20261011/reviews/`). 1 P0 + 5 P1 fixed in
U1-owned files only, no wire/schema change: `replay` selects the newest complete
checkpoint regardless of map order; `SnapshotRecord` flag constants;
genesis/vehicle-angle conventions documented above; record-level negativity
checks; wire rejection of application prefixes from the future. U1R runs the
full gates including the in-process GameTest server, which its task explicitly
allows as part of the required checks.

## U1F fidelity-audit P1 fixes (schema 2; wire/layout changes for family units)

Independent audit: `WS/.dev/coord-20261011/reviews/p3-anim-sync-u1-fidelity-audit.md`.
All four P1s are fixed here with pinned-bytecode evidence; nothing else changed.
Wire/layout changes versus schema 1: (1) `AnimationState.Lr.attackAction` is now
`@Nullable String` with an explicit null presence byte (idle null vs attack name,
never a `""` sentinel); (2) `Lr.config`/`display` are now `@Nullable` with presence
bytes (missing-config ticks carry null, never reused last-config bytes); (3) `Ease`
wire tags are the native `getId()` bytes (0, 1, 6..37) instead of 0..33 ordinals —
every fade/forced-fade ease byte after CONSTANT changes value; (4) `DiveInput.speed`
is removed (no native producer or reader). `AnimationSchema.layoutDigest()` changes
accordingly; `AnimationSchema.VERSION` stays 1 (no deployed readers; no family has
implemented on schema 1). Schema manifest `anim-sync-u1-schema.json` is now
141 layouts / 637 fields / 30 closed enums.

- P1-1 LR `attackAction` null: server `LrBodyPose.Stack.attackAction` is a nullable
field nulled in `clear`/`stop(UPPER)`/`playIdle` (attack-to-idle replacement); LR bf2
`PlayerAnimatorIntegration` reads `extraData["name"]` (String) and
`extraData["lr_attack_action"]` (Boolean) with `instanceof` guards, so absence/null is
a legal idle state; `ThirdPersonAnimationConfig.getAnimation` holds no string constants
and returns null on missing paths, so `""` is never a native outcome; the native
`"empty"` sentinel is a rotation-modifier clip key, distinct from `""`. Fixture:
`lrIdleNullAttackActionAndMissingConfigRoundTripExactly` (idle null vs named attack).
- P1-2 ParCool producers (all pinned against ParCool bf7 `9a0ec4…` with Temurin javap;
intermediary names below, mojmap/yarn in parentheses). `RideZipline` has native fields
`endOffsetFromStart:Vec3`, `speed/acceleration/slope:double`, `ridingZipline:
ZiplineRopeEntity`; `RideZiplineAnimator.animatePost/rotatePost` reads
`getEndOffsetFromStart/getSlope/getAcceleration` plus the gravity attribute direct
from the player. `ZiplineInput.gravity` producer: `LivingEntity.getAttributeValue(
Attributes.GRAVITY)` (intermediary `class_1309.method_45325`, yarn
`EntityAttributes.GENERIC_GRAVITY`), read live in `RideZipline.
onWorkingTickInLocalClient` (speed recurrence `speed -= gravity*slope/sqrt(slope^2+1)`
plus movement-speed-ratio term) — recorded redundantly with `Vanilla.gravity` (same
producer) because the animator reads it independently of the action. `ZiplineInput.
valid` is `ridingZipline != null`; `rope` is that entity; native `speed` is internal
recurrence state recomputed from these inputs, not a wire field. `Dive` has exactly
`justJumped/initialYVelocityOfLastJump/playerYSpeedOld/playerYSpeed/fallingTick`;
`DiveInput.fromSkyDive` is the `new DiveIntoWaterAnimator(SkyDive.isDoing())` argument
in both `Dive.onStopInLocalClient/onStopInOtherClient`; `notLandingTick` is
`AdditionalProperties.getNotLandingTick()` (install gated `>= 5`); `waterEntry` is
`Entity.isInWaterOrBubble() && getDeltaMovement().y < 0` (intermediary `class_1297.
method_5816`, `class_243.method_10214 → y`). `DiveInput.speed` is removed: no Dive
field, constructor argument, or animator read produces or consumes it (the host
constructor double is the synced y-speed; the child float is the transition blend
factor). ADR4 §4.4 row "Dive ... `speed double`" therefore has no native source;
U5 must derive Dive speed from the y-speed pair only, and a future pin showing a
reader needs a reviewed schema re-addition. Fixture:
`diveHasNoSpeedFieldAndZiplineGravityRoundTrips`.
- P1-3 PA `Ease` ids: pinned PA 2.0.4 `Ease.<clinit>` passes explicit id arguments
`(LINEAR,0,0) (CONSTANT,1,1) (INSINE,2,6) ... (STEP,33,37)` to `Ease.<init>(String,
int, int, Function)`; `getId():byte` returns the `id` field and `getEase(byte)` scans
it (default LINEAR), so ids 2..5 are unassigned. U1 `Ease` tags are now those native
ids. Test `pinnedEaseIdsMatchNativeGetIdByteValues` asserts all 34 native `getId()`
values against the schema and fails if the pin's ids change.
- P1-4 LR missing config: LR bf2 `IdleAnimationHandler.getConfig(ItemStack)` returns null
for non-`IMeleeWeapon` items and for empty `getMeleeDisplay(...).orElse(null)`; `onClientTick`
routes `config == null` to `stopAll(player)` (UPPER/LOWER/ROTATION stopAnimation), the
only driven missing-config outcome. The schema encodes it explicitly: null
config/display refs plus stopAll topology plus a NO_CHANGE decision (U4 records the
decision in its read layout). Fixture: same Lr test as P1-1 (null config/display
round trip).

## §4.2 vanilla/Blockfield descriptor verification (audit unverified item, closed)

Every row below was verified with Temurin `javap -p` against the loom-mapped
1.21.1 `minecraft-merged` jar (mojmap names) or in-repo Blockfield code; no mismatch
was found, so no schema change beyond the four P1s above.

| §4.2 row | Descriptor evidence | U1 slot |
|---|---|---|
| feet/origin + `xo,yo,zo` six f64 | `Entity.xo/yo/zo:double` public; `position():Vec3`, `getX/getY/getZ():D` | `Placement` two `Vec3` |
| 8 rotation f32 | `Entity.yRot/xRot:float` + `yRotO/xRotO:float`; `LivingEntity.yBodyRot/yBodyRotO/yHeadRot/yHeadRotO:float` | `Angles` 8×float |
| walk triple f32 | `WalkAnimationState` private `speedOld/speed/position:float` + `speed(F)` lerp / `position(F)` getters | `Walk` 3×float |
| two `deltaMovement` Vec3 f64 | `Entity.getDeltaMovement():Vec3` old/current pair | `Placement` two `Vec3` |
| `oAttackAnim,attackAnim` f32 | `LivingEntity.oAttackAnim/attackAnim:float` public | `Attack` 2×float |
| `swinging/swingTime/swingingArm` | `LivingEntity.swinging:boolean`, `swingTime:int`, `swingingArm:InteractionHand` (nullable until first swing) | `Attack` + `@Nullable Hand` |
| `swimAmountO/swimAmount/oBob/bob` f32 | `LivingEntity.swimAmount/swimAmountO:float`; `Player.oBob/bob:float` | `Vanilla` 4×float |
| 6 vital ints | `Entity.tickCount:int`, `getTicksFrozen():I`; `LivingEntity.deathTime/hurtTime/hurtDuration/fallFlyTicks:int` | `Vital` 6×int |
| `pose` | `Entity.getPose():Pose`; U1 18 tags match native 18 constants in order | `Pose` enum |
| 17 flags | `isAlive/isSpectator(Player abstract)/isBaby/isCrouching/isVisuallySwimming/isSwimming/isFallFlying/isAutoSpinAttack(Entity.onGround()/isInWater/isInBubbleColumn(private, via isInWaterOrBubble)/isFullyFrozen/isPassenger/isSprinting/abilities.flying/LivingEntity.onClimbable())` booleans; `upsideDown` is Blockfield `ServerBodyPoses.upsideDown` (Dinnerbone/Grumm + cape) | `Flags` 17×boolean |
| `fallDistance` f32 | `Entity.fallDistance:float` | `Vanilla` float |
| `mainArm/bedOrientation` | `LivingEntity.getMainArm():HumanoidArm`; `getBedOrientation():Direction` (nullable empty bed) | `Arm`, `@Nullable Direction` |
| 5 size f32 | `LivingEntity.getScale():F`; `Entity.getBbHeight/getBbWidth():F`; `getEyeHeight(Pose):F` (standing variant `getEyeHeight(Pose.STANDING)` per `ServerBodyPoses:157`) + `getEyeHeight():F` | `Size` 5×float |
| `gravity` f64 | `LivingEntity.getAttributeValue(Attributes.GRAVITY):D` (see P1-2) | `Vanilla` double |
| item-use block | `LivingEntity.isUsingItem():Z`, `getUsedItemHand():InteractionHand`, `useItemRemaining:int` field, `getTicksUsingItem():I`; `mainArmPose/offArmPose/useAnimation` are the native selection outcome over `HumanoidModel.ArmPose` (10 values, order-matched) / `UseAnim` (10 values, order-matched); durations are authoritative resolved values (U2) | `ItemUse` |
| equipment refs | retained `ItemStack`/component closure (U2/U9) | `Equipment` 4 refs |
| `medkitActive/medkitHand` | Blockfield `MedkitPoseMixin` composition | `Vanilla` + `@Nullable Hand` |
| stance block | Blockfield `StanceTransition`: `from/to:EntityDimensions`, `age:int`, `eased:boolean`, `fromFlat/toFlat/flat:float`; `EntityDimensions(width,height,eyeHeight:float,attachments,fixed)` record | `Stance` + `DimensionsValue` |

Independent review: `../.dev/coord-20261011/reviews/p3-anim-sync-u1-review.md`
(workspace-relative `WS/.dev/coord-20261011/reviews/`). 1 P0 + 5 P1 fixed in
U1-owned files only, no wire/schema change: `replay` selects the newest complete
checkpoint regardless of map order; `SnapshotRecord` flag constants;
genesis/vehicle-angle conventions documented above; record-level negativity
checks; wire rejection of application prefixes from the future. U1R runs the
full gates including the in-process GameTest server, which its task explicitly
allows as part of the required checks.

## U3F nullable Tacz (schema manifest 3)

Authorized by the ADR4.1 U3-review errata. `AnimationState.tacz` is `@Nullable`
with a presence byte: null is the native no-gun state (`setRotationAnglesHead`
calls `stopAllAnimation`), so empty-handed, LR melee, ParCool-only, SBW-seat and
holster-fade remotes have checkpoints. A TaCZ decision patch may also carry a
null `Tacz`. `AnimationSchema.VERSION` stays 1; the layout digest changes to
`0c668df57bf95e503ae99468702a0e0589f701363423c32dae5aa995b6949925`, pinned in
`anim-sync-u1-schema.json`. `AnimationSchemaTest` now checks every manifest
field's presence byte against `@Nullable` and the pinned digest, and round-trips
a null `Tacz`. Resource refs made by `PaGraph.Catalog` are generation-scoped
(`targetEpoch` 0); a target life belongs to `Identity`, never to resource content.


## U8 coordinated additions (schema manifest 4)

Authorized by the U7b handoff (no-node applications, probe packet, AnimationApplied
registration). `AnimationStartReport.Victim` gains `applications` (tag 142
`AnimationStartReport.Application(eventSequence, ObserverApply)`), every capsule the observer
applied after its seed prefix; `ObserverReducer.template` derives the replay start from the
delivered capsule. New probe payloads `AnimationProbeS2CPacket`/`AnimationProbeC2SPacket`.
Layout digest `b0ccce27e6fb1bfb6f3332429d30df51eb7a6a61b43526cdd54bc5a38be8dc04`; `VERSION` stays 1.
Details and tests: [U8 API](anim-sync-u8-api.md).

## FX-SEAT SBW read layout (schema manifest 4, layout digest unchanged)

Coordinator-authorized U1/U6 change (2026-10-18). bf24's seat direction
(`getTransformDirectionNoOrientation`) transforms `(0,0,0,1)` and `(0,0,1,1)` by the named
`Matrix4d` and subtracts them; for `Turret`/`Default` that matrix translates by
`getRotateOffsetHeight():double` and `getTurretPos():Vec3`, which change native float seat yaw.
`SbwLayouts.baseSlots()` therefore adds three `CONSTANT` slots after the four float constants,
captured by `SbwBodyPose.captureReads` at the same END-tick checkpoint as the other vehicle reads:
`getRotateOffsetHeight ()D` (`DoubleValue`), `hasTurret ()Z` (`BooleanValue`, the native
`getTurretPos() != null` test) and `getTurretPos ()Lnet/minecraft/class_243;` (`VectorValue`,
present exactly when `hasTurret` is true). Only existing typed value kinds are used, so no record,
field or tag changes: `AnimationSchema.layoutDigest()` and `VERSION` are unchanged and the schema
manifest stays 4. `SbwLayouts.identity()` (`compat.family.sbw`) changes, so the compatibility
digest differs and an old-layout peer is refused by the existing manifest compatibility path.
`SbwBodyPose.constants` requires the four floats, offset and coherent turret presence; the
continuation below extends that required read closure.

Client: inside a driven passenger's render scope, `CombatDrivenSbwInputsMixin` redirects the root
vehicle's `getRotateOffsetHeight`/`getTurretPos` to the decoded reads of the record that vehicle
was written from (`CombatVehicleScope.reads`); `DrivenSbw` refuses install (`sbw_vehicle_reads`)
when they are absent or incoherent. Neither getter is overridden in bf24 (pinned by
`DrivenSbwTargetTest`).

Continuation 3 (same coordinator authorization, 2026-10-18) retains the remaining native matrix
inputs: `getBarrelPosition`, `getPassengerWeaponStationPosition`, and
`getPassengerWeaponStationBarrelPosition`, each descriptor `()Lnet/minecraft/class_243;`,
`VectorValue`, `CONSTANT`. All three are `@Nullable` in bf24. Each has a derived null-test slot
named `<nativeGetter>$present`, descriptor `()Z`, `BooleanValue`, `CONSTANT`; these are explicitly
capture expressions, not invented native methods. `SbwLayouts.presenceGetter` resolves their
native getter for the compatibility/read-closure audit. The boolean is always present; the vector
is present exactly when true. Capture evaluates each getter once at the same END-tick checkpoint.
Both install and server constant extraction reject missing/inconsistent pairs; the client redirects
all three getters inside the driven root-passenger scope and leaves the native/local path unchanged.
The layout now has 13 slots (up to 13 values, depending on the four nullable positions).

The exact matrix paths now include Barrel, WeaponStation and WeaponStationBarrel. A null
WeaponStation position returns the turret matrix before gun yaw; native Barrel/WeaponStationBarrel
dereference their own positions, so the client refuses those selected paths when absent
(`sbw_vehicle_position`). The matrix barrel pitch uses the raw lerped gun pitch; the separate
native quaternion registry keeps its pitch clamp. Manifest remains 4, `VERSION` and layout digest
unchanged; `compat.family.sbw` changes. Native witnesses, null branches and full-render mixed
results are recorded in `anim-sync-fx-gate.md`.

## WIRE-CODEC envelope v2 (schema manifest 4, layout digest unchanged)

ADR4.2 v3 §5–§6 ([amendment](anim-sync-wire-amendment.md), [unit record](anim-sync-wire-codec.md)).
No record, field, tag or bound changes: `layoutDigest()`, `VERSION` and manifest 4 stay. U1 tag 0
stays unregistered; only the S2C wire gives it a meaning.

- `AnimationRefTable`: the sorted generation table (`derive(generation, manifestKeys, tacz, lr)`,
  `handle`, `entry`, `digest`), the common carrier list (`CARRIERS`, `MARKERS`, `carrier`) and
  `compatibility()` (`compat.wire`).
- `AnimationSchema.writeWire(buf, value, table[, shared])` writes a table ref as `uvarint 0,
  uvarint handle`, without the per-call 256 KiB count; `readWire(buf, type, table)` resolves a
  handle only through the given table, after the bound and type checks and the canonical depth
  check. `write`/`encode`/`digest`/`read` stay canonical and reject tag 0. Shared encodings are kept
  per mode (canonical or table identity); `SharedEncodings` is the frozen per-tick map for encode
  tasks (`builder().share(v).prime(root, table).build()`). `expand` is the WIRE-GATE byte
  transcoder back to canonical bytes.
- `AnimationWire`: `Header`/`Fragment`/`Transaction`, `fragments`, `Assembler`, CRC32C, bounds.
- Payloads `blockfield:anim_wire_s2c` (`AnimationWireS2CPacket`: snapshot/event bodies, `decode`,
  `expand`) and `blockfield:anim_wire_c2s` (`AnimationWireC2SPacket`: applied/ticket bodies,
  `decode`), registered in `NetworkHandler`; their senders and receivers are WIRE-SERVER,
  WIRE-CLIENT and WIRE-REPORTS.
- Manifest: `tacz.gun:<id>`, `wire.ref-table`, `compat.wire` (see `AnimationManifest`).

## WIRE-SERVER sender (schema manifest 4, layout digest unchanged)

ADR4.2 v3 §§5.1, 7, 13 ([unit record](anim-sync-wire-server.md)). Every exact-transport snapshot and
event delivery (END tick, mid-tick TaCZ/LR native, ParCool batch) is one `anim_wire_s2c`
transaction; `AnimationProbeS2CPacket` and unwrapped native payloads are unchanged. Header fields
as sent: `tableGeneration` = the observer's READY generation while the server holds its table,
else 0; `connectionId`/`observerStreamEpoch`/`sceneGeneration` = the stamped identities;
`logicalSequence` counts each family per connection from 1; SNAPSHOT `logicalTick`/
`previousTickPlusOne` mirror the body; EVENT `logicalTick` is the delivering server tick and
`previousTickPlusOne` is 0.

## STANCE-EASE crouch triple (schema manifest 5)

Coordinator-authorized U1 change (2026-10-20, phase-3 equality: the server six-box pose must equal
the remote body bit-identically at every tick of a stance change). `AnimationState.Stance` gains
`fromCrouch`, `toCrouch`, `crouch` (float, same semantics and declaration order as the flat
triple): the eased crouch coefficient over `StanceTransition.TICKS` (8), captured from the live
snapshot exactly like the box and the flat triple. Layout digest
`87caf7ae970cdb9b0dddb9156125a3212766c4ce044db9571be0bc3b24091549`; `VERSION` stays 1
(no deployed readers outside this repo's exact cohort). `AnimationSchema` needs no code change
(layouts are the record components in declaration order); `AnimationFixtures` samples the new
fields generically.

Consumers: `VanillaAnimSync.stance` writes the triple (idle snapshots stay snapped); the
exact-eval projection (`VanillaAnimSync.project`) blends the bones and the render offset by
`stance.crouch()` — medkit still forces full crouch, like the live freeze — so evaluated hit
boxes ease on the same clock as the live box; `DrivenPresentation` restores the replicated
triple into the driven mirror through the shared `StanceTransition.snapshotOf` mapping (also
used by the equality fixtures), so the remote body blends through the same `Crouch.at` path as
the server, with no copy and no inference from boxes. Interrupted transitions reproduce exactly
because restore carries the endpoints and both triples.
