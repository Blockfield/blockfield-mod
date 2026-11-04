# Lobby exit regression (releases#12)

Issue: https://github.com/Blockfield/blockfield-releases/issues/12

## Scope

`Hub.leave` previously called destructive `Hub.send` unconditionally. Cancelling VOTING/PREPARING while still in the overworld now removes room membership and closes UI without erasing inventory, accessories or the cursor. The decision is captured before membership is removed. There is no inventory snapshot, chest copy or later restore that could duplicate items.

Joining a VOTING room had the same bug one step earlier: `CampaignMode.onPlayerJoin` ran `enterVoting` → `Hub.send` 20 ticks after the join, so the hub inventory was already gone before any leave. A joiner who is still in the overworld now only gets the vote menu; `enterVoting` keeps stripping gear for players coming back from a round, and PREPARING still clears everything before deployment.

`Hub.send` remains destructive for actual returns from combat, respawn dimensions, GAME and MATCH_END. Existing match preparation and successful deployment still clear lobby gear. This change does not restore gear already discarded by a completed preparation/deployment transition.

Delayed initial-login cleanup has a per-invocation ticket. Explicit room join/create/leave and disconnect invalidate it, so login -> join -> leave cannot turn an obsolete login task into an inventory wipe. This is not a general rewrite of every campaign/team callback; same-room rejoin and round-generation behavior still require the live checks below.

## Automated checks

Run `just gradle --no-daemon --max-workers=2 :server:test --tests '*HubLobbyExitTest'` and then `just build`.

The entry-point tests exercise actual Hub methods with mocked Minecraft/network boundaries: both waiting phases, destructive phases, non-lobby worlds, a state change during removal, delayed login after join/leave, disconnect and replacement login callbacks. Mocks do not establish real container synchronization, world loading or client behavior.

## Required live acceptance

Use the isolated `dev/rig` environment, not production. Compare the unmodified base and this branch.

1. Put distinguishable stacks in inventory, armor, offhand and the rig; move the tablet and put a non-tablet item in hotbar slot 8. Leave voting at 1 second remaining. Check counts and components immediately and after the other participants start; the leaver stays in the lobby and receives no loadout or deployment UI.
2. Repeat through the menu `leave` action (there is no `/bf leave` command), with a chest/rig open and a carried stack. Vanilla close must return the carried stack normally (or drop it if the inventory is full); it must not erase or duplicate it or copy the chest inventory.
3. Test rapid login -> join -> leave within the queued login window; repeat with disconnect/reconnect and two players.
4. Leave -> rejoin the same room and another room; close/reuse a room number. Check stale campaign/team callbacks and report any remaining issue rather than treating this patch as a general lifecycle fix.
5. Do not leave: verify the other player starts normally and lobby infinite ammo is removed by the existing preparation/deployment flow.
6. Leave an active match, MATCH_END and the shared respawn dimension. Combat gear and cursor must be discarded and the normal lobby tablet/empty rig restored.

Status (rig, 2 clients, before = 1.18.0 main, after = this branch): join VOTING keeps items (before: all wiped); leave at 43.7 s of a 45 s vote keeps items while the other player deploys; no UI for the leaver; deployed leave still strips gear; a carried stack returns to the inventory (5/5, no drop or duplicate); login -> join -> leave inside the login window keeps later items. Before 4/8, after 8/8. Still unchecked: an open chest/rig during leave, same/other room rejoin, room number reuse (steps 2 and 4).
