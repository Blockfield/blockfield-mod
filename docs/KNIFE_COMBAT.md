# Knife-only melee and weapon inspection

Tracking: https://github.com/Blockfield/blockfield-releases/issues/13

## Implementation status

Draft. Server/client wiring is implemented and unit-tested with mocked game handles; NOT verified live.
Live two-player combat, full-pack launch and visual animation tests: NOT RUN. Do not merge/release before them.
No workflow, release, deployment, production or modpack changes are part of this PR.

Wired (server unless noted):
- Input: `mixin/lrtactical/KnifeSwingMixin` on LR `CombatProperties.preAttack(MeleeAction, Vec3, Vec3)Z` (RETURN).
  A swing LR accepted (its own cooldown/draw time) must also pass `KnifeCombat.swing`; otherwise the method
  returns false and LR skips its animation sync. Only the action enum is used; client origin/direction are ignored.
- Timeline: `combat/KnifeCombat`, one `KnifeCombatRules.Timeline` per player UUID on `server.getTickCount()`.
  Slot, player handle, level and `Room.of` are recorded at acceptance; impact is dropped (no refund) when any changed,
  the player died, is a spectator or no longer holds the knife.
- Hit: the blade sweeps an arc, not a single point. Light swings (LMB, `melee_1`/`melee_2`
  alternating) fan ±20° around the crosshair out to 2.4 m; the heavy thrust (RMB, `melee_3`,
  the overhand stab) is a narrow ±8° cone along the crosshair out to 2.8 m (owner 2026-10-31:
  the thrust hits farther, the swings hit wider but shorter). At click time (and again at
  impact when the click captured nothing) the server samples rays across the attack's cone
  from the effective eye, each stopped by `Level.clip` (COLLIDER) and resolved against the
  same rewound hitboxes as gun hit-reg; the nearest victim across all rays takes the one
  `hurt` per strike. Rear cone uses victim `getYRot` and positions.
  The client's crosshair ticket (`KnifeAimC2SPacket`, at most 1 tick old) is still honoured when
  alive, within reach + 1.5, in line of sight — and now also inside the swing arc or thrust
  cone. The pinned impact delay (2/4 ticks) anchors the active frame of the swing.
  When LR accepts the swing, the click-time target is the client's crosshair entity (`KnifeAimC2SPacket`, sent from
  `KnifeExhaustedSwingMixin` just before LR's packet, at most 1 tick old) if it is alive, within reach + 1.5 and in
  line of sight; otherwise the same server ray. Remote players are drawn 3-4 ticks behind the server (2-tick updates
  plus 3 lerp steps, ~1 m for a sprinter at any ping), so the server ray alone missed visible hits. The impact hits
  that click-time target and part, cancelled only by the same reach/liveness/line-of-sight and slot/world/room
  checks; an impact-tick ray never replaces them with another victim or part. It decides only when the click
  captured nothing. Lag compensation: hitboxes come from TaCZ `HitboxHelper.getFixedBoundingBox(target,
  attacker)`, the bullets' rewind by the attacker's ping (`ServerHitboxLatencyFix`); the attacker's own eye is current.
  Players are then clipped by `CombatHitbox`, shared with TaCZ/Tweaks and SBW bullets/hitscan;
  firing-range targets clip the same six boxes. A prone player has an oriented body (feet 1 m behind the centre, head 0.88 m ahead). Pose, yaw and eye height
  come from the shot's stamped view or the canonical S[t] body (`CombatViews.select`), instead of using
  the target's current rotation with its past position. These new paths still need live paired acceptance.
  With the body-parts gate enabled, the server records the exact click-time part through the impact
  delay and retains it only under the same reach/LOS/context checks. A client-named player without
  an exact server ray cannot receive unclassified fallback damage. Relative coefficients use
  `gunBalance["melee:lrtactical/dagger"].body_part_multipliers`; the wrapper item key is unsupported.
  The existing knife-only policy still denies karambit/baseball_bat and their native cone/AOE rules.
- Damage: `blockfield:knife` (`common` data, message `player`), tagged `minecraft:bypasses_armor` and
  `minecraft:bypasses_enchantments`. `DamageSource(type, attacker)` goes through `LivingEntity.hurt`, so
  ALLOW_DAMAGE (respawn room, spawn protection, friendly fire), `ServerPlayer.canHarmPlayer`, death events and
  kill credit apply. Vanilla hurt cooldown is reset before the hit, like TACZ bullets and LR melee do.
- SBW plates: `mixin/superbwarfare/KnifePlateMixin` cancels `LivingEventHandler.reduceDamage` for the knife type at
  its first `DamageSource.getEntity()` call (plate neither reduces nor loses points). Resistance, absorption and
  extra max HP are NOT bypassed: rear heavy 36 is lethal only for targets with at most 36 effective HP.
- LR damage replaced: `mixin/lrtactical/MeleeItemTargetsMixin` empties the target list of `MeleeItem.attack`
  (every LR melee item); LR still plays its swing sound.
- Other melee: `KnifeCombat.rejectsOtherMelee` first in ALLOW_DAMAGE denies player-attributed `minecraft:player_attack`
  (fists, held items, TACZ `doPerLivingHurt`) and `superbwarfare:melee_absolute` (close strike `MeleeAttackMessage`).
  TACZ `GunMeleeEvent` is cancelled on the server because `doPerLivingHurt` knocks back before `hurt`.
  The old melee buffs `GunMeleeDataMixin` (both sides) and `ModernKineticGunItemMixin` were removed.
- Lobby consent: `LobbyPvp.beginMelee` frame around the knife hit; `token` accepts `blockfield:knife` besides `player_attack`.
- Kits: `CombatKitManager.equipPlayer` adds `lrtactical:melee` with `MeleeWeaponId=lrtactical:dagger` to the first
  free hotbar slot 0..7 before the tablet (slot 8); a full hotbar puts it in the main inventory. Ammo already sits in
  the vest. Skipped if the kit itself contains the dagger. No modpack class JSON change is needed.
- Client: `KeyDefaults` binds `key.tacz.inspect.desc` to F and unbinds `key.tacz.melee.desc` and
  `key.superbwarfare.melee`; custom bindings survive through `KeyDefaultHistory`.
- Inspect interruption: `LocalPlayerAimInspectMixin`. `LocalPlayerAim.aim` sends the state machine no input, so
  inspect kept playing while aiming (rig-verified). On aim start the mixin removes only tracks whose running clip
  name starts with `inspect`; reload/put-away tracks are untouched. Every kit gun's inspect clips (including EliteX
  custom FSM variants `inspect_ext_*`, `inspect_extended*`, `inspect_01`) match that prefix.
  Inspection is client-only and never touches server timers.

Verified against pinned jars in the rig mod directories: `lrtactical-1.21.1-0.4.1-bf1.jar`
(`CombatProperties.preAttack`, field `player`, `MeleeItem.attack(Player, ItemStack, MeleeAction, List, int)`,
`IMeleeWeapon.ID_TAG = "MeleeWeaponId"`, `data/lrtactical/index/melee/dagger.json`),
`TACZ-Refabricated-1.21.1-0.7.0-forge1.1.8-hotfix.jar` (`GunMeleeEvent`, `ModernKineticGunItem.doPerLivingHurt`
uses `playerAttack`), `superbwarfare-0.8.9.1-mc1.21.1-bf4.jar` (`LivingEventHandler.reduceDamage` bytecode/locals,
`melee_absolute`). Mixin application in a running server was NOT checked.

## Gameplay contract and implemented policy

Intended controls: F inspects the held weapon; a separate held knife uses LMB light, RMB heavy.
Both attacks share one per-player timeline. A miss consumes recovery. Switching slots or
cancelling impact must never refund recovery or the first-light bonus.

| Attack | Damage / 20 HP | Impact delay | Recovery | Reach |
|---|---:|---:|---:|---:|
| First light from front | 8 | 2 ticks | 12 ticks | 2.4 blocks |
| Chained light from front | 5 | 2 ticks | 12 ticks | 2.4 blocks |
| Heavy from front | 13 | 4 ticks | 26 ticks | 2.8 blocks |
| Light from rear | 18 | 2 ticks | 12 ticks | 2.4 blocks |
| Heavy from rear | 36 | 4 ticks | 26 ticks | 2.8 blocks |

Damage values are chosen CS-inspired defaults, not verified exact current CS2 internals. Recovery equals the pinned
LR dagger cooldowns (`attack_left` 12, `attack_right` 26 ticks), which LR also predicts on the client and which match
the clip lengths (`melee_1/2` ~13, `melee_3` 26 ticks). Impact delay 2/4 ticks follows the first swing sound
keyframes (0.04 s light, 0.1 s heavy); visual contact alignment is NOT verified in game. Armor penetration
is an explicit Blockfield requirement. A light swing is first after 20 ticks without an accepted
light swing. Rear cone: 120 degrees, using victim horizontal forward and victim-to-attacker offset,
not two aim vectors. Same-position and nonfinite vectors cannot count as rear hits.

At 20 HP, permitted rear heavy damage is lethal. This numeric policy alone is NOT a guarantee
against arbitrary increased HP/absorption/resistance: the adapter must explicitly define and test
those cases while preserving lobby, respawn and other invulnerability rules. Never bypass such
protections merely to force a backstab kill.

## Remaining before ready

Everything above that says NOT verified, plus: two-player damage with full SBW plates and without armor; wall/edge
reach; packet spam and slot switching; fist/TACZ/SBW/other-LR melee dealing no damage; bullets, grenades and
vehicles still damaging; lobby consent, respawn room, spawn protection and friendly fire with the knife; kits of all
5 classes / 10 variants (engineer hotbar); F inspect and its interruption per kit gun; knife swing/impact feel.

## Animation audit: evidence levels

Audit date: 2026-09-14. This is a PARTIAL source-resource audit, not certification of every shipped gun.
A named input or a file name is insufficient: checked clips contain actual `bones` definitions.
Source clip presence, final resource resolution, shipped binary identity, and runtime behavior are
separate checks. The latter three are not automatically implied by the first.

Kit source: modpack `1dc07a53d4b4e93b0fb8758f22ed9d0b3e78a6f8`,
`server/lobby/blockfield/classes/{assault,mark,sniper,tech,drone}.json`.
Five classes, ten variants, twelve unique TACZ guns plus one SBW launcher.

### All thirteen kit weapons

TACZ source repository: `Sh1roCu/TACZ-Refabricated`, ref `1.21.1`; branch head observed during
inspection: `b3a33232e9714933559f7d46e99e464062f237f1`. Its `gradle.properties` declares
`1.21.1-0.7.0-forge1.1.8-hotfix`, matching the Blockfield dependency version. That matching string
is NOT a byte-for-byte verification of the installed jar or resource overrides.

For the seven confirmed base guns, the inspected path is:
`src/main/resources/assets/tacz/custom/tacz_default_gun/assets/tacz/animations/<id>.animation.json`.
The two time columns are the declared `animation_length` values in seconds, not combat cooldowns.

| Weapon ID | Kits | inspect | inspect_empty | Source clip result |
|---|---|---:|---:|---|
| tacz:glock_17 | assault, mark, sniper, tech | 11.25 | 12.2833 | Both present, nonempty |
| tacz:deagle | assault | 8.9667 | 5.8333 | Both present, nonempty; also inspect_xmag |
| tacz:m249 | assault | 9.05 | 9.05 | Both present, nonempty |
| tacz:sks_tactical | mark | 5.9 | 7.8667 | Both present, nonempty; xmag variants also present |
| tacz:spr15hb | mark | 7.5833 | 7.5833 | Both present, nonempty |
| tacz:ai_awp | sniper | 12.7833 | 10.25 | Both present, nonempty |
| tacz:hk416d | drone | 7.8667 | 8.3333 | Both present, nonempty; xmag variants also present |
| elitex:ak47 | assault | 9.6667 | 9.6667 | Both present, nonempty; default FSM |
| elitex:m4_sopmod | assault | 15.75 | 16.7833 | Present, nonempty; `m4a1_sopmod_state_machine` also plays `inspect_ext_1..3` / `inspect_empty_ext_1..3` |
| elitex:akx105 | assault | 12.875 | 11.375 | Present, nonempty; `aks_state_machine` also plays `inspect_extended[_empty]` |
| elitex:benelli_super | assault | 6.98 | 6.9167 | Present, nonempty; `pump_action_state_machine` also plays `inspect_01` |
| elitex:ro635v3 | tech | 9 | 8.0714 | Both present, nonempty; default FSM |
| superbwarfare:rpg | tech | none | none | SBW `rpg.animation.json` has no inspect clip (idle, edit, run, run_fast, fire, reload); F does nothing |

EliteX and the seven base rows above were re-read on 2026-09-14 from the rig client's installed pack
(`tacz/elitex_quality_guns`, `tacz/tacz_default_gun`) and `superbwarfare-0.8.9.1-mc1.21.1-bf4.jar`; base values match.

The SKS display resource was also read: `assets/tacz/display/guns/sks_tactical_display.json`
within the default gunpack references `animation: tacz:sks_tactical`, default `rifle`, and
`state_machine: tacz:sks_tactical_state_machine`. Complete index/display/FSM/override resolution
for every entry still needs an automated audit of the actual client distribution.

Direct source examples (the full seven paths follow the common template above):
- https://github.com/Sh1roCu/TACZ-Refabricated/blob/b3a33232e9714933559f7d46e99e464062f237f1/src/main/resources/assets/tacz/custom/tacz_default_gun/assets/tacz/animations/glock_17.animation.json
- https://github.com/Sh1roCu/TACZ-Refabricated/blob/b3a33232e9714933559f7d46e99e464062f237f1/src/main/resources/assets/tacz/custom/tacz_default_gun/assets/tacz/animations/sks_tactical.animation.json
- https://github.com/Sh1roCu/TACZ-Refabricated/blob/b3a33232e9714933559f7d46e99e464062f237f1/src/main/resources/assets/tacz/custom/tacz_default_gun/assets/tacz/animations/spr15hb.animation.json

### Knife: existing first-person and state-machine resources

Repository `khangiosipa-glitch/LRTactical-Fabric-1.21.1`, upstream main:
- `src/main/resources/assets/lrtactical/display/melee/dagger.json` resolves
  `animation: lrtactical:melee/dagger` and `state_machine: lrtactical:default_melee_state_machine`.
- `src/main/resources/assets/lrtactical/animations/melee/dagger.animation.json` has an explicit
  nonempty `inspect` clip (4.7333 seconds), sound events and the `melee_1/2/3` attack sequence.
  Observed lengths include `melee_2: 0.6563` and `melee_3: 1.3` seconds.
- `src/main/resources/assets/lrtactical/scripts/default_melee_state_machine.lua`
  (blob `326c7dcdf5e71ac4396657571106b9e1eb58f8c0`) maps `INPUT_INSPECT` to `inspect`,
  `attack_left` to alternating `melee_1`/`melee_2`, and `attack_right` to `melee_3`.
- Dagger display references third-person `dagger_attack` / `dagger_heavy_attack`.
  Their actual third-person resource contents and the shipped bf1 jar were NOT verified here.

Sources:
- https://github.com/khangiosipa-glitch/LRTactical-Fabric-1.21.1/blob/main/src/main/resources/assets/lrtactical/display/melee/dagger.json
- https://github.com/khangiosipa-glitch/LRTactical-Fabric-1.21.1/blob/main/src/main/resources/assets/lrtactical/animations/melee/dagger.animation.json
- https://github.com/khangiosipa-glitch/LRTactical-Fabric-1.21.1/blob/main/src/main/resources/assets/lrtactical/scripts/default_melee_state_machine.lua

### Unresolved coverage and validation

All thirteen kit weapons are covered above from installed resources. The complete non-kit weapon inventory has NOT been audited. Do not claim all-weapons coverage.
The release client archive could not be downloaded into the execution environment, so no final
jar checksums, resource-priority resolution or complete binary report was produced.

Before marking ready: exact dependency descriptors; complete client archive audit; full-pack
client/server launch; two-player damage tests with full SBW plates and no armor; wall/edge reach;
packet spam and slot switching; all non-knife melee denial paths; lobby/respawn/team protection;
kit overflow; visual light/heavy impact alignment; all kit inspections and interruption cases.
