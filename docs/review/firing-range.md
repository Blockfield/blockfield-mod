# Firing range — damage measurement slice

Refs Blockfield/blockfield-releases#5. This PR implements DamageTrial, not a playable firing range. Map, world lifecycle, class/kit transitions, targets, UI and runtime damage adapters are still unimplemented.

`bash dev/test-damage-trial.sh`: Java 21, 34 checks. Existing JUnit executes the same checks through DamageTrialTest; full mod build and multiplayer were not run locally.

## Metric contract

Create one trial per target/lane, with the authenticated owner and explicitly allowed shooters. Feed actual HP AFTER the entire armor/absorption pipeline, not nominal weapon damage. The adapter must observe every HP change in server order, including heal events. Do not synthesize shield absorption or limb/head damage from an unrelated number. Reset when changing armor, target life or max HP; old attempt keys are rejected. Only the owner can reset via this API. The gameplay layer must separately authorize administrative override.

Blocked/zero HP-loss hits do not start the timer. Deduplicate trusted hit event IDs, including delayed repeats. Cumulative HP loss includes damage restored by healing, excludes overkill, and is broken down by shooter. TTK is last-hit minus first-hit ticks when the target reaches zero. A one-shot/same-tick kill has TTK=0 at tick precision; displayed DPS uses at least one tick and exposes damageWindowTicks=1 rather than Infinity. Damage-window DPS does not claim sub-tick precision or wall-clock timing under server lag. UI must display the measurement basis.

Participants and event storage are bounded. At the event limit the trial is marked overflowed/incomplete and stops accepting measurement events; the game adapter must display that state, not a complete result. This does not stop the world's damage pipeline. Immutable result snapshots survive target reset. There is no MatchTracker/killstreak/MMR call in this component.

## Remaining delivery

- Open a dedicated Fantasy training world OUTSIDE the disposable `blockfield` namespace and room registry, protect it from MatchWorld.cleanupStale, cap loaded worlds and temporary entities. No arena world/coordinates are fabricated here.
- Add server-authoritative entry, kit/class selection and inventory-safe exit through the existing menu, without globally unlocking ClassRestrictionManager or moving training ammo into lobby chests/matches.
- Implement actual stationary/moving/firing target entities and compare the damage/armor/headshot pipeline against a real player. Armor stands alone do not establish equivalence.
- Add distance lanes, gongs, arsenal and vehicle/drone areas through a verified map package.
- Use the common ConsentPvp implementation from PR #2 rather than adding a second consent system. These measurement sources are independent; no branch dependency is required for the tests.
- Prove training doesn't consume max_rooms, award killstreak supply or enter the combat statistics producer, including replay/import.
- During workshop mode the firing range is unavailable; never start a second full backend to keep it open.

No deployment, world changes, class unlock, unlimited kit distribution or production enablement occurs in this PR. Full target/map/gameplay acceptance in the issue remains open.
