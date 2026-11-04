# Drone control session identity — private integration prerequisite

Refs Blockfield/blockfield-releases#4. **This is not the Drone Warfare Fabric port.** It implements only Blockfield-side game-session identity and stale-input/lifecycle policy. No upstream source/resources were copied and no public addon repository was created by this PR. The approved separate public fork remains required; do not publish private Blockfield sources to satisfy it.

`bash dev/test-drone-control-sessions.sh` compiles Java 21 with -Xlint:all -Werror and runs 45 checks. DroneControlSessionsTest exposes the same checks in JUnit. Full Gradle, actual SBW packets, camera, dedicated server and two-client E2E were not run locally.

## Implemented policy

One active session per operator and per drone, bounded total sessions, server-generated session UUID, dimension/activity/round context, strictly increasing input sequence, normalized finite virtual-game input, explicit gameplay/training/playtest purpose, scout/combat distinction, idempotent termination and bounded inactivity expiry. Ending the old session invalidates its input before external cleanup; an old end/input packet cannot affect a newly opened session. No entity/player objects are retained across respawn.

Returned End events are immutable facts for an adapter. **The model does not itself restore a camera, remove cables, release chunk tickets or stop sounds.** The adapter must do those operations, retain/retry failed cleanup independently and send generation-bound end notifications to clients. A repeated end() intentionally returns empty; it is NOT an automatic cleanup retry queue. No persistence through server process loss is provided by this in-memory registry.

## Required integration and remaining port scope

- Keep the public upstream port separate from private gameplay integration. Pin actual upstream/SBW commits, review code/resource/dependency permissions, preserve attribution and provide clean public build instructions before copying/publishing assets. Public fork/source and release work are still unimplemented.
- Existing DroneClassManager distinguishes superbwarfare:drone and superbwarfare:scout_drone; continue class stock/replenishment instead of creating unlimited drone issuance. The policy's scout guard must be applied to real equipment/fire paths; it does not change ScoutDroneEntity by itself.
- Before begin AND on input, resolve current entities, authenticated owner and live gameplay context on the server; never accept client-supplied ownership/world/round. Route accepted input into the actual simulation, not client final position or damage. Link loss and death events must invoke the common end path.
- Hook operator death/disconnect, drone removal, dimension/round changes, manual exit and shutdown. Control must not move/hide the operator's body. Reconnect must start a fresh authorized session rather than resurrect the old camera token.
- Client cleanup must target the current player after respawn, clear keys/audio/overlays, restore camera and view angles, and ignore stale end packets for a newer session. DroneCamera.pingSource currently only chooses a ping-ray source; it is not a camera lifecycle API and is unchanged here.
- Implement/port Angle/Acro game physics, battery/load, signal/interference/fiber, chunk budgets, interpolation, cable cleanup, contact game collision and sound. None is claimed as delivered by this identity model.
- Reuse consent policy from mod PR #2 for hub/training attacks; exclude non-gameplay results and awards at every event/import path. Purpose metadata alone does not enforce downstream reward isolation.

No hardware-control code, real-world flight tuning, new registry IDs, packet injection into upstream, modpack changes, release, merge or deployment. The full feature and licensing/resource audit remain open in #4.

## Note (2026-09-15): this class was not the integration point actually used

The in-game session/sequence anti-replay fix for `VehicleMovementMessage`/`MouseMoveMessage`/
`DroneFireMessage` (Blockfield/blockfield-releases#4) was implemented **natively inside
superbwarfare-fabric** instead of by wiring this `DroneControlSessions` class in. Reason: SBW's
repo has no build dependency on blockfield-mod (and per the owner's 2026-09-14 decision SBW is
becoming the *public* half of this feature, blockfield-mod stays private), so a public repo
cannot compile against this private class. SBW now carries its own much smaller equivalent
(`DroneControlSession` in `superbwarfare-fabric/src/main/kotlin/.../control/DroneControlPolicy.kt`)
scoped to exactly the anti-replay problem, without the round/activity/purpose/inactivity-expiry
concepts this class models (SBW has no round concept to bind to).

This class remains accurate as a private-integration design reference and is still exactly right
for anything Blockfield-side that needs session identity bound to a *round* (drone kill-streak
rewards, training/consent policy, etc.) — see `DroneClassManager` for the existing example of
that kind of round-bound bookkeeping. It is just not, and should not become, a dependency of the
public SBW repo.
