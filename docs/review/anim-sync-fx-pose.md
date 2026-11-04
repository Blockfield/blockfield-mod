# FX-POSE: native cancellation and retained model inputs

Status: **ParCool cancellation, driven elytra velocity and the retained SBW model-tail readers are
complete and integrated**; one recorded remaining item, the SBW seat-root mixed case (below).
Base: `18e0d02e52fc15ee5641f8aad50fb4bd74f6642d`; branch `fulla/as-fx-pose`.
Contract: ADR4.1 §§2.4, 4.2, 4.5, 5.2–5.3, 8–10 and both errata; U1 manifest 4 unchanged.
Both independent final reviews and the U1 API/reviews were read. No schema fields or gates changed.

## ParCool cancellation (client P0-4 / server P0-1)

**Root cause:** `AnimSyncEval.pose` consumed `animatePre`'s result only for vanilla setup,
then ran tails inside the method that native ParCool had cancelled.

**Native evidence:** digest-checked bf7 `9a0ec4e2…`, Temurin 21.0.12+8 `javap -p -c`:
`PlayerModelMixin.onSetupAnimHead` resets at 54, calls `Animation.animatePre` at 78,
copies wear at 87 and cancels at 100–102. `JumpChargingAnimator.animatePre` returns true
at 148–149; `ChargeJumpAnimator.animatePre` returns true at 559–560. PA 2.0.4
`86ef8a23…` `PlayerModelMixin.setEmote` is injected at the first `ModelPart.copyFrom`
in the cancelled method. Superclass HumanoidModel setup, SBW/TaCZ tails, Create,
PA part application and ParCool/medkit model tails are bypassed. Renderer preparation
and setupRotations are independent: bf7 `PlayerRendererMixin.onSetupRotationsHead`
cancels only on `rotatePre`; root post and PA root obey that separate result.
Both charging animators also return true from rotatePre (JumpCharging 41–42,
ChargeJump 133–134), so those cases bypass PA root too.

**Fix:** retain model cancellation throughout `AnimSyncEval.pose`. Gate every skipped
model stage, keep the separate renderer/root path and its own cancellation, and distinguish
PA part application from its body/root transform. `AnimSyncParCoolEval.Scratch` already
retains model/root cancellation independently; its model post guard remains unchanged.
No native client animation formula is replaced.

**Cases:** new `FxPoseCases`, appended to the shared case registry, supplies JumpCharging
and ChargeJump at age 3 with a nonneutral PA graph and with a retained non-PA TaCZ display
(raw aim .7). Four cases × 142 frames = 568 samples over 30/60/144/240 FPS. They execute
through the existing explicitly registered client/server MixedRendererOracleFixture and
its runner/audit, with the full production transformations. No standalone JUnit substitute.

## Elytra amplitude (client P0-2)

**Native evidence:** mapped 1.21.1 `HumanoidModel.setupAnim` 178–209 reads raw
`LivingEntity.getDeltaMovement`, computes float(lengthSqr)/.2f, then cubes it;
211–219 clamp that divisor to at least one. `getDeltaMovementLerped` is a different reader.

**Fix:** `CombatDrivenEntityInputsMixin` pins raw `Entity.getDeltaMovement` to the installed
R interpolation only while `DrivenPresentation.inputs(this)` identifies an active remote
render invocation. Outside that scope the native getter runs. Local players cannot install
such a frame. The existing lerped-velocity override is unchanged; its misleading comment
is corrected. The injection descriptor/subclass checks are pinned in OracleInjectionTargetTest.

**Case:** retained old/current velocity (.8,-.2,.3) → (1.2,-.4,-.1), nonzero R walk,
fall-fly ticks above four, live velocity zero. Every retained speed is above the .2 squared-speed
threshold. One case × 142 frames = 142 samples. Mixed renderer additionally asserts raw
velocity equals the R sample inside the scope and live zero before/after it.

## Retained SBW readers (client P0-3)

**Root cause:** `DrivenPresentation.Frame` carried vanilla/Create only, so bf24's native SBW
mixins read the live entity while the server port reads `AnimationState.Sbw` of S[ceil(A)].

**Native evidence** (bf24 `24ea4e4c…`, Temurin 21.0.12+8 `javap -p -c`, intermediary names):
`HumanoidModelMixin.setupAnim` (TAIL of `HumanoidModel.setupAnim`, merged handler
`handler$…$superbwarfare$setupAnim` in the transformed hook-order evidence) calls
`ParachuteItem.isParachuteOpen` at 1 (static bridge → `ParachuteItem$Companion.isParachuteOpen`,
which reads Accessories' first-equipped parachute stack and its `Open` custom-data bit);
`getVehicle` (`method_5854`) at 100, `VehicleEntity.getSeatIndex` at 123,
`computed().seats()` at 130–133, an out-of-range index `return`s at 147–155 (skipping the gun
tail), `SeatInfo.pose` from 172; the main hand is read once at 538–545 as
`getMainHandItem().getItem() instanceof GunItem`, then `getPose()==SWIMMING` at 552–558 and
`!isSwimming()` at 562–565 gate the prone pose. `LivingEntityRendererMixin.setupRotations`
(HEAD, cancellable) calls `getRootVehicle` at 1/9, `VehicleData.compute(root).seats()` at 31–34,
`getSeatIndex` at 42 (out of range `return`s uncancelled at 64), `getTransformDirectionNoOrientation`
at 89 (itself re-reading `getSeatIndex`, `computed().seats()` and `SeatInfo.transform`),
`SeatInfo.transform` at 115, `getRotationFromString` at 120 and `getPassengerRenderScale` at 191.
Only the base `VehicleEntity` declares `getSeatIndex`/`getPassengerRenderScale`; for players,
`ParachuteRenderer.onRenderLiving` returns before its parachute read.

**Fix (client, remotes only, render scope):** the installed frame carries the retained `Sbw`.
`CombatDrivenSbwInputsMixin` redirects `ParachuteItem$Companion.isParachuteOpen` to the retained
bit, `VehicleEntity.getSeatIndex(passenger)` to the retained direct/root index and the root
vehicle's `getPassengerRenderScale` to the retained scale. `CombatDrivenLivingInputsMixin`
returns an empty main hand to bf24's prone tail only (recognised by its merged handler frame)
when S[ceil(A)] holds no gun; the held-item layer, TaCZ and every other reader keep the live
stack. Pose and swimming were already presented from the checkpoint at install. At install
`DrivenSbw` requires the live direct/root vehicle ids to equal the retained ones (a mismatch is a
seat boundary: `sbw_vehicle_link`, never presented across), the retained indices to be in range,
the seat definition at the retained index to carry the retained pose and the retained root
transform (`sbw_seat_pose`/`sbw_seat_transform`). The transform name is a retained definition:
`ClientCombatScene.retainedDefinition` (one additive read-only accessor, coordinator decision)
or, for generation-scoped refs, the generation catalog; unresolvable is
`sbw_transform_unavailable`. A retained gun the live hand no longer holds while prone would need
the retained main-hand item, which the client cannot resolve: `sbw_main_hand`, fail closed. The
local player and all gameplay reads stay native. Server code is unchanged: `AnimSyncEval` already
reads the retained state; the mixed oracle now feeds it the retained model-tail inputs.

**Cases** (`FxPoseCases`, cohort `sbw`, live contradictions set by client-only `FxPoseLive`
before each install): parachute retained open/live closed; retained closed/live open (Accessories
lookup returns an `Open` stack, asserted live open outside the scope); prone with retained no-gun
and a live bf24 gun in the main hand; prone with retained and live gun; water swimming with a
retained gun; live pose standing and live swimming opposite to the checkpoint in every prone or
swimming frame. Five evaluated cases × 142 frames = **710 new samples, bit-identical**. Prone with
retained gun and live empty hand: 142 samples **unavailable** (`presentation:sbw_main_hand`) on
the client, never equality. Negative control (redirects inert, not committed): the three
contradiction cases differ in all 426 samples; the agreeing cases stay equal.
`DrivenSbwTargetTest` pins every redirected/checked bytecode read above against the SBW jar and
that no player class overrides `getMainHandItem`.

**Residual / remaining:**
- SBW seat-root mixed case (direct/root seat index and pose contradiction, seat transform,
  passenger scale): needs FX-GATE's retained root-vehicle fixture input, which is still
  uncommitted in its worktree; no parallel root-vehicle field was added. The production redirects
  and install checks above already cover it and are pinned; the case is the one remaining item.
- `retainedDefinition` sees only the latest applied batch; the stream sends each life-scoped
  definition once, so SBW passengers with a seat transform are mostly `sbw_transform_unavailable`
  until the client keeps a retained definition store (FX-CLIENT's in-progress store: swap the
  accessor body to it).
- Not in the reviewed offsets: bf24 `poseRightArm`/`poseLeftArm` (HEAD) call
  `ModEnumExtensions$Client.applyCustomArmPose`, which reads the live
  `getItemInHand(MAIN_HAND)` class (LungeMine/Minigun/M2HB/SuperStarShooter) when
  `getUsedItemHand()==MAIN_HAND`; the server applies `special` from the retained main hand. Exact
  presentation also needs the retained main-hand item on the client (same store); reported, not
  changed here.

## Verification

Earlier family runs: 4,544 then 4,828 bit-identical samples (hash
`fde3838d0c3f28bc8e1b6aafb35ee1faf2cec6476b8f6bc0932ef9b345a3e512`), OracleInjectionTargetTest 7/7.
SBW run (`spotlessApply :client:test --tests *DrivenSbwTargetTest* --tests *OracleInjectionTargetTest*
:client:oracleTest :server:oracleTest -PoracleOnly=MixedRendererOracleFixture`, heavy lock,
pinned JDK): both fixtures pass; the 4,828-sample slice hashes identically on both sides
(`fde3838d…`), 710 new samples bit-identical, zero differing samples, 142 unavailable;
DrivenSbwTargetTest 4/4, OracleInjectionTargetTest 7/7.
Integration (phase23 still at the base, nothing to merge): `just format`, `just check`,
`just test` and `just java-check` pass with the pinned JDK under the heavy lock; every client and
server transformed fixture passes and the offline equality report exits 0 (all mixed cohorts
bit-identical, 5,538 samples, max 0 m; `sbw` is `partial` only for the fail-closed case).
The stricter install check refused the reflective sample checkpoint of the U8 timing fixtures,
whose SBW ids 0 name a vehicle the mock does not ride; `SceneTimingOracleFixture.value` now
samples the captured unseated value (-1 ids), a test-data correction only.
[Machine-readable evidence](anim-sync-fx-pose-fixtures.json); logs, comparisons and the negative
control under `build/anim-sync-oracle/fx-pose/sbw/`.
No server/client/stand was started; no live/owner acceptance, release or deploy is claimed.
