# Server body frames (phase 2: six identical boxes)

Hit geometry is always exactly six boxes, one per model part (head, torso, left/right arm,
left/right leg), with the vanilla wide inner-skin cuboid sizes (`PlayerModel.createMesh(NONE,
false)`), identical for every player. No armor, no uniform, no skin outer layers
(hat/jacket/sleeves/pants), no slim/wide difference: armor does not change the hitbox (owner
decision 2026-10-13). There is no padding, no torso fallback and no frameless body.
`config/blockfield/body-parts.json` `enabled` controls only the six per-part damage multipliers.

## Shape

One state resolves to the singleton `BodyShape` (`BodyGeometry.skin()`); skin model,
customisation, uniform, armor and curios never change hit geometry, so the phase-1 gear
diagnostics (`unsupported:visible_armor_layer`, `unsupported:visible_curio:*`,
`unsupported:custom_head`) and the phase-1 uniform render-toggle interim block
(`UniformRenderToggleMixin`, `UniformGeometryLock`, `UniformVisibility`) are removed. Removed
earlier: uniform padding, `ARMOR`/`ARMOR_TOP`, `Body.legacy`, `BOOTSTRAP`, the frameless clip
branch and the legacy inflated-AABB path.

The skin model still comes from the forwarded Drasl `textures` property (`TrustedPlayerSkin`;
without it the vanilla default-by-UUID table applies), but only feeds the `skinSource` dump field
and diagnostic and Create mesh pivots; it never changes the local box sizes.

## Phase-2 evaluators

`ServerBodyPoses.evaluateInner` advances the canonical S[t] input state and captures it: the
skin, shape and dimension resolution the evaluator needs, the accepted TaCZ journal drain and
TaCZ loop/stop/fallback selection on a slim selector input (vanilla walk/ride state plus
`tacz, climbing, waterSwimming, pose, creativeFlying, sprint, aim`), the LR advance and
selection, and the graph freeze that `AnimSyncCapture` reuses, then the S[t] commit. No
per-tick body is composed: no ParCool animator tick, no vanilla/SBW/Create bone stages, no
`frame()`. The reference pose itself is evaluated on demand from the retained S[t]
(`AnimSyncEval.pose` over `ServerBodyPoses.Inputs/RootInputs`, the ParCool ports, the PA
graph and the TaCZ fallback): vanilla (walkAnimation, arm poses, attack, swim/crawl,
elytra, bed and death roots), then SBW bf24 humanoid/vehicle tails, ParCool bf7 pre/rotation/post
stages, and the shared TaCZ bf8/LR bf2 PlayerAnimator stack. Create bf4 chain-conveyor hanging overwrites the bones last while retaining the weapon root;
Zapper/PotatoCannon use the vanilla CROSSBOW_HOLD arm path. All stages position the same six
boxes. Range targets use their static frame rather than these animation stages.

ParCool includes the 16 existing ports, Flipping, ChargeJump, JumpCharging, CatLeap, WallJump,
BackwardWallJump, JumpFromBar, Tap, RideZipline, HideInBlock and PassiveFall. The retained
ExaggeratedSideDodge formula has no bf7 construction site: Dodge always builds DodgeAnimator
(`ParCoolNewInputsTest.officialPackSelectsDodgeAnimator`); its acceptance row is N/A.
Charge/zipline Normal payloads retain continuous phase; weapon intake retains event order and
server-selected TaCZ melee variants. LR melee intake is captured after acceptance at its
CPrepareMeleeAttack broadcast; bf2 has no third-person throw/consume configs (resource-scan test).
BreakfallReady is readiness only (bf7 selects Roll/Tap, with local sound/particles); it never
queues a pose or emits an unsupported animation diagnostic. LR absent or invalid custom ids
resolve to its native `lrtactical:empty` id; a missing third-person config stops the LR layers
without a diagnostic, including a plain flash shield.
Stock-melee damage vetoes still apply independently of pose evaluation.

Dive Finish captures ParCool's synced vertical speed, the airborne counter and the stop-time
SkyDive variant; water/bubble-column contact is resolved against accepted feet at pose commit,
after movement packets, rather than against stale packet-time water flags or entity velocity.
The water-entry animator lasts nine ticks. bf7 sends no stop-time water flag, client airborne
counter or fresh stop velocity: the server uses its shared airborne counter, last synced speed
and current water world state. Delayed movement/world updates and a speed sign change at the
stop boundary can still shift selection relative to a native client.
Zipline starts with a zero swing angle, advances its oscillator once per tick, and recovers
the shared endpoint and retained acceleration/slope after a teleport reseed. An absent Normal
is valid when those scalars have not changed. Local rope physics updates the movement marker;
the remote pose follows accepted entity feet without adding another rope translation. A reseed
starts a new oscillator epoch; native clients retain their previous visual phase until removal.
`ParCoolAcceptanceRegressionTest` replays the dry4 Dive/zipline timelines and teleport and tests
start/mid/end/exit formulas; the next acceptance run must measure these timing residuals.

The effective TaCZ/LR animation scan rejects non-rigid tracks at build time. Runtime
`unsupported:*`/`missing:*` diagnostics remain for malformed input, lost APIs or missing start
state; a healthy acceptance matrix must never emit them. Formula fixtures test pinned values
at start/mid/end/exit; this is not in-game acceptance. SkyDive keys use motion-derived intent:
there is no remote key wire in bf7, so residual momentum can differ from client key release.
Create client/server phase origins are unsynced; acceptance must measure the residual.

Attack selection is no longer RTT-rounded: every weapon family resolves its victims at the
shooter's rendered view time from the server's own sent snapshot prefix (phase 3, below), with a
counted current-frame fallback. PROTOCOL 7 covers the combined phase-2 six-box geometry and the
phase-3 snapshot/view wire (mod 1.52.0 released 6); it requires a paired client/server release, not a deployment from this
integration branch.

The workspace native-geometry comparator excludes outer skin, uniform, armor and curio layers.
Literal geometry acceptance uses **wide-skin cohorts only**: a slim rendered arm is 3 px while
the hit arm is deliberately 4 px. Slim receipts are explicitly marked unsupported comparison
cohorts, never counted as a failed animation match or accepted geometry evidence.

## Firing-range targets

Husks tagged `RangeTargets.TAG` test the same six boxes from a static standing frame at the
Husk's body/head yaw and position (`RangeTargetBodies`, rebuilt from the current state on every
call: no history, no rewind). `RangeHuskRenderer` draws them with a default wide `PlayerModel`
through `HumanoidMobRenderer`, which keeps the `LivingEntityRenderer.scale` no-op (unlike
`PlayerRenderer.scale` 0.9375), so the target frame mirrors the player root without the 0.9375
scale. Every hit path that tests players also tests these targets per part: TaCZ bullets, SBW
hitscan/projectiles/AP rays, the taser, LR throwables, the dagger and area exposure. Part damage
multipliers apply to them as to players, and the trial report names the part. Untagged mobs keep
the native box.

## Frames and lifecycle

Every non-spectator `ServerPlayer` has a committed frame from the moment it can be targeted.
`ServerBodyPoses.restart` starts a new epoch (a new S[t] ledger target; older ticks are absent) and
commits immediately on JOIN,
AFTER_RESPAWN, AFTER_PLAYER_CHANGE_WORLD, an end-tick clock gap and every real teleport (`/tp`,
`teleportTo`, dimension change). Movement corrections and teleport resends keep epoch and history.
`forget` runs only on DISCONNECT, SERVER_STOPPED and the old respawn instance. A missing frame at
lookup reseeds synchronously and increments `missingFrames`.

## Damage paths

Every family tests the six boxes: TaCZ bullets, SBW hitscan/projectiles, the taser, LR
throwables and the dagger. Area hits use the boxes too (`AreaExposure`): explosion
exposure is sampled over the six boxes through the one `Explosion.getSeenPercent` hook
(vanilla, SBW `CustomExplosion` and LR grenades); the Beast area hit is a segment-to-box distance
of at most 3 m; vehicle ramming tests the vehicle OBB against the boxes. The boxes only
replace the exposure source: SBW bf24 keeps `max(seen, 0.01 * explosion_penetration_ratio)`
(default 15%), LR bf2 keeps `max(seen, 0.01)`, and vanilla keeps its +1 HP term inside the
radius. Fully blocked boxes therefore take exactly the native floor damage, with native
ignition/knockback rules. SBW/LR damage is `(impact² + impact) / 2 * damage`, where
`impact = (1 - distance / diameter) * clampedExposure`; vanilla uses
`(impact² + impact) / 2 * 7 * diameter + 1` without an exposure clamp.
TaCZ bf8 uses the nearest visible box sample with its native `BlockRayTrace` (including
pass-through blocks), rather than a fraction. Its native `minDistance` starts at `radius`;
if all rays are blocked, `2 * minDistance / radius = 2 > 1` skips damage. Otherwise damage
is `(1 - 2 * minDistance / radius) * power` within range. Open ground keeps full exposure
(or TaCZ's native distance falloff); non-six-box entities keep their native paths. No part
multiplier applies to area damage.

Melee vetoes (regression-tested in `MeleeVetoTest`): TaCZ stock melee is cancelled server-side
(`KnifeTaczEvents`); vanilla `Player.attack` and SBW `melee_absolute` from a player are rejected
(`KnifeCombat.rejectsOtherMelee`); non-dagger LR melee stays denied by the knife-only policy.

## Debug

`bf bodypose dump <uuid|entity-id>` (players or tagged range Husks, across loaded levels) returns schema `blockfield.bodypose.v3` (the canonical body `Eval(S[t], R = A = t)`; v2's legacy `animator`, `animatorAge`, `doingTick`, `notDoingTick`, `weapon`, `display`, `lower`/`upper`/`once`(+`Age`), `sampleUnixMs` and `sampleMonoNs` are absent): `skinSource`, `shape`, `bones`
(six world←bone 3×4 matrices, metres), `partBounds` and
`visible{HEAD,TORSO,LEFT_ARM,RIGHT_ARM,LEFT_LEG,RIGHT_LEG:[{id,local,world}]}`; `skinObbs` stays for
v1 consumers. `bf bodypose status` reports the commit/clip timing histograms, the
`missingFrames` and `fallbacks` counters, plus the phase-3 netcode counters below. With F3+B on,
the client draws each tracked player's and each tracked range target's six boxes (one colour per
part) from the locally captured posed model and driven entity feet instead of the vanilla AABB;
no server bone stream exists (the phase-2 U17 debug channel is removed).

Range dumps/captures use `RangeTargetBodies.current`, exactly like hit selection, and include
`entityId` and `samplePoint:current_entity`. Player samples remain `end_server_tick`. The client
`BodyPartHitboxRenderer.debugSnapshot(entityId)` used by testbot `bodydebug` returns the last
actually drawn boxes for a range Husk too; enable F3+B and keep the target in view.

## Phase-3 wire contract (snapshot interpolation + lag compensation)

Three packets, one pair, PROTOCOL 7 (combined phase-2+3 release; old clients fail login
compatibility). No bones, shapes or pose dictionary on the wire.

- S2C `blockfield:player_snapshot`: exactly one batch per observer per END tick after the
  body-pose commit (`snapshot_send` ordered after `body_pose_commit`), all tracked eligible
  non-spectator remote players plus already-paired ridden vehicles, strictly increasing entity
  ids, empty batch when none tracked. Header `observerStreamEpoch/previousTickPlusOne/serverTick uvarlong`
  (absolute ticks; predecessor is 0 initially, otherwise previous actual send tick + 1), `interpMin/interpMax/dilationPercent u8`, `holdToSnapMs u16`, `recordCount uvarint`
  (cap 4096 records; the exact wire fragments any batch up to its 64 MiB failure
  bound, never a partial prefix). Record `entityId uvarint, kind u8, flags u8`: kind 0 player (feet + yaw/pitch/body/head),
  kind 1 simple vehicle (origin + yaw/pitch), kind 2 SBW vehicle (origin + 7 angles). Fulls carry
  absolute int32 mm, absolute u16 turns and the `targetEpoch/seatGeneration/teleportGeneration`
  triple; deltas carry zigzag-varint mm against the last sent/decoded quantized record and fixed
  i16 shortest modular angle deltas. Flags FULL=1 SNAP=2 SEAT=4 RESET=8, bits 4–7 rejected, 1–3
  require a full; every new pairing starts FULL|RESET. Deltas beyond ±(2^30−1) mm are sent as fulls.
- C2S `blockfield:combat_view`: `sequence/observerStreamEpoch/receivedSnapshotTick H/tick/alpha
  uvarlong/uvarlong/uvarlong/uvarlong/u16`. One fresh ticket immediately before every allowlisted
  attack when the client clock is driven (duplicate R gets a new sequence). The sequence
  high-water belongs to the play connection: a `snapshot_restart` keeps it (and the refresh
  budget); only a fresh connection, or JOIN on a new backend together with that server's new
  intake, resets it; tickets from a retired stream epoch are rejected. The server validates stream identity, sequence high-water, absolute
  non-future time, H membership in its 40-tick send log, and the RTT age bound, then resolves each
  victim from its own sent prefix — never from client-claimed epochs. Missing/invalid/stale views
  sample the current committed frame with a counted `view:current:<reason>` label; non-spectator
  victims stay hittable.
  R-only tickets (owner decision 2026-10-30, protocol 9; changes R10-OUT): when a revision has no complete
  victim report the client still sends the ticket with R, A and the receipt identity but no rows (presence
  byte 0). Intake applies the same stream/sequence/future/H/ageBound checks; the stamp is labelled
  `incomplete` (counter `viewsIncomplete`) and its rewind uses the reported R instead of `R_est`, with the
  stale ceiling (16, still bounded by `ageBound` at intake). Resolution is the fallback rewind below:
  retained canonical server poses, never the client's animation. Before this, any one unpresentable
  victim cost every shot its ticket and the shooter tested current poses (moving targets 0–13% on the
  2026-10-30 stand).
  Fallback rewind (owner decisions 2026-10-27, 2026-10-28 and 2026-10-29; an explicit change to
  ADR4 §3.5 item 7's fallback policy): a shot that advances (TaCZ bullets, SBW projectiles, LR
  throwables) with a `ServerPlayer` owner, no blast margin and a `none`, `stale` or
  `exact_unavailable` stamp tests the victim's retained server pose at `R_est + max(0, age − 1)`,
  the flight-start convention of exact — except a `stale` ticket, which tests the rendered pose
  `R_est` itself with no forward advance (owner 2026-11-02: the stale view is already older than
  the shooter saw, so advancing tests the runner ahead of the drawn pose). `R_est = map(intake − RTT_min) − K` uses only server-clock
  probe round trips and the recorded tick map (`FallbackRewind`). A `stale` ticket's identity/provenance-validated R
  is older than `ageBound`, so K alone tests ahead of that view: `R_est = min(…, R_ticket)` may
  only deepen the rewind (evidence, never authority; no probe still refuses). The full receipt
  must pass the exact path's identity, generation/activation, victim-set and application-proof
  validation, including reconstruction; only age is relaxed. Reconstruction is lazy until native
  admission or geometry consumption, using the same pending receipt as exact shots. Both fallback
  estimates are frozen at intake; rejected weapon requests do not reconstruct. The complete R/A
  span must fit the retained 40-tick history, and traversal visits only retained sent entries.
  Invalid or unresolved stale receipts use the ordinary `none` estimate and its 7-tick ceiling. The rewind is clamped
  to `[0, min(16, ageBound)]` on the stale path and `[0, min(7, ageBound)]` otherwise, and resolution
  re-checks the per-reason cap. At default netcode the age bound still binds first (8 at 0 ms, 9 at 1–50 ms,
  10 at 100 ms), so 16 needs a wider bound (owner decision, wider exact window). The pose is the canonical S[n+1] at
  placement R (`CanonicalBody` ring + `AnimSyncEval.canonicalAt`), labelled `view:rewound:<reason>`
  (before/after n/n+1, counters `viewRewound:<reason>`, `viewRewoundEvals`). Every refusal keeps
  `view:current:<reason>` with subcause `rewind:<cause>` (counter `viewRewoundRefused:<cause>`):
  `no_probe`/`no_mapping`, `cap`, `at_current`, `outside_ring`, `no_state` (respawn or teleport
  restart, missing tick), `identity` (epoch/seat/teleport generation changed), `vehicle` (SBW
  vehicle root then or now), `eval`. Rejected tickets, lifecycle reasons, lost context, knives,
  instant hitscan (SBW `shootRay` resolves non-advancing via `BodyPartGunDamageMixin`, audited),
  blasts, rams and lunges keep the current pose. Admission, validation, `ageBound`/stale windows and
  damage vetoes are unchanged: the rewind only picks which retained pose is tested. Rewound shots
  stay fallbacks (no equality statistics). `K` = 3.928, frozen by the U6 calibration (2026-10-28).
  PROTOCOL 8: the ticket's receipt body may end with `fireEye` (3 big-endian f64, finite): the
  TaCZ shooter's `getEyePosition(partialTick)` at the `doShoot` fire frame, attached only to the
  `tacz:client_player_shoot` ticket. A body without it decodes as before; any other tail length is
  rejected; applied reports never carry it. The server starts that action's fresh hand-fired spawn
  at `fireEye` + its effective-eye shift only if, at ticket intake, the eye lies within 0.05 m of
  the vanilla eye path of the shooter's last two accepted move packets that moved the feet (each
  pre-move → applied position; rotation-only packets such as the shot's own `Rot` do not count; a
  real teleport clears them) or its current eye, and the spawn follows intake within
  one tick; otherwise the native origin stays (`fire_eye` ShotDebug stage records both outcomes).
  The check reads only the shooter's own path, so a stale/rejected remote view still validates it.
  No eye (old client, non-gun attack) is native. `fireAim` (2 big-endian f32 after the eye: yaw,
  pitch) is the rotation the client's `Rot` sent with the shot, Camera Overhaul tilt included.
  TaCZ re-queues the shot, so a newer `Rot` can land first; the shot's pitch/yaw suppliers then
  return `fireAim` when it was within 5° of the server rotation at intake and under the same
  one-tick rule (later burst rounds read the live rotation).
- Predecessor reordering: a current-stream batch ahead of H with an unavailable predecessor is
  held without decoding or advancing H. At most 20 batches are retained, each for at most its
  normalized `holdToSnapMs` (strict-greater timeout, polled even without arrivals). Filling the gap
  applies the whole available chain in predecessor order, including empty batches; clock arrivals
  follow that same order. Overflow, timeout, conflicting successors or other invalid data count a
  protocol defect. Twenty batches cap storage at one second of normal sends even with a 2 s hold.
- C2S `blockfield:snapshot_restart`: `observerStreamEpoch uvarlong, reason u8`. On a protocol
  defect the client stops driving, reports the reason and requests a fresh epoch. Success re-arms
  recovery for later faults. The server accepts at most one restart per 10 seconds per connection
  (monotonic elapsed time); `snapshotRestartRejected:limit` remains observable. Ten seconds bounds
  reset work without permanently disabling a session after repeated lifecycle switches. Rejected
  or unanswered requests retry at that interval, so rate limiting cannot strand a valid stream.
  Encoding-disabled server streams cannot restart. The fresh epoch clears observer
  log/seeds/baselines and pending action, preserves pairings/history/replay/refresh budgets, sends
  FULL|RESET at the next unsent END tick, and requires rewarming. No guessed baseline or
  damaged-prefix H is accepted. See [the original bytecode proof](review/p3-raknet-fix.md) for r9
  history; its one-shot policy is superseded by this policy.
- JOIN resets buffer, clock, receipt/deadline, native references, seats, ticket sequence and net log
  on the client thread. Fabric networking 4.3.0 invokes JOIN from `onGameJoin` RETURN, including
  Velocity's 1.20.2+ configuration/backend switch without DISCONNECT. Both lifecycle callbacks
  use `Minecraft.execute` off-thread, because DISCONNECT may originate on Netty. On the client
  thread they reset synchronously: the reentrant event loop otherwise queues the reset behind
  already queued new-backend payloads. Ordinary
  respawn/dimension resets preserve connection sequence and decode state; surviving native entities
  are re-added from the current level; only already-adopted native entities resume retained
  deltas, preserving pending re-pair/seat-full guards. Net-log `tick` remains monotonic through scene resets,
  `sceneGeneration` increases, and `netlog <since>` keeps returning newer entries.
- Channels: C2S custom payloads default to order channel 2, together with Interact, UseItem,
  UseItemOn, PlayerAction, SetCarriedItem, PickItem, ContainerClick and PlayerCommand. This keeps
  weapon draw/aim/reload, turret aim/weapon selection and inventory state ordered before attacks.
  The sole explicit bulk exception is `blockfield:workshop_ai_audio` (8 KiB recording fragments)
  on channel 3; it has no gameplay-state dependency. Existing attack/view id overrides remain 2.
  S2C routing stays native. Every Respawn/Login and configuration synchronization can disable
  multichannel and the next Commands starts it again: queued snapshots ride channel 0 while later
  ones ride channel 3. The bounded predecessor queue handles each such switch. Snapshot/native
  Add/Remove/Move/Teleport/SetPassengers order remains independent via pending buffers/kind checks.
  Version-pinned tests cover Fabric and Velocity codecs and effective native/class overrides.

Known limitations from the client review:

- Malformed snapshot wire framing (unknown kinds/flags, noncanonical varints, trailing bytes or
  unordered ids) throws in the codec on Netty and disconnects before the buffer can count a
  protocol defect. Semantic defects after decoding are counted and recoverable. A counted
  malformed-wire recovery would need a separate decoder/error-handling contract (P2-1).
- The local player's ride stack, including remote co-passengers, renders natively to avoid
  camera/seat judder. Server resolution can still select those co-passengers from the snapshot
  stream, so they are outside the translation-equality cohort and require a separately reported
  mounted residual; no matching visual-time guarantee is claimed for that observer (P2-3).
