# Consent PvP — policy implementation, not enabled gameplay

Refs Blockfield/blockfield-releases#7 and the shared consent requirement of #5.

Implemented: OFF/ON/DISABLING, configurable quiet interval, accepted-shot and applied-hit activity, expiry and generation-bound attack tokens, independent hub/range contexts, reset on re-entry and disconnect, third-party participation without duels. The caller supplies cooldown ticks; 200 ticks in tests is the proposed ten seconds, not a hard-coded gameplay setting.

`bash dev/test-consent-pvp.sh` compiles with Java 21 and runs 32 checks without Minecraft/Gradle downloads. The existing JUnit task runs the same checks via `ConsentPvpTest`. All state changes must run on the server thread. Use a monotonic long tick counter; do not pass a wrapping integer tick count or wall clock. Tokens are trusted server-side objects, NOT network authorization credentials.

## Integration still required before enabling

This PR intentionally adds no `/pvp` command, menu button, damage mixin or production configuration. A tested policy is not proof that TaCZ/SBW damage and side effects are intercepted. Do not label the feature playable merely because these unit tests pass.

1. Resolve real hub/range/battle activity, including a room member waiting in overworld and MatchWorld's overworld fallback. Call enter/leave on every context generation change.
2. Share one policy between hub and range; issue attack tokens only on actual accepted actions. Attach the token to bullets/mines/drones at creation, never reconstruct it from the owner's current generation at impact or after reload. Unknown/untracked player attack sources must fail closed in safe contexts.
3. Check permission before damage AND knockback/fire/effects; recordDamage only after a permitted hit actually took HP. Reuse Hub regeneration; blocked hits must not update LAST_HURT.
4. Wire server-validated command/menu and status indicators, including observers and pending-disable countdown. Never accept another player's UUID or client-supplied Attack object.
5. Implement inventory-safe hub defeat/respawn separately from Hub.send and match deaths. Preserve current chest contents/placement/refill, spent ammunition, Accessories and cursor/container transactions. Clear lobby gear before match deployment.
6. Run the issue's two-client TaCZ/SBW/AoE/vehicle/old-projectile matrix and verify no lobby/training rewards or match ticket loss.

No data/world edits, kit changes, duels, release or deployment. Respawn opt-in/protection and voluntary combat-exit penalties remain explicit product decisions; no penalties are invented here.
