# FX-GATE: admission reload and required mixed evidence

Scope: independent final server review P1-7/P1-8 at `18e0d02e`; ADR4.1 §§6/8–10,
U1 schema manifest 4, unchanged. Branch/worktree: `fulla/as-fx-gate`,
`WS/.dev/worktrees/as-fx-gate`. The FX-SEAT continuation below additionally changes the
server SBW seat-root arithmetic and mixed-fixture diagnostics. No stream, validation,
observer reducer, fork, release, production service or live stand changes.

## P1-7: obsolete configuration READY

**Root cause.** `configured` compared the response only with the manifest captured when the
configuration task was enqueued. Activation announced to play connections, so configuring clients
could miss a new generation both before READY and between READY and the finish ACK.

**Fix.** Read `AnimSyncResources.current()` before accepting the pending response. If its
resource generation changed, replace the pending request/deadline and send the new manifest while
leaving the current configuration task open. Only a matching active READY completes that task.
`AnimationAdmissionMixin` fences `handleConfigurationFinished` immediately after vanilla's
server-thread dispatch and before `finishCurrentTask`, outbound protocol switching or player
creation. A reload after READY refuses JOIN with a reconnect message: the client has already
switched protocols when sending this ACK, so another configuration payload is unsafe then.
Activation and this final transition are server-thread owned. Missing production channels are
still refused; the explicit `BLOCKFIELD_DEV=1` no-mod exception is unchanged. Configuration
failure/disconnect also releases this connection's admission state.

**Test.** `AnimationAdmissionOracleFixture`, registered in `ServerTransformedOracleSession`,
executes the transformed native configuration task queue and finish ACK. G=51 is sent, G=52
activates before its response, G=51 READY leaves JoinWorld queued and sends G=52, G=52 READY
starts JoinWorld, then G=53 before ACK disconnects before any connection protocol/player-list call.
Requires successful application of `blockfield-server.mixins.json`. Targeted transformed result:
**pass**. This is an in-process lifecycle interleaving; no real socket/Velocity connection.

## P1-8: unavailable required cohorts counted as a green gate

**Root cause.** The per-cohort reducer labelled missing evidence `partial`/`unavailable`, but the
summary returned `pass` unless a cohort was literally `fail`. The seat case was skipped before
sampling; non-PA TaCZ and full-render LR local metadata had no mixed coverage.

**Fix.** The summary preserves partial/unavailable outcomes and requires evaluated seat, non-PA
hold/aim and LR metadata cases. The LR native metadata fragment must additionally contain
nonzero rendered evidence at 30/60/144/240 FPS. The executable already rejects every non-pass
summary. Optional artifact diagnostics stay in `unavailableArtifacts`, outside equality totals.
Mismatch diagnostics now identify a differing part/corner and both coordinates; a missing LR
fragment cannot suppress the other mixed-cohort results.

**Fixtures.** Shared `MixedRendererCases` retains root-vehicle R endpoints/constants and the typed
seat transform definition. The server fixture feeds those into the existing
`SbwBodyPose.project/constants/projectedRoot` dependency path. The client uses the native transform
registry, production `ClientCombatScene.write`, retained seat resource data on both native lookup
paths, and the full transformed renderer/pass/capture path. Production pose code is untouched.
The two non-PA cases install retained TaCZ fallback resources and raw aim 0/.65; the real native
hold/aim callbacks execute in the full renderer. The LR case includes a driven remote's full
render, plus a separate comparison of the local player's full native MAIN geometry/shared metadata
with two native remotes versus their native compatibility views, through the pinned selectors and
production mixins. It includes remote-to-local attack metadata and native first-person mode.

**Report regression tests:** nine pass (all unavailable, partial case/sample, missing required case,
missing/zero LR renders, optional artifact separation, exact mismatch diagnostics).

## Results and remaining acceptance

**PARTIAL: implementation and required checks complete; integration withheld for the newly
evaluated seat-root bit-equality gap below.** After merging `ac472953`, all four commands passed:
`just format`, `just check`, `just test`, `just java-check`. Gradle ran under the shared heavy
lock with pinned Temurin 21.0.12+8, `--no-daemon --max-workers=2`, and the memory gate.
All 98 client and 27 server transformed fragments passed, including the admission interleaving,
all four ParCool differentials and the LR local-render fixture. JUnit reported 531 client tests
(two opt-in uniform artifact generators skipped), 1,688 server tests, zero failures/errors.
All 13 required in-process GameTests passed. The nine report regression tests passed.

The final mixed report evaluates **6,248 required samples**, **5,976 bit-identical**, zero required
unavailable and zero beyond the existing 1 mm tolerance. Each of the five added cases has 142
frames (9/18/43/72 at 30/60/144/240 FPS): `passenger-seat`, `fx-pose-sbw-seat-root`,
`non-pa-hold`, `non-pa-aim`, `local-native-metadata`. All original 4,118 client and server samples were verified
unchanged against U10's retained fragments (original digest
`a79464ac8049a45fbee9a424dd4d29b611154f5c34046f9a49a1108529b61b63`).
The initial seat differential exposed a harness resource-lookup omission: native SBW's static
`VehicleData.compute` did not receive the retained seat definition. That run is not evidence of a
production port mismatch. After supplying that retained definition to both native lookup paths,
Both seat cases have 142/142 samples within 1 mm, **6/142 bit-identical each**, maximum corner difference
**4.917383193969727e-7 m**. No unavailable sample or fallback hides the remaining bit differences.
Both new TaCZ fallback cases and the LR mixed case are **142/142 bit-identical**. LR's separate full-local-render fixture
passes with **1,447** compared native MAIN samples (92/183/440/732 at 30/60/144/240 FPS) in each
world: local geometry and shared metadata are bit-identical with native remotes versus the native
compatibility views. The harness supplies the constructor-owned profile, camera mode and player
registry; native selectors and renderer methods run unchanged.

### Retained SBW arithmetic difference (family-port owner)

First sample: fixture `blockfield.mixed-renderer-native` versus
`server.blockfield.mixed-renderer-server`, case `sbw/passenger-seat`, 30 FPS, frame 0,
R=`102:49807`, A=`105:655`. Head corner 0, feet-relative metres:

- Native: `(0.03463950753211975, 1.416906714439392, -0.18025261163711548)`.
- Server: `(0.03463949263095856, 1.4169068336486816, -0.18025261163711548)`.
- This sample's maximum world-corner difference: `2.3889104820542146e-7 m`; origins match.

The first sample's differing root slots are `0,1,2,4,5,8,9,12,13`; part parameters agree.
Pinned bf24 `LivingEntityRendererMixin.setupRotations` bytecode (offsets 98–132) constructs the
cancel-yaw quaternion with float `Axis.YP.rotationDegrees(-yaw)`, then converts it to
`Quaterniond`; `SbwBodyPose.project` instead uses `new Quaterniond().rotateY(-yaw * DEG_TO_RAD)`.
The native final facing rotation also uses `Axis.YP.rotationDegrees`, while `projectedRoot` uses
`Matrix4f.rotateY`. These operation-order differences are concrete family-port follow-up evidence,
not an authorization for FX-GATE to edit that port. Raw fragments and the exact per-cohort
comparison are retained under `build/anim-sync-oracle/verification/corrected-seat-*`.

The report's existing native-port tolerance gate remains 1 mm; it correctly exposes the bit
counts and vector above. The unit's stricter instruction to preserve newly found pose differences
for the later family fix is why this branch will not be integrated with an unresolved seat
bit-equality gap. No gate was relaxed and no mismatch was relabelled unavailable.

### Coordinator integration (`ac472953`)

Merged FX-POSE into this branch (`64796e90`), resolving only the three owned mixed-fixture files.
The server dependency keeps both retained SBW model-tail inputs and retained seat-root placement;
TaCZ resources choose each case's explicit catalog or FX-POSE's retained view. `FxPoseLive` and
U8's unseated `SceneTimingOracleFixture.value` are preserved. `FxPoseCases` is append-only here:
one added `fx-pose-sbw-seat-root` case uses the retained passenger recipe and deliberately supplies
live seat index 1 / MachineGunStand and live scale 1.1 against retained seat 0 / Pilot and scale .75.
Its real transformed getters must return the retained values inside the render scope and native
values outside. This adds 142 samples and exercises the production redirects, not mocked getters.
All existing 710 evaluated FX-POSE SBW samples remain bit-identical. Filtering the five added
cases preserves FX-POSE's complete baseline hashes exactly:

- Client: 5,538 evaluated samples,
  `a74fb6414cc7d795859ddcaa5454c0a53e708483247b807094e70d76695e8b37`.
- Server: 5,680 evaluated samples (including its 142 diagnostic-only samples),
  `32feacbb2a706f3dd382e9ef8cfeadfbef939fe6bc69151b3b5fa1ec3437183e`.

The coordinator's known `fx-pose-sbw-prone-retained-gun-live-empty` vector is separately diagnostic:
its 142 `sbw_main_hand` refusals and the corresponding server-only evaluations are excluded from
equality totals. Only that named case/reason is exempted; an unexpected reason/evaluation fails,
and unavailable required SBW seats still make the required summary partial/failing. Regression
tests assert all three distinctions.

The full report, exact vectors, command logs/statuses and baseline checks are retained in this
worktree's ignored `build/anim-sync-oracle/verification/` (`full-equality-report.json`,
`final-validation.json`, `fx-pose-baseline-preservation.json`, `full-command-results.tsv`).
The existing report's cost measurements remain report-only: final native frame p50/p95
12.52/24.60 ms, server evaluation p50/p95 .253/.337 ms; the instrumented native fixture includes
multiple render passes and is not a live performance benchmark. No bandwidth claim is made.

No ADR4/schema deviation or gate relaxation was introduced. No integration commit exists on
`fulla/phase23`: the family-port owner must reproduce/fix the retained quaternion operation-order
gap, then rerun both 142-frame seat cases and the full acceptance commands before integration.
The complete FX-GATE checkpoint is pushed on `fulla/as-fx-gate`; its worktree is left in place.

No live TCP/RakNet/Velocity, GPU, Windows or owner appearance acceptance is claimed. Admission's
final-race behavior is an explicit reconnect refusal, not silent admission with old resources.

## FX-SEAT continuation (2026-10-18): partial arithmetic correction

**PARTIAL; exact gate remains red and integration is withheld.** `SbwBodyPose.project` and
the frozen seat-root path now use the native float `Axis.YP.rotationDegrees(-yaw)` before
conversion to `Quaterniond`. The final facing operation now multiplies
`Axis.YP.rotationDegrees(180 - bodyYaw)` instead of calling `Matrix4f.rotateY`. No tolerance,
fixture label, schema, manifest or diagnostic exemption changed. `ServerBodyPoses` already
consumes that root and needs no arithmetic change.

Pinned Temurin `javap -p -c` confirms bf24 `LivingEntityRendererMixin.setupRotations`:
offsets 84–96 query direction and narrow native yaw to float; 98–132 build the float cancel
quaternion, widen it and right-multiply the vehicle's double quaternion; 137–169 narrow the
result to float and call `PoseStack.mulPose`; 172–186 multiply the float facing quaternion;
230–237 apply passenger scale. Mapped 1.21.1 `Axis.rotationDegrees` performs a float multiply
by `0.017453292f`; `PoseStack.mulPose(Quaternionf)` calls `Matrix4f.rotate(Quaternionfc)`.

The targeted transformed command passed:
`./gradlew --no-daemon --max-workers=2 :client:oracleTest :server:oracleTest -PoracleOnly=MixedRendererOracleFixture`
(shared heavy lock, pinned JDK, memory preflight). The raw comparison remains **6,102/6,248
bit-identical**, each seat case **69/142** rather than 6/142. Maximum corner difference is
`3.6286698764222625e-7 m`; the first seat sample differs only in root slots 2, 8 and 9.
Every non-seat sample is unchanged, including all original **4,118** U10 samples and all
**710** original FX-POSE SBW samples on both sides. The 142 `sbw_main_hand` diagnostic
samples remain excluded, with no unexpected reason or unavailable required sample.

### Additional native dependency found

`VehicleEntity.getTransformDirectionNoOrientation` offsets 31–112 do **not** rotate a unit
direction with `getRotationFromString`. They obtain the named **Matrix4d**, transform
`(0,0,0,1)` and `(0,0,1,1)`, then subtract the transformed positions. For `Turret`, the
native matrix path is `VehicleVecUtils.getTurretTransform` →
`VehicleEntity.getVehicleTransformWithCustomPitch` → `getVehicleYOffsetTransform`:
translate by the interpolated double world origin plus rotation offset, multiply the three
float axis quaternions separately, translate by negative rotation offset, apply custom
pitch, translate by **getTurretPos()**, then multiply turret yaw. The existing
`seatDirectionYaw` instead transforms a direction with the combined double quaternion.
The two operation sequences are not bit-equivalent.
Pinned JOML 1.10.5 `Matrix4d.rotation(Quaternionfc)` offsets 0–192 perform quaternion
component products as `fmul` followed by `f2d`. Combining widened quaternions first and
calling `Quaterniond.transform` does not preserve those intermediate float roundings.

The retained capture/layout is insufficient to reproduce the native sequence:
`captureReads`/`PitchConstants` and `SbwLayouts.baseSlots()` contain only
`getTurretCustomPitch`, `getPassengerWeaponMinPitch`, `getPassengerWeaponMaxPitch` and
`getPassengerRenderScale`. They omit `getRotateOffsetHeight():double` and
`getTurretPos():Vec3<double>`. `seatRef` retains only
`seat:<entityId>:<index>:<pose>:<transform>`; `transformRef` retains only the name. Neither
resource reference supplies those omitted computed vehicle-definition values. Translation
cannot be dropped merely because it cancels in real arithmetic.

The registered `MixedRendererOracleFixture` now also executes native fixture
`sbw/seat-direction-translation-dependency`, through `AnimSyncOracleRunner.run` with
successful SBW and Blockfield production-config audit requirements. Its fixed witness
uses origin `(16777216,63,-16777216)` and vehicle-yaw bits `1101363960`
(`20.685043334960938` degrees), keeping every retained angle and scalar unchanged.
Changing only computed `getTurretPos()` from `(0,2,0)` to `(4,3,-2)` changes the native
direction x from `0.13610164262354374` to `0.1361016444861889`. Native seat yaw changes
from bits **1090147837** (`7.822996616363525`) to **1090147838**
(`7.822997093200684`), while `getRotationFromString("Turret",.5f)` remains identical.
This demonstrates an omitted input affecting required native float state inside the
supported world envelope, rather than merely a statically reachable getter. A preliminary
search away from a double-precision boundary found no float-yaw difference and is not
used as equality evidence.

The coordinator/U1/U6 must review and retain the complete named matrix-query inputs and
their read-layout/resource identity before this port can execute the native sequence.
The existing typed `VehicleRead` supports double/vector values, so a new parallel field
list or an assumed wire-version change is unnecessary; the layout/capture contract still
needs coordinated correction. No such contract change was made in this unit. After that,
replace the yaw query with the native matrix/position/subtraction order, require both seat
cases to reach 142/142 exact samples, merge current phase23 and run all four full commands
before integration. The earlier full-command results above belong to checkpoint `a3509837`,
not this incomplete continuation.

Bytecode, raw before/after fragments and strict partial comparison are retained under
`build/anim-sync-oracle/verification/fx-seat/` (`*.javap`,
`float-quaternion-only-*.json`, `partial-exact-validation.json`, `targeted-mixed.log`).
Final continuation checks passed: `just format`, `just check` and the targeted oracle command
above, with three passing transformed fragments (native mixed renderer, native translation
dependency witness, server mixed renderer). Final raw mixed output is identical to the
6,102/6,248 partial result above; see `final-partial-validation.json`, `final-*.json`,
`just-format.log`, `final-check.log` and `final-targeted.log`. The full `just test` and
`just java-check` were not rerun for this incomplete family. No stand or client was started.
There is no FX-SEAT integration SHA on `fulla/phase23`.

## FX-SEAT continuation 2 (2026-10-18): retained seat matrix inputs, exact seat equality

**Coordinator-authorized U1/U6 layout change; exact gate green.** Commits on
`fulla/as-fx-gate`: `bed0012b` (layout, capture, server port, client redirect, fixtures) and
`95c827d2` (bytecode pins, U1 docs).

- **Layout/capture.** `SbwLayouts.baseSlots()` adds `getRotateOffsetHeight ()D`
  (`DoubleValue`), `hasTurret ()Z` (`BooleanValue`) and `getTurretPos
  ()Lnet/minecraft/class_243;` (`VectorValue`, present exactly when `hasTurret`), all
  `CONSTANT`, captured by `SbwBodyPose.captureReads` with the other vehicle reads at the same
  END-tick checkpoint. `PitchConstants`/`constants` require all seven slots and a coherent turret
  presence. Layout digest unchanged (slots are data), so schema manifest stays 4;
  `compat.family.sbw` changes and `AnimationResourceGate.verdict` refuses an old-layout peer.
  U1 change recorded in `anim-sync-u1-api.md`, `anim-sync-u1-schema.json` (§4.5 derived row),
  `anim-sync-u1-fixtures.json` (`fxSeat`).
- **Server port.** `seatDirectionYaw` runs bf24's `positionTransform` registry with the same
  JOML 1.10.5 `Matrix4d` calls in bytecode order (`translate` by the R origin + offset height,
  `rotate(Quaternionfc)` per float `Axis` quaternion, negative offset, custom pitch, turret
  position, turret yaw; `Default`/unknown/`Vehicle`, `VehicleFlat`, `Turret`, null turret
  position), transforms `(0,0,0,1)`/`(0,0,1,1)`, `Vec3.vectorTo`, then the native yaw expression.
  The origin is `SnapshotWireMath.origin` because a driven client collapses `xo == x` to that
  sample before the native lerp.
- **Client.** Redirect, not refusal: neither getter is overridden in bf24 (pinned in
  `DrivenSbwTargetTest`), so `CombatDrivenSbwInputsMixin` presents the root vehicle's decoded
  reads (`CombatVehicleScope.reads`, retained when `ClientCombatScene.write` samples the vehicle)
  inside a driven passenger's render scope; `DrivenSbw` refuses install (`sbw_vehicle_reads`) if
  they are absent or incoherent. Outside the scope and for the local player everything is native.
- **Fixtures.** `MixedRendererCases` retains offset 0 and turret `(0,2,0)`; the
  `fx-pose-sbw-seat-root` case's live data now contradicts them (offset .5, turret `(4,3,-2)`),
  and the client asserts native values outside the scope and retained ones inside. The client
  witness pins native seat-yaw bits 1090147837/1090147838; new server fixture
  `sbw/seat-direction-translation-port` reproduces both bits from the retained reads and matches
  the server JVM's native query bit for bit for `Vehicle`, `Default`, `VehicleFlat`, `Turret` and
  an unknown name, over offset 0/1.25 and turret `(0,2,0)`/`(4,3,-2)`/null, from the real capture
  (`exactNativeComparisons` = 30). U6's `sbwRotationPortsMatchTransformedNative` seat-yaw check
  is tightened from two ulps to bit equality.

**Results.** Targeted transformed run (`-PoracleOnly=MixedRendererOracleFixture,
CreateSbwServerOracleFixture`) and the full `just test`: the mixed report evaluates **6,248
required samples, 6,248 bit-identical**, maximum corner difference **0 m**, zero unavailable;
both seat cases **142/142** bit-identical (previously 69/142). Strict comparison against this
branch's retained fragments: all 6,390 client samples unchanged; all 6,106 non-seat server
samples unchanged; all original **4,118** U10 and **710** FX-POSE SBW samples unchanged on both
sides; the 142 `sbw_main_hand` diagnostic samples stay excluded with no unexpected reason.
127 transformed fragments pass (the previous 125, the client witness and the new server port
fixture). Unretained-closure diagnostic at the witness origin: `Barrel` 1 ulp, `WeaponStation`
and `WeaponStationBarrel` 0 ulp there (not an equality claim). Evidence:
`build/anim-sync-oracle/verification/fx-seat2/` (`exact-mixed-validation.json`, fragments,
`equality-report.json`, command logs).

**Open items for dependent units.**

1. Barrel/WeaponStation/WeaponStationBarrel seat directions also translate by
   `getBarrelPosition()` (non-null) and the nullable `getPassengerWeaponStationPosition()` /
   `getPassengerWeaponStationBarrelPosition()` (`VehicleVecUtils.getBarrelTransform` 25–79,
   `getGunTransform` 25–82, `getPassengerWeaponStationBarrelTransform` 25–79). They are outside
   the authorized slots, so these transforms keep the rotation-only port; bit equality needs a
   coordinator decision adding three more vector slots by the same process.
2. Client vehicle sampling (`SnapshotResolver.exact`, FX-CLIENT) collapses turret and gun yaw with
   `Mth.rotLerp` and yaw at `p == 1` to `yRot`; the server SBW port lerps the raw pairs (as native
   bf24 does). They agree in every fixture but can differ across a ±180° turret/gun step within
   one tick or by an ulp at exact integer R. The owners must choose one canonical sampling.
3. `getTurretCustomPitch` and the weapon pitch range remain retained on the server but read live
   on the client (no redirect); equal only while the resource manifest matches (JOIN-gated).

**Integration.** `origin/fulla/phase23` had not moved from `ac472953`. On the final tree
`e47a60fc` all four commands passed (pinned Temurin 21.0.12+8, shared heavy lock,
`--no-daemon --max-workers=2`, memory gate): `just format`, `just check`, `just test` (127
transformed fragments, mixed 6,248/6,248 bit-identical, report regression tests pass) and
`just java-check` (JUnit 531 client with two opt-in generators skipped, 1,688 server, zero
failures; all 13 in-process GameTests). Pushed as **`e47a60fc`** on `fulla/phase23`
(fast-forward). No stand, client, release, tag or production change.

## FX-SEAT continuation 3 (2026-10-18): exact remaining named matrices

Coordinator-authorized extension of the same U1/U6 read-layout process. `SbwLayouts` retains
`getBarrelPosition`, `getPassengerWeaponStationPosition`, and
`getPassengerWeaponStationBarrelPosition`, each native descriptor `()Lnet/minecraft/class_243;`.
bf24 marks all three nullable: each has a derived `<getter>$present` boolean slot (`()Z`),
with its vector present exactly when true. Capture reads each getter once at the same END-tick
checkpoint. Missing/incoherent pairs are unavailable. The derived IDs denote null-test
expressions, not native methods; compatibility resolution and the transitive read audit resolve
the corresponding native vector getter. There are now 13 slots. U1 record/tag/layout digest and
schema manifest 4 stay unchanged; `compat.family.sbw` changes. The three U1 docs record this change.

`CombatDrivenSbwInputsMixin.Seat` redirects all three getters from `CombatVehicleScope.reads`
inside the driven root-passenger scope. Native/local reads outside it remain native. Bytecode
pins verify exact names/descriptors, nullable annotations and absence of overrides. `DrivenSbw`
requires coherent retained reads; it refuses `sbw_vehicle_position` for a selected native Barrel
or WeaponStationBarrel path whose required position is absent. A null WeaponStation position
instead keeps the native early return of the turret matrix, before gun yaw.

`SbwBodyPose.positionTransform` now implements all three native Matrix4d paths, including
identity-matrix position transformation, translation and the individual float-axis quaternion
multiplies, in bf24 order. The rotation-only fallback is removed. WeaponStationBarrel's matrix
uses raw lerped gun pitch, while the separate native quaternion path retains its pitch clamp.

**Targeted checkpoint:** `spotlessApply`, the four `DrivenSbwTargetTest` tests, 20
`SbwBodyPoseTest` tests, five `SbwVehicleLayoutTest` tests, both transformed oracle tasks
(`-PoracleOnly=MixedRendererOracleFixture,CreateSbwServerOracleFixture`) passed under the heavy
lock and pinned JDK. The dependent in-process GameTest task passed all 13 required tests.
The transformed server compares all eight dispatch names at six fractions (0, 2^-16, .5,
.760009765625, nextDown(1), 1), two rotation offsets and three turret positions, including null:
**288/288 bit-identical**. Separate native null fixtures cover the station early-return and
both native required-position dereferences; missing presence is rejected. Both runtimes execute
the production SBW/Blockfield mixins, with successful configuration audit requirements.

At the retained large-origin witness, native client and server port both produce raw yaw bits:
Barrel **1090564608**, WeaponStation **1110615499**, WeaponStationBarrel **1110573878**.
New full-render mixed cases `seat-matrix-Barrel`, `seat-matrix-WeaponStation` and
`seat-matrix-WeaponStationBarrel` each have **142/142 bit-identical** samples at 30/60/144/240 FPS.
Their live native resource positions contradict the retained reads; scope assertions verify all
three redirects and restoration. Shadow/main/repeat/culling/F3+B paths use the existing full
renderer harness. All **6,248** previously required samples are unchanged on both sides, and the
combined required total is **6,674/6,674 bit-identical**, no required unavailable sample. The
142 `sbw_main_hand` diagnostic samples and server-only counterparts remain excluded exactly as
before. Raw fragments, baseline comparison and command log are retained in
`build/anim-sync-oracle/verification/fx-seat3/` (`exact-mixed-validation.json`, `before-*`,
`after-*`, `VehicleEntity.verbose.javap`, `targeted.log`).

This is the tested branch checkpoint; full post-merge checks and integration are recorded below
when complete. No ADR4 deviation or gate relaxation. No stand/client, production, release or tag.

### Continuation 3 final validation and integration

Validated implementation: **`c80847e3`**, after fetching and merging the latest
`origin/fulla/phase23` (**`25bead1b`**, already up to date). All four full commands passed:
`just format`, `just check`, `just test`, `just java-check`. Pinned Temurin 21.0.12+8, shared
heavy lock, `--no-daemon --max-workers=2`, and the >=3,000,000 KiB memory preflight were used.
There are **99 client + 29 server transformed fragments, all pass**; JUnit reports **531 client
cases (two existing opt-in generators skipped)** and **1,688 server cases**, no failures/errors.
All **13 required in-process GameTests** passed and shut down normally. No stand/client started.

The final strict baseline script again requires **6,674/6,674 bit-identical** mixed samples,
**zero** local/world corner error, all previous **6,248** required samples unchanged on both
sides, and all three new named cases 142/142 exact. It separately asserts the exact existing
`sbw_main_hand` diagnostic and matches the native/client named witness map to the server port.
The native matrix differential was extended to raw gun-pitch endpoints -3/-75/+75 (including
values outside the quaternion clamp) and now passes **864/864** comparisons. Null behavior,
codec round-trip (including negative-zero vector components), and inconsistent presence rejection
all pass. The initial full attempt caught a nested Mockito stubbing error in the new codec
fixture; it was corrected before repeating the whole four-command sequence. That unsuccessful
attempt remains in `fx-seat3/full-attempt-1/`; it is not green evidence.

Report-only measurements from the final full fixture run: encoded all-present vehicle placement
**1,675 B**, previous seven-slot form **897 B**, increment **778 B** in the current generic codec.
This is standalone placement payload cost, not total transport traffic. Instrumented native
frame p50/p95 **12.264/25.482 ms**, allocated **5,736,640/9,637,280 B**; server evaluation p50/p95
**0.257/0.355 ms**, allocated **171,272/224,600 B**. The native harness includes several render
passes; these are fixture measurements, not live per-shot or throughput claims. Full distributions,
fragment/runtime identities and equality/diagnostic accounting remain in `final-validation.json`
and `final-equality-report.json`; exact preservation and witness checks are in
`exact-mixed-validation.json`. `full-command-results.tsv` and `just-*.log` retain command outcomes.

The verified implementation and this evidence record are integrated by fast-forward to
`fulla/phase23`; the integration commit is the accompanying evidence-record commit (reported to
the coordinator). The continuation branch is also pushed and its worktree remains in place.
No ADR4/schema deviation or gate relaxation. No release, tag, deployment or live-game acceptance.

Dependent-unit items remain as in continuation 2: the canonical turret/gun wrap/endpoint sampling
choice belongs to the vehicle/client owners; the already retained custom turret pitch and weapon
pitch range still rely on matching resources for their native client reads. This continuation
closes the three named position-matrix paths and does not alter those separate contracts.
