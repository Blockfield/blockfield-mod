# STANCE-EASE c2c native-equality regression

At `2c2d44be`, the saved mixed fragments fail in `vanilla/crouch-walk`
(142 samples, max 0.450975274 m) and `tacz/pa-fire-crouch`
(142 samples, max 0.480849774 m). Each case contributes 9/18/43/72 failures
at 30/60/144/240 FPS. Every sampled frame fails; all six bones differ.
The shared fixture sets CROUCHING with a reflective default stance:
`age=0`, `eased=false`, zero crouch triple. These are non-eased samples,
not ticks within the eight-tick transition. The native path correctly keeps
the boolean crouch (head.y=4.2, body.xRot=0.5, arm.y=5.2, leg.z=4);
the projection incorrectly used coefficient 0, including the render offset.
The regression entered in c2 (`916b6516`): it replaced the boolean-only Inputs
constructor (still used in c1) with an unconditional `stance.crouch()` read.

`VanillaAnimSync.project` now matches the live freeze: medkit forces 1,
eased stance uses its replicated coefficient, otherwise the native boolean wins.
No renderer, wire schema, cohort, sample schedule or 1 mm threshold changed.
The existing transformed stance fixtures now explicitly check the zero-triple,
non-eased crouch on both JVMs (native bones; projected bones and -0.125 offset).
The native fixture sets crouching from the requested pose, leaving suppression
to the production mixin rather than pre-clearing it whenever age is below eight.
The server regression failed before the fix: expected 1.0, received 0.0.

Verification: `just format`, `just check`, `just test` passed through gradle-slot;
all four client and two server oracle shards ran, including both new regression checks.
The equality report passes all 7,952 mixed samples bit-identically, max error 0 m;
vanilla 1,704 and TaCZ 568 samples have zero beyond the unchanged 1 mm gate.
The wire gate passes using pinned evidence (the existing matrix runs predate HEAD).
Logs and before/after reports: `build/anim-sync-oracle/verification/stance-c2c/`.
No live stand, integration, release or deployment was performed.
