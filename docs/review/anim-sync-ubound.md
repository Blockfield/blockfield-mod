# Anim-sync UBOUND: analytic broad phase (perf option A8)

Status: **wired checkpoint** (branch `fulla/as-ubound-1`, not integrated). Bound, runtime
defect counter and prefilter predicate exist and the candidate paths consume the predicate:
`ConservativeCoverage`/`FamilyStamps` segment-aware bodies, `CombatViews.exact` (label
`view:culled`, null body = miss) and the per-stamp `plannedEvaluations` batch skip victims a
bounded presentation proves unreachable (`PoseBound.reaches`); everything else resolves exactly
as before. Bounded: vanilla poses, PA clips, ParCool passive fall, Create hanging, SBW
seats/gun/vehicle roots; ParCool animator stages stay exhaustive (c7). Positive-age flights stay exhaustive (c6 below); instant/age-zero
culling also retains any possible current-body fallback hit.

Code: `server/.../combat/PoseBound.java`; defect check in `CombatViews.exact`; prefilter in
`CombatViews.exact` + `plannedEvaluations`, `FamilyStamps`/`ConservativeCoverage` segment
overloads, and the seven loop/single-target call sites (SBW hitscan/projectile/AP, LR
throwable, knife, TaCZ flight, TaCZ blast minimizer); tests
`server/src/test/.../combat/PoseBoundTest.java` and `PoseBoundGateTest.java`.

## A8 proof

**Claim.** For an instant presentation whose families are all bounded below, every corner of the
six boxes that `AnimSyncEval.pose` + `BodyPose` produce lies within
`r = scaled·|scale| + absolute + 1e-4` m of the segment between the two R placement origins
`placement.previousOrigin()` → `placement.origin()`. A shot segment (or explosion centre inflated
by its radius) farther than `r` from that segment cannot hit any pose of the victim.

**Structure.** A corner is `feet + root · T(p/16) · R · local · c` (`BodyRig.bones`, `BodyPose`).
`feet = SnapshotWireMath.origin(placement, pR)`, a double lerp with `pR ∈ [0,1]`, so it lies on the R
segment (rounding is covered by the slack). Every root/bone operation is a rotation (quaternion
`mulPose`, `rotationZYX`), a uniform scale, the `scale(-1,-1,1)` reflection or a translation. Rotations
and the reflection preserve length, so with the root's linear part `L = k·Q` (Q orthogonal) the corner
distance from the feet is at most `|t_root| + k·(|p|/16 + |local·c|)`, and each translation `T(v)`
applied while the linear part is `k·Q` adds at most `k·|v|` to `|t_root|`.

**Terms** (all from the code path `AnimSyncEval.pose`; line references at `3063328b`):

| term | source | contribution |
| --- | --- | --- |
| render scale | `ServerBodyPoses.renderOffset`: `new Matrix4f().scale(scale)` | `k = scale` |
| crouch offset | `renderOffset`: `translate(0,-.125,0)` after scale | `.125·scale` |
| medkit offset | `renderOffset`: `translate(0,-.125/scale,0)` | `.125` absolute |
| living root | `ServerBodyPoses.livingRoot` (`ServerBodyPoses.java:1412-1459`): yaw, shake, death tilt and riptide (`autoSpin`: `xp(-90-pitch)·yp(-75·age)`): rotations only | 0 |
| sleep | `livingRoot`: `identity().translate(-dx·eye,0,-dz·eye).scale(scale)`, `eye = standingEye - .1`, unit horizontal step | `|eye|` absolute (when SLEEPING with a bed) |
| upside-down | `livingRoot`: `translate(0,(h+.1)/scale,0)` | `|bbHeight + .1|` absolute (flag set) |
| elytra | `ServerBodyPoses.playerRoot` (`ServerBodyPoses.java:1463-1477`): `xp(min(f²/100,1)·(-90-pitch))` and the banking `yp(±acos)`: rotations only; it excludes the swim branch | 0 |
| swim / TaCZ Tweaks crawl | `playerRoot`: `translate(0,-1.4·s,.3·s)` after scale, `s = lerp(pA, swimAmountO, swimAmount)` | `√(1.4²+.3²)·max(|swimAmountO|,|swimAmount|)·scale` |
| model root | `pose()` end (`AnimSyncEval.java:381`): `scale(-1,-1,1).scale(.9375).translate(0,-1.501,0)`, applied after `renderOffset`, the native/ParCool roots and `applyWeaponRoot` | `k ← .9375·scale` for the drop and the bone pivots only; `1.501·.9375·scale` |
| pivots | `VanillaBodyPose.reset` (arms ±5,2,0; legs ±1.9,12,0), `crouch` (head y 4.2, torso 3.2, arms 5.2, legs y 12.2 z 4), `attack` (arm x,z on radius 5); TaCZ minigun fallback (`TaczPose.fallback`: arm x,z rotated about y, radius ≤ 5); other fallback kinds, `special`, medkit, SBW-default `apply`: rotations only | per bone `PIVOTS[i]` px |
| rig | `BodyShape.of().boxes()` (= `BodyGeometry.skin()`), generated at class init | per bone max `|local·c|` |
| PA pivots | `paHull`: every `KeyframePlayer` clip under the PA roots from `HeldWeaponPose.assets(gen).catalog()` (`PaGraph.Stack.materialize` builds PA roots only). Pinned 2.0.4 `KeyframeAnimationPlayer$Axis` bytecode: `getValueAtCurrentTick` lerps `findBefore`→`findAfter`, which besides consecutive keyframes yield `(0,input)`/`(beginTick,default)`→first, last→`(endTick,last)`, last→`(stopTick,input)` after endTick, last→`(endTick+1,default)` for loops, and the loop pair `findBefore(findAtTick(endTick))`→`findAfter(findAtTick(returnToTick-1))`; synthetic frames copy a keyframe's ease or use `KeyFrame(int,float)`'s `INOUTSINE`; the pair eases with either frame's ease (`isEasingBefore`). So every value is `a+(b-a)e` with `a,b ∈ {default, keyframes, input}` and `e` in the union ease range `[lo,hi]`: per axis `|v| ≤ (1+2m)·max(k,|input|)`, `k` = max |default/keyframe|, `m = max(hi-1,-lo)` (`e>1`: `M(2e-1)`; `e<0`: `M(1-2e)`). Over three axes with input norm `P`: `(1+2m)·√(|k|²+P²)` px. Each stack layer takes the preceding output as input. Per axis, repeatedly applying `F·max(k,|input|)` is bounded by `∏F·max(max(k),|initial input|)`. The graph walk retains player multiplicity across the seven roots, including a shared player/clip (deduplicating only modifier host back-edges within a root); factors from fade alternatives may overestimate but never shrink that product. Sorting the multiset for caching does not change the product. Input = the incoming bone pivot (`pa()` passes `b.x,b.y,b.z`): vanilla `PIVOTS[i]`, maxed with Create/SBW (both overwrite). A disabled track returns the input (covered). The ease argument `(tick+δ-t0)/(t1-t0)` is in [0,1] for `0 ≤ currentTick < limit` (native `tick()` stops a finite player at `currentTick ≥ stopTick`, wraps a loop past endTick; the loop wrap re-bases `t0`/`t1` by `endTick-returnToTick+1` around `currentTick`); a graph player outside that domain stays exhaustive | `fade·F_i·√(k_i²+P_i²)/16` m per bone, `F_i = ∏occurrences(1+2m_i)` (pixels to metres; c6 compared pixels with metres, an over-estimate) |
| PA ease | `PoseBound.easeRange`, derived instruction-by-instruction from the pinned `Ease`/`Easing` bytecode: LINEAR/CONSTANT(`0`)/STEP/`IN/OUT/INOUT` sine/cubic/quad/quart/quint/expo/circle stay in [0,1] (monotonic bases, `easeOut(f)=1-f(1-t)`, `easeInOut` halves); CATMULLROM base is exactly `t+2` wrapped in `easeInOut` (native samples 1, 4/3, -1/3, 0) to [-0.5, 1.5]; BACK `t*t*((c+1)t-c)`, `c=1.70158*arg`, critical points `0, 2c/3(c+1)` in closed form, OUT/INOUT mapped; ELASTIC is analytic, no sampling: the native base is `1-cos³(πt/2)·cos(πat)` in double narrowed once (`Easing.lambda$elastic$2`), both cosines in [-1,1] for every finite arg, hence IN [0,2] / OUT [-1,1] / INOUT [0,1] up to the 1e-6 narrowing slack (the `|arg| ≤ 8` gate is conservative); BOUNCE is the min of four fixed-form quadratics (`7.5625t²`, `30.25·a·(t-0.54545456)²+1-a`, `121·a²·(t-0.8181818)²+1-a²`, `484·a³·(t-0.95454544)²+1-a³` from `Easing.lambda$bounce$3..7`), so the combinator is Lipschitz with constant `max(15.125, 33a, 198a², 924a³)` (maximum absolute derivative of each quadratic on [0,1], with float coefficient rounding covered by the evaluation slack) and the 65536-grid (+ refinement) misses no value by more than that times half the step (mean value theorem), plus `1e-6·(1+484·max(a,a³))` float coefficient/subtraction/value slack, for arg in [0,8]. The grid samples the native IN/OUT/INOUT ease itself (c7: OUT was wrongly mapped through `1-·` a second time, inverting it for arg > 1; native OUTBOUNCE spans [0, 3.375] at arg 1.5 and reaches ~512 at arg 8); OUT `1-f(1-t)` and INOUT halving keep the Lipschitz constant. The ideal rational centres 6/11, 9/11 and 21/22 give endpoints 0/1 and a continuous INOUT; native float boundary jumps are covered by the rounding slack. Every keyframe ease of the catalog is sampled dense natively in `PoseBoundTest` | per-track `[lo,hi]`, unioned per state (+INOUTSINE synthetics), easing every consecutive keyframe pair |
| PA fades | every `Fade`/`LrForcedFade` node: `alpha=ease(progress)` (no arg), blend factor `max(1, 2hi-1, 1-2lo)`; the product over all graph fades over-approximates any nesting/cross-layer path (each factor >= 1) | `fade` multiplies pivots, body terms |
| PA body root | `applyWeaponRoot` (`ServerBodyPoses.java:1713`): `T(p+(0,.7,0))·R·T(0,-.7,0)`, translation `p + c - Rc`, `|c-Rc| = 2|c|sin(θ/2) ≤ .7·min(2,θ)`; body input is zero (`Vec3f.ZERO`), so `|p| ≤ (1+2m)·|k|`, same for the angle; applied at the render scale **before** `scale(.9375)` | `fade·|p| + .7·min(2, fade·(angle + 85°))` at render scale (c7: c6 multiplied it by .9375) |
| PA snapshots/speed | `LrFrozen` (source is always a `KeyframePlayer` per the graph validator) samples its clip; `Speed` only changes tick rate; `Container` passes through; empty graphs keep the exact old bound | no new term |
| ParCool animator | `Transformer.translate*` add (`bone.x += …`, `ParCoolBodyPose.java:453-487`) on top of the PA/vanilla pivot, and the root stages translate by height-scaled, tick-dependent factors (`getTick()/MAX_TICK` phases fed to `Easing`/`EasingFunctions` outside their segments, e.g. `KongVaultAnimator.getFactor` for phase > 1); the c6 «factor ≤ 2/8» and 5.1 root claims are not provable from the ports in this round | **exhaustive** (`unboundedFamily` = `parcool` whenever an uncancelled animator is present) |
| ParCool passive fall | `PassiveFallAnimator.animatePost`: `addRotate*`, `makeArmsMoveDynamically`/`makeLegsShakingDynamically` (`ParCoolBodyPose.java:387-413`: `xRot`/`zRot` only); native `rotate` is empty | 0 |
| Create hanging | `CreateBodyPose.apply` (`CreateBodyPose.java:416-463`, bf4 `setHangingPose`): `mesh` overwrites body/arm/leg pivots (arms `(±5, 2|2.5 slim, 0)`, legs `(±1.9,12,0)`), keeps head y/z; right arm `y -= 3` stays put (`|(-5,-1)| = 5.10`); arm pivot = right arm rotated by the swing + `(4.5,2)`, norm ≤ `5.10 + 4.92 = 10.02`; legs drop 0.8/0.2, every non-right-arm part is rotated (head not) and re-based by the arm pivot | `CREATE_PIVOTS` px per bone: head `4.2+10.02`, torso `10.02`, left arm `5.59+10.02`, right arm `5.10`, legs `11.36+10.02`, `11.95+10.02` (max 21.97; the c6 constant 27 was loose, not under) |
| SBW seats/gun | `SbwBodyPose.apply` (`SbwBodyPose.java:70-118`): parachute and every seat pose write rotations only; the swimming-gun branch overwrites the arm x with ±3 and keeps vanilla y (≤ 5.2) and z (≤ 5, attack/minigun radius) | `SBW_ARM = √(3²+5.2²+5²) = 7.81` px on the arms when `gun` (c6's 3 px under-estimated the norm) |
| SBW vehicle root | `SbwBodyPose.applyRoot`: `root.mul(vehicleRoot)` with `vehicleRoot = rotate(q)·rotate(180-yBodyRot)·scale(s)` (`freeze`/`projectedRoot`); SeatInfo carries no player-model translation (`position` only places the passenger entity, which IS the placement origin), so seats add no offset; `livingRoot` (sleep/upside-down) is skipped with a root | swim/body/model terms multiplied by `|s|` (reflection-safe), preceding crouch/medkit render offsets excluded, sleep/upside-down dropped with a root; non-finite `s` stays exhaustive |
| flight advance | c6: `advancing && age > 0` is never culled, so a mid-flight scale/seat/pose change never meets the launch bound; age 0 reads the launch recipe itself | none (exhaustive) |

`LIMB = max_i(PIVOTS[i]/16 + rig[i])` (crouched leg: 0.8113 + 0.7706 m), so
`scaled = .125 + vehicle·(1.4318·swim + BODY + .9375·(1.501 + LIMB2))` and `absolute = .125 (+ sleep, upside-down without a vehicle root)`, with `P_i = max(PIVOTS[i], CREATE_PIVOTS[i] if hanging, SBW_ARM on arms if gun)` px, `LIMB2 = max_i(fade·F_i·√(k_i²+P_i²)/16 + rig[i])` with clips (else `P_i/16 + rig[i]`), `BODY = fade·F_b·|k_b| + .7·min(2, fade·(F_r·|k_r| + 85°))` with clips else 0, and `vehicle = |passenger scale|` with a vehicle root else 1.
Summing both renderOffset branches and every flagged term is an over-estimate, never an
under-estimate (triangle inequality).

**Bounded only when** (`PoseBound.unboundedFamily`, else `null` → exhaustive):

- PA: the graph walks to exactly `KeyframePlayer` clips (plus `LrFrozen` snapshots of one),
  `Fade`/`LrForcedFade` blends, `Adjustment` (rotation-only, zero offset), `Speed` (tick rate only),
  `Stack`/`Container`/`ModifierLayer` plumbing; every track's eased range is bounded by
  `PoseBound.easeRange` (exact per ease, see below) over the catalog clip, fades multiply the
  `max(1, 2hi-1, 1-2lo)` overshoot product, and the `body` root translation/rotation feeds the
  `applyWeaponRoot` term. Anything else (`Modifier`, `Animator`, non-empty adjustment parts,
  non-finite speed, unknown catalog/clip, unboundable ease/arg) returns `null`:
- Create/SBW: always, given the PA walk above and finite size/scale; ParCool only without an
  uncancelled animator (passive fall rotates only; a cancelling option contributes nothing);
- every `KeyframePlayer` of the walk has `0 ≤ currentTick < (loop ? endTick+1 : stopTick)`;
- finite scale, bbHeight, standing eye height and SBW passenger scale.

**Transform sources checked:** `AnimSyncEval.pose/pa/fallback`, `ServerBodyPoses.renderOffset/
nativeRoot/livingRoot/playerRoot/applyWeaponRoot/medkit`, `VanillaBodyPose.reset/crouch/attack/special`,
`TaczPose.fallback`, `SbwBodyPose.apply/applyRoot`, `AnimSyncParCoolEval.Scratch` hooks,
`BodyRig.bones`, `BodyPose.accumulate`, `BodyShape`, `SnapshotWireMath.origin`,
plus the PA composition: `AnimationStack.get3DTransform` (layers chain, last active layer with
the part wins), `ModifierLayer.get3DTransform` (first modifier, else the animation),
`AbstractFadeModifier.get3DTransform` (`anim·α + begin·(1-α)`, `α = ease(progress)`),
`AdjustmentModifier.transformVector` (POSITION `v + offset·f`, ROTATION `v + rotation·f`;
both ports return zero offset, so pivots never grow; body yaw ≤ ±85° feeds the 0.7 arm),
`KeyframeAnimationPlayer.Axis.getValueFromKeyframes` (`lerp(a, b, ease(t))`, ease chosen by
`isEasingBefore`), `RotationAxis` (input/output clamped to radians: rotations stay
length-preserving), `GeckoLibSerializer.readCollection` (body x parsed ÷ 16 to metres with
sign flips; limb tracks stay pixels, like the vanilla pivots consumed by `BodyRig.bones`;
relative scale tracks are never read by `pa()`, which samples POSITION and ROTATION only).

**Not proved (exhaustive):** ParCool animator stages (c7); PA players outside the native tick
domain; positive-age flights (c6); PA graphs the catalog walk refuses (non-clip nodes, missing catalog
generation/clip, unboundable ease/arg — STEP with < 2 steps, ELASTIC with |arg| > 8, BOUNCE with
arg outside [0, 8]); non-finite size or passenger scale.

**Runtime check.** `CombatViews.exact` calls `PoseBound.check` on every evaluated exact body: the
largest distance of the 48 corners from the R segment against `r`; a violation is counted
(`poseBoundDefects`, `AnimSync` counter) and logged at error. `PoseBoundTest` fails on any defect.

**Evidence** (`:server:test --tests PoseBoundTest`, 10 tests green): rig term equals the generated
crouched-leg value; every scenario victim (incl. armed) is bounded (`unboundedFamily == null`); the same presentations with their PA
clips removed evaluate through the production composition inside the bound, and over 4000 random
segments per victim no segment that hits the evaluated body is rejected (candidate gate in
miniature), while far segments are rejected. `paClipsFromTheCatalogStayInsideTheBound` evaluates every armed victim through the full production composition (real graphs, no stripping) inside the bound with zero defects, sweeps every clip of every victim graph natively across all its ticks and {0, 0.5, 1} fractions (all seven parts, POSITION plus body ROTATION) against the hull, and samples every keyframe ease of the catalog dense natively inside its exact range. New this step: `parCoolRollVictimStaysInsideBound` (a real Roll victim: animator root + pivots through the full composition), `createHangingPoseStaysInsideBound` (hanging pivots), `sbwSeatPoseStaysInsideBound` (parachute + Pilot seat + gun) and `sbwVehicleScaleMultipliesTheBound` (the `|s|` scaling law), plus `flightAdvancedVictimStaysInsideBound` (one flight tick advanced over the retained closure at the same resource generation, evaluated at A+1).

## Remaining (continuation)

1. ~~Consume `PoseBound.reaches` on the candidate paths (`ConservativeCoverage`, `FamilyStamps`,
   the per-stamp `plannedEvaluations` batch) while keeping the current-body fallback reachable,
   and the ADR4 §10 exhaustive-vs-prefiltered candidate gate per family plus transformed oracle
   fixtures.~~ Done: `PoseBoundGateTest` (5 cohorts × 8 family paths × 3 anchors + 52 fixture
   segments, exhaustive vs prefiltered byte-identical hits with position+part, misses, verdicts;
   `view:culled` engaged on far segments, `view:exact*` kept on hits; `poseBoundDefects == 0`
   asserted in the gate and in `AnimSyncScenario.close`, so every scenario-based differential
   fails the build on a defect). Not transformed (plain JUnit), so no oracle-shard registration.
   Culled victims resolve to a null body (miss). The original c4 gate did not force exact
   unavailability on a current-body hit outside the R bound; the c6 correction below covers it.
   Current and R-time bodies need not coincide spatially in production.
   Extended (c4): the random cloud is replaced by every shot/segment the existing candidate-path
   fixtures generate (`FIXTURE_SEGS`, 52 distinct, re-based onto each victim): SbwHitscanTest,
   LrThrowableCombatTest (incl. the per-part rays), FamilyStampsTest (throwable chord, SBW-AP
   refine, lunge eye rays), BodyPoseBindingTest, StanceHitboxTest (prone + standing),
   BodyPartHitboxTest (incl. the assertPart grid), RangeTargetBodiesTest (husk grid, miss probes)
   and the blast centres of AreaExposureTest/AreaExplosionOcclusionTest/RangeTargetBodiesTest.
   5/5 green: 10 victim-runs × 55 segments = 550 exhaustive-vs-prefiltered comparisons
   (2310 family-path evaluations), defects 0.
2. ~~`AnimSyncBench` before/after (needs 1 and 3 to show any effect); the full four checks and
   `:server:oracleParallelGate`.~~ Done (c4, same machine, `--exclusive` slot,
   `-PanimSyncBench=30:9,30:29`, defaults: 120 warmup + 300 measured ticks, 3 runs each,
   medians; main-thread wall = `perTick.cpuNanos.mean`, alloc = `perTick.allocatedBytes.mean`):

   | cohort (combat) | wall before → after (ms) | alloc before → after (KB) |
   | --- | --- | --- |
   | 30x9 | 103.94 → 107.18 (+3.1%, run ranges overlap: 102.78–104.49 vs 102.46–107.22) | 64830 → 64823 (≈0%) |
   | 30x29 | 201.00 → 201.72 (+0.4%) | 128775 → 129379 (+0.5%) |

   Before = `origin/fulla/phase23` (`3063328b`, no UBOUND); after = this branch. Workload
   identical both sides (30x9: 409 receipts, 404 exact / 4 rejected / 1 unavailable; 30x29:
   389 receipts, 367 exact / 21 rejected / 1 unavailable). No measurable effect: the bench
   workload almost never leaves a victim reachable-but-far, so the prefilter rarely engages;
   the `reaches` check itself is noise (the c4 shot path never passed a segment, so
   `reaches` never culled; fixed in c5 below). Full gates green: `just format`, `just check`,
   `just test`, `just java-check`, `:server:oracleTest` (all shards), `:server:oracleParallelGate`.

   Done (c5, same machine, `--exclusive` slot, `-PanimSyncBench=30:9,30:29
   -PbenchCombatOnly=true`, defaults: 120 warmup + 300 measured ticks, 3 runs each,
   medians; main wall = `perTick.mainThreadWallNanos.mean`, evaluate = `evaluatePerVictim`
   CPU mean): the shot path now passes the production TaCZ segment (shooter eye along its
   look vector, 100 m) through the c3 segment-aware `resolve` overload, so `reaches` culls.
   Before = `origin/fulla/phase23` (`7f26d254`, USHARE-1) with the same bench fix adapted
   to its API (same eye ray derived, segment-less exhaustive `resolve`, exact-only audit);
   after = this branch. Receipts/verdicts identical both sides (same counts as c4 above).

   | cohort (combat) | wall before -> after (ms) | evaluate/victim before -> after (us) | culled share after |
   | --- | --- | --- | --- |
   | 30x9 | 123.24 -> 118.90 (-3.5%) | 265.3 -> 194.7 (-27%) | 2410/3636 (66%; 404 exact receipts x 9 rows) |
   | 30x29 | 260.55 -> 251.68 (-3.4%) | 265.8 -> 183.7 (-31%) | 8095/10643 (76%; 367 x 29) |

   The prefilter engages on most victims (off-axis from the shot ray) while the exact
   receipt count is unchanged and `poseBoundDefects` stays 0. Per-tick wall moves only
   ~3.5% because victim evaluation is a small share of the tick; culled victims skip
   `AnimSyncEval.evaluate` entirely. Culled victims resolve `view:culled` (null body =
   miss); the bench audit accepts it only when `PoseBound.reaches` is false for that victim.


## c6: advancing placement and current-body fallback

**P0.** Both `CombatViews.exact` and `plannedEvaluations` pass the flight age and advancing
flag to the same candidate predicate. For `advancing && age > 0` it keeps the victim
exhaustive: `prepareAdvance` resolves placement at R+age from retained real-state endpoints,
which can be arbitrarily far from the launch R segment. A generation-wide pose radius bounds
offsets from the *advanced* placement, not displacement since launch. Skipping this cull
preserves the original advance/evaluation and exception-to-current-body path, including
scale changes during flight. Instant and age-zero actions retain the analytic cull.
This trades positive-age culling performance for identical outcomes; c5 benchmark numbers
are historical instant-shot results, not measurements of this correction.

**P2c.** A failed R-bound test alone no longer returns `view:culled`. The candidate also
checks the committed current frame's union AABB against the shot's endpoint AABB, inflated
by the nonnegative blast radius plus `PoseBound.SLACK`. `BodyPose` constructs that union from
all compound-box corners, including its rounding slack. Every point of a shot segment lies
inside its endpoint AABB. Every sphere about a degenerate segment is inside its radius-inflated
AABB. Thus any possible current-body hit requires those AABBs to intersect; when they do,
the original exact evaluation and its labelled fallback remain reachable. The union check
can retain extra candidates, never remove a fallback hit. Culling still returns a null body
when both exact and fallback geometry are unreachable, so a successful exact miss cannot
silently turn into a current-body hit. A missing returned body/frame stays exhaustive
(`ServerBodyPoses.current` normally reseeds missing committed frames synchronously).

**Stronger gate.** `PoseBoundGateTest` uses the actual launch stamp throughout all family
paths (the old helper manufactured age-one stamps). Flight ages 1, 6 and 9 retain targets
moving 1 m/tick; age 6 also runs with two workers. At ages ≥6 the unarmed victim's through-body
ray is asserted outside the launch R bound while both victims resolve `view:exact_advancing`
in both modes (the armed victim's larger catalog hull may still cover the displacement). Every bounded
cohort (vanilla/PA, ParCool Roll, Create hanging, SBW seat) adds segment distances ρ±1e-5 and
blast-centre distances ρ+8±1e-5; analytic bound decisions are asserted on either side before
exhaustive/prefiltered outcome comparison, with exact/culled labels asserted at instant and
age-zero blast boundaries. Every instant cohort also checks age-zero
projectile/AP/throwable/blast paths. Injected evaluation unavailability and missing retained
flight boundaries force current-body hits far from R, serially and with two workers; both
modes must retain the labelled fallback and the actual hit. The defect counter remains a
zero-tolerance assertion. These are in-process combat checks, not live-client acceptance.

P1a/P1b/P2a/P2b and the review's other unverified transform claims are unchanged and remain
for the next round; this correction does not certify those parts of the radius proof.

Checks (2026-10-20):

- `./gradlew --no-daemon --max-workers=2 spotlessApply :server:test` with `--tests` filters
  `*.PoseBoundGateTest`, `*.PoseBoundTest`, `*.CombatViewsTest`, `*.FamilyStampsTest` and
  `*.AnimSyncParallelEvalTest`: **80/80 pass**, including **12/12 gate cases**, no skipped
  tests, zero pose-bound defects; the dependent GameTest run passed **13/13**.
- `just check`: pass. `just test`: pass (56 + 40 Python tests, client/server transformed
  oracle shards, offline equality report with zero unavailable/failing fixtures and all
  mixed samples bit-identical, pinned ADR4.2 wire gate).
- Gradle commands ran through the timeout-wrapped coordinator `gradle-slot`, with the
  pinned Temurin 21 JDK. No integration, timing benchmark or live-client acceptance in c6.

## c7: interpolation segments, scale order, verified family constants

Each item cites the pinned source; the table above is updated in place.

- **P1a OUTBOUNCE** (`PoseBound.bounceRange`): the grid samples the native ease itself, so its
  range is returned unmapped (c6 mapped OUT through `1-·` again). `outBounceStaysInsideItsRange`
  samples native OUTBOUNCE at args 1.5 and 8 (200 001 points): inside the range, max > 3 and
  > 500. The old mapping capped them near 1.02 and 1.12.
- **P1b synthetic segments** (`PoseBound.PaHull`, `extend`, `hullOf`): all-pairs hull over
  {default, keyframes, input} with the ease-union overshoot, derived from
  `KeyframeAnimationPlayer$Axis.findBefore/findAfter/getValueFromKeyframes` and
  `KeyFrame(int,float)` = INOUTSINE (javap of `player-animation-lib-fabric-2.0.4+1.21.1.jar`).
  The catalog walk fails closed outside `0 ≤ currentTick < limit`, where the ease argument
  leaves [0,1] (`Ease.invoke` does not clamp). Units fixed: clip pivots are pixels (`pa()` writes
  them into `b.x`) and are divided by 16 like the vanilla pivots. `endTickAndLoopOvershootStaysInsideTheHull`
  builds a clip whose consecutive pair is linear and whose last→input (after endTick) and loop
  last→first pairs ease with OUTBACK(2). Native samples exceed `input + 1` px, past the c6
  `max(PIVOTS, keyframes)` cover, and stay inside the hull term that feeds ρ.
- **P2a** (`PoseBound.of`): `applyWeaponRoot` runs before `root.scale(.9375)` (`AnimSyncEval.java:369-381`),
  so its term is at render scale; only the model drop and the pivots get `.9375`.
- **P2b / ParCool**: no longer applies. ParCool animators are exhaustive because the c6 pivot
  (12 px), root (5.1) and «factor ≤ 2/8» claims rest on tick ranges and out-of-segment
  `Easing` values that the ports do not bound (`KongVaultAnimator.getFactor` at phase > 1,
  `SpeedVault`/`Dodge` height-scaled `translateY`). Passive fall is rotation-only, so it is bounded with
  no term.
- **Constants:** Create 27 → derived per-bone `CREATE_PIVOTS` (max 21.97 px, `CreateBodyPose.java:416-463`);
  SBW 3 → `SBW_ARM` 7.81 px (`SbwBodyPose.java:105-117` keeps vanilla y/z; c6 was an under-estimate);
  elytra/riptide rotation-only (`ServerBodyPoses.java:1412-1477`); mid-flight scale change never
  reaches the bound (age > 0 exhaustive, c6). The SBW vehicle root is `rotate·rotate·scale(s>0)`
  (`SbwBodyPose.java:57-61`, `projectedRoot` `:750-755`), unchanged.


Checks (2026-10-20, timeout-wrapped `gradle-slot`, pinned Temurin 21): `spotlessApply :server:test
--tests *.PoseBoundTest --tests *.PoseBoundGateTest` **24/24 pass** (12 + 12, zero pose-bound
defects; the Roll cohort is asserted exhaustive with no culled label); `just check` pass; `just
test` pass (56 + 40 Python, transformed client/server oracle shards, offline report 0
unavailable/failing fixtures, mixed samples bit-identical, ADR4.2 wire gate). No integration,
benchmark or live-client acceptance in c7.

## c8 review fixes and remaining gate work

- PA: compose the overshoot factors for every reachable player occurrence across roots;
  preserve duplicate clip references in the cache key. A native two-layer CATMULLROM fixture
  returns 25.2 px from input 7.2 px and keyframe −7.2 px (the old hull was 20.365 px).
  The same fixture freezes a shared player under two PA roots and checks the production graph
  hull against the two-occurrence hull. Non-materializable nested Stack actors stay exhaustive.
- SBW: renderOffset precedes the passenger root; both crouch and medkit offsets stay outside
  the vehicle scale. Tests cover passenger scale 0.25 and 2.
- BOUNCE: the pinned bytecode uses powers a/a²/a³ in both coefficients and constants;
  the cubic slope allowance replaces the incorrect linear one. Native IN/OUT/INOUT sweeps
  cover arguments 0, 0.5, 1, 1.5, 2, 4 and 8 at 200001 points each, excluding the INOUT
  discontinuity from the secant-slope check. OUT at 8 exceeds the old `1893a` slope bound.
- Transformed `PoseBoundOracleFixture` registers in the server session and exactly one shard.
  It repeats the shared-player native counterexample with clip-digest immutability checks,
  and sweeps native BOUNCE at default/large args with raw-bit measurements. It requires a
  successfully applied production server mixin config; PA's own config is client-only.
- NaN query distances/margins retain exhaustive resolution. Knife spectator victims retain
  the body fallback; ordinary culled victims remain misses.

P1b is **not closed**: native pivot/rig sweeps isolate those terms from root-translation
slack. The added PA fixture installs a synthetic native body clip in the fixture catalog,
freezes/materializes it through the production graph and evaluates its six boxes; its
−1000→1000 metre CATMULLROM pair deliberately makes the PA term dominate the full radius.
Sleep/swim witnesses measure every corner's translation relative to the identical orientation
without translation; elytra measures the preserved pivot/rig radius (its added translation term
is zero). These source witnesses do not replace the analytic proof or the exhaustive candidate
gate. Baseline `PoseBoundGateTest` (19 cases), `PoseBoundTest` (15), `KnifeCombatTest` (19) and
`KnifeCombatRulesTest` (13) all pass, as do `just check` and `just test` (both transformed oracle
suites, cross-JVM differentials and the pinned wire gate).

Measured term margins `(rho - farthest)/rho` in c8:

| Family | Farthest / term, metres | Margin |
| --- | --- | --- |
| vanilla crouch + attack | 1.577271 / 1.581729 | 0.282% |
| Create hanging sweep (includes maximum swing) | 1.567357 / 2.144016 | **26.896% — unmet** |
| SBW gun/swim arms | 0.878351 / 1.152685 | **23.800% — unmet** |
| PA synthetic overshooting body clip (full pose radius) | 2000.023087 / 2004.180629 | 0.207% |
| sleep corner displacement | 1.520000 / 1.520000 | 0.000% |
| swim/crawl corner displacement | 1.431782 / 1.431782 | 0.000% (float tolerance) |
| elytra rotated pivot/rig | 1.577271 / 1.581729 | 0.282% |

No ≤5% witness is claimed for Create/SBW. In particular,
`CREATE_ARM = hypot(5,1)+hypot(4.5,2) = 10.023448 px` is a loose triangle bound.
The native swing is only 5°..25°. For the wide arm pivot, the squared norm is
`50.25 − 49 cos(θ) − 11 sin(θ)`; for slim it is
`49.5 − 47 cos(θ) − 15.5 sin(θ)`. Both are convex on that interval, hence their maxima
are at endpoints: wide 1.091842 px, slim 1.152361 px (float noise below 0.001 px).
No native arm-pivot witness can approach 10.023448 px within 5%. Meeting that requested
criterion needs a tighter, correlated Create bound and analogous per-family proof work;
this round does not reduce a proven bound just to manufacture mutation sensitivity.


Local 10% mutation results (`PoseBoundGateTest` + `PoseBoundTest`; mutations restored):

| Reduced term | Gate result | Bound-unit result |
| --- | --- | --- |
| whole radius | fails PA worst-corner witness | passes |
| vanilla pivots | fails crouch + attack and elytra witnesses | fails rig term |
| PA hull (axis and pivot) | fails PA worst-corner witness | fails catalog and synthetic-segment sweeps |
| swim | fails swim corner-displacement witness | passes |
| sleep | fails sleep corner-displacement witness | passes |
| `CREATE_ARM` | passes — remaining P1b gap | passes |
| `SBW_ARM` | passes — remaining P1b gap | passes |
| upside-down | passes — no tight witness added | passes |
| model scale | passes — no tight witness added | passes |
| render offset | passes — no tight witness added | passes |

Elytra/death/passive-fall add rotation only, so their added translation term is zero and
has no nonzero 10% mutation. Evidence (logs, per-case failures, baseline JUnit XML and the
reproducible mutation script): workspace `.dev/coord-20261011/evidence/ub1-c8/`.
No integration, release, deployment or live-client acceptance in c8.

Verification note: after registering the transformed fixture, a reused configuration-cache
entry retained the old shard map and correctly failed `verifyOracleShards` for the new class.
The final `just test` passed with `GRADLE_OPTS=-Dorg.gradle.configuration-cache=false`,
recomputing both complete shard plans; this worktree's stale configuration cache was then
cleared. No build configuration or gate was weakened. The focused transformed fixture also
passed independently with `:server:oracleTest -PoracleOnly=PoseBoundOracleFixture`.
The normal cached `:client:verifyOracleShards :server:verifyOracleShards` invocation also
passed after cleanup (37 client fixtures and 24 server fixtures).

## Residuals (coordinator-accepted P1b remainder, 2026-10-20)

Coordinator decision: the Create and SBW bound terms stay 24–27% looser than
their worst-case native witnesses (Create hanging sweep 26.896%, SBW gun/swim
arms 23.800%; c8 table above). A loose bound is conservative — it can only
cull less, never turn a hit into a miss — so it is accepted without tightening.

- **Witness margins:** every other family term is within ≤0.3% of its witness
  (vanilla crouch + attack 0.282%, PA synthetic 0.207%, sleep 0.000%,
  swim/crawl 0.000% float tolerance, elytra 0.282%).
- **10% mutations the gate does not reject:** reducing `CREATE_ARM` or
  `SBW_ARM` by 10% still passes `PoseBoundGateTest` + `PoseBoundTest` (c8
  mutation table); upside-down, model-scale and render-offset reductions also
  pass for lack of a tight witness. Only the radius, vanilla-pivot, PA-hull,
  swim and sleep reductions fail. This round does not shrink a proven bound
  just to manufacture mutation sensitivity.
- **Regression guard:** the runtime `poseBoundDefects` («box beyond ρ»)
  counter stays a zero-tolerance assertion in `PoseBoundGateTest` and
  `AnimSyncScenario.close`, so every scenario-based differential fails the
  build on a defect.

## c9b: integration gates and current benchmark (2026-10-20)

Mockito inline invocation graphs retained scenario mocks between tests (Mockito #1619).
`PoseBoundGateTest` and `PoseBoundTest` now clear inline mocks after their existing
defect assertions. The full `:server:test` passes at its unchanged 384 MiB heap:
1861 tests, no failures, errors or skips. No fixture or production code changed.

Full gates passed: `just format`, `just check`, `just test`, `just java-check`,
`:server:oracleTest` (all shards), and `:server:oracleParallelGate`. GameTest:
13/13 required tests. The scenario defect assertions passed, no gate logged
`A8 box beyond bound`, and all three benchmark counter deltas had
`poseBoundDefects == 0` (absent zero-valued counters are omitted by the runner).

Current branch (`d9727b29`, including merged phase23 `02ef5534`), same machine,
one exclusive slot for three runs: `:server:oracleTest
-PanimSyncBench=30:9,30:29 -PbenchCombatOnly=true`, 120 warmup + 300 measured ticks.
Medians of per-run means: wall = `perTick.mainThreadWallNanos.mean`,
evaluate/victim = `operations.evaluatePerVictim.cpuNanos.mean`. Culled share uses
`eval:view:culled / (EXACT:exact * seenPerObserver)`, as in c5.

| cohort (combat) | wall (ms) | evaluate/victim (us) | culled share |
| --- | --- | --- | --- |
| 30x9 | 125.84 | 331.5 | 1898/3636 (52.2%) |
| 30x29 | 281.99 | 325.2 | 4800/10643 (45.1%) |

Receipt verdicts remain 404 exact / 4 rejected / 1 unavailable (30x9) and
367 / 21 / 1 (30x29) in every run. The c4/c5 tables above remain historical;
these are current-branch measurements, not a new before/after comparison.
Raw run JSON, command logs and medians are retained in workspace
`.dev/coord-20261011/evidence/ub1-c9b/`.

## WALL-CLIP (UBOUND-3, 2026-10-20)

Owner decision 2026-10-20: every combat shot segment ends at the native first-block stop
*before* candidate victims are chosen, so players behind walls are neither resolved nor
evaluated. Outcomes never change: a victim beyond the block the native code stops at cannot
win.

**Rule.** The A8 candidate test ([`CombatViews.candidate`](../../server/src/main/java/com/lunares/blockfield/server/combat/CombatViews.java))
receives the segment `from→stop+ε` instead of `from→to`, where `stop` is the block location the
native path itself already computed on the same ray (no second raycast) and
`ε = ConservativeCoverage.WALL_SLACK = 1e-3` m toward inclusion
([`ConservativeCoverage.wallCut`](../../server/src/main/java/com/lunares/blockfield/server/combat/ConservativeCoverage.java)).
Only candidacy shrinks: the per-victim clip keeps the native segment, so every victim whose hit
could still beat the block resolves exactly as before. A hit at distance `d ≤ |stop−from|` lies
on the shortened segment, so the A8 bound (`PoseBound.reaches`) still reports it reachable.

Per path (bytecode of the pinned JARs):

| path | native stop | verdict |
| --- | --- | --- |
| TaCZ bullet (bf8) | `EntityKineticBullet.onBulletTick` @71–152: `BlockRayTrace.rayTraceBlocks(COLLIDER, NONE)`, `end = hit.location` unless MISS, then `findEntityOnPath(start, end)` | already capped natively: `getHitResult` (our `BulletHitboxMixin.blockfield$combatBody`) receives that `end`, which is the A8 segment. No change. |
| TaCZ Tweaks raytracer (3.0.0-alpha.10) | `raytracer.EntityKineticBulletMixin` cancels the rest of `onBulletTick` after `rayTraceBlocks`; `BulletHandler.handle` calls `findEntitiesOnPath(currentHitPosition, blockHit.location)` per block hit, piercing per `bullet_interactions` | already capped natively per sub-segment; a pierced block starts the next sub-segment. No change. |
| SBW hitscan (bf24) | `GunItem.shootRay` @68–282: `level.clip(VISUAL, NONE)` over the full range; `pos = state.canOcclude() ? blockHitResult.location : null`; entity search over the full range; block wins iff `eye.distSq(pos) < eye.distSq(entityHit)` | **capped**: `BodyPartGunDamageMixin.blockfield$posedPlayers` captures `shootRay`'s own `blockHitResult`/`state` locals (`@Local`, unique types at the call); `SbwCombatHitbox.nativeStop` applies the same `canOcclude` rule; `hitscan(…, stop)` passes `wallCut` to the candidate test. Ties (`d == stop`) are kept by ε. |
| LR throwable (bf2) | `ThrowableItemEntity.getHitResult`: `level.clip(COLLIDER, NONE)`, `to = hit.location` unless MISS, then `getEntityHitResult(level, this, from, to, …)` | already capped natively: `LrThrowableCombat.select` receives that `to`. No change. |
| SBW taser | vanilla `AbstractArrow.tick` clips blocks first and hands `findHitEntity` the clipped end (`CombatProjectileRayMixin`) | already capped natively. No change. |
| LR knife | `KnifeCombat.target`: `level.clip(COLLIDER, NONE)`, `maxSq = eye.distSq(wall)`, winner needs `distSq < maxSq` | **capped**: the same `wall` result feeds `wallCut` for `nearest(…, cullTo)`. |

**Exceptions (behaviour unchanged).** SBW penetration (`SbwPenetration.refine`) keeps its own
trace `start→start+direction` as the segment, never a wall. TaCZ glass: `BlockRayTrace` passes
through its pass-through blocks, so they never become the native stop; glass it does hit is the
native stop of that tick (the native entity search ended there before this change too;
`DestroyGlassBlockMixin` is untouched). SBW glass: a non-occluding first block yields
`nativeStop == null` (no cap), exactly as native lets the entity win past it. Explosions
(`AreaExposure`) keep the sphere test and per-victim exposure rays. No path was found whose native
winner admits an entity beyond its first stopping block.

**Tests.** `WallClipGateTest` (plain JUnit on the `AnimSyncScenario` cohort; the shooter's level
stub clips every ray against a wall plane at 5 m (in front of the cohort, 20 m away), 30 m
(behind it) or nowhere, opaque or glass): SBW hitscan and knife give identical outcomes with the
test-only `ConservativeCoverage.wallClip` on and off, and with the wall in front resolve zero
victims (`CombatViews` `resolved:*` counters) where the uncapped run resolves them; glass keeps
SBW's resolution count; TaCZ and LR throwable on the native capped segment equal the uncapped
segment with hits beyond the stop turned into block wins and resolve nothing behind the wall;
SBW penetration resolves victims behind the wall in both modes. `wallCut` slack/never-extend and
`nativeStop` (occluding only) have unit checks. No gate loads SBW's `GunItem` through the mixin
transformer, so an ASM test pins the `@Local` capture: in bf24 `shootRay` exactly one
`BlockHitResult` and one `BlockState` local are live at the wrapped `getEntityHitResult` call.

Checks (2026-10-20, timeout-wrapped `gradle-slot`, pinned Temurin 21): `just format`, `just check`,
`:server:test` (1864 tests at the unchanged 384 MiB heap, 0 failures; the ASM case was added
afterwards and passed in a targeted `WallClipGateTest` run, 4/4), `:server:oracleTest` (both
shards) green.

**Bench.** `AnimSyncBench` gained `-PbenchWallAt=<blocks>`: a wall plane that far in front of
every shooter, the same plane stub the gate clips against; the TaCZ shot segment then ends at the
plane, as the native bullet tick hands it to `getHitResult`. Same machine, `--exclusive` slot,
`:server:oracleTest -PanimSyncBench=30:9,30:29 -PbenchCombatOnly=true [-PbenchWallAt=8]`, 120
warmup + 300 measured ticks, 3 runs each, medians of per-run means. Wall =
`perTick.mainThreadWallNanos.mean`, evaluate/victim = `operations.evaluatePerVictim.cpuNanos.mean`,
resolved/shot = non-culled victim resolutions per measured exact shot (of all reported victims).

| cohort (combat) | wall | main wall (ms) | evaluate/victim (µs) | resolved / reported per shot | culled (every run) |
| --- | --- | --- | --- | --- | --- |
| 30x9 | none | 119.39 | 313.9 | 4.72 / 9 | 1898 |
| 30x9 | 8 blocks | 124.50 | 296.5 | 3.73 / 9 | 2271 |
| 30x29 | none | 265.40 | 303.7 | 17.70 / 29 | 4800 |
| 30x29 | 8 blocks | 260.74 | 283.8 | 13.99 / 29 | 6130 |

The no-wall baseline culls exactly what A8 culled (1898 and 4800 in c9b) with identical receipt
verdicts (404/4/1 and 367/21/1); its wall/evaluate medians sit 5–7 % below the c9b numbers
(125.84 ms / 331.5 µs, 281.99 ms / 325.2 µs), inside this machine's run spread (no-wall runs:
117.7–140.8 ms and 258.4–296.7 ms). The wall at 8 blocks resolves 21 % fewer victims per shot
and lowers evaluate/victim by 6–7 %; the main-thread wall change is within noise (one shot per
tick against ~120–260 ms of other per-tick work). No run reported a `poseBoundDefects` counter.
Raw JSON, logs and the median script: workspace `.dev/coord-20261011/evidence/ub3-c1/` (the
first two no-wall attempts failed writing `NaN` into the JSON; fixed, rerun as runs 1–2).
