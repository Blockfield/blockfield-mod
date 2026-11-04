# U5 ParCool implementation checkpoint

Status: **items 1–4 of the coordinator's resume list complete with fixtures; integrated
into `fulla/phase23`** (see the fourth-resume section). Item 5 (remaining port-vs-native
model differentials) and the wiring owned by U7c/U8 remain, listed below. Branches are
`fulla/as-u5c` in the mod and fork; worktrees `WS/.dev/worktrees/as-u5c` and
`WS/.dev/worktrees/as-u5c-parcool`. Never resume the old `as-u5` worktree.
No exact ParCool cohort is enabled, and no release, tag, deployment or live stand ran.

## Fourth resume: production reducer, producers and adapters (2026-10-16/17)

Started from `bead0b3d`; merged phase23 `d2cb37ed` in `c2a8c863` (clean, both fixture
registries kept). Increments, each pushed: `28843ffb` production `ParCoolStages`;
`ed7797d1` native/cross-JVM pins; `dedb5ece` accepted patches, target intent sync and
canonical yaw/Kong production; `51dafb0a` Dive leg on the 60 Hz grid; `60d5e71c` current R
limb input. API and the exact U7c/U8 calls: [anim-sync-u5-api.md](anim-sync-u5-api.md).

Coordinator items: (1) `ParCoolStages` is the production `Stages` with recorded holds
(older sample named explicitly; a later or missing sample fails). (2) Hang R grid and Dive
A grid are produced at boundaries; Dive host entry/exit topology, current Dive root pitch,
SkyDive start/exit pitch and the six-tick fade are integer-boundary decisions. The singular
sample audit found no ParCool formula singular at fraction 0, so no limit substitution is
needed (fixture `production-zero-fraction-limits`); nonfinite inputs still fail validation
on both sides. (3) `ParCoolStages.canonical` folds placement yaw at zero partial and steps
Kong over the folded endpoints; the zipline integer step runs in the reducer tick. (4)
`ParCoolIntentC2SPacket` carries the target's own keys; QuickTurn's flag is server-owned;
the five selections, removal, options and passive keys follow ADR4 order; observer
successors are PROJECTED_SUCCESSOR when a predecessor node exists.

New fixtures (transformed, production mixins): server+client `production-stage-replay`
(95 boundaries, 12 acceptances, checkpoint import, five bounded folds, recorded holds,
fail-closed inputs, producer fold/Kong/Hang checks; identical bytes in both JVMs via
`dev/check_parcool_oracles.py`); client `production-stage-native-primitives` (18,000
native removal cases, 64 selection cases, 486 counter cases, 64 passive steps, 81 Kong
yaw pairs, 18 zipline and 16 Dive intent cases), `production-dive-topology-ab` (native
renders vs boundary topology; start pitch and grid recorded as A/B), `production-accepted-
patches` (all 27 actions, start/finish, empty/occupied Animation, Dive water, QuickTurn
local semantics; 111 cases), `production-dive-leg-fps` (grid leg term, five render
histories), `production-limb-current-r`, `production-zero-fraction-limits` (180 cases).
Server JUnit adds the intent codec and the port leg hook.

Remaining (not in items 1–4): native-vs-server-port root/model differentials for the
animators not yet covered (zipline is), Dive children and passive through a typed-frame
server evaluator (U7's evaluator does not exist yet: `AnimSyncEval` still refuses an active
ParCool animator); FPS/culling/pass permutations through the full mixed renderer (U10).
U7c: replace `AnimSyncCapture.parcool`, commit folded angles, pass the composed graph,
replace the legacy zipline caller. U8: observer projection/presentation wiring. U9b: fork
pin and pre-JOIN ClientInfo timing. Owner A/B items recorded: SkyDive start pitch, grid Dive
leg term, removal-before-selection timing, observer holds.

## Resume increment (2026-10-16)

Resumed from the coordinator's merge `4d8d7d3d` (checkpoint `c02959f9` plus
phase23 `bda5649c`), preserving both sides of the shared fixture registry.
The first merged-tree targeted ParCool/server-witness/client-oracle run passed.

New common `ParCoolGraph` captures and materializes all 26 registered animator
records plus both Dive children, constructor fields, inherited ticks, topology,
passive counters and all raw option masks using the frozen U1 schema. It binds
native client classes and server port classes without loading client classes
on the server. Native field names are pinned: JumpFromBar's native `MAX_TICK`
constant differs in spelling from the source port's `maxTick` (both 8).

New common `ParCoolActions` captures/restores all four counters for 27 actions
and the typed native visual inputs. Accepted constructor outcomes, config and
resource refs, stop outcomes, target intent and deterministic seeds must be
supplied explicitly by the authoritative journal. Missing/wrong/duplicate input
families fail closed. Materialization creates separate scratch actions and never
calls gameplay start/finish. It does not construct or install the full driven
attachment; zipline rope physics is deliberately not reconstructed.

New transformed `ParCoolCheckpointOracleFixture` exercises native constructor
combinations, root plus all six model-part outputs, twelve subsequent ticks,
Dive entry/exit interruption, passive continuation, 256 option values and all
27 action types. U1 encode/decode is part of the checkpoint round trip. Existing
`ParCoolReducerOracleFixture` adds all 16 target-intent masks against native
SkyDive counters and Kong's native atan recurrence across 49 R yaw endpoint
pairs. The server Kong port uses the shared primitive. FastRun/HWR retain the
native continuation field for the adapter while deterministic callbacks still
consume current R limb input. `ParCoolGraphTest` covers all server port bindings
and subtree replacement/collision behavior.

These are native restoration and reducer-primitive fixtures. They do not close
the server-port-vs-native family differential, complete deterministic projection,
FPS/pass/culling, mixed-renderer or final six-box gates. See
[API and precise boundaries](anim-sync-u5-api.md). Final command/fragment/source
hashes for this resume are recorded in `anim-sync-u5-fixtures.json`.

Resume validation: `just format`, `just check`, `just test` and `just java-check`
passed under the heavy lock with the pinned JDK. Client JUnit: 504 tests, zero
failures/errors, two existing skips. Server JUnit: 1,594 tests, zero failures/
errors/skips. All 13 GameTests passed. A subsequent targeted client oracle checks
constructor-time transforms and retains raw U1 wire bytes/model scalar bits;
it does not alter runtime code. The final manifest identifies that run separately.
There are 50 native constructor combinations, six constructor sample fractions
and 78 subsequent root/model comparisons per combination, plus passive coverage.
All 13 ParCool transformed fragments pass with actual mixin evidence. Native
SkyDive counters match 4,800 steps (all 16 masks); Kong matches 49 endpoint pairs.
The first resumed code checkpoint is `8eb42a2d949a7f513e48d197d28c37dcd24e4cd1`.
The fetched phase23 tip subsequently advanced to U4 `9f35782b`; the next
increment merges that tip into U5. No U3 schema-3 change was present in that
fetch. Final integration must fetch again.

## Canonical capture / scratch increment

Merged phase23 U4 `9f35782bf76c6ec62ba3dd7780fe0e15e47d0261` early in
`9c87be5c`, preserving all client/server fixture registrations. New
`ParCoolFrame` binds the typed graph and family checkpoint and materializes a
separate complete native action attachment. `ParCoolPoseInputs.capture` supplies
the post-tick server capture API: native counters plus retained authoritative
visual inputs, without rerunning any tick or reading client-only server defaults.

`RemoteParCoolPresentation` and five production ParCool mixins install a fresh
native Animation/Parkourability view for each invocation. Nested/exception
cleanup, local-player isolation, mirror tick/selection/removal suppression,
policy-only geometry getters and per-player A partials are covered by actual
transformed native calls. The real physics attachment remains unchanged. This
increment does not supply canonical input production, deterministic topology,
R-specific family adapters or transport activation.

New transformed fixtures: `ParCoolAttachmentOracleFixture` (client) and
`ParCoolCaptureOracleFixture` (server), both registered explicitly. The server
case preserves canonical Dive inputs despite a deliberately different native
server field and checks twelve counter continuations. The client executes the
actual getters, Animation pre/post and root callbacks, partial getters and
nested/exception exits. Shared U1 fixture values are in `ParCoolNativeValues`.
The fork is unchanged at `402eaa65`; its pre-existing wrapper status is preserved.

Code checkpoint: `7f8a4546`, pushed to `fulla/as-u5c`. All four full commands
passed after these changes: `just format`, `just check`, `just test`, and
`just java-check`. Client JUnit: 506 tests, zero failures/errors, two existing
skips. Server JUnit: 1,606 tests, zero failures/errors/skips. All 13 GameTests
passed. All 15 U5 transformed fragments pass, including both new fixtures with
actual production mixin evidence. Logs are `.cache/u5/capture-*.log`; hashes
and measurements are in `anim-sync-u5-fixtures.json`. This is still a partial
U5 branch, not integration or six-box equality evidence.

## Zipline and yaw increments

`c5a738372312a6df35a51ad0bd9268ef7280bd40` fixes restored zipline sampling:
typed old/current angle doubles already include the integer oscillator step,
so the server adapter suppresses the legacy callback's extra advancement in
restored scratch. It adds transformed client/server root/model fixtures and a
strict cross-JVM comparator (`dev/check_parcool_oracles.py`). All 5,460 raw
root/six-part float values agree across thirteen integer checkpoints, including
signed-zero gravity, idle hold, changed slope/acceleration and null/zero offsets.
Both sides verify repeats, reverse fractions, root/model order, two seconds of
30/60/144/240 FPS history and unchanged retained U1 state. Native integer tick
continuation also agrees after restoration. The focused server tests passed.

`ea5e8290` adds common `ParCoolYaw.apply` and a transformed native ordered-writer
oracle: 7,680 cases cover all 64 action-writer subsets, Cling facings, both
QuickTurn directions, four fractions and ten animator variants. The fold
preserves native old/current yaw writes and the final animator override. Hang
consumes an existing grid. The fixture obtains that endpoint from the native
Cling/Hang prefix; it isolates writer order and does not close retained Hang
grid production or composed trajectory A/B. Both code increments are pushed.

The final fetch still has phase23 at `9f35782b`, already merged in `9c87be5c`.
There is no schema-3 change in that fetch. The fork is unchanged at `402eaa65`.
The remaining list below is normative for continuation; no partial U5 is
integrated into phase23.

Final validation after all three increments: `just format`, `just check`,
`just test`, `just java-check` and the native/server comparator passed. Client
JUnit: 506 tests, zero failures/errors, two existing skips; server JUnit: 1,607
tests, zero failures/errors/skips. All 13 GameTests passed. All 18 U5-owned
transformed fragments pass (16 client, two server); the separate U10 shared
`player-hook-transformed` fragment also passes. Final logs are
`.cache/u5/final-*.log`; source/fragment/log hashes are recorded in the manifest.
The signature wrap applied by final Spotless is formatting only. No processes
or watchers are left running by this task. Worktrees and evidence remain for
continuation. No exact cohort, phase23 integration, release, tag, deployment or
live stand was performed.

## Third resume: bounded scheduler and seed checkpoint

Code checkpoint: `b57ed16aa5cb8e45ce4244d958521d75620ab7db`, pushed to
`fulla/as-u5c`. Phase 23 `7aeec56d` was merged first in `928c8731`.
Intermediate tested/pushed increments are `7300103d` (scheduler/seeds) and
`3a81c280` (application-prefix and negative-consuming-age refusal).
U1 manifest 3 is consumed without schema changes. U5 has no old two-argument
PA catalog call; no TaCZ/U3/LR source was edited.

`ParCoolProjection` now supplies bounded canonical replay and observer-only
replay through `AnimationResolver.replay`, complete start/graph validation,
immutable decision-journal capture, other-family isolation, phase-preserving
ParCool seed activation and U1 seed folding. Folded checkpoint and graph both
contain the projected ParCool values. Missing continuation/steps, future
application prefixes, negative consuming ages, regressing fold prefixes and
missing contributing passive proofs fail closed. A neutral row may have zero
starts: the unused typed passive key round-trips unchanged, with no sentinel.

Four new transformed fragments (three client, one server) bring the U5 total to
22. They cover eight bounded folds/160 native action-counter ticks in both
runtimes, all 26 native animators plus both Dive children (50 constructor cases,
24 scheduled integer ticks and six root/model samples per case), passive/foreign
anchor preservation and neutral/passive eligibility. Client/server native
counter continuation seeds are identical across all 2,923 encoded U1 bytes.
The existing zipline differential still matches all 5,460 raw float bits.

The passive gate is pinned in `.cache/u5/session3-passive-bytecode.txt` and a
native model fixture: bf7 starts falling at counter 14 and suppresses it while
Dive is doing; unused creative counters do not create a pose. The initial new
fixture failed because it omitted Accessories' mock Minecraft service. The
fixture setup was corrected; no production mixin or assertion was removed.
The failed log remains `.cache/u5/session3-neutral-test.log`.

Final `just format`, `just check`, `just test`, `just java-check` and the strict
cross-JVM comparator all passed on the pinned JDK under the heavy lock. Client
JUnit: 506 tests, zero failures/errors, two existing skips. Server JUnit: 1,616
tests, zero failures/errors/skips. All 13 GameTests passed. Final logs are
`.cache/u5/session3-verified-*.log`; the manifest records hashes and exact scope.

**Still partial:** production deterministic `Stages`, retained input/hold
production, Hang/Dive topology/grids/current pitch/limits, yaw/Kong/zipline
scheduling, intent/QuickTurn/selectors/removal/options and the remaining native
server-port/FPS/pass gates remain as listed under Exact continuation work.
The new native callback fixtures prove scheduling/seed machinery, not those
unfinished replacements. U5 is not integrated into Phase 23 and no exact cohort
is enabled. Fork remains `402eaa65251fa902ada674ed5d499eb21bef31a3` on
`fulla/as-u5c`; U9b owns its pin promotion and pre-JOIN ClientInfo timing.
U7b/U8 own transport/presentation wiring; U10/U11 own mixed/live/owner gates.
No release, deployment or live stand ran. Both worktrees remain for continuation.

## Earlier checkpoint files and evidence

Mod paths below are under `com/lunares/blockfield` in their respective source sets.

- `common/.../common/combat/ParCoolReducers.java`: rational Q16-to-60-Hz indices,
  checked retained-interval lookup, native Hang recurrence/rotation interpolation,
  native Dive factor and deterministic grid-increment leg sampling, integer
  double-precision zipline recurrence, target-intent passive reducer and immutable
  native TaCZ option reducer. These use U1 values, not another wire schema.
- `server/.../server/combat/ParCoolBodyPose.java`: separate A and R fractional
  arguments for scratch root/model callbacks; the existing zero-partial API stays
  available for the phase-2 caller until the complete U5 adapter lands.
- `FastRunningAnimator.java`, `HorizontalWallRunAnimator.java`: root and model use
  the current supplied R limb input without retaining the preceding model render.
- `KongVaultAnimator.java`: retained yaw-difference interpolation uses R partial.
- `DiveAnimationHostAnimator.java`, `RideZiplineAnimator.java`: reuse the common
  native scalar/oscillator primitives. Full deterministic host topology and moving
  zipline advancement out of the legacy sampling path remain unfinished.
- `ParCoolPoseInputs.java`: A-domain double y-speed pair overload, policy-forced
  server lean, exact native gravity attribute capture including zero/negative zero.
- `client/src/main/resources/META-INF/licenses/ParCool-LICENSE.txt`: license for
  the common formula ports now included in the client artifact as well.

Fork paths are under `src/main/java/com/alrex/parcool/`:

- `common/info/ClientSetting.java`: local geometry policy entries reread their
  live getters; other local entries and decoded remote settings remain snapshots.
- `client/animation/Animator.java`, `common/action/Action.java`,
  `client/animation/PassiveCustomAnimation.java`: direct counter read/import hooks,
  without start/stop/tick or gameplay side effects.
- `common/attachment/client/Animation.java`, `client/ClientRenderProcessor.java`:
  remote-only driven mode, replacement of materialized scratch, suppression of
  mirror animation/frame option advancement/removal, and a separate driven slot
  protected from U9a's direct native animator assignment. Native physics/lifecycle
  and the local player remain outside this mode. These hooks are not yet wired
  into Blockfield's presentation path.

## Fixtures and review findings

- Client `ParCoolReducerOracleFixture`: real bf7 Hang callbacks match 1,200
  virtual recurrence steps bitwise; native zipline tick matches double bits for
  12 ticks and eight restored continuation ticks; 30 native Dive factor cases
  include the native singular outcomes; all 26 constructed registered animators
  plus null/passive exercise the actual native TaCZ option listener.
- Its Hang/Dive fragments retain Q16 times, raw native trajectories and selected
  deterministic scalar trajectories at 30/60/144/240 FPS. They are offline scalar
  A/B assets, not final model renders or owner visual approval.
- Client `ParCoolReadLayoutOracleFixture`: native geometry/action-render getter
  closure visits 280 methods and excludes every omitted cooldown/physics field
  listed in fidelity P2-1. QuickTurn's two real render reads map to
  `AcceptedStart.direction=startAngle`, `flag=server-owned turnRightward`; its
  native payload supplies only x/z, so acceptance still needs the canonical flag
  producer. Vault variant/duration mapping is recorded in the fragment.
- Its complete 302-class native-jar scan includes the declared-only dead class
  itself and finds zero ExaggeratedSideDodge construction sites; the live registry
  has 26 entries. SkyDive `maxCount=8` and all three passive fields are executable
  assertions (fidelity P2-6/7). Full subtype materialization is still pending.
- Client `ParCoolPolicyOracleFixture`: actual U9a transformed getters force stored
  false to true, fresh native ClientInfo encoding/decoding carries true, and
  disconnect restores false. It deliberately records bf7's stale pre-JOIN
  snapshot as a hazard, not a policy-cohort equality pass.
- Server `ParCoolReducersTest`, `ParCoolFractionalSampleTest`, and the ParCool
  sections of `ParCoolNewInputsTest`/`PosePortWitnessTest`: rational endpoints,
  interval/overflow failures, singular behavior, double continuation, passive
  intent/options, native gravity, policy lean and current-R root determinism.
  The old F7 witness now asserts ADR4's current R limb rather than the replaced
  previous-render behavior. Other witness families are unchanged.
- Fork `ClientSettingPolicyChecks` and `AnimationReplayChecks` are called by its
  existing regression runner: in-memory snapshot/wire policy change and restoration,
  counter import/next tick, local-player refusal, driven tick/selection suppression,
  native-slot isolation. `lintJava regressionCheck` passed with a 2 GiB heap.

The new client fixtures are explicitly registered in `TransformedOracleSession`.
They use the U10 runner, production intermediary namespace, Loader 0.19.3, actual
production mixins and `requireConfig`; vanilla reflection uses `OracleReflection`.
Per-run fragments are in `client/build/anim-sync-oracle/fragments/parcool.*.json`.
`anim-sync-u5-fixtures.json` records final validation and their measurements/hashes.
The runner's pre-existing unavailable music artifact is not equality evidence.

Final checkpoint validation on Temurin 21.0.12+8/Linux amd64: `just format`,
`just check`, `just test`, `just java-check` all passed under the heavy lock.
Client JUnit: 498 tests, zero failures/errors, two existing opt-in skips; server
JUnit: 1,562 tests, zero failures/errors/skips; all 13 GameTests passed. All seven
new ParCool transformed fragments passed with actual successful mixin evidence.
The earlier full run caught the legacy F7 witness; the verified rerun uses the
current-R assertions described above. Logs are `.cache/u5/*-verified.log`.

The fork worktree reports an EOL-only `gradlew.bat` status entry from its checkout
attributes; its raw bytes equal HEAD. No wrapper change was made or committed.

## Producer details and dependencies

Server lean deliberately does not trust a pre-JOIN ClientInfo snapshot. The fork
fix makes a snapshot created before policy installation use the overridden getter
on **later** encoding. It cannot change an already-sent packet. U9 must install
policy before the first admitted ClientInfo encoding or resynchronize afterward,
promote the verified fork pin, and include its hooks/read layout in compatibility.
U9a source files were not changed by U5.

`ZiplineInput.valid` is native rope presence, not proof that the remote animator
is unavailable. Native `RideZipline.onStart` fills the offset, while only the local
physics path acquires a rope; the remote animator ticks the synced acceleration,
slope and gravity without reading rope/valid. The common scalar reducer therefore
does not reject a complete input merely because rope presence is false. Missing
input still cannot be sampled. No U1 field or validator was changed.

`PassiveInput.fall` supplies native `isFallFlying`; `flying` supplies abilities
flying. Target-originated intent transport is not implemented yet. The existing
phase-2 motion-inference path remains, and is not exact-cohort evidence.

## Exact continuation work

Third resume starts from `e9a73385`, merges Phase 23 `7aeec56d` in `928c8731`,
and adds `ParCoolProjection` scheduler/seed primitives (see the API's "Third
resume" section). Canonical replay, observer-only bounded replay and U1 seed
fold/rebase infrastructure now exist. **The production deterministic `Stages`
implementation is still missing**; fixture native callbacks are not a production
substitute. Do not mark item 1 of the coordinator's resume brief fully closed or
activate U5. Continue in these same `as-u5c` worktrees; no fork edits this resume.

The exact remaining order is: implement the production stage/input resolver and
recorded holds using this scheduler; Hang/Dive intervals and Dive topology/current
pitch/right-hand singular limits; schedule yaw/Kong/final R endpoints and integer
zipline steps; accepted target intent/QuickTurn plus five packetless selections,
passive/removal/options/projected successors; finish native/server model
differentials and deterministic seed/FPS/culling/pass fixtures. Items 1–4 and
their fixtures must be complete before integration; U7b/U8 own delivery/wiring.

1. Capture and separate scratch-attachment APIs are implemented. Complete their
   canonical input producer in the ordered reducer (item 2), then U7b/U8 consume
   these APIs. Add native-versus-server formula differentials over the same typed
   fixtures; native restoration and capture checks do not replace them.
2. Connect bounded canonical/observer seeds and retained Hang/Dive grids to the
   shared resolver. Finish Dive host entry/exit topology/current root pitch and
   singular right-hand-limit handling. Schedule the implemented `ParCoolYaw`
   ordered fold in the canonical placement producer; feed its final R endpoints
   to Kong. Schedule pure integer zipline advancement in canonical/observer
   projection. Typed restored zipline callbacks are now pure; the legacy
   direct-construction caller still advances lazily. Kong and SkyDive scalar
   recurrences are available, but ordered scheduling is not implemented.
3. Add target-originated accepted intent transport (including QuickTurn's
   authoritative direction/turn flag production); all five packetless selections,
   passive, native removal/option ordering and PROJECTED_SUCCESSOR derivation.
4. Expose complete capture, restore, project and seed-fold APIs for U7b/U8.
   Accepted native batch capsules/snapshot stream/client presentation wiring is
   owned by U7b/U8 under the coordinator's second-session re-scope, not U5.
5. Complete transformed native-vs-server root/model differentials for all 26+2
   and passive; checkpoint/seed continuation and deterministic FPS/culling/pass
   permutations for the implemented replacements. Mixed/live gates stay with
   U10/U11. Current scalar A/B and restoration evidence are not owner approval.
6. U9b: fix pre-JOIN ClientInfo timing, promote the verified fork pin
   `402eaa65251fa902ada674ed5d499eb21bef31a3`, and include its hooks/read layout in
   compatibility. Fork remains unchanged during this resume; its EOL-only wrapper
   status is pre-existing and must not be committed as a task change.
7. Only after items 1–3 and their fixtures are complete: merge latest
   `origin/fulla/phase23`, run all four full checks, then integrate the complete
   U5 family. This checkpoint is partial and must not be pushed to phase23.
