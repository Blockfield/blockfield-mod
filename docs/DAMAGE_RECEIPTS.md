# Damage receipts: why a hit did or did not damage or kill

One bounded per-hit receipt per attempt: attacker, victim, victim life id, source/weapon,
hit part, raw/final amount, outcome (`APPLIED` | `CANCELLED:<reason>` | `ZEROED:<reason>`),
HP before/after, death + credited killer. Off by default; when off every entry point is a
single volatile read, with no synchronous logging in hot paths. Op-only (`/bf damagelog`),
also over RCON.

## Where damage flows (file:line)

- Guns (TaCZ/SBW hit): projectile hit resolution via `BulletHitboxMixin:80`
  (server body null -> the hit is dropped before any damage event) and `GunCombatHitboxMixin`,
  part scaling in `BodyPartBulletDamageMixin` / `BodyPartDamage`.
- Knife: `KnifeCombat.swing` (accept) -> `KnifeCombat.strike:244` (target/part/reach,
  `LobbyPvp.beginMelee`, `victim.hurt` with `invulnerableTime = 0` first).
- LR throwables/grenades: `BodyPartThrowableMixin`, `BodyPartStickyGrenadeMixin`
  via `LrThrowableCombat.direct`.
- Vanilla melee/fall/other: `LobbyMeleeMixin` (`beginMelee` gate), then vanilla `hurt()`.
- Explosions: `LrExplosionStampMixin`, `CustomExplosionMixin`, `ProjectileExplosionMixin`.

## Where damage can be cancelled or zeroed

Central choke point: `BlockFieldServer.onLivingHurt:408` (Fabric `ALLOW_DAMAGE`), in order:
`pre_match`, `other_melee` (`KnifeCombat.rejectsOtherMelee`: fists, vanilla/LR/SBW melee),
`drone`, `range_target`, `lobby_pvp`, `respawn_room`, `range_pvp`, `friendly_fire`,
`protection` (`GameManager` spawn shield). `canHarmPlayer` team checks live in
`PlayerFriendlyFireMixin`. Knife aims that never reach `hurt()` report their own miss in
`KnifeCombat.strike`: `knife_no_target` / `knife_no_part` / `knife_unreachable` /
`knife_lobby_refused` (the 2 m HEAVY miss in server#238 lands here).

Vanilla-side rejections never reach a mod gate, so the hurt() exit hook records them:
hurt cooldown (`ZEROED:hurt_cooldown`, `invulnerableTime > 0`), creative/spectator
invulnerability (`ZEROED:invulnerable`). `RangeTargets.afterDamage` and knife both zero the
cooldown, so a second hit right after the first still applies. Lethal hits report the fatal
blow amount stashed at ALLOW_DEATH as `taken` (pre-armor, like survived hits), never the HP
delta.

## Kill attribution

`BlockFieldServer.onPlayerDeath:608` credits `DroneDeploy.attacker` (direct players and
drone operators), else the molotov igniter from `FireAttribution`, and passes
`(victim, source, killer)` to the mode (`DeathmatchMode.onPlayerDeath:759`,
`CampaignMode.onPlayerDeath:1579`) and `MatchTracker.registerKill`. Killfeed isolation
per room is a separate SBW-broadcast concern (server#187). The receipt's `killer` calls
the same resolver without the room check, so cross-room hits still name the hitter; with no
drone, fire or direct hitter (a fall after a firefight) it names nobody instead of vanilla's
stale last-hurt memory.

## Use

```
/bf damagelog on|off|dump [player] [n]
```

`dump` writes `debug/damagelog/damagelog-<ts>.ndjson` (one JSON object per line, newest
first), logs every line, and replies with counts. Ring holds 256 receipts; overflows
count as `dropped`. The `life` field is a per-victim death counter owned by this
facility, so a respawned victim's receipts do not mix with the previous life.
