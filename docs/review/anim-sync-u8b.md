# U8b client/server equality gaps (after U8 and U9b)

Contract: ADR4.1 §§2.2–2.3, 3.2–3.6, 4.2, 4.5, 5.3, 6, 9–10 and both errata; U1 schema manifest 4
(unchanged). Branch/worktree `fulla/as-u8b`, `WS/.dev/worktrees/as-u8b`, base phase23 `d913678a`.
No live stand, game or owner A/B was run; the gates below are offline (plain JUnit and the
transformed production runner).

## 1. Driven inputs from the checkpoint (client, both evaluators)

`DrivenPresentation.scope(entity)` is opened around every `EntityRenderDispatcher.render` pass of a
driven remote (`CombatDrivenRenderMixin`, exception-safe, nested scopes restore). Inside it these
getters return the checkpoint's A-domain values; outside it (client gameplay, the local-native view)
the live values stay:

| Input | Pinned reader | Checkpoint field |
| --- | --- | --- |
| item use | `LivingEntity.isUsingItem/getUsedItemHand/getUseItemRemainingTicks/getTicksUsingItem` | `itemUse.*` |
| arm poses | `PlayerRenderer.setModelProperties` TAIL (native two-handed rule) | `itemUse.mainArmPose/offArmPose`, `mainArm` |
| crossbow charge | `CrossbowItem.getChargeDuration` inside `AnimationUtils.animateCrossbowCharge` | `itemUse.crossbowChargeDuration` |
| riding / climbing | `Entity.isPassenger`, `LivingEntity.onClimbable` | `flags.passenger/climbing` |
| elytra / riptide | `LivingEntity.isFallFlying/isAutoSpinAttack` (fall-fly ticks were already written) | `flags.fallFlying/autoSpinAttack` |
| freezing | `Entity.isFullyFrozen/getTicksFrozen` | `flags.fullyFrozen`, `vital.ticksFrozen` |
| bed | `LivingEntity.getBedOrientation`, `Entity.getEyeHeight(STANDING)` (bed offset) | `bedOrientation`, `size.standingEyeHeight` |
| upside-down (audit find) | `LivingEntityRenderer.isEntityUpsideDown` | `flags.upsideDown` |
| Create hanging | `PlayerSkyhookRenderer` set membership + Catnip clock in `setHangingPose` (`DrivenSkyhookMixin`) | reduced membership start; phase `CreateHanging.ticks(u0, sA, floor(A)) + frac(A)` |

The server already consumed every field (`AnimSyncEval`, `VanillaAnimSync.project`, `RootInputs`,
`CreateBodyPose.apply`); `CreateBodyPose.phase` now delegates to the common `CreateHanging.phase`
the client feeds the native formula through `i2f(ticks) + frac`.

## 2. TaCZ and LR capsules sealed at their native broadcast (server + client)

- **Acceptance-time application.** `ServerBodyPoses.acceptedWeaponAction` (TaCZ fire/reload/melee/
  draw) and `AnimSyncNative.lrAccepted` (LR `CPrepareMeleeAttack`) apply the event on the canonical
  stack in the acceptance call with the END-tick drain's operation and order (on the previous
  boundary's session, before the next tick's advance and selection), then `AnimSyncCapture
  .sealAccepted` seals its capsules (stage `INTER_TICK_ACCEPT`, or `NATIVE_TICK` inside a tick body;
  broadcast stage the same). S[t] is the END-tick drain's bit for bit (`AnimSyncNativeTest`,
  `HeldWeaponPoseGraphTest.eventsAppliedAtTheirBroadcastFreezeAndJournalLikeTheEndTickDrain`).
- **Routing.** `AnimSyncTaczBroadcastMixin` / `AnimSyncLrBroadcastMixin` wrap the per-tracker
  `ServerPlayNetworking.send` inside the native `sendToTrackingEntity`: an exact observer of the
  shooter's life is held, every other tracker gets the native packet at once (the attacker's own LR
  copy is never routed). When the native call returns (`flush` hooks on shoot/reload/melee/draw and
  the LR intake; `flushAll` at the snapshot phase for leftovers) each held observer gets one
  `AnimationEventS2CPacket`: the stamped capsules, the first carrying the native payload encoded with
  its own `STREAM_CODEC` (`native.payload:<type id>`, later ones `native.batched`). The END-tick
  delivery skips capsules already delivered. Sound/fire/particle packets of the same call stay native.
- **Client.** `ClientCombatScene.capsules` runs the carried native body once per event identity
  (`NativePayloads`: TaCZ `doClientEvent`, LR `lambda$handle$2`, reflection on the pinned names)
  before the journal applies the capsule in the same task; the journal accepts the carrier, an
  unknown carrier stays `native_payload_unavailable`.
- **Fallback (labelled, counted).** When the life's previous tick was not captured, its checkpoint is
  latched, or an earlier capsule-bearing entry is still pending (a queued TaCZ event, LR attack or
  ParCool acceptance: journal and predecessor order), the event stays queued for the END-tick drain
  and its native packet goes out unchanged (`nativeUnwrapped`, `capsulesSealedAtEnd`). A refused
  acceptance-time seal latches the life (`reseedRequired`), never diverges from the journal.

## 3. ADR4 §9 timing cases (`SceneTimingOracleFixture`)

`u8b-stall-hold-timeout` (production `CombatSceneClock`: 300 ms stall, hold at H, install at the held
bucket, anchor kept on resume, timeout freezes A), `u8b-nested-fade-late-event` (fire, reload two
ticks later, stop two ticks after that; every frame's row replayed by the server reducer: starts,
prefix and presentation digests, graph bytes), `lr.u8b-lr-attack-zero-tick` (carried native body
once, install below the event tick, visible in its frame), `create.u8b-create-membership-zero-tick`
(enter/exit in their frames, native pose equal to the server port's mirror at every hanging frame),
`tacz.u8b-packetless-decision-eligibility` (CANONICAL_TICK at the received S[105] only when floor(A)
reaches 105; PROJECTED_SUCCESSOR at the observer's own end boundary). 30/60/144/240 FPS, 0/1/3 client
ticks where relevant; client ticks never change the driven bits.

## 4. Receipt generation vs READY (U7)

`AnimSyncValidation.validate(observer, view, receivedNs, activated)`: a receipt whose resource
generation differs from `AnimationResourceGate.activated(player)` is `rejected:generation_not_activated`
(after the existing `unavailable:generation` check against the server's active generation).

## 5. SBW layout and Create adapter identity in common (U6/U9)

`common.combat.SbwLayouts` (moved from `SbwBodyPose.Layouts`; `identity()` also records whether each
slot resolves with its descriptor on the loaded bf24 classes) and `common.combat.CreateHanging`
(`identity()`, `phase`, `ticks`). `AnimationManifest.compatibility` adds `compat.family.sbw` and
`compat.family.create`, so both peers compare them at READY (manifest 112 entries in the production
pack runs). U6 fixtures unchanged and green.

## Evidence

Plain JUnit: server `AnimSyncNativeTest`, `AnimSyncValidationTest.aReceiptOfAGenerationTheClientNever
ReadiedIsRejected`, `AnimationManifestTest` (family identities, Create float order), `HeldWeaponPose
GraphTest` (acceptance-session equivalence); client `OracleInjectionTargetTest.drivenInputTargetsArePinned`.
Transformed (client): `vanilla.u8b-driven-item-use-arm-poses`, `vanilla.u8b-driven-root-flags`,
`vanilla.u8b-driven-riding-climbing`, `create.u8b-driven-hanging-membership-phase` (each: a driven
remote whose live state contradicts its checkpoint equals an undriven remote whose live state is the
checkpoint, which differs from the contradicting live state), `tacz/lr.u8b-native-payload-dispatch-once`,
the five §9 cases above, and the manifest fixtures now asserting `compat.family.sbw/create`.
Results: [anim-sync-u8b-fixtures.json](anim-sync-u8b-fixtures.json).

## Deviations from ADR4 (recorded, not silent)

- §2.2 LR capsule `patch.lr` is the LR state the accepted attack applied to (the previous boundary's
  selection with the attack fields), not the END-tick S[t] value; no reducer or validation reads a
  capsule's `lr` (decisions keep theirs).
- §2.2 fallback above: an event behind an earlier unsealed capsule-bearing entry of the same life is
  still sealed at END with its native packet unwrapped (journal/predecessor order outranks timing).
- §2.3 an accepted TaCZ event now uses the weapon identity at acceptance; the END-tick drain dropped a
  fire followed by a weapon switch in the same drain (`weapon_event_superseded`), the native remote
  plays it. Queued-path behavior is unchanged.
- §5.3 riding's `LivingEntityRenderer.render` statements (`model.riding`, walk gate) and climbing's
  native readers (TaCZ `InnerThirdPersonManager` fallback, LR `IdleAnimationHandler`) are covered by
  the pinned getters; the full mixed renderer executing them is U10's gate.

## Open items

- **U10:** full mixed renderer (render/root/layers, Iris/EntityCulling) with the driven scope; capture
  identity of driven frames.
- **U11:** live TCP/RakNet cohorts (item use, elytra/riptide, freezing, sleep, Create, TaCZ/LR with
  their native effects), owner A/B.
- **U5/U7cP:** acceptance-time sealing still falls back behind pending ParCool acceptances (they wait
  for the END-tick batch); sealing those at acceptance would remove that fallback.
- **Coordinator:** `compat.family.sbw/create` change the compatibility digest (paired release).
