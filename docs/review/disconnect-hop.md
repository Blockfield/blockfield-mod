# Server connection callback ownership

Fabric networking can dispatch DISCONNECT from the Netty `Connection.channelInactive`
callback or the server's `handleDisconnection`; its addon emits the event once, on
whichever caller wins. `ServerThreads.run` executes inline on the server thread and
otherwise queues the entire listener body. Cleanup order inside a listener is unchanged.

All `ServerPlayConnectionEvents`, `ServerConfigurationConnectionEvents` and
`ServerLoginConnectionEvents` registrations in the server sources were inspected.
There are no other connection-event registrations or INIT listeners.

| Class | Event | Decision |
| --- | --- | --- |
| BlockFieldServer | JOIN, DISCONNECT | Hop: hub/room state; disconnect also clears grenade, knife, range, tab-list state and removes owned drone entities. |
| SnapshotStream | JOIN, DISCONNECT | Hop: observer identity map, stream leases and validation state. |
| ServerBodyPoses | JOIN, DISCONNECT | Hop: pose state and LR weak-map stacks. |
| CombatViews | DISCONNECT | Hop: intake identity map. |
| MedkitChannels | DISCONNECT | Hop: channels hash map iterated at END tick. |
| Workshop | JOIN, DISCONNECT | Hop: markup/game-mode/ghost state and activity's player-list read. |
| WorkshopAi | DISCONNECT | Hop: jobs/uploads/actions maps, rollback list and world edits. |
| TeamVoiceManager | JOIN | Hop: group assignment and player state. |
| AnimationResourceGate | CONFIGURE | Inline on Netty: Fabric requires task insertion during CONFIGURE, before vanilla queues JoinWorld. Only concurrent CONFIGURING state and a volatile immutable manifest are read. |
| AnimationResourceGate | configuration/play DISCONNECT | Inline: only concurrent maps and stable connection identity. |
| ParCoolPoseInputs | JOIN, DISCONNECT | Inline: only concurrent intent-map removal by UUID. |
| PackVersionGate | QUERY_START, DISCONNECT | Inline: concurrent pending map, thread-safe futures and Fabric's login synchronizer/sender. No main-thread game state. |

The queued cleanup keeps the original handler/player reference: it does not require
the player to remain in `PlayerList`. Map removals are idempotent; workshop job removal
only releases/enqueues rollback for a job it actually removed; drone cleanup finds only
still-live owned entities. Workshop activity already counts everyone except the exact
changed player, so its count is valid before or after vanilla removes that player.
JOIN retains its server-thread inline order. CONFIGURE runs on Netty event loops; deferring
`addTask` to the server queue can place the manifest after JoinWorld and skip READY.

`ServerThreadsTest` checks deferred execution, the execution thread and inline nested
ordering. `ConnectionDisconnectTest` invokes the actual registered SnapshotStream
listener from a worker during an open observer-map iteration, checks that the map stays
unchanged, then drains the task and checks cleanup and a repeated notification.
`AnimationAdmissionOracleFixture#offThreadConfigureQueuesManifestBeforeJoinAndFencesReload`
invokes the registered CONFIGURE listener off-thread with an undrained server queue, then
checks synchronous manifest insertion, READY completion and JOIN admission.
`DisconnectThreadOracleFixture` (server shard 1, T16b) closes a real localhost peer
socket and observes the transformed Fabric/Netty callback, queued cleanup and Fabric's
suppression of a second server-side notification. It uses no game stand or player client.

Verification: restoring only SnapshotStream's original DISCONNECT body makes the
registered-listener regression fail with `no mutation inside END iteration`. Restoring
the hop passes it. The full server unit suite passed 1,866 cases (zero failures/errors/
skips), along with both server oracle shards, `spotlessCheck` and `:server:lintJava`.
The bundled 13 GameTests also passed. The timing benchmark was excluded as required
by the task; no live stand, release or deployment was run.
