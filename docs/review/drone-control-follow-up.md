# Follow-up: authenticated manual exit

PR #7 review of `b072f24cc2dc9f20573848b996285d915d2dc1dd` identified a missing integration boundary: input had an authenticated path, but manual exit only had privileged lifecycle hooks. No runtime packet handler exists yet, so this is prevention of unsafe future wiring, not a claim of a reproduced live exploit.

`requestEnd(authenticatedOperator, sessionId, currentContext)` now permits manual exit only for the server-resolved operator and exact current context. Rejected/unknown/null/stale requests do not remove a session or refresh its inactivity lease. An old exit cannot terminate a replacement session belonging to the same player/drone. The common `end` path still invalidates identity before emitting cleanup events.

Route C2S exit requests through requestEnd on the server thread. Resolve the player UUID and context from the authenticated connection/game state, not client fields; the packet supplies the session ID only. Keep end/endOperator/endDrone/endContext/shutdown for trusted lifecycle events. Actual camera/audio/ticket cleanup must still consume returned End events and handle failure/retry.

Validation: Java 21 `javac --release 21 -Xlint:all -Werror` and the shared DroneControlSessionsChecks runner passed **60 checks** (45 existing + 15 manual-exit checks). The existing standalone script and JUnit adapter execute the expanded shared runner. Full Gradle/new-head CI must be checked separately; no live Minecraft, network or camera scenario ran locally.

This does not supply the separate public Fabric port, physics or real SBW integration. Private visibility, class stocks, registry IDs, runtime hooks and release/configuration remain unchanged. Keep the issue open and this PR draft.
