# Follow-up: reject contaminated damage trials

Reviewed PR #4 at `f7924e07bf8879c6d6fab300e798e1d3f1c991c0`.

## Reproduced defect

With initial target HP 20: the owner deals 4 (HP 16), an excluded shooter deals 6 (HP 10), then the owner deals 10 (HP 0). The original model ignored the outsider callback without updating its HP baseline, and credited **20 HP instead of 14** to the owner. It also produced an apparently valid TTK for an attempt interrupted by another damage source.

## Correction and adapter contract

Current-attempt damage events are now validated and deduplicated before attribution. An actual HP loss from an excluded shooter or a null/unattributed source marks the attempt `contaminated`. It cannot complete, accept subsequent hits/healing or produce a valid trial until an owner-authorized reset. Earlier known observations remain immutable diagnostic data; they are not a valid final TTK/DPS. `Result.valid()` is false for contamination or bounded-event overflow, and consumers must check it before presenting metrics as a valid measurement.

A blocked attack with unchanged HP does not contaminate the trial. Stale-generation events remain ignored and cannot invalidate a reset attempt. Null sources allow the trusted adapter to report applied environmental damage instead of crashing or silently attributing it to a later shooter. The adapter must route every actual HP change in server order, including damage from outside the allowed roster; ignoring such an event before calling this model would recreate the attribution gap. Damage prevention and PvP consent remain separate policies.

The old outsider test supplied HP 10 but later assumed the target still had HP 20. It now models a genuinely blocked attack with unchanged HP 20, alongside explicit contaminated-attempt regressions. No failing case was merely removed.

## Verification

Java 21 compilation with `--release 21 -Xlint:all -Werror` and the shared DamageTrialChecks runner: **52 checks passed** (34 existing + 18 added). A standalone reproduction failed against the exact original blob before the change. The existing standalone runner/JUnit adapter pick up the expanded checks.

No real target, TaCZ/SBW damage adapter, training world, UI or multiplayer rig was run locally. The result contract now has an additive contamination flag; future adapters/UI must honor it. Full build/new-head CI must be checked separately. Keep the feature issue open and PR draft; no production/runtime configuration, release or deployment changes.
