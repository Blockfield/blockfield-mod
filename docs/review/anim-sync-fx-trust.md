# FX-TRUST server trust and lifetime fixes

Review base: `18e0d02e`; initial merged equality base: `ac472953` (FX-POSE),
merge `3ad38cb4`; previous integration base: `25bead1b` (FX-GATE/FX-SEAT), merge `bdc37d98`;
current base `cc156290` (named SBW matrix paths), merge `d9ba214f`. Findings P0-2/P0-3/P0-4/P1-5/P1-6 and
coordinator seed-closure/bootstrap additions. Contract: ADR4.1 and errata;
U1 manifest 4 unchanged. Integration validation is recorded below.

## P0-2 — authorize applications before replay or folding

Root cause: client-selected frame/revision could reverse native Create ENTER B /
EXIT C despite matching public reducer digests. Mapping IDs were ignored, send
probe evidence was recalculated at report time, seed activation was unbounded,
and folding consumed mutable reports without a common authorization history.

Fix: freeze each capsule's E/D deadline evidence at actual delivery, and each
seed's issuance E / actual-send D evidence at publication. The last <=32 eligible
completed probes determine RTT_hi/J_hi; their freshness is measured at D's tick,
including when acceptance occurred earlier. Missing evidence is frozen too.
`AnimationTiming.freeze` / `bound` still determine Ue; later echoes cannot widen
or repair that deadline.

The actual application mapping is separate from that frozen cohort. Its nonce
must have completed on this connection before the application's first report and
remain inside the retained probe window (64 entries, 40 ticks). A probe completed
after D can supply that mapping. Manifest 4 does not carry a client monotonic apply
coordinate: over all nonnegative unknown client elapsed times, the server-known
mapping's admissible interval is `[probeSendNs, firstReportNs]`. Intersect that
interval with `[E, Ue]`, using recorded boundaries without extrapolation (decided at the boundary
after the first report when it is not yet recorded: see FX-TRUST-2 below). A structurally authorized application's
first receive time is retained even if its bound is not yet available, so a retry
cannot borrow a nonce completed only after that first report. Intersect
with E before projection so a probe sent before the first recorded boundary does
not incorrectly make a later valid E unavailable.

A whole-packet preflight precedes receipt reconstruction, application-report
mutation and folding. It checks the actual sent incarnation/seed and application H;
application frame/revision <= consuming frame/revision; applyA <= receipt A;
connection frame/revision/A high-waters; and consistent connection application
clock history. Merge retained and new applications by authoritative event sequence.
Check each capsule predecessor against the previously applied capsule (or absorbed
seed prefix), with non-decreasing frame/revision/applyA. Freeze all ObserverApply
fields including sW, mapping, R/A/H, frame/revision and prefix, including no-node
outcomes. Reconstruction and digests remain subsequent consistency checks.

Incremental heartbeats carry a seed-activation template. Its ObserverApply proof is
immutable; native node/layer/u0 metadata is derived from the server seed during
reduction, rather than confused with contributing receipt-start metadata. Reports
rehydrate the complete authorized prefix, including applications first reported by
a receipt. Missing delivered applications cannot enter a fold. Successor adoption
retains post-prefix applications at their original authorized coordinates/H, which
can precede that adoption. History and frozen evidence expire with their retained
seed/capsule. Shared `ObserverReducer` remains untouched.

Tests: `reversedCreateEnterBExitCIsRejectedBeforeReplayOrFolding`,
`applicationCoordinatesCannotComeFromAFutureFrameRevisionOrA`,
`firstAppliedReportMakesEveryApplicationImmutableBeforeAnyReceiptReplay`,
`frozenNonceEvidenceIsConnectionLocalAndCannotBeRepairedByLaterEchoes`,
`delayedDeliveryFreezesTheProbeCohortAtSendRatherThanAcceptanceTick`,
`aHeldCapsuleUsesTheCompletedReleaseMappingAfterItsSendCohort`,
`anUnavailableFirstReportCannotBorrowALaterCompletedMappingOnRetry`,
`aHeartbeatCannotFoldAnAppliedPrefixWithoutItsApplicationProofs`,
`nativeActivationTemplatesDoNotRewriteContributingStartMetadata`,
`aReceiptFirstApplicationIsAvailableToTheNextIncrementalHeartbeatFold`,
`successorSeedAdoptionRetainsPostPrefixApplicationsAtTheirOriginalCoordinates`,
`aPrefixBeforeTheIssuedSeedIsRejectedBeforeAnyRangeRead`, and no-node coverage.
Transformed fixture: `fx-trust-application-history` (native PA/LR seed, Create
reversal, immutable no-node EXIT, authorized fold, 360 raw-bit comparisons).

Residual (ADR4 §3.5): an observer may choose a plausible schedule inside the bounded
window; these checks cannot prove its real client application instant. Anti-abuse
history retains each first authorized proof, first server receive ns and its
mapping/deadline intersection. Ue is bounded by frozen D + RTT_hi + J_hi, first
report and E + 40 ticks. No concrete additional exploit requiring manifest 5 was
established. No raw timestamp is treated as trust and no parallel schema is added.

## FX-TRUST-2 — first reports are decided at the boundary after them

Root cause (FX-CLIENT production wire, reproduced on `fc3b218a`): C2S packets run in the task
drain after the latest START boundary L (tick T). A prompt first report has receive time f > L,
and usually also D' = D + RTT_hi + J_hi > L, so `Boundaries.at(min(D', f))` was undefined where the
report arrived: `start_bound_unavailable` for every honest first report (recorded vector: both
warm-seed reports refused, both same-drain tickets unavailable). A retry could only map the retained
f, and a later ticket raising the A high-water then made the never-admitted seed activation
`application_history` (the third report). Server tests had recorded future boundaries in advance.

Fix (`AnimSyncValidation`, frozen bound/admission only; `AnimationTiming` unchanged):

1. Known part. Before the next boundary, `frozenBound` maps `min(D', f, L)` instead of
   `min(D', f)`; everything else (E, mapping nonce, frozen deadline, E+40) is unchanged.
2. Early decision. If that interval contains sW, the application is admitted now.
3. Deferral. Otherwise, when frozen evidence and a mapping nonce completed by f exist and f > L
   (`StartBounds.awaiting`), the report or action receipt gets `start_bound_pending` and is
   queued per observer. Every later report/receipt of that observer is queued behind it. START
   records B(T+1), then `boundary` decides the queue in arrival order with each packet's
   original receive time, through the same `applied`/`prepareAction` code. Missing evidence,
   unknown or stale nonces and receipts with f <= L are decided at once, as before.
4. Consumers. A queued receipt's verdict is `start_bound_pending` (labelled UNAVAILABLE,
   counted) until decided; the decided authorization commits as at intake. `materialize`
   then returns the decided verdict. The queue holds at most 64 packets; overflow is refused
   (`deferral_capacity`). Disconnect/clear drops the queue undecided.

Equality with the rule. Let map be the recorded boundary map: monotone non-decreasing,
defined only on recorded boundaries. The rule, once B(T+1) exists, gives
`I = [max(map E, map max(E, P)), min(map min(D', f), map E + 40)]`, where P is the nonce's
server send; I is empty if f < D, or if it is inverted. The known interval K replaces
`min(D', f)` by `min(D', f, L)` <= `min(D', f)`. Its broadcast check, using min(f, L), is not weaker.
Hence K is either empty or has the same lower end and an upper end <= I's. So sW in K implies
sW in I: the early admission is the rule's own decision. Every other case waits for B(T+1).
Then all inputs (E <= D <= f, P <= f, min(D', f) <= f < B(T+1)) are recorded, and the same
code computes I itself.

Between the drain and START no END runs (no freeze, prune or publish). Probe echoes later in
the drain have receive time >= f, so they can qualify only at the rule's own tie
(`receivedNs <= f`). Later packets of the observer are queued in arrival order, so nothing
overtakes the pending decision and it overtakes nothing. The only difference is that nonce
freshness (40 ticks) is measured at T+1, which is stricter or equal. The recorded window of an
early admission is K. Its exact Ue remains recomputable from the retained f and frozen deadline.

Adversary. f is the server's own receive time, retained as a minimum; a retry cannot move it,
and the deferral adds only the boundary the rule needs. sW forged into K is inside I: no
latitude beyond the §3.5 residual. sW forged outside is decided one tick later with the same
rejection. A flood fills only the attacker's own queue, at most 64 per tick. Its overflow is
refused, never admitted, which is equivalent to not sending. Nothing undecided is ever EXACT.

Tests:
- `FxClientWireTest` (production stream, scripted clock, boundaries recorded at START after
  each drain, never in advance). It now requires every warm-seed AnimationApplied admitted and
  all four tickets EXACT. The cold join seed's activation (sent 5010 ms before any completed
  echo, so it froze no evidence) must be refused `start_bound_unavailable` at once, not deferred.
  A same-drain consumer must see exactly `start_bound_pending`, an earlier-drain one EXACT.
  Tickets use the production `prepareAction`/`materialize` path.
- Before/after on the recorded vector: reports rejected/rejected/`application_history` became
  refused(cold)/admitted/admitted; tickets UNAVAILABLE/EXACT/UNAVAILABLE/EXACT became EXACT x4.
- Adversarial wire cases:
  `forgedApplicationTimesAreRefusedAtTheBoundaryAndCannotTaintTheHonestTicket` (sW 107.0 after the
  first report, 105.0 before the nonce's send: both deferred, refused `start_late_unavailable`,
  every ticket EXACT); `aFloodedDeferralQueueIsBoundedAndAdmitsNoForgedCopy` (70 forged copies:
  peak queue 64, 7 refused including the attacker's own honest report, no forged proof admitted,
  the next drain's ticket decided at once and EXACT);
  `aTicketThatFirstReportsAnApplicationIsDecidedAtTheNextBoundary`;
  `aDisconnectBeforeTheBoundaryLeavesDeferredPacketsUndecided`. In the forged/flood cases the
  refused packet was the warm seed activation's only report. It then stays below later
  high-waters, and the next report is `application_history`: the forger loses.
- `AnimSyncValidationTest.aBoundBeforeTheNextBoundaryIsAKnownSubIntervalOfTheDecidedOne` covers
  real-order boundaries only. K = [102, 104] and the decided I = [102, 104+39321/65536].
  Containment is checked over a 1/16-tick grid. A nonce sent after L waits, and an unknown nonce
  is final.

Residual: a synchronous consumer (hitscan, launch capture) in the drain of an undecided first
report uses the labelled fallback for that action. This occurs when the capsule send, the
client's application and its report all fit in one server tick (round trip under about 40 ms).
With longer round trips sW usually precedes L, so K decides at once. Native combat is not
delayed a tick to wait, since that would change native timing. The live rate is not measured
(no stand). No bound, schema or client change.

Changed: `AnimSyncValidation.java` (frozen bound, `StartBounds.awaiting`, admission deferral,
two one-line hooks in `applied`, `PreparedReceipt.deferred`), `FxClientWireTest.java`,
`AnimSyncValidationTest.java`, this document. `applied`'s body is otherwise unchanged.

Verification at code commit `3d6edc4c` (base `fc3b218a`): `just format`, `just check`,
`just test`, `just java-check` passed in order under the heavy lock, memory guard and pinned
JDK. JUnit: client 534 (two existing opt-in skips), server 1,724, zero failures/errors.
Transformed fragments: 115 client and 33 server, all pass, including the four FX-TRUST fixtures
and `FxClientWireOracleFixture`. All 13 required GameTests pass and the server shuts down.
Mixed renderer: 7,952/7,952 bit-identical, zero unavailable (as at `fc3b218a`).

## Added seed delivery and cold bootstrap corrections

Root cause: `prepare` emitted the canonical checkpoint closure before creating the
seed, omitting its SeedRef and observer-only closure. Separately, the first seed
and first probe publish in one END callback, so no echo exists at that seed's D.
Strict frozen absence could never authorize that seed's activation.

Fix: include every new seed's complete per-life closure, including its own
SeedRef definition, in the existing `Batch.definitions` channel. Generation-scoped
content remains the READY-preloaded catalog. When fresh completed probe evidence
exists but the live subscription's newest seed lacks send-time evidence, issue a
fresh seed with its own E/D/evidence. Never repair the cold seed or loosen Ue.

Tests: fresh-observer reference resolution and join -> cold seed unavailable ->
actual nonce echo -> reseed -> bounded activation -> no redundant reseed in
`AnimSyncStreamTest`; transformed `fx-trust-seed-bootstrap` additionally checks
360 native raw-bit samples and the mapping-102 regression (sent 5100 ms, echo
5110 ms, release 5111 ms; frozen cohort 99). Unknown nonce 777 and nonce 103
completed only after first report are rejected.

Residual: client actual-application mapping/reporting and its seed activation wait
are FX-CLIENT responsibilities. This server supplies a fresh bounded seed and all
its definitions; an unavailable activation is never an equality pass.

## Added UOPT P0 — continued seed actors can legitimately end

Root cause (also present at the review base): `victim` required a SEED_CONTINUE
entry whenever the original seed contained starts. UOPT's 30x9 benchmark instead
builds honest rows from the current reduction: after finite actors end or are
replaced, their continued starts correctly disappear. The seed still needs its
original activation for replay, but that historical anchor is not a current node.

Fix: when the current row has no continuation, retrieve the seed's already
authorized immutable application from this connection's retained history. Build
only a replay template from server seed metadata and that proof. Never invent an
activation when no authorized first report exists. Reconstruct normally and
compare the complete reduced start list and presentation digest, so omitting a
still-contributing node (even with a recomputed public digest) still rejects.
Changed activation proofs remain rejected before replay. No reducer or phase
formula changes.

Regression: `anHonestReceiptKeepsItsSeedAnchorAfterAllContinuedNodesEnd`, a minimal
version of UOPT's finite-actor scenario on the real LR stack. An LR actor is in
the seed; the observer reports activation, advances until no continued node
remains, then its complete honest receipt validates. Negative rows hide a live
node, rewrite the historical mapping, or lack the authorized activation entirely.
The transformed application-history fixture additionally advances its native
LR/PA seed through replacement/expiry, recovers the retained activation and
compares the final graph with the native canonical graph: 360 additional raw-bit
comparisons pass after the missing native LR config fades all three layers.

Residual/dependent item: the new trace also reproduced the pre-existing
`prefix_backwards` failure before reaching this rule. The real client
`ClientAnimationJournal.row` and UOPT's emulation compute the prefix from seed,
capsules and currently contributing starts; a high canonical start can vanish,
lowering the next row's prefix. The server's monotonic check already existed at
`18e0d02e` and remains intact. This regression explicitly carries the prior
acknowledgement forward and rehashes the honest row. FX-CLIENT should preserve
that per-life/subscription prefix high-water when a canonical start ends. The
30x9 performance benchmark itself remains UOPT-owned and was not rerun here.

## P0-3 — unsolicited report lives

Root cause: `applied` allocated `Reports` before authorizing client-named lives.

Fix: validate the complete packet's sent batch, player incarnation, issued seed
and delivered capsules before allocation. Report-life capacity is bounded by the
number of retained server-issued seeds; applications can name only retained issued
capsules. No active legitimate graph is evicted or silently truncated. Expire
reports/application/first-report state with the retained seed/capsule window.

Tests: `inventedTargetLivesNeverAllocateReportState` (10,000 epochs),
`appliedReportsRequireASentBatchAndTheWholeIssuedSeedIdentity`, transformed
`fx-trust-report-lifetimes`. Residual: memory is proportional to server-issued
retained closures and active legitimate graphs, without a fixed victim cap.

## P0-4 — native admission before reconstruction, immutable verdict reuse

Root cause: every fresh attack sequence reconstructed every victim before the
native weapon could reject no usable gun/cooldown/other eligibility failures.

Fix: ordered action intake performs authorization and freezes application history,
then retains an immutable prepared receipt. Reconstruction occurs once when native
code actually consumes view geometry or captures an admitted projectile/throwable
launch. Queued TaCZ/SBW work carries its own prepared observation and captured
policy, rather than a newer action's receipt. Lunge's receive scope uses a pending
capture; its native range gate precedes direct-hit geometry. Existing native
eligibility predicates remain authoritative; no cooldown approximation, rate cap,
victim cap or omission of automatic-fire observations is introduced. A launched
projectile keeps only its immutable verdict, releasing pending connection references.

Memoize one EXACT verdict per connection by the complete receipt (frame/revision,
starts, applications/digests/generations), stream, H and R. Only attack sequence is
excluded; `CombatViews` consumes/binds each sequence separately. Reuse follows
current envelope/identity/history authorization and normal sent-window expiry.
Rejected/unavailable verdicts are not cached. New deferred reconstructions also
require their retained sent H; already materialized launch verdicts retain their
existing independent lifetime.

Tests: `repeatedImmutableReceiptsReuseOneVerdictAndExpireWithTheSentWindow`
(1,001 sequences, one replay, 1,000 verdict reuses, expiry),
`rejectedNativeActionsDoNotReconstructAndQueuedActionsKeepTheirOwnObservation`
(two honest PA/LR victims, delayed older action after newer intake), existing
CombatViews/FamilyStamps acceptance tests. Transformed `fx-trust-native-admission`
executes real pinned TaCZ `LivingEntityShoot` with production mixins: 1,000 distinct
frames rejected as NOT_DRAW cause zero reconstructions; an admitted geometry
consumer reconstructs once, then 1,000 identical observations reuse that verdict.
Player services are mocked and the native no-draw fixture has zero driven victims;
it is an admission workload, not a fabricated geometry equality sample.

Residual: authorized work still costs proportional to legitimate victims/graphs.
No CPU acceptance ceiling is claimed. Native eligibility remains at native call
sites; newly introduced receive-time consumers must use pending capture until
eligibility, and launch consumers must materialize before independent flight.

## P1-5 — removed target seed lease

Root cause: newest meant only no newer seed for the entity id, even after unpair.

Fix: newest retention requires the matching live subscription, target life,
subscription generation, seat and teleport generations. Retain replaced/removed
seeds through their final 40-tick receipt window, then close leases and prune
capsule/report state. Unpair immediately clears the incarnation; disconnect releases
all leases. Disabled streams prune every END tick despite skipping publication.

Tests: constant-subscription/removed-seed plateau, entity-id reuse, disabled stream,
`unpairedObserverSeedReleasesItsGenerationAfterTheReceiptWindow`, transformed
lifetime workload. Reload retirement verifies old family views and manifest retire
after the final lease closes. Residual: carried launch verdict lifetime remains
separate from intake retention.

## P1-6 — bounded send/proof caches

Root cause: dedupe retained every per-tick InputRef forever; accepted proofs only
expired when the entire life disappeared.

Fix: retain full per-life refs with each sent batch. Dedupe retains only the union
of 40-tick batch refs and retained seed closures. Accepted proofs retain their last
actually-sent H plus contributing seed start keys/activation aliases. Prune obsolete
nonseed proofs every publish, including a constant subscription. Reject expired H
and capsule references rather than allowing old anchors to re-enter. Empty capsule
maps, reports, history and evidence expire together.

Tests: 200-tick real capture/send plateau; transformed 401-tick cache workload
(send refs plateau 40, accepted proofs 43 including three protected seed proofs),
`expiredProofsArePrunedAndTheirOldReceiptCannotReintroduceThem`. Transformed native
PA/LR integrity checks compare 720 raw scalar bits around that synthetic cache
churn; the synthetic nature is explicit. Residual: retained memory scales with
server-issued active graphs; no truncation or fixed CPU ceiling.

## Verification

Targeted server tests: PASS (110 tests, zero failures). All four transformed trust
fixtures: PASS. All 13 required in-process GameTests passed and shut down.
Full `just format`, `just check`, `just test`, `just java-check`: PASS, run in order
under the shared heavy lock with the pinned JDK and memory guard. Complete JUnit
results: client 531 (two existing opt-in skips), server 1,710, zero failures/errors.
All 127 transformed fragments pass, including all four FX-TRUST fixtures; all
13 required GameTests pass and their server shuts down.

Final before/after comparison against the merged FX-POSE base: all mixed cohort
counts, statuses and numerical fields identical; native and server SHA-256 values
below each remain unchanged. 5,538 evaluated samples before / 5,538 after, all
bit-identical, maximum corner error 0 m. The baseline SBW partial/unavailable vector
is preserved and is not claimed as equality.

Original review-base report had 4,118 evaluated
mixed samples, all bit-identical, maximum error 0 m, SHA-256 on both sides
`a79464ac8049a45fbee9a424dd4d29b611154f5c34046f9a49a1108529b61b63`.

Merged FX-POSE base (`ac472953` / `3ad38cb4`): 5,538 evaluated mixed samples,
all bit-identical, maximum error 0 m. Native all-sample SHA-256
`a74fb6414cc7d795859ddcaa5454c0a53e708483247b807094e70d76695e8b37`;
server all-sample SHA-256
`32feacbb2a706f3dd382e9ef8cfeadfbef939fe6bc69151b3b5fa1ec3437183e`.
The hashes differ from each other because the baseline includes an unavailable
SBW seat vector; each side is compared to its own before value. Unavailable samples
are not counted as equality. FX-GATE owns that required-cohort gate and seat fixture.

No stand/client, production operation, release, tag or paid call. Runtime evidence
is Linux amd64, pinned Temurin 21.0.12+8, transformed Loader 0.19.3/intermediary with
production mixins and available pinned pack artifacts. It does not claim live
TCP/RakNet/Velocity, GPU/Iris rendering, Windows or the owner's FPS A/B acceptance.

## U1 review findings applicable to this boundary

U1 review P2-1 was already closed at the base: SnapshotRestart reason bytes use
explicit IDs; `SnapshotRestartPacketTest.reasonIdsArePinnedWireBytes` stays green.
Fidelity audit P2-9 remains enforced without relaxation: complete graph/root,
reachability, depth, host/alias and domain checks are untouched. The existing
`AnimationGraphTest` negative tests and all full resolver/graph tests remain part
of the required checks; patches retain their existing fragment semantics.

## Final merged verification (after the UOPT correction)

Current focused JUnit: PASS (112 tests, zero failures). The ended-node receipt
and unavailable-report retry negatives pass. All four transformed trust fixtures pass, including the expired-seed extension.
Final full checks after merging `cc156290`: PASS at code commit `059f9954`.
`just format`, `just check`, `just test`, and `just java-check` all completed
in order under the heavy lock, memory guard and pinned JDK. JUnit: 531 client
(two existing opt-in generators skipped), 1,712 server, zero failures/errors.
All 132 transformed fragments and all 13 required GameTests pass; the in-process
server shut down normally. Before this last correction, all four
commands passed on `25bead1b`: 131 transformed fragments, all 13 required
GameTests, and 6,248/6,248 required mixed samples bit-identical with zero error
and zero unavailable required samples. Direct comparison to FX-SEAT's retained
report at that exact head found every mixed cohort/count/status/numerical field
and both all-sample digests unchanged. Native SHA-256:
`afc350feb6ce0eab54aa8966303b731980ebecf44c24468725a3cfc1eebaefc9`;
server SHA-256:
`274d09ea46efacf86ed6a046be907c61792d096048778c8c999bb725f0a9865a`.
The one explicit `sbw_main_hand` diagnostic remains excluded from equality.

Latest merged baseline `cc156290`: 6,674/6,674 required mixed samples, zero
error/unavailable required samples; all previous 6,248 preserved by FX-GATE.
Retained baseline report copied from that owner's tested worktree at this head:
native `0d5213fa55d6775eea70b43e1fc11f6797319c590b5069a3f119ff9a95eb4817`;
server `f53e3954b2cbf5fb7131141fe4012f407afb5517746272cd91b76d24a77c3d10`.
Final direct comparison PASS: 6,674 required samples before / 6,674 after, all
bit-identical; zero local/world error and zero unavailable required samples.
Every mixed cohort/count/status/numerical field and both above digests are
unchanged. The diagnostic exclusion is identical. This evidence-only update
does not change the checked source tree.

## Changed files and dependent units

- `server/.../combat/AnimSyncValidation.java`, `AnimSyncStream.java`, `SnapshotStream.java`,
  `CombatViews.java`; `server/.../mixin/superbwarfare/SbwLungeAttackMixin.java`.
- `server/src/test/.../combat/AnimSyncValidationTest.java`, `AnimSyncStreamTest.java`,
  `AnimSyncResourcesTest.java`, `AnimSyncFoldTest.java`, `AnimSyncScenario.java`.
- `server/src/oracleTest/.../combat/AnimSyncJournalCases.java`, `AnimSyncAdmissionCases.java`,
  `oracle/AnimSyncTrustOracleFixture.java`, `oracle/ServerTransformedOracleSession.java`.
- `docs/review/anim-sync-fx-trust.md`.

No ADR4 or schema deviation. No pose evaluator/family port, resource admission gate,
mixed-renderer case or equality reporter changed. UOPT has no shared reducer edit
to reconcile. FX-CLIENT should rebase on this result: mapping is actual release,
new seed definitions use Batch.definitions, and cold seeds are replaced after echo.
