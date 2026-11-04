# Phase 3 client integration evidence

U12 consumes the U9 contracts unchanged. `ClientCombatScene.register()` is called once from
`BlockFieldClient`; all snapshot handling runs on Fabric's client-thread payload receiver. The
public `receipt()` and `debugSnapshot()` are the U13 observation surfaces. Debug snapshots include
R/H/stream, sequence, delay, jitter, occupancy, rate, lifecycle/recovery snaps, protocol defects,
pending expiry and per-entity resolved endpoint/generation/mode tuples. No outgoing timer exists.
`BodyPartHitboxRenderer` captures the already-posed native model; F3+B does not subscribe to the
old server bone debug channel. Its six boxes retain `BodyGeometry.skin()` dimensions.

## Pinned send order

No transformed classes are exported: plain JUnit is not a Fabric/Mixin launch. The order proof
uses the exact, required targets plus executable adapter tests:

- Mapped Minecraft 1.21.1 merged JAR SHA256
  `e8a55e60824b5e80cf526dcb2e61bc7c8deb4c41f0d7770e40ff9835ce926d53`:
  `ClientCommonPacketListenerImpl.send(Packet)` is the single play outbound funnel. The
  `CombatViewSendMixin` WrapMethod names this one method; the helper invokes the wrapped original
  with the ticket wrapper before invoking it with the original attack. Both calls are synchronous.
- Fabric networking API 4.3.0 `ClientPlayNetworking.java:239–245` calls listener.send with
  `createC2SPacket(payload)`. The executable fixture calls the actual Fabric API for each of the
  fourteen independent pinned custom IDs, observes the wrappers at listener.send, and executes
  the same helper as the mixin. Raw Interact uses `dispatch`, including nonattack negative cases.
- Raknetify `0.1.0+alpha.5.196`, SHA256
  `c7ed7782a940b1e880c1c1d1639a2cc27a176c228ec2a7c99435d5a13edbc741` (pack SHA512
  `4ef946cc940966d245a1ba02cf1fcb488617285cb19d66e6f99ad7721068eea124f21023141fa687259e42a05c42ff5a8d8a1a59bc6f1cfea0878ac42d2270ad`):
  `RakNetMultiChannel.getPacketChannelOverride(Class,boolean):int` is intercepted at HEAD,
  cancellable, require=1. Exact raw classes return 2 before the upstream class table executes.
  Every custom ID plus combat_view is installed in the supported `identifier2channel` map.
  `CombatChannelsTest` loads this checksum-matched upstream JAR, executes its real payload
  override handler against encoded packet bytes, and executes the mixin callback alongside its
  original raw selector. It asserts vanilla Interact/UseItem/UseItemOn=2, PlayerAction=1 before
  the callback and all four=2 after it. Noncombat payloads retain upstream handling.

These targets fail on a renamed/missing upstream method, rather than silently losing ordering.
The synchronous ticket-first calls and one effective order channel establish client emission
order. U13 must still run the real proxy/server channel-stall gates; unit checks cannot establish
network delivery or arbitrary third-party transformer behavior.

## Transform writers

Pinned vanilla `ClientPacketListener.handleMoveEntity` offsets 38–66 decode/setBase, 148/207
lerpTo, 215 setOnGround; `handleTeleportEntity` offsets 49 codec sync, 100 lerpTo, 108 onGround.
Only the lerp INVOKEs are wrapped (require=3 across those methods); real-handler tests execute
these native methods with the same compiled guard and check codec parity through driven/fallback
transitions. Head and motion guards each require=1. SBW bf24
`ClientMotionSyncMessage.kt:20–22` is the additional remote lerpMotion dispatch (require=1);
`ClientSetMotionMessage` is untouched.

The main-frame write is injected at the sole `GameRenderer.render -> renderLevel` call
(require=1), after Minecraft's native ticks and before renderLevel's `pick` (offset 42). Iris
shadow re-renders enter LevelRenderer, not that main call. This avoids advancing R during shadow
passes. `setPos` updates AABBs, then `setOldPosAndRot` freezes both old-position triples and old
angles. Player body/head old fields and type-specific SBW angular old fields are frozen too.

Boat and AbstractMinecart own distinct lerpSteps, and minecarts also own targetDeltaMovement.
SBW bf24 `VehicleEntity.kt:368–374,414–423,2706,4322–4365` owns interpolationSteps and seven
angle inputs; the client neutralizes handleClientSync and writes all seven sampled angles.
Subclass interpolation overrides are refused. The only pinned angle-property overrides,
Ju87/Ztz99a, are audited plain field setters and remain enabled; unknown angle writers are refused.
Create vehicles are not driven. BVR phantoms never enter ClientLevel's tracked-entity registry and cannot be driven.

Native linkage is compared after a complete SetPassengers handler; repeated identical lists are
idempotent. A seat-change full arriving before linkage has one matching native-linkage credit;
linkage arriving first waits for that full. Native Remove clears only that ID. New Add validates
pending kind before adoption. The server remains responsible for current-seat guards and labelled
fallbacks during linkage transients.

## Controller sampling clarification

The literal per-render occupancy law reacts to the packet-arrival sawtooth: an independent
uniform ±15ms arrival simulation at 200fps yields about 86% exact-unity rate, below r8 §7's
mandatory 95% fixture. The implemented controller applies the specified occupancy/deadband/rate
formula once per received tick, sample-held between updates, phased halfway through that tick's
smoothed arrival interval. R itself advances on every main frame, using monotonic frame duration only. No client
tick or render-partial phase enters R. This retains the specified target, deadband and dilation
bounds while avoiding packet-phase chatter. Delay adapts no faster than once/500ms, one tick at a
time, inhibited above 5% dilation. This sampling cadence is the unit's explicit clarification of
§2.4; it is not a second time coordinate or a server clock synchronization protocol.

## Remaining acceptance boundary

No game/client/server/Xvfb is started by this unit. U13 owns real mounted seat/config parity,
transformed full-pack writer coverage, TCP/RakNet delivery stalls, jitter/teleport profiles and
rendered geometry comparisons. The independent nonlinear seat fixture demonstrates that rotating
an offset at the interpolated angle differs from the committed-feet chord; the final passenger
write is what restores chord equality. These mathematical/unit witnesses do not claim game gates.

## Lifecycle clarification required before integration

r8 requires a full at first pairing/re-pairing but does not explicitly require its RESET flag.
Without an observable start, a full for a new pairing may overtake the old native Remove/Add and
is indistinguishable from an ordinary full refresh of the old pairing (same ID, kind and
life/seat/teleport generations). Remove carries neither a tick nor subscription identity. Clearing
all state on that delayed Remove would destroy the new pairing's only full; no periodic full is
promised. Conversely, adopting an unmarked old refresh on the following Add inherits a stale trail.

The client now follows snapshot decode order independently of native presentation lifetime.
A complete-batch membership break or RESET flag identifies a new run. An older native Remove
clears the old live run but preserves a newer unclaimed run pending its Add; absent-ID removes
from vanilla ClientLevel.addEntity do not erase full-before-Add data. Old in-flight deltas still
decode and cannot drive a new native Add without an adoptable start. Kind mismatch discards
presentation, retains the ordered codec baseline and waits for a matching full. All three native/
snapshot orders are tested, including newer-run full before old Remove/Add and Remove/Add before
old deltas. Pending rings remain bounded to forty ticks.

This relies on U10 setting FULL|RESET for every new pairing, as its current SnapshotStream does
at baseline == null. U14 must make that explicit in the binding contract and retain a server-side
assertion. It also clarifies the literal clear-all-on-Remove instruction: a demonstrably newer
run is quarantined, never driven while absent, rather than erased by an older native event. No
new field or reserved flag is used. A first mounted full with a nonzero seat generation pays for
one following native linkage update; native-first seat changes still wait for the matching full.
The wire carries no vehicle/seat identity, so live seat/config order remains a U13 acceptance gate.

## Additional integration fixes

TaCZ bf8 LocalPlayerShoot.lambda$doShoot$2 emits on a scheduler thread. Render receipts and their
send deadline are published through volatile fields; sequence uses AtomicLong, and each listener's
send helper serializes the complete ticket+attack pair. Remote walk animation samples chord
speed per server tick instead of native x-xo, which becomes zero between main-frame writes.
F3+B uses the actual render origin and renderer offset for undriven/range bodies too. None of
these paths streams or overwrites bones.

Deviation from r8 §2.5(3): the local player's own ride stack (its root vehicle and every entity
under it, co-passengers included) stays native. `positionRider` places the local passenger and its
camera at tick time; a frame-sampled vehicle would move up to one tick ahead of that camera and
judder with frame/tick phase (an SBW gunner at 20 m/s: decimetres). Locally controlled vehicles
were already excluded. Cost: views of a co-passenger in the shooter's own vehicle compare the
server's streamed chord with native client visuals; every other mounted target is driven.
