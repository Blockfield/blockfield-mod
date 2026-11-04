# Ranked campaign admission — wired

Refs Blockfield/blockfield-releases#6; interfaces to #3 and #8. `CampaignAdmission` is the server-owned, independently tested policy; `CampaignMode` (`getId() == "campaign"`) now drives it as **Ranked**. `RoomMode` (`getId() == "room"`) is **Casual** and has `admission == null`: its behaviour is unchanged.

Checks: `bash dev/test-campaign-admission.sh` (plain Java 21) and `:server:test --tests '*CampaignAdmissionTest' --tests '*MaintenanceTest' --tests '*CampaignFrontTest'` (JUnit). Multiplayer scenarios to run on the rig: `dev/test-ranked.md`.

## Settings

Optional `balance` keys in `blockfield/campaigns/<id>.json`: `min_players_per_team` (2), `ready_countdown_seconds` (15), `reserve_seconds` (180), `ready_timeout_seconds` (120). Capacity is `max_players_per_team`. The clock is `server.getTickCount()`.

## Where it is wired

- **Ready gate.** `Room.tick` PREPARING calls `CampaignMode.tickReady`: `prepareStart` → no pending start means no countdown and action bar «Готовы X/Y»; pending shows seconds in `phaseTimer`; when due, `start(...)`, then `room.state(GAME)` + `onStart`. `/ready [on|off]` and menu `ready:on|off:<round>` both call `CampaignMode.setReady`. An unready online member of a complete roster is sent to the lobby after `ready_timeout_seconds`; this never starts the round without the others.
- **Rounds.** `nextRound` at the top of `setupCurrentCampaignStep` after the map opens, then waiting members are admitted into free seats (replaces `distributeWaitingPlayers`/shuffle). `finish()` right after `saveCampaignStats` on victory/draw and in `onStop`.
- **Join/reconnect** (`PlayerTeamManager.handleRankedJoin`). Live reservation → own team. Same round in GAME → dead state/frozen respawn timer kept, a living player keeps inventory and position. Otherwise current-phase rules (dead, timer 0, cleared inventory, RespawnRoom, class menu). Newcomers are admitted only in PREPARING; others stay room members without a team in the hub. Expired reservation or finished campaign → lobby.
- **Leave.** `Hub.onPlayerLeave` → `disconnect` (reservation); `Room.remove` (voluntary) and `/bf team kick` → `leave` (seat freed).
- **Gates.** `CampaignMode.mayFight` guards `selectClass`, `deploy` and AFTER_RESPAWN (non-members go to the hub). `canSwitchTeam` false; `joinTeam` rejects any team except `teamOf`; `swapPlayers` and `/bf config shuffleteams <bool>` refused. Only seated players get `PlayerStats`.
- **Stale callbacks.** `Room.generation()` increments on every `state(...)`; the join TickTasks and the Casual vote TickTask bail when it changed.
- **Cleanup.** No players online: live reservations reset the idle counter; when all expired, `stop("Abandoned")` once.
- **Frontline.** `CampaignMode.frontStep` is the unchanged rule (draw: `matchCount >= 5 && index == 1`, checked first), pinned by `CampaignFrontChecks`.
- **DRAINING.** `/bf maintenance drain|status|interrupt [confirm <reason>]`, see `docs/AGENTS.md`.
- **Status.** `RoomPresence.describe` adds `format`, `admission`, and for Ranked `round`, `front`, `ready`, `reserved`; top-level `maintenance: "DRAINING"`. `phase` keeps the five existing values.

## Runtime acceptance — 2026-09-24

The local three-client rig completed the scenarios in `dev/test-ranked.md`; evidence is retained in `/var/tmp/bf-jobs/closure-20260924/evidence`. Server changes are shipped in mod v1.25.2 / pack v2.46.3.

- Ready cancellation, duplicate/stale round packets, newcomer deployment denial, two independent rooms, and training without a room slot passed. `ready-cancel-current.json` records cancellation and a valid restart.
- Living and dead reconnect preserved team, inventory and respawn restrictions. Reconnect after a map transition uses the new round. `reserve-expiry.json` covers expiry after 212 seconds; `post-final-reconnect.json` covers the closed campaign and denied join/deploy.
- DRAINING allowed full multi-map campaigns to finish and stopped Casual after its current round. New rooms were refused. `draining-presence-fixed.json` records the public admission state.
- `emergency-interrupt.json` covers missing/expired confirmation, a valid interrupted result without a winner/MMR, and replay denial. `final-interrupt-race.json` records natural campaign b3b202f5-38c3-4152-a51c-3e5bc717ab20 followed by confirmed interruption: the saved final SHA stayed unchanged and SQLite contained exactly two rating changes, one per player. The cleanup command still broadcasts its generic interruption notice for an already finished room; it does not overwrite the completed outcome.
- `proxy-switching-reconnect.json`: a real Velocity + Limbo stack admitted the returning UUID during DRAINING, then moved it to Limbo on a closed gate and kept it there after reconnect and automatic-return ticks. Authentication used locally signed test JWT/HMAC sessions; this is not a real Drasl account-login test.
- Website desktop/mobile screenshots and `native-launcher-maintenance.jpg` confirm maintenance visibility and an empty match list. The native launcher uses its real Rust HTTP/IPC path with a test identity and local endpoints. The local public maintenance export was supplied explicitly; controller publication of that export belongs to workshop acceptance.
- Independent statistics acceptance is recorded in `../statistics/PRODUCER.md`; noncompetitive and workshop playtest sessions do not change MMR.

Runnable checks: 71 admission checks, 23 front checks (lengths 1/2/3/5, edges and draws), server JUnit tests, six launcher status compatibility tests, and 16 website room/status tests passed.

## Operational limits

- `saveLife` is not used: dead state/respawn timer live in `GameManager` and are kept because nothing resets them on disconnect. A full server crash loses all reservations (in-memory).
- Disconnected seats block the start until reconnect or expiry.
- The ready screen reuses `MenuScreen`; old clients may retain their last countdown display until the next update. Server readiness remains authoritative.
- MMR eligibility and formula are documented in the statistics implementation; `/bf maintenance interrupt` does not enable DRAINING by itself.
