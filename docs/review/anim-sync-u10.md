# U10 native oracle, capture and offline equality report

Contract: ADR4.1 §§8–10, §11 row U10 and both errata; U1 schema manifest 4 (unchanged).
Branch/worktree `fulla/as-u10`, `WS/.dev/worktrees/as-u10`, base phase23 `5183e3cb`. Workspace
tooling: `fulla/as-u10` in `WS/.dev/worktrees/as-u10-ws` (merged into `fulla/p3-u13-harness`).
No stand, game, live server or owner A/B was run: everything below is offline (plain JUnit and
the transformed production runner, [runner doc](anim-sync-oracle-runner.md)).

## 1. §8.3 capture (`client/render/RenderCapture`, `BodyPartHitboxRenderer`)

`CombatRenderedBodyMixin` captures every armed `LivingEntityRenderer.render` of a player or named
range Husk at its final `renderToBuffer` call as one immutable `RenderCapture`:

- **Pass and invocation:** `MAIN` inside the main `GameRenderer.renderLevel` call
  (`CombatCaptureFrameMixin` brackets it), `SHADOW` while Iris's public API reports its shadow pass,
  `OTHER` outside (GUI previews); a monotonically increasing renderer invocation id.
- **Identity:** connection/stream/scene, frame/revision, H/R/A, target life/seat/teleport/
  subscription, resource/policy/compatibility/manifest identity, placement endpoints and sample
  mode, the victim row's seed, applied prefix and prefix/presentation digests, whether the
  animation was driven, the report-unavailable label. Zeros and `native` without an exact stream.
- **Geometry:** double feet origin (the dispatcher's `xOld` lerp), renderer offset and camera; the
  feet-relative float root (exact when the dispatcher's entry pose is its float translation, which
  `rebaseExact` records; otherwise derived from the inverted entry pose and diagnostic); the six
  part parameters; the six `BodyGeometry` boxes' 48 local float corners through `BodyRig`. A world
  corner is `origin + (double) local`; no absolute coordinate enters a float matrix.
- **Validity:** `frameEntry()` resets MAIN and SHADOW at every main frame. A culled or skipped main
  draw leaves `mainCapture(id) == null` / `captureSnapshot(id).status == not_rendered`, never the
  last frame's entry. Shadow and other passes never replace MAIN.
- **Consumers:** F3+B draws the current MAIN capture's local corners (no world round trip) in the
  main pass and nothing in other passes; `debugSnapshot(id)` keeps `serverTick`/`shapeKey`/`boxes`
  for the testbot and adds the complete `capture` record; `captureSnapshot(id)` is the evidence
  read; `evidence(true)` arms capture without F3+B.

Workspace: testbot `capture arm|disarm|<entityId>`; `compare_body_pose.py --f3b` pairs only MAIN
captures, counts `not_rendered` receipts and lists each paired receipt's frame/revision/R/A/H.

## 2. Mixed-renderer differential (`MixedRendererCases`, client and server fixtures)

Shared typed cases (common code only) give both JVMs the same lossless R placement, A checkpoint
and observer presentation (the shared reducer with TaCZ fire / Create membership capsules, or a
fixed composed ParCool/PA/LR graph). Frames visit 30/60/144/240 FPS over 0.3 s with A 2.25 ticks
ahead of R.

- **Client (`blockfield.mixed-renderer-native`):** the production `ClientCombatScene.write` and
  `DrivenPresentation.install`, then the real `LevelRenderer.renderEntity -> EntityRenderDispatcher
  .render -> PlayerRenderer.render` with every production mixin (PA, ParCool, TaCZ, TaCZ Tweaks with
  the armed policy, LR, Create, SBW, Accessories, VanillaBackport, Iris, EntityCulling, Blockfield
  scopes), captured by the production §8.3 hook. Per frame: Iris shadow pass before and after main
  (Iris's own `ShadowRenderer.ACTIVE`), two main passes at different ambient partials, F3+B drawing
  170 line vertices from the capture; every fifth frame a new frame culled through EntityCulling's
  real `renderEntity` gate (`not_rendered`, no F3+B) and the same frame with culling disabled. The
  live entity state contradicts the checkpoint (attribute scale 1.25, standing bbHeight, opposite
  health and water) so a live read reaching the boxes shows up as a difference.
- **Server (`server.blockfield.mixed-renderer-server`):** `AnimSyncEval.pose`, the composition the
  server rewinds with (extracted from `body`, unchanged dependencies resolved lazily by `evaluate`).
- **Comparator (`dev/anim_sync_report.py`):** root, six parts, 48 corners and the double origin per
  sample; bit identity and the 1 mm world-corner gate per cohort.

Result: all 4,118 evaluated samples (8 cohorts) bit-identical, max corner error 0 m ([fixture manifest](anim-sync-u10-fixtures.json)). The SBW passenger seat is
`unavailable` (`sbw_root_vehicle_placement_not_typed`): the server seat root reads the retained
root-vehicle placement (`AnimSyncStream.vehicleAt`), which a typed case does not supply; U6's
fixtures cover the seat transforms. It is counted, never a pass.

### Gaps the comparison found (fixed here, native evidence in the oracle fragments)

| Finding | Native reader | Fix |
| --- | --- | --- |
| Root scale, walk gate, baby, swim tilt, upside-down lift read live state | `LivingEntityRenderer.render` `getScale/isAlive/isBaby`, `PlayerRenderer.setupRotations` `isInWater`, `setupRotations` `getBbHeight` | driven scope adds `getScale`, `isAlive`, `isBaby`, `isInWater`, `getBbHeight` from the checkpoint (`OracleInjectionTargetTest` pins them) |
| ParCool FastRun/wall-run limb gate read live alive/passenger | `RemoteParCoolPresentation.limb` runs before the driven-input scope opens | the limb is computed inside `DrivenPresentation.scope` |
| Medkit render offset read live `MedkitPickup` | `MedkitPoseOffsetMixin.getRenderOffset` | driven remotes use `DrivenPresentation.medkit` |
| Server elytra/swim/spin pitch used the R record's current pitch | `getViewXRot`/`getXRot` hold the R-sampled pitch on a driven entity | `RootInputs.entityPitch` = R-sampled pitch |
| Server elytra banking used the view yaw | `LivingEntity.getViewYRot` is the head yaw | `RootInputs.viewVector` = view(pitch, head yaw); ParCool's `getLookAngle` keeps (pitch, view yaw) |
| Server composition order | transformed hook order: HumanoidModel RETURN `tacz` then `create.afterSetupAnim`; PlayerModel `playeranimator.setEmote`, `parcool.onSetupAnimTail`, `blockfield.medkitReach`; PlayerRenderer.setupRotations `parcool` tail then `playeranimator.applyBodyTransforms` | fallback, Create, PA, ParCool model post, medkit; ParCool root post before PA's body transform |
| Create hanging dropped the head look | bf4 `setHangingPose` writes `head.x` and `head.zRot` only | `CreateBodyPose.mesh` resets body/arms/legs, sets head x only |
| Root ULPs | `PoseStack.mulPose(Axis.*.rotation)` and PA's single `y + 0.7` translate | server roots rotate through the same quaternions |

The merged handler order of the pipeline methods is recorded in every run (`hookOrder`), so a pin
changing the order is visible next to the differential that depends on it.

## 3. Offline equality report (`dev/anim_sync_report.py`, `just test`)

Runs after both oracle tasks; writes `build/anim-sync-oracle/equality-report.json` and fails on any
failed fragment, missing mixed fragment or failed differential. Contents: per-cohort fixture counts
(`pass`/`unavailable`/`fail`; the U8 §9 timing fixtures are their own `timing` cohort), the mixed
renderer per cohort (samples, bit-identical, beyond 1 mm, unavailable cases/samples, maxima,
first difference), the four ParCool cross-JVM differentials (`check_parcool_oracles.py`, previously
manual, now part of the gate), unavailable pack artifacts, the runtime matrix and every checked-in
`docs/review/anim-sync-*-fixtures.json` manifest with which of its named fixtures re-ran now.
Unavailable is never counted as a pass. Costs (time and allocation per mixed sample, both sides)
are reported only.

**Runtime matrix:** each fragment records JDK build, vendor, OS, architecture, CPU and the effective
JOML options. Only the rows actually executed are claimed (this run: pinned Temurin 21.0.12+8,
Linux amd64, one CPU model); Windows is listed unavailable. No cross-runtime bound is asserted.

## Deviations from ADR4 (recorded, not silent)

- §8.3 decoded endpoint digests are represented by the endpoint ticks plus the receipt's
  presentation digest; per-capture SHA of the decoded records is not computed.
- §10 P2-9 Iris on/off is exercised through Iris's shadow-pass flag in the transformed JVM, not a
  GPU shader pipeline; body render layers (armor, held items, cape) are not executed because they
  draw after `renderToBuffer` and cannot move the captured boxes.
- The mixed cohort uses fixed composed graphs for ParCool/LR/PA overlaps (no reducer steps); the
  reducer itself is differential-tested by U7b/U7cP/U8.
- `-PoracleOnly=Fixture1,Fixture2` runs a subset for iteration; the gate always runs every fixture.

## Open items

- **U11 (live, consent):** real Velocity handshake and READY, live TCP/RakNet cohorts, F3+B and
  testbot capture against server rewind with the same receipt, owner 30/60/144 A/B.
- **SBW seats** in the mixed cohort need a retained root-vehicle input on the server side.
- **TaCZ non-PA fallback evaluator** and the LR local-native metadata view are not in the mixed
  cohort (U3/U4 fixtures cover them in isolation).
- **END-tick legacy pose** (`ServerBodyPoses` gameplay fallback) keeps its previous stage order;
  it is labelled fallback and not equality evidence.
