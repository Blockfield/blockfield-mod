# Deathmatch: draft foundation, not a playable mode yet

Tracking issue: [blockfield-releases#9](https://github.com/Blockfield/blockfield-releases/issues/9).
Inspected baseline: `main` at `0d1b64f77912fdd45b627db61df236c232eeac92` (2026-09-13).

**This draft adds isolated, tested building blocks. It does not wire them into
`DeathmatchMode`, add a menu entry, build an arena, change packet schemas or enable
anything in production. The old Deathmatch stub and its known defects remain. Do
not use `/bf start deathmatch` as a test of this draft.** The linked issue remains open.

## Product boundary

The working interpretation is free-for-all, not team deathmatch. A distinct map has
spawn positions and battle bounds, but no capture points, tickets or team bases.
Use the existing room slot budget and one `MatchWorld` within the existing backend;
no additional Minecraft server/JVM, no increase of `max_rooms`. Workshop/playtest
must continue respecting the single-backend requirement in issue #8.

Proposed configurable defaults, subject to playtesting:

| Rule | Value |
| --- | --- |
| Kill limit | 30 |
| Active time limit | 600 seconds |
| Preparation | 15 seconds |
| Minimum players | 2 |
| Respawn delay | 3 seconds |
| Spawn protection | 2 seconds, cancelled by a confirmed attack |

A valid opponent kill earns one frag. Suicide/environment deaths earn no frag and
apply no frag penalty. Maximum kills wins; tied leaders draw. UUID only stabilizes
table ordering, never determines rank or winner. Administrative cancellation has
no winner. This is unranked FFA; do not introduce campaign MMR or frontline changes.

Draft policy choices: late join is allowed; departed players retain their scores
and remain eligible for the time-limit lead. There is no automatic disconnect
penalty or forfeit. At the kill limit, the first server-accepted event ends the
round; there is no same-tick overtime. Revisit these choices explicitly during
balance review rather than changing them accidentally in an adapter.

## What this PR implements

- `DeathmatchRules`: validated immutable rules. Preparation/respawn/protection
  values are only configuration at this stage; their runtime timers are NOT implemented.
- `DeathmatchRound`: `WAITING -> RUNNING -> FINISHED`, minimum roster, late join,
  retained scores on leave/rejoin, immutable standings and one final result.
  Active time advances only through `advanceSecond()`; it is not a wall-clock timer.
- `Life(roundId, playerId, generation)`: one death per deployed life. Old callbacks
  remain invalid after respawn, leave/rejoin or a different round. No unbounded
  death-event deduplication cache. A dead, present shooter's current life can still
  score an already-issued projectile trade; respawn/departure invalidates old sources.
- `DeathmatchArena`: typed map/mode/bounds/spawn validation, immutable snapshots,
  distinct positions (even with different facing/signed zero), finite coordinates,
  safe map ID and bounded orientation. No campaign configuration is required.
- `selectSpawn`: first apply the server-supplied world-safety predicate, then
  maximize distance to the nearest live opponent. Ties use reservoir sampling.
  No eligible candidate returns `Optional.empty()`; the caller must keep waiting.

These types do not own worlds, kits, connection authorization, collision checks,
Minecraft damage attribution, UI or file I/O. Their existence does not mean those
integrations are complete. All mutation is owned by the Minecraft server thread;
there is no background thread and no shared singleton state.

## Configuration contract (proposed, not yet parsed at runtime)

`deathmatch.example.json` illustrates the proposed mode settings. Copying it into
`blockfield/modes/` currently has no effect. The adapter must parse and validate
integers without truncating fractions, catch invalid data with useful diagnostics,
and snapshot settings once per match. Do not reinterpret seconds as ticks.

`dm_arena.example.json` illustrates a separate deathmatch-only map. The positions
are synthetic documentation values, **not coordinates of an existing world**. Its
floor, cover, safe space, chunk availability and playability have not been tested.
Do not install this example on production or claim it supplies an arena.

The map adapter must read `supported_modes` explicitly: the current
`MapConfigManager.MapConfig` does not retain it. Map `mode_data.deathmatch.spawn_points`
to `DeathmatchArena.Spawn`, preserving orientation; proposed defaults for omitted
`yaw`/`pitch` are zero. Validate JSON shape/nulls/numbers, world-compatible coordinate
limits and dimension height in addition to the typed checks. Never silently
substitute a campaign map when the requested map lacks a deathmatch profile.

A future private modpack change must supply matching config and world template as
one checked release. Verify the template exists before opening the room dimension.
The example does not publish map assets or change a release pin.

## Runtime integration checklist (NOT delivered by this draft)

### 1. Existing mode and room lifecycle

`server/src/main/java/com/lunares/blockfield/server/core/Room.java` already registers
`deathmatch -> DeathmatchMode::new`. Extend that mode rather than adding a second
registry entry or a subclass of `CampaignMode` that pretends to have capture points.

- [ ] Parse settings and explicit map ID; reject missing/incompatible config/template.
- [ ] Open the arena with the actual `MatchWorld` API, then call `setActiveMap`.
  `Room.level()` falls back to overworld before a world is open: never treat that
  fallback as a successfully loaded arena.
- [ ] Coordinate preparation with the existing `Room` state machine. It decrements
  `phaseTimer` after `onTick` and calls `onStart` automatically. Merely returning
  `false` from the pure model's `start()` will not stop `Room` from entering GAME.
  Cancel/restart preparation when the required roster disappears; do not create a
  second independent scheduler. `IGameMode.onTick` is called once per second.
- [ ] Create one stable round ID; `join` each participant before explicit deployment.
  Tick the pure model once per active second, not during preparation or result UI.
- [ ] Remove the stub's global `getPlayerTeams().forEach(setAllowFriendlyFire(true))`.
  Do not modify shared RedTeam/BlueTeam or another room's friendly-fire policy.

### 2. Deployment, damage and reconnect

Entry points include `BlockFieldServer.handleMenuAction`, death/respawn callbacks,
`GameManager`, `RespawnRoom`, `CombatKitManager` and `PlayerFriendlyFireMixin`.

- [ ] Route DM class/spawn actions to an FFA policy, not campaign bases/points/team
  assignment. Use existing validated kits, not arbitrary client item data.
- [ ] Validate room public ID, round generation, actual arena dimension, membership,
  life state and action permissions on the server. Waiting in the hub is not combat.
- [ ] Before a deployment, close containers, isolate lobby gear, check kit and timer,
  evaluate floor/collision/hazards/chunks, reserve a safe candidate, and coordinate
  kit/teleport/model state as one recoverable operation. A second deployment of an
  alive life must not grant another kit. An empty spawn selection means wait/retry.
- [ ] Tag accepted attacks and delayed projectiles with server-owned context/life
  tokens. Do NOT create a new killer token when a delayed hit arrives, and never
  accept a token supplied by a client as authorization. Map respawned player objects
  to the same UUID while retaining the victim's original death token for callbacks.
- [ ] Apply FFA hostility without global scoreboard mutations; prevent teamkill
  classification for FFA. Test melee, TaCZ, supported explosions and owned projectiles.
- [ ] Implement actual respawn countdown and protection, including attack cancellation
  of protection and invalidation of pre-departure/pre-respawn damage sources.
- [ ] Reconnect preserves score, authoritative life state, kit and remaining delay.
  `join()` alone neither revives nor grants gear. The draft invalidates deployed life
  on `leave()`; adapter policy must explicitly distinguish a disconnect from a
  temporary player-object replacement and preserve external cooldowns.

### 3. Results, isolation and cleanup

`MatchTracker.startTracking()` currently creates a Campaign session, and its
`registerKill()` treats equal scoreboard teams as teamkills. Do not call those paths
blindly and do not increment both the model and tracker independently from duplicate
callbacks. Accept a death once, then project the accepted event to stats/UI once.

- [ ] Use deathmatch mode ID, stable match/map ID, participant UUIDs, personal
  winner/draw and end reason. Keep FFA separate from campaign progression/MMR.
- [ ] Save the result exactly once before closing. The stub currently sets MATCH_END
  before `Room.stop()`, but that method saves only from GAME/PREPARING. Correct this
  lifecycle mismatch and support non-team outcomes without overloading winnerTeam.
- [ ] A repeated `Result` read is not permission to publish it again: key persistence
  by the stable round ID and consume/finalize once. Cancellation does not overwrite
  an already finalized normal result. Failed writes require explicit recovery.
- [ ] Clear player state for offline UUIDs as well as online players on room closure,
  invalidate queued actions/projectiles, release the world and leave the map template
  intact. No score/protection/kit state may leak into the next reused numeric room ID.

### 4. Menus and HUD

- [ ] Add a server-authorized entry from the hub, respecting room capacity/maintenance.
- [ ] Show a flat FFA table, player rank/frags, leader, remaining seconds and kill limit.
- [ ] Explicitly clear stale tickets, points, markers, teams and spawn targets on all
  entry/exit/reconnect paths. The stub's empty UI JSON is not a reset protocol.
- [ ] Check both directions: campaign -> hub -> DM -> hub -> campaign, including a
  waiting room member physically located in the hub. Other rooms must be unaffected.

## Verification and next-agent commands

Requires Java 21. From the repository root:

```sh
bash dev/test-deathmatch-core.sh
```

This compiles only the three pure production classes and shared contract checks
with `javac --release 21 -Xlint:all -Werror`, then executes them. It uses a temporary
directory, downloads nothing and touches no server/world/config. The same 45 named
contracts are exposed to existing JUnit 5 through `DeathmatchContractTest`.

With the repository's Gradle/Minecraft dependencies available:

```sh
just gradle --offline :server:test --tests '*DeathmatchContractTest'
just gradle --offline build
```

Actual draft validation (2026-09-13): Java 21 compilation and **45/45 dependency-free
contract checks passed**. JUnit/Gradle execution and full mod build were not run in
the authoring environment; external dependency download was unavailable. The JUnit
adapter is supplied for the existing test task, not claimed as an executed CI result.
No Minecraft server, real arena, two-client session, HUD or weapons were tested.

After wiring, follow `docs/AGENTS.md` and the existing `dev/rig` instructions. Use a
built/verified arena with two clients: start, select kits, score repeated kills,
suicide, respawn, projectile trade, reconnect, reach kill/time limits, tie, close,
reopen and return to campaign. Test missing map, all unsafe spawns, late join,
concurrent campaign and offline cleanup. Record the actual build SHA and results.
Do not run a second full backend on the production node during workshop/playtest.
