# Roll constructor reachability in parallel capture

Base: `906e8344`. The intermittent proof failure reported `Roll.<init>` from
`AnimSyncParallelProof.checkWorkerStack`; the supplied log has no worker caller
stack, so it cannot identify which capture call was sampled. Static inspection
finds a deterministic forbidden path:

- `ServerBodyPoses.compute` → `AnimSyncCapture.capture` → `freeze` →
  `ParCoolCheckpoints.definitions` → `duration(ROLL)` → `new Roll()`.
- The same helper constructs `Tap` for `duration(TAP)`.
- `freeze` and `AnimSyncParCool.canonical` also call `neutral`, which reads each
  duration twice (the input value and its definition). `familyIdentity` reads
  the same durations when building the compatibility manifest.

## Verdict and fix

Treat this as a C1/C3 reachability defect, rather than weakening the forbidden
frame rule. In the pinned bf7 artifact (SHA-256
`9a0ec4e2a5ae2477884cf4a6663afed9d7b55a259e4ef4bc05bb428c24e367eb`), `Roll` and
`Tap` constructor bodies only initialize instance fields, and their duration
getters are exactly `bipush 9; ireturn` and `bipush 8; ireturn`. This does not
prove construction is globally pure: both classes' `<clinit>` call
`BehaviorEnforcer.newID()`, whose nested `ID.newID()` increments a shared static
counter. A warmed constructor need not mutate shared state; this finding is
not evidence of persisted player corruption. The helper need not rely on any
class-initialization ordering at all.

`duration` now returns the pinned bf7 scalar constants, like the existing native
formula ports. `Vault.MAX_TICK` is already a compile-time constant. Definition
bytes, config references, neutral rows and manifest identity remain unchanged.
The regression compares these scalar results with the installed native getters,
so a future dependency update cannot silently change them.

## Other construction paths checked

`ServerBodyPoses.classify` and `ParCoolPoseInputs.capture`, `sample` and
`accepted` read `Parkourability.get`, which
can lazily create the live attachment and its entire action registry. Each entry
checks the server/main thread before that read; the END raw phase calls `sample`
before forking. A failed raw read marks the capture unavailable, so the worker
cannot fall back to a live attachment read. No stream build/encode path calls
these live helpers.

The victim evaluator intentionally has a separate permitted path:
`AnimSyncEval.pose` → `AnimSyncParCoolEval.scratch/inputs` →
`ParCoolFrame.scratchAttachment` → `new Parkourability` →
`Actions.constructActionsList` → every action constructor; `scratchAttachment`
also restores every row through `ParCoolActions.materialize` and reflective
constructors. These objects are fresh geometry scratch, never attached to a
player. A retained JFR sample independently confirms this evaluator path;
it does not recover the overwritten recording of the original failure.
`ParCoolGraph.materialize` constructs server animator ports, not native action
objects. Registry class literals and native enum values do not construct actions.
The existing proof still allows evaluator scratch and rejects native ParCool
frames under capture or stream; no exception was added.

## Deterministic regression

`AnimSyncParallelReachabilityTest` forces one main and one worker lane with a
timed barrier. Each lane installs thread-local constructor hooks for both Roll
and Tap, then calls `duration`, `definitions`, `neutral` and `familyIdentity`.
It asserts native-equivalent values and zero constructor invocations. This
exercises the exact helper reached by capture without relying on JFR sampling.

## Verification

The new regression failed on `906e8344` with “checkpoint constructed Roll:
expected 0, actual 5”; after the fix it passed. The forced barrier verifies that
one task actually ran on a worker and one on main. All 64 `AnimSyncParallel*`
unit cases passed. Server oracle shard 1 (the shard containing
`AnimSyncParallelOracleFixture`) passed in full; the workload recorded 469 worker
samples / 15,226 frames / zero forbidden frames. `spotlessCheck` passed.

Command (Temurin 21.0.12+8, through the workspace `gradle-slot`, bounded by
`timeout 2400`, `SLOT_WAIT=1800`):

```sh
./gradlew --no-daemon --max-workers=2 :server:test --tests '*AnimSyncParallel*' :server:oracleShard1 spotlessCheck -x :server:runGameTest
```

The baseline regression command also ran Fabric's automatic `runGameTest`
prerequisite; subsequent focused checks excluded it. No full gate, benchmark or
workspace gameplay stand was run. Original failure log, pinned native bytecode,
before/after test XML, command logs and the successful proof JSON are retained in
workspace `.dev/coord-20261011/plans/anim-sync/evidence-roll-reach/`.
