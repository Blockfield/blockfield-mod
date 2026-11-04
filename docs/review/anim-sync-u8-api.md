# U8 client presentation API and handoff

Contract: ADR4.1 §§2.2, 2.4, 3.1–3.6, 4.2, 5.3, 8.3, 9, 11 (row U8) and both errata sections.
Branch/worktree `fulla/as-u8`, `WS/.dev/worktrees/as-u8`, base phase23 `d2cb37ed`. U1 schema
moved to manifest 4 by the coordinated additions below; nothing else in U1 changed.

## Transport switch

- `NetworkHandler.SNAPSHOT_WIRE` / `VIEW_WIRE` are the ADR4 codecs directly (R10-OUT 3b
  removed the r10 migration codecs and their format byte; the exact bytes are unchanged). The
  server always sends the ADR4 stream. A legacy C2S view cannot be constructed: `CombatView`
  requires its receipt, and the intake's exact path is the only path.
- Consequence until U9 installs the manifest and client resource generations: every exact receipt
  is `manifest_unavailable`/`resource_unavailable`, the client sends no ticket for such frames, and
  hits use the labelled current-body fallback.
- New payloads: `AnimationEventS2CPacket` (registered), `AnimationProbeS2CPacket`/
  `AnimationProbeC2SPacket`, `AnimationAppliedC2SPacket` (registered). The two C2S ids join the
  combat RakNet channel so applied reports flush before the attack that uses their revision.

## Coordinated U1 additions (schema manifest 4)

- `AnimationStartReport.Victim.applications`: `List<Application(eventSequence, ObserverApply)>`,
  every capsule the observer applied after the seed's event prefix, node or not (missing clip,
  idle-layer stop, Create exit, expired actor). Strictly increasing sequence, inside the applied
  prefix. Wire: after the starts, `uvarint count` then `uvarlong sequence` + the ObserverApply
  fields. Layout digest `b0ccce27…`; `VERSION` stays 1.
- `ObserverReducer.template(event, apply, policy)`: the replay start of an application (the
  patch's layer and kind, policy domain, whole application bucket, u0 0). Both evaluators derive
  it from the delivered capsule; no client start is trusted.
- `ObserverReducer.Reduction`: the reducer as one running value (`step` per bucket, `apply` per
  capsule); `reduce` is that loop. The client presents every frame from it.
- Probe packets: `id uvarlong, serverSendNs i64, boundaryTick uvarlong, boundaryNs i64` and the echo
  `id uvarlong`. Codec bit tests in `AnimationPacketTest`.

## Server changes (U7 files, minimal)

- `AnimSyncValidation`: applications replace the per-start capsule loop (delivered capsule, frozen
  send/probe bound, template); a delivered capsule in `(seed prefix, applied prefix]` without its
  application is `unreported_application`. The applied-prefix high-water is keyed by target life
  (a respawn keeps its entity id but restarts its journal). `applied(...)` records first-report
  times and the observer's reported seed, activation, applications and A.
- `AnimSyncStream.probe/echo`: one probe per observer END tick before its capsules (latest
  START-tick boundary); echoes become `AnimationTiming.Probe`s.
- `AnimSyncStream.fold` (ADR4 §3.6): once the newest seed of a life is 20 ticks old, the reported
  presentation (bounded applications, every delivered capsule of the folded prefix reported) is
  reduced to the last whole bucket before the reported A and issued in the next batch as a seed
  naming its predecessor. Receipts on the successor validate exactly (`AnimSyncFoldTest`).

## Client pipeline

- `ClientSnapshotBuffer`: the exact pairing/lifecycle path; exact records keep the
  stamped identity, flags, raw f64/f32 placement and decoded checkpoint; connection/scene must not
  change without a restart. `frames(id)` exposes a live track's records by tick.
- `SnapshotResolver.exact(frames, R)`: S[ceil(R)] with its own old/current pair at p (integer R
  selects (R, 1)); a missing endpoint holds the newest earlier record, never stretches. View yaw
  follows `getViewYRot`, pitch the model lerp (they differ by at most one ulp at integer R).
- `CombatSceneClock`: R by the occupancy law, then A by `AnimationTiming.advance` once per frame, both
  Q16. A keeps its connection high-water across stream restarts and scene resets (rewarm).
- `ClientAnimationJournal`: probes and consecutive server boundaries; seeds (FULL) activate once A
  reaches their tick; capsules are deduplicated, wait for their named predecessor or a pending
  newer seed (bounded 64, labelled), and are applied in the frame that first presents them with
  `sW = max(acceptance, lower mapped application instant)` on the server's tick-time map
  (extrapolated at 20 ticks/s past the last boundary). Rows: active seed, reduced starts, every
  application, prefix digest, presentation digest; unavailable (labelled) without mapping, past the
  40-tick sent window, without resources or on a missing boundary. Reports activations and
  applications per revision plus a heartbeat every 10 buckets; adopts a fold only when it
  reproduces the running presentation node for node.
- `ClientCombatScene`: frame entry (`CombatSceneRenderMixin`, before `renderLevel`) advances R/A,
  writes R placement, steps the journal, installs driven presentations, sends AnimationApplied
  and then publishes the immutable receipt; a capsule applied during an active frame is a new
  revision with the same clocks. Tickets need the complete report of every player of the H batch,
  at most 32767 − 256 report bytes, or none is sent.
- `DrivenPresentation`: per driven remote, A endpoint partial for every render pass
  (`CombatDrivenRenderMixin` on `EntityRenderDispatcher.render`), A-domain native pairs from
  S[ceil(A)] (attack, swing, swim, age, death, fall-fly ticks, pose, swimming, main arm, stance via
  `setDriven`/`restore`, medkit), R collapsed (placement, facing), R walk triple
  (`CombatWalkScopeMixin`, the exact R walk triple) and R velocity (`VanillaDrivenScope`). The
  PA graph installs through `RemoteLrCompat.install` (attach before the first install, detach
  after clear; a lost view keeps that entity object native). ParCool installs through
  `RemoteParCoolPresentation` from the composed reduction (U7c).
- `ClientAnimationAssets`: U9's hook. `install(Generation(resourceGeneration, policy, tacz, lr))`
  for every activated generation; cleared on DISCONNECT, kept across JOIN.

## Evidence

Plain JUnit: client `ClientAnimationJournalTest` (rows replay through the server reconstruction for
node and no-node applications, causal hold/dedupe, pending-seed hold, timing map, unmapped and
window labels, fold adoption), `ClientExactStreamTest` (lossless decode, pairing, format and
connection identity, R sampling, clock A), `ClientExactSceneTest` (complete ticket, no ticket
without rows); server `AnimationPacketTest` (application and probe layouts, exact wire, measured
ticket bytes), `AnimSyncValidationTest` (no-node application exact, unreported/unsent vectors),
`AnimSyncStreamTest` (probe nonce/echo once), `AnimSyncFoldTest`.

Transformed (production client Knot, `SceneTimingOracleFixture`): `blockfield.u8-zero-tick-capsule-
install` (codec → task → 0/1/3 client ticks → frame at 30/60/144/240 FPS: installed at the
application frame's bucket below the event tick, visible in that frame, driven bits independent of
the client ticks, native twin visible too), `blockfield.u8-no-retroactive-revision`,
`blockfield.u8-capsule-causal-dedupe`, `lr.u8-lr-compat-capsule-lifecycle`. The fixture executes
the native order itself; `Minecraft.runTick`, GL and the network stack are not started.

## Open items

- **Native broadcast wrapping (U7/U8):** done by U8b ([handoff](anim-sync-u8b.md)): TaCZ/LR capsules
  seal at their native broadcast and carry its payload. Earlier state: capsules were sealed at END tick with the
  `native.unwrapped` marker; the native TaCZ/LR packet runs its side effects once on its own and
  driven geometry ignores it. The client fails closed on any other payload type. Wrapping the
  native broadcast needs U7 to seal at broadcast time and the client to dispatch the payload.
- **Native inputs still read live (U2/U8):** done by U8b (render-pass checkpoint scope). Earlier state: item-use arm poses, riding and climbing, elytra and
  riptide flags, frozen shaking and bed orientation; Create hanging phase keeps the observer
  Catnip clock (the fork hook is unpinned). These remotes are not equality evidence.
- **U9:** install `ClientAnimationAssets` generations and `AnimSync.manifest`; until then no exact
  receipt exists.
- **U5:** ParCool presentation and capsule wrapping of `ActionSynchronizationBroadcaster.send`: done by U7c
  ([handoff](anim-sync-u7c-parcool.md)); TaCZ/LR natives stay unwrapped.
- **U10/U11:** capture identity of driven frames (`BodyPartHitboxRenderer` still keys by entity),
  Iris/culling pass gates, live acceptance.
- Server CPU (report only, U7b): ~2.5 ms per player per END tick of capture now runs whenever the
  exact transport is on (default).
