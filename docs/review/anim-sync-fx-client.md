# Animation sync FX-CLIENT

Contract: ADR4.1 and its errata, U1 schema manifest 4. Starting integration pin:
`18e0d02e52fc15ee5641f8aad50fb4bd74f6642d`. This unit changes client scene,
journal, ticket, native-carrier delivery, capture validity and asset retention.
The coordinator's follow-up also authorizes additive retained-item readers in
the FX-POSE SBW adapter and living-input mixin; its `ac472953` integration is merged.
Server validation and resource admission remain with their assigned units.
Continuation 2 additionally owns the four server SBW special-arm formulas by the
coordinator's explicit decision. All six assigned review findings were confirmed
in the starting source; none was rejected as a false positive.

## P0-1 — render clock units

`CombatSceneRenderMixin` passed nanoseconds to `mainFrame(double nowMs)`, which
multiplied them again. It now calls `frameEntry(long nowNs)` directly. Snapshot
arrival and frame sampling both use `System.nanoTime` with one conversion.
`FrameEntryOracleFixture#renderHookUsesTheArrivalNanosecondClock` warms the real
scene, invokes the production callback merged into `GameRenderer`, and requires
a receipt and ticket. `CombatPinnedWritersTest` pins its exact caller.

## P0-5 — complete reports require installed presentations

`exactReport` previously looked only at journal rows. It now refuses the entire
revision if any required H-batch remote is absent, excluded, missing its sample,
labelled unavailable or lacks a successful `DrivenPresentation` installation.
The victim catalogue is unchanged; no row is silently removed. Client installs
now consume `AnimationResolver.resolve` with the complete buffered history,
seed, projected graph, victim row, policy and retained definitions. This checks
R/A generation boundaries and complete endpoints identically to the server.

`ExactSceneOracleFixture` requires a real transformed installation before a
positive ticket, then forces LR-view loss and checks refusal. A second case
excludes a visible co-passenger and checks the whole report is unavailable.
`ClientExactSceneTest` now explicitly checks that its plain mock cannot install
native PA roots and cannot obtain an exact ticket from a valid journal alone.
Missing state remains labelled unavailable and native animation continues.

## P1-6 — mapping belongs to actual application

A held capsule previously remapped its original receipt timestamp through the
latest probe. The journal now captures the mapping id and causal coordinate at
causal release, retaining the initial receipt separately. It reuses that value
when the first showing frame supplies its frozen R/A/H and revision. Later probes
cannot rewrite it. Pending seed/native-presence waits use their actual release
boundary; no earlier application time is invented.

`ClientAnimationJournalTest#heldApplicationKeepsItsReleaseMappingAcrossNewerProbes`
executes receipt → newer probe → predecessor release → newer probe → first frame,
checks server mapping/deadline intersection with `AnimationTiming`, and replays
the report. The transformed reordered-capsule case in `SceneTimingOracleFixture`
also crosses probe boundaries and reconstructs the same graph/start digests.
FX-TRUST owns authoritative mapping-id, application-clock and causal-order admission.

The original integration audit found that FX-TRUST `d8824276` required the
release mapping to belong to probes completed before send, incorrectly rejecting
release mapping 102. The remote FX-TRUST branch now separates the actual completed
application mapping from the frozen send deadline and carries seed closure/reissues
cold seeds (`bdc37d98`, including `fb9a5d3b`). It has not landed on `phase23` as of
this checkpoint. Its bounds-only mapping test is not the remaining production
receipt-admission proof. The coordinator's honest `seed_activation` follow-up and
that integration proof remain pending; no client timestamp is relabelled.

## P1-7 — pair before per-target native side effects

TaCZ/LR carrier dispatch moved from the scene's unconditional receive loop to
the journal's validated causal release. A release requires the active seed's
complete generation identity, no pending replacement seed, the correct native
Add/current entity object, the paired subscription and its predecessor. New-run
snapshots waiting for native Remove/Add cannot authorize an old object. Pending
entries retain the whole event/payload. A Remove preserves a newer unclaimed
run's journal for the matching Add. Dispatch deduplication commits only after
native decode/invocation succeeds, so failure is retryable. ParCool's all-player
batch remains a separate once-per-delivery path.

`NativeCapsuleOracleFixture` uses production scene intake, the real carried
TaCZ/LR codecs and native handler bodies. It covers delivery-once, missing Add
followed by valid release, and a delayed old-life carrier around a same-id
replacement. The native shoot-event counter must remain unchanged for absent or
wrong-life targets. Existing timing fixtures still execute native animation.

## P1-8 — current-frame F3+B evidence

`frameEntry` now expires DRAWN along with MAIN/SHADOW. `debugSnapshot` returns
null when current MAIN is absent. The capture reset moved to `GameRenderer.render`
HEAD; the level-pass start/end remain around `renderLevel`. Thus a frame omitting
the level draw also invalidates both evidence APIs.

`FrameEntryOracleFixture` executes merged production entry/pass callbacks and
checks both public readers after a drawn frame, a culled main and a no-level
frame. `OracleInjectionTargetTest` pins the HEAD reset; the F3+B unit tests check
the drawn-cache regression. These tests make no GPU or live-render-loop claim.

## P2-9 — generation retirement

The scene enumerates generations referenced by buffered records/baselines/held
batches; active/pending/fold seeds, reductions and full pending capsules; installed
presentation inputs, current captures and still-sendable immutable receipts; and
READY/deferred resource requests. Catalog dependency generations are retained
transitively. Only unreferenced generations leave both GENERATIONS and ADMITTED;
the newest staged generation remains available for activation. The added common
resolver's definition store retires unpinned definitions after the same reference
scan, including transitive dependencies, rather than growing per tick.

`ClientCombatScene.retainedDefinition` now reads this store, preserving FX-POSE's
additive API. Exact scene fixtures send the main-hand definition only in the first
batch and require its availability after eleven later batches omit it.

The transformed three-generation lifecycle keeps generation 0 while its old
record/seed/receipt is live, retires unused generation 1, then retires generation
0 after Remove, the 40-tick record window and receipt expiry without clearing the
connection's generation 2. Newer arrival alone never evicts a live generation.

## Coordinator follow-up — retained SBW items

The prone tail's retained-gun/live-empty case could not install because it checked
the live stack. The two native custom arm handlers also call
`ModEnumExtensions.Client.applyCustomArmPose`, which reads
`getItemInHand(MAIN_HAND)` and tests the live item's class. The retained item ref
now resolves its canonical item id through the connected player's item registry.
Only the class is materialized, matching these pinned native readers and the
server's retained-item port; other native held-item readers retain their stacks.
Missing definitions, malformed values and unknown item ids are unavailable.

The driven frame retains that stack. The existing SBW-only stack-walk guard now
covers the merged right/left arm handlers as well as the model tail; an additive
`getItemInHand` injection serves the arm reads. It cannot affect local players,
undriven remotes or readers outside those handlers. Seat/link/transform validation
is preserved. `DrivenSbwTargetTest` pins the native class tests and getter call.

The mixed renderer gains retained-special/live-empty and retained-empty/live-special
cases for LungeMine, Minigun, M2HB and Super Star Shooter; the previous prone
retained-gun/live-empty case now has its canonical definition. Fixtures supply
real constructor-free pinned item classes and isolated item-registry lookup
services because mod entrypoints are not run. Their holder/components are fixture
services, not an item-registration acceptance claim. The server fixture supplies
the same retained class to its existing special-pose port. Non-SBW scope reads
are asserted to retain the live stack. That first continuation changed no pose
formula; the correction below belongs to continuation 2.

## Continuation 2 — native float arms, report retention and raw yaw

Merged the latest `phase23` (`25bead1b`, FX-SEAT) as `4f7788c4`. The server
mixed-fixture merge preserves both retained-special item selection and retained
seat-root projection; no other unit's semantics were changed.

`VanillaBodyPose.special` now passes the existing head float radians directly to
`HeldWeaponPose.sbwArms`. Each LungeMine/Minigun/M2HB/Super Star constant, float
addition and Minigun/M2HB float clamp matches pinned bf24
`ModEnumExtensions.Client.*Arms`. The degree-based legacy capture calls the same
float helper after the native head conversion. Super Star also preserves its
native z reset; later walk/bob composition still runs. The client native renderer
is unchanged. `VanillaBodyPoseTest.sbwSpecialArmsKeepNativeFloatHeadBits` checks
raw arm bits and z ownership; the retained-special mixed cases execute the real
native item handlers with production mixins. All former 226 differences are gone.

`ClientAnimationJournal.report` previously keyed pending reports only by entity.
A same-revision fold replaced the old-seed report and lost its unsent applications.
It now keys them by full identity and seed, keeping insertion order and immutable
application lists. `drainUnreported` returns those ordered reports.
`ClientCombatScene.reportApplications` emits ordered batches with at most one row
per entity: the old-seed applications reach the server before the successor's
activation. No application proof, seed identity or application H is invented,
rebased or discarded. `sameRevisionFoldKeepsTheUnsentApplicationUnderItsOriginalSeed`
reproduces the exit-after-fold-prefix case and reconstructs its stopped state with
the retained seed activation. Transformed `fx-client-fold-unsent-application`
installs a Create event and adopts the fold in one revision, requires both report
identities/proof coordinates once, and samples the actual native model repeatedly.
This changes the client-local drain API, not the U1 schema or packet layout.

`SnapshotResolver.exact` now samples view yaw, turret yaw and gun yaw with the
native renderer's raw-pair float lerp, including `p == 1`. Body/head yaw retain
their native wrapped rule. Transformed `fx-client-native-yaw-pairs` runs real SBW
Default/Turret/WeaponStation quaternion functions before and after the production
scene write. Every quaternion component agrees bit for bit at `R=103.5` across
179→-179/-179→179 and at integer `R=104`, where the base-yaw raw lerp is
`0.00099945068359375` rather than the current field's `.001f`.

## Continuation 3 — FX-TRUST integration and the production wire

Merged `phase23` at `9cf76d2a` (FX-TRUST, FX-SEAT-3) and again at `f2150793` (UOPT). The only
conflict was the shared `MixedRendererCases` registry; both sides' entries are kept.

**Prefix high-water.** `ClientAnimationJournal.row` now keeps, per life (subscription), the highest
prefix it has named. A canonical start that ends no longer lowers the next row's prefix, which the
server rejects as `prefix_backwards`. Transformed `tacz.fx-client-prefix-high-water`: a TaCZ lower
selection at S[105] and its no-gun stop fade at S[110] raise the prefix to 26,872 then 28,152; after
the fade completes no start names either (recomputed prefix 25,601), the row keeps 28,152 and the
server-side replay of all three rows reproduces starts and both digests.

**Production wire.** `docs/review/anim-sync-fx-client-wire.json` is a recorded cross-JVM vector,
re-verified by both halves on every run:

- `FxClientWireTest` (server) runs the production stream — capture, Create journal, probes,
  `capsules`/`delivered`, `prepare`/`publish` — on a scripted server clock (boundaries, drains, END;
  capture readings, probe send times and seed issuance are scripted, everything else is production).
  Its S2C section must equal the committed one (definitions compared as a set: production orders them
  by hash iteration). It then processes the recorded client C2S in their task drains, before the next
  START boundary as on a real server, through `AnimSyncStream.echo`, `AnimSyncValidation.applied` and
  `AnimSyncValidation.validate`.
- `FxClientWireOracleFixture` (client, transformed) plays those S2C bytes into the production scene at
  their arrival times, 60 FPS frames, with **no added definitions**; it records echoes,
  AnimationApplied and four tickets, which must equal the committed C2S section.

Scenario: cold join with a 140 ms S2C stall (no completed probe at the first seed); the first echoes
complete at drain 104, the server reissues the seed at END 104 and the client presents only that warm
seed; a Create enter (accepted 5249 ms, sent 5260 ms) arrives while that seed is still pending and is held
until it activates at A = 104.15, released under mapping 105 — a probe sent after the enter's
send-time cohort 99–103; the exit follows at tick 108. Result: the tickets one drain after each first
report are **EXACT** (held enter under release mapping 105; after the exit with mappings 105/107).

`ExactSceneOracleFixture`'s positive fixtures (ticket install, co-passenger, generation retirement)
now run on this recorded production stream; `aMissingServerIssuedSeedDefinitionIsExplicitlyUnavailable`
withholds exactly the stream's SeedRef definitions and still fails closed. `prime()` keeps empty
definitions only for the native-carrier/timing fixtures' fixture-built seeds, which have no server
issuance.

### Blocker (FX-TRUST): a first report is never mappable where it is processed

Every C2S runs in a task drain after the latest START boundary. `frozenBound` →
`AnimationTiming.bound` maps `min(networkDeadline, firstReportNs)` with `Boundaries.at`, which refuses
any time after the last recorded boundary. A prompt first report is therefore always
`start_bound_unavailable` in its own drain; only a later receipt can map the stored first time. In the
recorded run: both honest AnimationApplied reports are refused `start_bound_unavailable`, the third
`application_history` (the never-admitted seed activation lies below the A high-water a later EXACT
ticket raised); tickets in the drain of a first report are UNAVAILABLE; `Reports` is never created, so
`AnimSyncStream.fold` can never fold this life; per `ClientAnimationJournal.row` its rows then
become `seed_window` once the seed leaves the 40-tick window (follows from the code; not run here). Deferring every AnimationApplied by one drain (local experiment, not committed) changes
nothing: the seed activation is still first reported there. Server tests pre-record future boundaries
and do not see this. FX-CLIENT cannot fix it (the client cannot learn or influence the drain); the
`AnimSyncValidation`/`AnimationTiming` bound belongs to FX-TRUST. `FxClientWireTest` records every
outcome (`serverOutcomes` in its build output) and asserts only the admitted later-drain tickets, so it
is the regression to flip to "every report admitted" when fixed.

### Verification (final merged head)

After merging `f2150793`, all four commands passed in order under the heavy lock, memory guard and
pinned JDK: `just format`, `just check`, `just test`, `just java-check`. JUnit: client 534 (two
existing opt-in skips), server 1,719, zero failures/errors; 113 client and 33 server transformed
fragments, all pass; all 13 required GameTests pass and the server shuts down. Mixed renderer:
**7,952/7,952 bit-identical**, zero unavailable, maximum error 0 m; native and server SHA-256 both
`30c1c4204c68c15034a899a6980293a77e36a294fbad6a4d4fd8746277b80ac8`. The retained-gun/live-empty SBW
case FX-GATE listed as a fail-closed `sbw_main_hand` diagnostic is exact since continuation 1, so the
report's exemption is removed (it is now counted, 142 samples); its regression test keeps the
mechanism with a synthetic case.

Integrated into `fulla/phase23` at `106ce145` (code verified at `49bb0abd`, then docs only).

No ADR4/schema deviation; U1 manifest unchanged. No stand/client, production operation,
release/tag or Deploy.
