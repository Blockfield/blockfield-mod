# Grenade throw modes

Tracking: Blockfield/blockfield-releases#10.

## Behaviour

- Holding a supported grenade, **LMB** is a long throw and **RMB** a short one.
- Both start the mod's own item use (LR Tactical / SuperbWarfare): pull sound, use animation,
  hold time, power ramp, cooking, cooldown and consumption stay exactly as in the mod.
- LMB does not hit or break blocks while a grenade is in hand; the use keeps going while LMB is held.
- A HUD line under the crosshair shows the chosen mode and hold time.
- The server scales the velocity of projectiles spawned by that release to `UNDERHAND_FACTOR` (0.45)
  for RMB. The client can only slow its own throw, so the mode packet is not a trust boundary.

Owner decision 2026-09-14: keep the native hold/cooking/animation; the first version that spawned
projectiles itself shortened throws and lost them.

## Supported items

`GrenadeThrowSupport`: LR `lrtactical:throwable` with `ThrowableId` m67, rgn, smoke_grenade,
flash_grenade, molotov (not c4); SBW `hand_grenade`, `rgo_grenade`, `m18_smoke_grenade`.

## Code

- Client: `GrenadeInputMixin` (startAttack/startUseItem → native use), `GrenadeThrowInput`
  (mode packet, keeps use held for LMB, HUD hint).
- Common: `GrenadeThrowC2SPacket(mode, slot)`, `GrenadeThrowMode`, `GrenadeThrowSupport`.
- Server: `GrenadeReleaseMixin` wraps `LivingEntity.releaseUsingItem` and watches
  `ServerLevel.addFreshEntity`; `GrenadeThrowService` applies the factor once per release.
  An LR grenade thrown automatically after overcooking (`onUseTick`) keeps native speed.

## Rig check

Measure horizontal launch speed from a barrier platform for LMB vs RMB after the same hold,
one entity and one item per throw, cooking (hold ≥ fuse explodes/throws as in the mod).
