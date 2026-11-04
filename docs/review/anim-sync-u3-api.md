# U3 PA/TaCZ family API and ownership handoff

Contract: ADR4.1 §§2.3–2.4, 3.2/3.6, 4.3, 5.1, 6, 8–11, its 2026-10-16
errata (including the U3-review errata), and the fourth-session coordinator
re-scope. The U3F fix session (worktree `WS/.dev/worktrees/as-u3f`, branch
`fulla/as-u3f`) made the authorized U1 change to schema manifest 3 (nullable
`Tacz`); see "U3F fix session" at the end, which supersedes conflicting text above.
The family implementation is complete under that re-scope; full integration gate
results are recorded in `anim-sync-u3-fixtures.json`. This API does not activate
an exact cohort. Unwired capture, missing resources/inputs or invalid proofs must
remain unavailable. Family oracles do not certify final six-box equality.

## Common native graph

`common.combat.PaGraph` executes pinned PlayerAnimator 2.0.4. Frozen values are
U1 `AnimationGraphState`; there is no competing actor field schema.

- `Catalog(resourceGeneration)` owns private parsed clip copies. Resource refs are
  generation-scoped (`targetEpoch` 0): every life's stack, the TaCZ/LR asset views
  and all projections of a generation share one catalog. `resource(key, clip)`
  stores an effective clip under its native lookup key without LR's mutable
  `lr_attack_action`; `register` (identity) is for frozen native stacks. Canonical
  content includes enabled/default/end/loop/bend/scale, raw keyframe/easing bits,
  native Ease IDs and the stored name. `named` describes resource IDs; `define` describes content with immutable
  dependencies. `definitions()` contributes to U9's store. `references(graph)`
  supplies roots to `Store.pin`. Keep the native catalog with every referring
  graph/seed/history/ticket; the wire definitions alone are not a clip parser.
- `freezeNative` captures all seven roots without sampling/ticking. Its caller
  supplies causal starts, adjustment bindings, the actual rotation alias winner,
  disjoint IDs and other-family base. Unknown topology, unhosted modifiers,
  changed tracks or cross-domain aliases fail closed.
- `Stack.create`/`materialize` restore all seven roots, native scalars, metadata,
  first-person fields, modifier host/child links, fade ancestry, LR frozen/forced
  nodes and the `tacz:rotation` alias. Priority 42 and 96 remain physical roots.
  Frozen LR sources do not tick and sample at zero; standard fade sources stay live.
- `tick()` advances the native ordered mixed stack once. `tick(PhaseDomain)`
  advances only that domain. `freeze(base)` preserves other-family values/IDs;
  `started` lets U4 bind its nodes before freezing. Restoring a post-tick graph
  replaces state; do not tick that bucket again or tick TaCZ and LR separately.
- `sample(pA,pR)` performs one scratch setup. R lower adapters scope complete
  41/93 subtrees; composition remains native. Sampled scratch cannot tick/freeze.
  Recreate scratch for every invocation/pass, even with the same fractions.
- `loop/once/rotation/stop`, `fragment(layer)` and `apply(Patch,sequence)` are the
  native TaCZ transition surface. Production starts pass a `ClipRef` (`loop` also
  the native lookup key) and play a Stack-private `copy(ref)`; the catalog never
  grows per start, and `tick`/`freeze` drop bookkeeping for unreachable natives. A SELECTED fragment contains the authoritative
  actor topology/variant, without starts. Apply installs it onto the observer's
  incumbent using native fade semantics; missing/ignored/no-change outcomes do
  not invent starts. This API dispatches no sound, particle, physics or packet.
- `stableEventIds()` is used by observer projection. Actor/wrapper/fade IDs derive
  from event sequence and native creation ordinal, so independently advancing
  R/A domains cannot change them through allocation interleaving. Hash collisions
  are explicit unavailability. U9 must include this algorithm/version in the
  compatibility digest. Canonical legacy allocation is unchanged.
- `selectedClip` reads the actual native incumbent, including an exhausted
  wrapper; capture never resolves an incumbent name through a newly selected
  profile. `start` returns the actual current subtree's start key.

`PaAdjustments.Inputs` supplies the reviewed dedicated-server TaCZ/LR ports.
Client scratch retains the native functions; U8 must collapse facing endpoints
at R and scope the global DeltaTracker to A. `TaczPose.select` preserves native
prone/ride/flying/aim predicates and float-to-double widening before `> .05`.
`TaczPose.fallback` supplies the native default/minigun body formulas to server
scratch, including raw aim <= 0 and unknown-name default behavior.

## Retained resources and authoritative capture

`TaczAssets` is a generation-owned effective resource view, not another wire
state layout. `register` freezes an effective display's ID, animation-set ID,
fixed-hand flag, fallback name, native has-PA outcome and all named ClipRefs.
The resulting `DefinitionRef` covers these values and clip dependencies.
`resolve(gun,override)` implements native missing-override/default resolution.
`require(Tacz)` refuses another generation or mismatched evaluator/fixed-hand.
Do not register changed data under an already used ID in a generation.

Client `RemoteTaczAssets.retain` reads the staged native `GunDisplayInstance`
and native PlayerAnimatorAssetManager contents once, keeping private catalog
copies. U9 supplies the **actual effective display ID and common-gun/default
lookup table** from native resolution, including user-pack precedence. No GPU
or first-person asset object is retained as animation state. U9 additionally
hashes raw resource bytes, defaults/missing-ID resolutions and loader precedence;
the family semantic definition does not replace the complete manifest.

Server `HeldWeaponPose.reload` retains a generation cache of defaults, profiles,
fallbacks, native metadata/clip definitions and effective display refs. A display
with no native animation set is a legitimate native fallback; decoder/bootstrap
failure is unavailable. `assets(generation)` retrieves the retained view;
`retireAssets` is only for U9 after every native handle/store lease/pending record/
valid ticket expires, and refuses the active generation. Ordinary activation
never makes an old ID resolve through the new cache.

`HeldWeaponPose.Animation` exposes package-level APIs for U7b:

- `sequence(LongSupplier)` installs the per-target journal allocator.
- `graph(base)` freezes the complete mixed graph; `catalog()` supplies resources.
  Missing integer steps make capture unavailable until an explicit reseed.
- `loops(Profile,Vanilla,walkSpeed,rawAim)` and its legacy string overload drive
  native selection. `fallback()` selects the no-profile native evaluator without
  clearing incumbent PA layers (native behavior). Accepted actions there record
  MISSING_CLIP/no-start outcomes rather than disappearing from the journal.
- `tacz(TaczAssets,WeaponIds,rawAim)` exports the U1 checkpoint: effective display
  ref, fixed-hand even without PA, raw aim bits, actual incumbent clip/start refs,
  exhausted/prone/variant and accepted outcome. Missing resolution or a resource
  generation mismatch requires an explicit reseed/unavailable result.
- `ops()` returns ordered immutable Patch values for the tick. A transition's
  sequence comes from the allocator; -1 means native no-start outcome, which U7b
  sequences as a decision without fabricating a start. These are family patches,
  not complete native event capsules.

`LrBodyPose.Stack.pa` is the shared seven-root stack with its actual alias winner.
The existing LR canonical advance remains its sole integer owner. A standalone
HeldWeaponPose.Animation owns its own stack; the shared form never ticks twice.

Legacy END-tick output materializes separate scratch, retaining its existing
EndpointAdjustment right-limit workaround only there. It remains diagnostic/
gameplay output, not equality evidence. The exact path retains native zero-age
NaN as unavailable and must never route through that legacy sampler.

## Observer starts, successors and seed continuation

`PaProjection` takes and returns immutable U1 values. `Result(graph,starts)` is a
local operation result, not a codec or new packet layout.

- `apply` takes a complete current integer graph, an authoritative Patch and an
  authorized Start proof template. It performs the native replace/stop with no
  tick, attaches native allocated start IDs, retains incumbent fade ancestry and
  produces the new immutable bucket. Sample common frac(A), or scoped frac(R),
  afterward. Several events in a bucket do not retick it. It has no native side
  effects; U7b/U8 must dedupe by event identity **before** calling it.
- `tick` advances one whole domain and retains every reachable start, including
  those referenced by live/frozen fade sources. `retain` refuses a missing start
  and prunes only unreachable ones.
- `taczDecision` consumes a U1 Decision whose Patch includes authoritative Tacz
  and Vanilla, plus the immutable Placement named by its InputRef. It resolves
  clips through retained assets and runs native eligibility on the observer
  incumbent. On first eligible expiry of an observer predecessor it emits a
  PROJECTED_SUCCESSOR proof with boundary/inputRef/ordinal; ordinary packetless
  choices are CANONICAL_TICK. Rotation remains an unconditional canonical choice.
- `project` executes a complete TaCZ tail of at most 40 integer boundaries for one
  domain. Every boundary must exist in the journal, even if empty. Its decisions
  are ordered POST_NATIVE_TICK/END_COMMIT selections, with complete input refs;
  missing steps/inputs never borrow a later H. Other callback stages use explicit
  tick/apply operations in the owning unit's complete mixed schedule.
- R lower policy covers selection inputs, whole ticks, phase anchors, proofs,
  starts, seed continuation and sample fraction, not only the selection flag.
  Upper/action remain A. No graph node may cross domains.
- `seedContinue` takes a server-issued seed activation ObserverApply and a fresh
  sequence. It rebases all contributing starts to SEED_CONTINUE with integer u0,
  preserving exact outer age and every native node scalar/topology/alias. This
  does not run a constructor, gameplay effect or integer tick.
- `fold` writes the retained family graph/starts into a new U1 ObserverSeed with
  its predecessor, nondecreasing prefixes, dependencies and matching reducer
  digest. U7b validates the prefix first and owns old-seed acknowledgement/leases.
  U7b/U8 retain the fold's **R/A recipe coordinates** in the seed ledger/application
  context; never infer them from canonical checkpoint.serverTick. Continued seed
  activation carries those coordinates in existing ObserverApply.applyR/applyA.
  Native continuation is bounded even when a live clip is older than 40 ticks.

U4 composes LR-specific decisions/frozen/forced transitions through the graph
primitives; do not call the TaCZ-only `project` convenience over an LR decision.
Canonical/observer checkpoints replace derivation rather than adding another tick.

## Client driven scratch

`RemotePaPresentation.Frame` contains the projected graph, catalog, policy,
fractions, U1 Tacz checkpoint and retained TaczAssets. Missing/mismatched values
fail closed. `install` materializes fresh native scratch into the player's own
seven registered roots for **each invocation/pass**. Original associated-data
references and rotation alias identity survive. LocalPlayer installation is refused.

Production mixins suppress driven TaCZ geometry leaves and PA stack ticks;
physics/lifecycle stays native. PA processor partial is A; 41/93 adapters use R.
The driven InnerThirdPersonManager entry uses the authoritative evaluator and
retained fallback name/raw aim, with no live display query. The actual native
fallback formulas still run. Native TaCZ arm adjustment keeps its formula but
uses authoritative fixed-hand; undriven/local behavior still calls native lookup.
U8 still supplies R-facing, A-global partial and authoritative entity input scope.

`clear` restores original rotation modifiers and empty layers; `clearAll` is for
JOIN/disconnect. U8 clears on Remove/unavailability and restores scopes on every
exit, including exceptions. No adapter is currently wired to exact transport.

## Explicit downstream ownership (binding re-scope)

U3 releases the LrBodyPose file lease after integration. Remaining work is owned
by the following units; it is not permission to activate a partially wired cohort.

- **U7b/U8:** authoritative capture boundary; complete Decision input refs and stage
  journal; acceptance/broadcast event capsule wrapping; native-payload bytes and
  exactly-once side-effect dispatch; causal holds/dedupe; receive/task/main-frame
  ordering and zero-client-tick timing oracle; immutable revisions; apply/start
  acknowledgement, server validation, observer seed ledger/recipe coordinates,
  complete victim reports, lifecycle and transport activation. Use capture ->
  project/apply -> freeze -> install; never rerun native geometry callbacks after
  installing an authoritative Patch. Snapshot watermarks acknowledge events.
- **U9:** generation activation/READY/JOIN refusal, complete effective manifests,
  native catalog registration and all asset leases/retirement, including active
  loops retaining old profiles. Activation is not an implicit cache reset.
- **U4 + U10:** LR observer selector/frozen/forced wiring, mixed LR/TaCZ/local-native
  metadata compatibility view (including legacy remote-to-local observations),
  mixed pass-order regressions and registration order. Private actor copies do
  not prove that local-native compatibility schedule by themselves.
- **U10/U11:** full renderer/capture/48-corner/mixed-pack/runtime/live/owner A/B
  gates and CPU/allocation/byte/memory measurements. Music's unavailable pin stays
  explicit; no complete-pack equality or in-game verification is claimed here.

## Family evidence

The machine manifest lists source hashes, full command results, fragment digests
and exact scope. New transformed fixtures are explicitly registered in both
sessions. Client/server observer cases execute native PA with production mixins:
same-bucket .30/.85 starts, live fade ancestry, bounded folding/wire continuation,
120-tick live clips, A/R lower policies, projected successors and seed u0 rebase,
missing-boundary refusal and historical arrival-prefix separation.

The client transformed third-person closure audit observes successful postApply
ClassNodes, traverses native call/lambda edges and inventories field reads. It
refuses first-person GunAnimationStateContext/AnimationStateMachine/DiscreteTrackArray
or walkDistAnchor reads. Native resource loaders are an explicit immutable
boundary; their precedence remains U9's manifest gate. Native default/override,
fixed-hand and fallback fixtures exercise the actual pinned lookup/formulas;
a driven read still succeeds when current display lookup is made to throw.
This closes U1 fidelity P2-2 within the family boundary, not U8's timing oracle.

P2-4's no-runtime-track-toggle scan and transformed private-track/metadata checks
remain required. P2-5's three unsupported modifiers remain fail-closed with a
negative fixture. The linked exhausted-wrapper regression retains the old inactive
construction as a witness and tests native strict >1 float stepping at 2f/3.
The native zero-age 0/0 fade-in now uses its right-hand limit on both evaluators
(U3F); any other nonfinite sample is refused (`requireFinite`), never equality.

## U3F fix session (2026-10-16)

Review `WS/.dev/coord-20261011/reviews/p3-anim-sync-u3-review.md` (0 P0/5 P1/6 P2)
and the shared PA catalog findings of the U4 review. Every finding was verified
against the pinned bytecode (Temurin `javap`, TaCZ bf8, PA 2.0.4, LR bf2) before
fixing; none was rejected. Plain JUnit runs the real server classes; transformed
fixtures run production mixins.

| Finding | Fix | Evidence |
| --- | --- | --- |
| P1-1 display port | `TaczAssets.DisplayFile.parse` mirrors `GunDisplayInstance.initBase`: `defaultIfBlank(tpa,"empty")`; `player_animator_3rd` and `3rd_fixed_hand` only when the animator is non-null (defaults rifle/false). Server reload and the native-key parse share it. | client `display-loader-differential`: all 54 default-pack displays (20 without a name) plus 5 blank/absent/animator-less edge cases through the real loader and `PlayerAnimatorAssetManager.putAnimation`: equal displays, refs and all 625 definitions; mutation (fallback `default`) fails it. |
| P1-2 + U4 P1-1 epochs | One scheme: generation-scoped refs; LR stacks use the generation catalog shared with TaCZ; `LrAssets.scoped` removed. | `LrBodyPoseTest.respawnedLifeProjectsItsCaptureThroughOneGenerationCatalog` and `respawnedGunLifeExportsAndProjectsTaczThroughTheSharedCatalog`: target epoch ≥ 2, `tacz()`/`lr()` refs resolve, `LrProjection.project` (LR, and mixed TaCZ+LR through `taczDecision`) samples bit-equal to the canonical stack; per-life catalog mutation fails both. |
| U4 P1-2 retention | `Stack.copy(ref)` private per-start copies (LR actor/rotation, TaCZ loop/once/rotation); `retained()` bounded by the live graph. | Same tests: 50 idle/walk/run/attack ticks and 8 projections keep `Catalog.size()` constant and bookkeeping ≤ 3·nodes+2; registering copies (mutation) fails them. |
| U4 P2-3 keys | `PaGraph.nativeKey` ports `serializeTextToString(name).toLowerCase(ENGLISH)` (the server strips PA's client class); TaCZ and LR server parses use `TaczAssets.keyed`. | client `native-clip-key`: 12 names (quoted, JSON text, list, null, numbers, non-ASCII, malformed) equal the native registry. |
| U4 P2-4 metadata | Stored resource clips take name := native key and drop `lr_attack_action`; registration order is sorted. | `display-loader-differential` (client live clips vs server parse); `LrBodyPoseTest` stays green. |
| P1-3 entry gates | `TaczPose.stopAll` (no gun, SLEEPING, onClimbable, isSwimming, FALL_FLYING) decides `Frame.thirdPerson()` from the frame's authoritative `Vanilla`; STOPPED/PA cancel without formulas. Server legacy `fallbackWeapon` already gates; `taczDecision` uses the same predicate. | client `third-person-entry-gates`: each gate and no gun, PA and non-PA, 324 raw-bit comparisons against native `setRotationAnglesHead` (native stopAll observed), live entity disagreeing both ways; removing the gate fails it. |
| P1-4 no gun | U1 `AnimationState.tacz` is `@Nullable` (schema manifest 3, digest `0c668df5…`, VERSION 1); `Animation.tacz()` returns null without a gun; `Frame` accepts null `tacz`/`assets`; `LrProjection.project` dispatches null-Tacz TaCZ decisions. | `AnimationSchemaTest` (presence bytes per manifest field, pinned digest, null round trip); `noGunExportsANullTaczCheckpoint`; LR-only frame installs in `third-person-entry-gates`. |
| P1-5 singular sample | `PaGraph.LimitAdjustment` (default `Sources.modifier`, both evaluators; legacy `EndpointAdjustment` removed) returns 1f only for the native `0f/0f` (beginTick 0, tick+delta 0). `install` probes the revision and refuses nonfinite samples. | shared `native-zero-age-singularity` on both sessions: native NaN, 2^-16/2^-24 converge, pinned limit bits equal on client and server; client `zero-age-draw-tick-install` (draw tick, frac(A)=0) and `public-play-methods-canonical` (8 singular samples now limits). |
| P2-1 reseed | `Animation.reseed()`: TaCZ roots keep only their adjustment, selection/outcome cleared, checkpoint available. | `reseedRestoresAnEmptyAvailableCheckpointAfterAGap`; mutation fails it. |
| P2-2 stopAll events | `action(display, …)` plays the once actor whatever the pose and stops it in the same bucket in a stopAll state; non-PA displays record MISSING_CLIP (-1). `ServerBodyPoses` passes the held PA profile and keeps a stack for gated PA holders. | `eventsInAStopAllStatePlayThenStopWithSequencedOutcomes`; mutation fails it. |
| P2-3 decision order | `taczDecision`: stop gate first (any evaluator, layers 93–96), non-PA keeps PA layers, 95 has no packetless selection. Compatible non-PA guns call `fallback()` in `ServerBodyPoses`. | `taczDecisionStopsEveryLayerBeforeConsultingTheEvaluator` (incl. a projected tail into climbing with an active once actor); weapon-tick test keeps PA layers for a non-PA display; both mutations fail. |
| P2-4 loop key | `loop` compares the incumbent name with the lookup key; a loop clip whose raw name is not its key is refused (`unsupported:clip_name`, checkpoint unavailable). | `loopEligibilityUsesTheNativeLookupKey`. |
| P2-5 exhausted | Not reverted (coordinator). | Owner A/B item below. |
| P2-6 retention | Client: install sweeps removed players; entity unload, world change, JOIN/DISCONNECT clear (`RemotePaPresentation.register`). Server: reload keeps the active and previous generation (ponytail until U9 leases). | client `presentation-retention-backstop`; `generationRetentionIsBoundedToTheActiveAndPreviousGeneration`. |

Owner A/B note (P2-5): the linked exhausted-reload wrapper is now active. Before
U3 the unhosted wrapper left the reload layer inactive; the local player (F5),
undriven remotes and driven remotes now show the reload pose at 2/3 speed while
exhausted. Release notes and the §5.1 exhausted A/B must show this change.

Declared remote-only replacements added here: none. The non-key loop refusal is
fail-closed (unavailable), not a replacement of native per-render restarts.
Downstream: U7b calls `Animation.reseed()` and `LrBodyPose.Stack.reseed()`; U8
supplies `Frame.vanilla` from the same checkpoint and calls `register()` once;
U9 replaces the two-generation retention with lease-based retirement.

