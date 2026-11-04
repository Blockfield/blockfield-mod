# Room isolation review

Refs release issues #3, #5, #6 and #7. This is a small runtime prerequisite, not a complete gameplay feature.

## Changes
- Team capacity reads the owning room's connected participants, not a server-wide scoreboard team. Reserved slots still require the separate Ranked adapter.
- MatchWorld.contains requires a real opened battle world; the hub fallback of levelOr is not deployment.
- Room.isActiveCombatant requires membership, GAME, the actual match world and no recorded pending-death state.
- The server and campaign death paths do not record a waiting/hub death as a match death. Cross-room killers are not credited.
- Closing a room clears offline members' team/game/boundary state as well as online members.
- Legacy TotalPlayers counts distinct non-null UUIDs rather than archived team segments.

## Do not merge duplicate policy implementations
During preparation, independent focused drafts became available: mod PR #2 (ConsentPvp), #3 (CampaignAdmission), #4 (DamageTrial), and modpack PR #1 (workshop controller). The earlier parallel session classes and their harness were removed from this PR's final tree. Use the focused drafts instead; no competing policy engine is retained here. The temporary review workflows are also absent from the final tree.

## Verification
The implementation before removal of the redundant policy classes passed GitHub Actions run 34760483981, including ./gradlew --no-daemon build and 17 rig regression tests. The narrowed final PR must pass its own current-head CI; do not substitute that older successful run for its result. Added regression tests cover room-local team counts and real-world-vs-hub fallback behavior.

Local: `just gradle --offline build` and `python3 -m unittest discover -s dev/rig/tests -v`.

## Live acceptance still required
Use the isolated dev/rig, never a production world. Two rooms sharing RedTeam must not consume each other's capacity. A member of a live room waiting in the hub or respawn room must not lose match tickets, receive kills or have inventory cleared through the match death handler. An actual battle death must still record exactly once and start the normal respawn path. Close a room with one disconnected member, then reconnect and confirm no stale team/class/death state. Review same-room delayed-projectile trades separately; this patch is not the consent/source-generation damage integration.

No new PvP command, ready UI, range map/targets, stats outbox, MMR, kit restoration, release, deployment or production migration is included. These are unimplemented companion work, not merely unrun tests.
