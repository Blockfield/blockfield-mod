# Anim-sync multithreading: design and thread-safety audit (UPAR-1)

Owner directive 2026-10-18: use multithreading to offload the server main thread. The owner rules of
2026-10-13/15 stay binding: exact server = client six-box equality, no visual degradation, accuracy
first, no approximation. This unit is design and audit only; no production code changed. Base:
`origin/fulla/phase23` `9cf76d2a`; cost data and code read on `origin/fulla/as-uopt` `cc428a8e`
(UOPT, [anim-sync-perf](https://github.com/Blockfield/blockfield-mod/blob/fulla/as-uopt/docs/review/anim-sync-perf.md)).
Contracts: ADR4.1 + errata (`p3-anim-sync-adr4.md`), ADR4.2 v2 (`p3-anim-sync-wire-amendment.md`).

`K/`, `S/`, `C/` are the mod's `common/…/common/`, `server/…/server/`, `client/…/client/` source roots.

## 0. Result

**Rule.** A parallel path is admissible only if, for the same inputs, it produces the same values,
bytes, verdicts and exceptions as the serial path, and every *visible* effect (game state, ledgers,
wire bytes and their per-connection order, counters, logs, D/freeze timing points) is applied on the
main thread in the serial order. Workers only compute pure functions of immutable inputs; the main
thread plans (reads live state) and commits (writes state). Caches whose content is a pure function
of their key (identity memos of immutable values) are invisible performance state; their per-thread
copies may differ, their results cannot (§3.8).

| Stage (30x9 combat, bench CPU/tick) | Verdict | Why |
| --- | --- | --- |
| Capture: r10 pose + native freeze/commit (≈48 ms = r10 26.6 + 21.2) | **main only** | live entities, chunk access deadlocks off-main, lazy entity fields, shared catalog/ledger writes (§3.7, §3.8) |
| Capture: retention round trip (9.4 ms **[P]**) | **parallel**, deferred commit in player order | pure encode/decode/digest of an immutable checkpoint (§4.3) |
| Observer fold reductions (≈21.9 ms) | **parallel**, speculate + verify inputs at the serial point | pure reducer over immutable values; memo copied per task (§4.2) |
| Fold commit (id mint, seed round trip, install, pin; ≈7.7 ms) | main (seed round trip optionally in the encode task, §4.5) | mints per-target ids in order; ledger writes |
| Stream prepare bookkeeping, probe, END capsules | main | observer/target ledgers, D of capsules |
| Snapshot encode + fragment + CRC32C (WIRE) | **parallel**, pipelined with the stream loop | pure function of the immutable batch and generation table (§4.5) |
| Netty enqueue, D, publish | **main**, observer order | ADR4.2 §7.2 D before last-fragment enqueue; ledger writes |
| Receipt validation rows (6.6 ms/exact receipt) | **parallel** per row, first failure decided in row order | reducer + resolver pure; row prologue/epilogue main (§4.4) |
| Victim evaluation (0.37 ms/victim) | **parallel** per victim of a stamp; SBW-root victims serial | pure composition; live deps resolved on main (§4.6) |
| Client decode (ADR4.2 §9) | **off-thread**, ordered per connection | pure; application stays main in arrival order (§4.8) |
| Client reducer steps per remote | **parallel** per remote at bucket entry (optional) | per-life `Reduction`, immutable records |
| Client driven install / render | **render thread only** | writes live entity fields, SynchedEntityData, native PA/LR/ParCool state |

Projected anim-sync main-thread time at 30x9 combat (bench machine, AMD 7535U): **71.4 → ≈47 ms/tick**
with the default pool (2 workers + main); homeserver i7-4710MQ (×1.5–1.8): **107–129 → ≈71–89 ms/tick**.
Multithreading alone does **not** bring 30x9 under the 50 ms tick on the homeserver; it removes
≈34 % of the anim-sync main-thread time (fold reductions 2.6× and round trips 2.35× faster on 3
threads, measured **[P]**, 0 mismatches). The remainder is the main-only native freeze/commit
(≈21 ms), the fold commits and prepare bookkeeping (≈15 ms) and, outside anim-sync, the r10 body
pose (27 ms). 30x29 needs ~3.2× the fold work and stays out of reach without an ADR4 fold change
(§5).

Pool default: **2 worker threads plus the participating main thread** on the 4C/8T homeserver
(knob 0 = today's serial path, 1–4). UPAR-2 order: UOPT integrates → UPAR-2 server fork-join
(pool, folds, validation, evaluation, retention) in parallel with WIRE-CODEC → WIRE-SERVER encodes on
the same pool → client slices with WIRE-CLIENT/after FX-CLIENT-3 (§7).

## 1. Constraints from ADR4/ADR4.2 that bind threading

- **§2.3 item 4/5**: S[t] is frozen at END tick, encoded, decoded, retained and *published before
  `SnapshotStream` builds that tick's batch*; never mutated after send. → retention commits must all
  land before the stream phase of the same tick.
- **§2.2**: native-wrapped capsules ride their native broadcast in the same server call (task
  drain), not END tick → `AnimSyncNative` stays inline on main.
- **§3.5 item 4 / ADR4.2 §7.2**: D is the actual logical send instant, taken immediately before the
  last fragment enqueue, with `(D, freeze(D, tick, probes))` stored before that enqueue. → enqueue
  and the D/freeze ledger write stay on main, per observer.
- **Per-connection order**: probe → capsules → snapshot fragments of tick t, all before any tick
  t+1 capsule (one logical END-tick commit per observer, ADR4.2 §1). Cross-connection order is not
  part of the contract.
- **Deterministic iteration orders** inside values (sorted victim tables, TreeMap target order,
  application order comparator) are computed inside pure functions and do not depend on threads.
- **Ids minted in order**: `Target.next()` (seed/fold activation ids), `nextSubscription`,
  vehicle epochs (`SnapshotStream.vehicleEpoch` mints on first lookup) → main thread, serial order.
- **Wall-clock reads that feed decisions** (`needsTimingSeed` reads `System.nanoTime()` against the
  probe window; capsule `sentNs`; seed `issuedNs`) stay at their serial program point on main.
- **ADR4 §1 item 7**: CPU is reported, not gated; no candidate cap, no skipped work. Parallelism
  only moves work.

## 2. Pipeline map

Costs: UOPT §2, 30x9 combat, bench machine, one thread (`after` rows); r10 = the legacy END-tick body
pose that runs with or without animation sync. Prototype measurements are labelled **[P]** (§5).

### 2.1 Server

| # | Stage (call site) | Thread today | Inputs | Outputs | Ordering constraints | Cost/tick |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | START tick: tick boundary map, pre-tick velocity (`ServerBodyPoses.startTick`) | main | live players | `AnimSyncValidation.BOUNDARIES`, `VanillaAnimSync.PRE_TICK_*` | before world tick | <0.1 ms |
| 2 | Task drain: C2S applied reports (`AnimSyncValidation.applied`), ticket intake (`CombatViews` → `prepareAction`), native broadcasts with capsules (`AnimSyncNative.route/sealed/flush`), ParCool acceptance | main | packets, observer intake, target journal | intake high-waters, sent-capsule ledger, held native batch | arrival order; capsules in the native call (§2.2) | ~1 ms |
| 3 | Shot: lazy receipt reconstruction (`PendingReceipt.get` → `AnimSyncValidation.materialize/replay/victim`) | main | immutable seeds, sent checkpoints, applied capsules, memo per (observer,target), Store | `Verdict`, memo, intake commit | before native hit resolution; rows in receipt order, first failure wins | 6.6 ms per exact 9-row receipt (0.66 ms/row) |
| 4 | Shot: victim evaluation (`CombatViews.exact` → `AnimSyncEval.evaluate`; projectiles `AnimSyncValidation.advance`) | main | validated victim (immutable recipe/reduction), A, live deps (catalog, TaCZ assets, item, SBW root vehicle, skin) | `CombatHitbox.Body` | per victim at hit resolution; counters at call | 0.37 ms/victim (3.3 ms per 9-victim shot) |
| 5 | END: r10 body pose per player (`ServerBodyPoses.evaluateInner`) | main | live player, server PA/LR/ParCool mirrors | mutated natives, `CombatPoseHistory` | player-list order | 26 ms (865 µs/player) |
| 6 | END: capture freeze (`AnimSyncCapture.commit` → `freeze`, `seal`, `stage`) | main | live player + natives, prior S[t-1] | checkpoint S[t], sealed capsules, staged definitions (store install) | interleaved per player with 5 | ≈21.2 ms = capture 30.6 − round trip 9.4 **[P]** (13 % of capture samples are Mockito dispatch in the bench) |
| 7 | END: retention round trip (`retain` → `AnimSyncLedger.roundTrip`, pin, `target.commit`, `generationBoundary`) | main | immutable S[t] | decoded S[t] in history, bytes, digest, lease | committed before 9 and before `AnimSyncLedger.expire` | 9.4 ms **[P]** (0.31 ms × 30 checkpoints) |
| 8 | END: `AnimSyncNative.flushAll`, `AnimSyncParCool` batch | main | open pairings | native sends | before snapshot batches | <0.5 ms |
| 9 | END per observer: probe, END capsules (+ParCool wrap) send + `delivered` | main | observer ledger | probe/capsule packets, sent-capsule log, freeze evidence | per connection: probe → capsules → snapshot | <0.5 ms |
| 10 | END per observer: `AnimSyncStream.prepare` bookkeeping (flags, stamping, closures, issue) | main | observer/target ledgers | `Prepared` (immutable batch) | targets in TreeMap order; `target.next()` in order | ≈7 ms incl. size-check encode |
| 11 | END per observer: folds inside prepare (`fold` → `ObserverReducer.reduce`) | main | immutable seed, reports, checkpoints, assets, memo | successor seed | id minted after the reduce | fold ≈29.6 ms = reduce 21.9 + commit 7.7 (UOPT JFR shares of 36.6 ms); memo-less replay 35.8 ms **[P]** |
| 12 | END per observer: snapshot encode (today: size-check encode in prepare + netty encode; WIRE: one interned encode + fragmentation + CRC32C on main) | main / netty IO | immutable batch, shared target encodings, table | fragments | before 13 | 9.7 ms for 30 batches with warm splices **[P]**; phase23 codec 0.84 ms/observer (ADR4.2 §3.5) |
| 13 | END per observer: enqueue fragments, D, `publish` (ledger, seeds, freeze evidence), `CombatPoseHistory.sent` | main | fragments, Prepared | packets on the connection, sent ledger | D before last fragment; observer in player-list order | µs + publish ≈0.1 ms |
| 14 | Netty IO: write/flush, Raknetify | netty | bytes | socket | per channel FIFO | not on main |

### 2.2 Client

| # | Stage | Thread today | Inputs | Outputs | Ordering | Cost |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | Payload decode (vanilla/Fabric payload codec) | netty IO | bytes | packet records | per channel | today on netty |
| C2 | WIRE: fragment assembly, CRC, interned decode (ADR4.2 §9) | proposed main handler | fragments, generation table | one decoded transaction | arrival order; atomic publish | 3.0–4.5 ms per 30-player tick (phase23 reflective codec) |
| C3 | Apply: `ClientSnapshotBuffer`/`ClientAnimationJournal` intake, capsule install (native handler + capsule in one task) | main (render) | decoded transaction | buffers, journal, native installs | arrival order; zero added frames (ADR4 §9) | small |
| C4 | Presentation revision: A/R advance, `journal.frame` → per remote `Reduction.step` to floor(A), capsule `apply`, fold `adopt`, heartbeat `report` (C2S) | render | immutable records per life | reduction state, C2S applied reports | once per main frame; reports ordered C2S | ≈30 × 110 µs ≈ 3.3 ms in the frame that enters a new bucket (estimate from server step cost) |
| C5 | Driven presentation per remote: `SnapshotResolver.exact`, `ClientCombatScene.write`, `DrivenPresentation.install` (LR/PA/ParCool natives, vanilla fields, pose) | render | reduced graph, checkpoint, frac(A/R) | live entity + native state | every frame before render | 0.90–1.19 ms/frame at 30 remotes, p99 1.9–3.1 ms (UOPT) |
| C6 | Render + MAIN capture | render | live state | frame, capture | — | — |

## 3. Thread-safety audit

### 3.1 Method and pins

`javap -p -c` from the pinned JDK (`temurin-21.0.12+8`) on the pinned JARs of
`fulla-p23/.gradle/pinned-mods` (SHA-256 verified): PlayerAnimator 2.0.4 `86ef8a23…7b624efd`, TaCZ bf8
`e128c1dd…2a472056`, LR bf2 `c31b4d22…c3982d9b1`, ParCool bf7 `9a0ec4e2…e367eb`, SBW bf24
`24ea4e4c…a9563963`; Minecraft 1.21.1 merged/named from the Loom cache (same mappings the build uses);
ParCool/SBW also as Loom-remapped named jars for readable signatures. Our code read at `cc428a8e`
(UOPT) with a static scan of every `static` field in `K/combat`, `S/combat`, `C/combat`.
"Reached" means reachable from a parallel candidate's call graph by source reading; the runtime
reachability guard in §6.4 checks it under load. A guess is never a verdict: every row cites code.

### 3.2 PlayerAnimator 2.0.4 — safe on distinct instances

- Runtime classes reached by the reducer, evaluation and the LR/TaCZ projections
  (`KeyframeAnimationPlayer` and inner classes, `AnimationStack`, `ModifierLayer`,
  `AnimationContainer`, `AbstractFadeModifier$1/$2`, `AbstractModifier`, `SpeedModifier`,
  `AdjustmentModifier`, `MirrorModifier`, `FirstPersonModifier`, `Vec3f`, `Vector3`, `Ease`,
  `Easing`, `MathHelper`): a scan of every `getstatic/putstatic/invokestatic` finds **no `putstatic`
  outside `<clinit>`**; `getstatic` only reads enum constants, `FirstPersonMode.NONE/DISABLED`,
  `Vec3f.ZERO` (fields written only in `Vector3.<init>`: 3 `putfield`) and
  `MirrorModifier.mirrorMap` (read-only after `<clinit>`). All mutable state is instance state.
- `AnimationStack.getFirstPersonMode` calls `FirstPersonMode.isFirstPersonPass()`, a
  `ThreadLocal<Boolean>` whose initial value is `lambda$static$0: iconst_0` (false). Only a client
  first-person render sets it; on server threads and worker threads it is false, as on the server
  main thread today. Not reached by six-box geometry.
- Shared retained clips: `PaGraph.Catalog.clip(ref)` returns `stored(ref).copy()`.
  `KeyframeAnimation.copy()` = `mutableCopy().build()`; `State.<init>(State)` copies the keyframe
  list; the new instance's constructor runs `verifyAndLock`/`lockAndVerify` (the only writers of
  `isModifiable`/`keyFrames`) on **its own copies** (`lambda$new$0`). The stored clip is only read.
  Per-actor metadata writes (`extraData.put`) happen on Stack-private copies (`LrPose.actor`,
  `PaGraph` 1582/1596), never on stored clips.
- `KeyframeAnimation$AnimationBuilder.staticThreshold` is a public non-final static float read by
  every builder; its only `putstatic` is `<clinit>` (8.0f) and no other pinned JAR or our source
  references it. Safe while that holds (UPAR-2 asserts the value at pool start).
- `PlayerAnimationRegistry.animations` (static `HashMap`), `PlayerAnimationAccess`,
  `PlayerAnimationFactory$FactoryHolder`: not reached by any parallel stage (capture/client only).

### 3.3 LR bf2 — safe on distinct instances; live integration main only

- `PlayerAnimatorIntegration$FrozenAnimationSnapshot` (field `source`) and `$ForcedFadeModifier`
  (field `ease`), constructed reflectively by `PaGraph.Reflect.frozen/forced`: no static access in
  any method; behaviour is delegation to the instance's `KeyframeAnimationPlayer`/`Ease`.
  The outer class's `<clinit>` builds three `ResourceLocation`s (JVM-synchronized class init).
- The live integration (`playIdleAnimation/playAttackAnimation/getLayer/getAnimation`, static
  `initialized`, `IPAAssetManager`, `PlayerAnimationAccess.getPlayerAssociatedData`) is reached only
  by capture/the r10 pose (`LrBodyPose`) → main only.

### 3.4 TaCZ bf8 and LuaJ — main only; not reached by any parallel stage

TaCZ ships `luaj-core/luaj-jse 3.0.8-figura` in `META-INF/jars`; its state machines
(`LuaAnimationStateMachine`, `LuaStateMachineFactory`, `vmlib/Lua*Constant`) and server gun logic
scripts run inside TaCZ's own client/native handlers. Our references to the TaCZ runtime
(`IGun`, `IGunOperator`, `CommonAssetsManager`, `TimelessAPI`, `GunData`, events) are in capture
(`ServerBodyPoses`, `AnimSyncCapture`), native broadcast (`AnimSyncNative`), asset building
(`HeldWeaponPose`) and damage code — all main thread. The reducer, validation, evaluation and encode
use only our ports (`TaczAssets`, `TaczPose`, `PaProjection`) and `HeldWeaponPose.assets/policy`
static accessors, which UPAR-2 resolves on main before a fork (`GENERATIONS` is a main-thread
`HashMap`). Verdict: TaCZ runtime and LuaJ are **main-thread only and unreached**; the §6.4 guard
fails the run if a worker frame ever enters `com.tacz.` or `org.luaj.`.

### 3.5 ParCool bf7 — safe on distinct scratch instances; client animators render-only

- Evaluation builds a fresh scratch attachment (`ParCoolFrame.scratchAttachment`): `Parkourability.<init>`
  → `Actions.constructActionsList()` (`getstatic ACTION_REGISTRIES`, stream map to constructors),
  `ActionInfo.<init>` reads the immutable `ClientSetting/ServerLimitation.UNSYNCED_INSTANCE`; action
  constructors contain no `putstatic` (ClingToCliff reads an enum constant, WallJump allocates a
  `ByteBuffer`). `Actions` registries are filled in `<clinit>` and only read after.
  `setClientSetting(GEOMETRY_POLICY)` writes the scratch's own `ActionInfo`; `GEOMETRY_POLICY` is a
  stateless anonymous `ClientSetting`.
- `MathUtil`/`VectorUtil` (used by `ParCoolStages`/`ParCoolReducers`/`ParCoolYaw`): pure static
  functions over `Math`, `Mth.sin/cos`, `Vec3`.
- Server ParCool animators are our ports (`S/combat/*Animator`, `ParCoolGraph` with package
  `com.lunares.blockfield.server.combat.`): no static mutable fields. The client's native animators
  (`com.alrex.parcool.client.animation.impl.*`) mutate live attachments → render thread only.
- `ParCoolActions.capture/synced` read `player.getAttributeValue(GRAVITY)` (lazy attribute creation,
  §3.7) → capture only.

### 3.6 SBW bf24 — main only

Seat capture (`SbwBodyPose.capture`, `VehicleEntity.getSeatIndex`) and evaluation's SBW root
(`victim.level().getEntity(id)`, `SnapshotStream.vehicleEpoch` → `computeIfAbsent` that **mints** a
`VehicleState` epoch, `AnimSyncStream.vehicleAt`) read live entities and mint ids → main. The pure
projection (`SbwBodyPose.project/projectedRoot/constants` over retained reads) is safe; UPAR-2
evaluates victims with a SBW root vehicle serially at their call point (§4.6).

### 3.7 Vanilla 1.21.1 — capture is main only

- **Chunk access deadlocks off-main.** `ServerChunkCache.getChunk(int,int,ChunkStatus,boolean)`:
  `Thread.currentThread() != mainThread` → `CompletableFuture.supplyAsync(…, mainThreadProcessor).join()`.
  With the main thread parked in a fork-join, any worker block read (`Level.getBlockState`) waits for
  the main thread forever. The END-tick inputs read blocks (`LivingEntity.onClimbable`,
  `ParCoolPoseInputs.waterAtCommit`, ParCool/vanilla capture).
- **Reads with writes.** `Entity.getInBlockState()`: `getfield inBlockState; ifnonnull; … putfield
  inBlockState`. `LivingEntity.onClimbable()`: two `putfield lastClimbablePos`.
  `AttributeMap.getInstance(Holder)`: `Map.computeIfAbsent` on `attributes`. A "read-only" freeze of
  a player mutates that player and the chunk cache's `lastChunkPos/lastChunkStatus`.
- **RandomSource.** `LegacyRandomSource.next`: `AtomicLong.compareAndSet` failure →
  `ThreadingDetector.makeThreadingException("LegacyRandomSource")`. Only used at acceptance
  (`ServerBodyPoses.acceptedWeaponAction` variant choice), main; unreached by parallel stages.
- **Registries**: frozen `MappedRegistry` reads (`BuiltInRegistries.ITEM.get`) are pure reads; still
  resolved on main in UPAR-2's evaluation prologue.
- **`Mth`**: `SIN`/`COS_TAB`/`ASIN_TAB` filled in `<clinit>`, read-only; `sin` is `getstatic SIN;
  faload`. `Mth.RANDOM` (thread-safe) and the mutable JOML statics `X/Y/Z_AXIS` are not referenced by
  our code (scan). **JOML**: `Options` static finals (`fastmath=false`, `useMathFma=false`) are read
  once; Java 17+ floating point is strict and HotSpot uses the same math stubs in every tier, so the
  same code gives the same bits on every thread of one JVM (the U10/UOPT bit-identity gates already
  rely on this within a run).

Verdict for ADR task 3(a) "parallel freeze of distinct players while the main thread is parked":
**not side-effect free → rejected.** Beyond the vanilla reads above, freeze writes shared state:
`PaGraph.Catalog.register` (identity `refs` + `LinkedHashMap clips/content`) from `LrBodyPose`
741–782 and `PaGraph.Stack` 1004/1028/1049, `Catalog.named` from `HeldWeaponPose` 596–611,
`AnimSyncLedger.install` via `stage`/`closure`, the static `AnimSyncCapture.staged/stagedRefs`,
`GENERATION_DEFINITIONS`, counters, and it is interleaved per player with the r10 pose that advances
the server PA mirrors. Only the round trip (stage 7) moves.

### 3.8 Our code

| State | Where | Kind | Verdict |
| --- | --- | --- | --- |
| `AnimationSchema.SCRATCH/SCRATCH_DEPTH` | encode buffers per nesting level | `ThreadLocal`, bounded like `Unpooled.buffer(256, MAX)` | per-worker copy (automatic) |
| `AnimationSchema.DIGESTS/REFERENCES/FINITE_VALUES`, `PartLists.REFERENCES`, `AnimationGraphValidator.VALIDATED`, `ParCoolFrame.ORDERED`, `BodyPose.SCRATCH` | identity memos of immutable values (UOPT, "this thread"), cleared at 1024 | `ThreadLocal` | per-worker copy; result-neutral (values immutable; a hit only skips a deterministic check/derivation) |
| `AnimationSchema.SPLICES` (`shareEncoding`) | per-tick shared component encodings | `ThreadLocal`, 8 scopes | per-worker copy is correct but loses reuse; UPAR-2/WIRE make it an explicit immutable per-tick map passed to encoders (§4.5) |
| `AnimationSchema.LAYOUTS/CONSTANTS/FINITE_DEPTH` (`ClassValue`), `BY_ID/BY_CLASS` (`LinkedHashMap` filled in `<clinit>` only) | codec layouts | thread-safe / immutable after init | safe |
| `PaGraph.Catalog` memos `metadata/parts/bodyParts/copyStable` | generation catalog | `ConcurrentHashMap` (`computeIfAbsent` atomic) | safe |
| `PaGraph.Catalog.firstPerson[]` | lazy array of immutable records | benign race (equal records) | safe; prewarm 16 entries at generation build |
| `PaGraph.Catalog.refs/clips/content/names/values` | identity/LinkedHashMap, written by capture `register/named/define` | not thread-safe | safe in fork-join (no writer while main is parked); **unsafe for overlap** (§4.9) |
| `PaGraph.Reflect.standardEase/forcedEase/frozenSource/frozenCtor/forcedCtor` | lazy reflective handles | `volatile`, idempotent | safe; prewarm at pool start |
| `ObserverReducer.Memo/Reduction` | per (observer, target life) | "Not thread-safe" (javadoc) | confined: one task per pair; speculative tasks run on a copy (§4.2) |
| `ObserverReducer.shareStepScratch/verifyMemo`, `AnimSyncValidation.bounds/memoize`, `AnimSync.exactTransport/captureAlways` | switches | `volatile`, written by tests/config | safe |
| `AnimationResources.Store` (`AnimSyncLedger.STORE`) | definitions + pins | `HashMap`, written by `install/pin` on main | reads safe in fork-join; writes main |
| Main-thread registries: `AnimSync.COUNTERS`; `AnimSyncLedger.TARGETS/INSTALLED`; `AnimSyncCapture.GENERATION_DEFINITIONS/staged/stagedRefs`; `AnimSyncStream.VEHICLES/CLOSURES/closureTick/nextConnection/nextSubscription`; `SnapshotStream.OBSERVERS/VEHICLE_EPOCHS/nextStream/nextSubscription`; `AnimSyncValidation.PROBES/BOUNDARIES/EVIDENCE/INTAKE/STAMPED`; `ServerBodyPoses.STATES/nextEpoch`; `HeldWeaponPose.GENERATIONS/cache/resourceEpoch`; `LrBodyPose.GENERATIONS/STACKS/OWNERS`; `CombatPoseHistory.*`; `AnimSyncNative.OPEN`; `AnimSyncParCool.batch/inTick`; `CombatViews.*` (incl. its `ThreadLocal` hit contexts); `ServerBodyPoses.State` lazy fields (`skin`, `shape`, `dimension`) | plain maps/fields | not thread-safe | **main only**: read during planning, written during commit |
| `ParCoolPoseInputs.INTENTS` | `ConcurrentHashMap` | thread-safe | main anyway |
| Client: `DrivenPresentation.FRAMES/WALKS/LOST/scoped`, `ClientCombatScene.*`, `CombatVehicleScope/VanillaDrivenScope.FRAMES`, `ClientAnimationAssets.GENERATIONS/ADMITTED`, journal state | render-thread registries | not thread-safe | render thread only |

**Constant-pool scan.** `javap -v` over the 283 compiled classes of the reducer/resolver/codec
closure in `K/combat` (`ObserverReducer`, `PaGraph`, `PaProjection`, `LrProjection`, `LrPose`,
`ParCool{Stages,Projection,Reducers,Frame,Graph,Actions,Yaw,Checkpoints,Accepted}`,
`AnimationGraphValidator`, `AnimationResolver`, `AnimationSchema`, `TaczAssets`, `TaczPose`,
`LrAssets`, `PaAdjustments`, `CreateHanging`, the value types, `BodyRig`, `BodyGeometry`) finds **no**
class reference to `com/tacz`, `org/luaj`, `net/minecraft/server`, `net/minecraft/world/level` or
`io/netty/channel`. External references: PlayerAnimator (64), ParCool action/attachment/info/config/
utility classes (32), `Mth`, `Vec3`, `FriendlyByteBuf`, `Unpooled`, `ResourceLocation`, commons
`StringUtils`, `Component.Serializer`/`RegistryAccess.EMPTY` (only in `PaGraph.nativeKey`, called
from `TaczAssets` at generation build) and `Player`/`Attributes` (only in `ParCoolActions.capture/
synced`, capture path). ParCool `EasingFunctions` used by `ParCoolStages` has no static fields.
Server-side parts of evaluation (`AnimSyncEval.pose`, `VanillaAnimSync.project`, `SbwBodyPose.project`,
`ServerBodyPoses` static helpers, `BodyPose`, the ParCool animator ports) live in classes that also
hold live-entity methods, so for them the verdict rests on the source reading of the called methods
plus the runtime guard (§6.4).

The reducer's own contract (`ObserverReducer` javadoc: "Inputs are immutable decoded values; nothing
here reads a live entity, a clock or a later H") holds on the code read: its call graph
(`PaProjection`, `LrProjection`/`LrPose` with `ClipRef` overloads only, `ParCoolStages`
`Mode.OBSERVER`, `ParCoolProjection`, `AnimationGraphValidator`, `AnimationSchema`) touches only the
values passed in, the catalog's read paths and the thread-confined memos above. `AnimationResolver`
is static methods over its request, history and the Store.

## 4. Design

### 4.1 Execution model: plan → pure tasks → ordered commit

One server-side helper, `S/combat/AnimSyncWorkers`:

```java
// Runs task(i) for i in [0, n) on the pool and the calling (main) thread; returns outcomes by index.
static <R> Outcome<R>[] run(int n, IntFunction<R> task);
record Outcome<R>(R value, Throwable thrown) { R get() /* rethrows thrown unchanged */ }
```

- **Lanes.** W pool threads plus the calling main thread claim indices from one `AtomicInteger`
  (dynamic load balance; outcomes stored by index, never by completion order). W = 0 runs the same
  loop inline, which is exactly the serial path. No nesting: a task calling `run` throws.
- **Happens-before.** Everything a task reads is written by main before `run` publishes the batch
  (executor submission/volatile); outcomes are read after the completion latch. Workers never read a
  structure the main thread writes concurrently, because main only claims tasks or waits.
- **Exceptions.** Each task's `Throwable` (any type, including `Error`) is captured as its outcome;
  nothing is cancelled or dropped. The main thread replays outcomes in serial order and, at the
  exact program point where serial code called the pure function, either uses the value or rethrows
  the **same Throwable object** into the same surrounding `catch` (e.g. `fold`'s
  `IllegalArgumentException | ArithmeticException` → `seedFoldUnavailable:reducer`; `exact`'s
  `IllegalArgumentException | IllegalStateException` → observer disabled; anything else propagates
  as it would have). The stack trace shows worker frames; class, message and identity are the same.
  Work after a serial early exit (a failing row) is computed but its outcome and effects are discarded
  exactly where serial code would not have run it.
- **Waiting** is uninterruptible (interrupt status restored after completion): serial code has no
  wait, so the parallel path must not add an `InterruptedException` failure mode.
- **Speculate and verify.** Where planning cannot be done at the serial point (folds), a task's inputs
  are recorded; at the serial point the main thread re-derives the inputs and uses the outcome only
  if they are identical (identity for immutable values, `equals` for small records), else computes
  inline. Correctness then rests only on purity of the function.
- **Effects as values.** Tasks return everything they would have written (memo copy, counter deltas,
  per-row nanos); the main thread applies them in serial order, so `AnimSync.COUNTERS` (a
  `LinkedHashMap`) gets the same keys in the same insertion order.

### 4.2 Observer folds (stage 11) — the main win

Today `prepare` calls `fold(observer, target, identity, tick)` per non-FULL target; `fold` reads the
newest seed, `AnimSyncValidation.reports/foldable`, the boundary checkpoints, runs
`ObserverReducer.reduce(..., memo(observer, target))`, then mints `target.next()`, stamps, encodes the
seed content, round-trips the successor seed, installs its definition and pins it.

UPAR-2 split (no behaviour change):

1. `foldInputs(observer, target, identity, tick)` → `FoldInputs | Skip(reason)`: the read-only
   preconditions up to the reduce call, without counters.
2. **Plan** (main, after `AnimSyncNative.flushAll`, before the observer loop): for every observer in
   player-list order and every non-fresh eligible target in `TreeMap` order whose seed is ≥ `FOLD_AGE`
   old, compute `foldInputs` with the identity `prepare` would stamp (non-fresh ⇒
   `previous.generation()`, no counter is minted). Record `FoldInputs` + a **copy** of the pair's
   `Memo` (shallow field copy; `resume` already returns a private `Reduction` copy and never mutates
   the retained one). Planning uses only non-mutating lookups: `intake(observer).memos.get`, never
   `AnimSyncValidation.memo`/`intake`, whose `computeIfAbsent` and stale-memo pruning would run at a
   different time than in serial code.
3. **Tasks**: `ObserverReducer.reduce(inputs…, memoCopy)` → `(Result, memoCopy)` or Throwable.
4. **Serial point** (inside `prepare`, unchanged order): `fold` recomputes `foldInputs` (cheap) and
   looks up the planned outcome; on identical inputs (seed `==`, activation `equals`, bucket `==`,
   every checkpoint `==`, applied list element-wise event `==`/proof `equals`, assets/policy `==`, and
   the memo returned by the serial `memo(...)` call still the instance that was copied, or both
   absent/fresh) it installs `memoCopy` as the pair's memo and uses
   the outcome (value, or rethrow into fold's own catch); otherwise it reduces inline. Then the
   unchanged commit: `target.next()`, stamp, content digest, round trip, install, count, pin.

Why speculation and not a reorder: `foldable` reads the observer's sent-capsule log, which
`delivered(observer, …)` prunes right before `prepare(observer)`; a plan taken earlier can differ at
the 40-tick edge. Verification at the serial point makes that a cache miss, not a semantic change.
Memo state after the tick equals serial: the copy started from the live memo's state and consumed the
same inputs; nothing else touches `memo(O,T)` during the END stream phase (validation runs in the task
drain).

Expected: folds dominate (13.5 per tick at 30x9, bursty: targets seeded together fold together, see
ADR4.2 §3.3). The commit stays on main (its seed content needs the minted id): ≈7.7 ms/tick, of
which ≈4.2 ms are successor-seed round trips (13.5 × the measured 0.31 ms per round trip **[P]**);
§4.5 moves those into the observer's encode task.

### 4.3 Retention round trip (stage 7) — ordered per-player joins

**Coordinator correction, 2026-10-19 (supersedes the deferred design below):** caught exceptions,
including RuntimeException, preserve the complete serial state/capture order. An escaping
`java.lang.Error` may be fatal at the same parallel-stage boundary, with the same identity, only
if both the no-continue catch audit and the no-persistence/no-send shutdown audit pass. Otherwise
retention joins before advancing the next player; folds/rows/evaluation remain parallel.

UPAR-2 chooses the conservative fallback: the shutdown-isolation audit is **not certified**.
The pinned named MinecraftServer bytecode has `runServer` crash handling followed by
`stopServer`; `stopServer` calls `PlayerList.saveAll` (offset 61), `removeAll` (68), and
`saveAllChunks` (224), and continues chunk work during shutdown (206). Native capture has live
getters (including ParCool `getAttachedOrCreate`, vanilla lazy block/climb reads), native item
getters and catalogue writes (§3.7). Merely reaching the outer crash handler cannot prove that
all intervening pack callbacks and saves exclude every additional mutation. The limited audit
also confirms TaCZ gun/display getters read a copied CustomData tag and ParCool PARKOURABILITY
is nonpersistent (STAMINA is persistent); neither is evidence that the full path is isolated.
No actual saved-data divergence is claimed. The exhaustive two-part proof required to permit
speculation has not passed, so no fatal-Error exception to equality is used.

Evidence bytecode: `WS/.dev/coord-20261011/upar2-cx-error-audit/` (MinecraftServer, PlayerList,
LivingEntity, AttributeMap, TaCZ GunItemDataAccessor, ParCool Attachments/Parkourability).
Each pure round trip may run on the shared pool but is joined and consumed inside the original
`complete` call before the next native capture. Even Error end state remains serial-identical;
the original JUnit and transformed END-loop counterexamples remain strict equality tests.
There is no shutdown-isolation claim to trust and no new shutdown hook. The END scope still
bounds retention, and every job is already joined when it closes.

The following is the **superseded overlap proposal**, retained to explain the rejected optimization:

`commit(player)` today: `freeze` → `seal` → `retain` (`roundTrip`, equality check, pin, `target.commit`,
counters) → `generationBoundary`, or the failure path (`unavailable`, `missing`, `parcoolLost`,
`latched`/`reseed`). UPAR-2:

- After `freeze`+`seal` of player P, submit `roundTrip(S_P[t])` (pure: `AnimationSchema.encode`,
  `readComplete`, `Digest.hash`, `equals`) and continue with player P+1's r10 pose and capture.
- **Barrier** at the end of the `ServerBodyPoses.endTick` player loop, before `AnimSyncLedger.expire`
  and before `SnapshotStream.endTick`: join, then for each player in loop order run the original
  `retain` tail with the outcome (pin `stagedRefs` captured per player instead of the static
  `staged/stagedRefs` pair, `target.commit`, counters, `generationBoundary`) or the original failure
  path (`target.unavailable("retain:…")`, `checkpointUnavailable`, `missing`, `parcoolLost`,
  `latched → reseed`).

This *is* a reorder (P's commit after P+1..N's capture), so it carries a commutation proof:
P's commit writes only P's target (history, revision, unavailable/missing/reseed fields), P's
`ServerBodyPoses.State` and LR stack/weapon (reseed), ledger pins and counters. Players P+1..N's
r10+capture read their own prior S[t-1] (`target.at(tick-1)`), their own state/stack, the Store via
`installed/get` (installs happen in `stage` *before* retain, unchanged), and register clips in the
shared catalog. Catalog registration is content-addressed and idempotent; its `LinkedHashMap` order is
consumed only into hash indexes (`AnimSyncCapture.index`, `LrAssets.definitions` → `HashMap`, client
linear `equals` search), so a reseed's registrations moving after P+1's are unobservable. Counters
are applied in player order at the barrier (first-insertion order of a new key may differ only if
P's commit creates a key P+1's capture also creates; UPAR-2 makes the counter report order canonical
(sorted) — diagnostics only — or pre-registers capture keys). The failure-injection differential
(§6.1) forces a retain failure for player k and compares end states. Deferral applies only inside
the END-tick player loop; every other caller of `evaluate`/`commit` (restart, seat transition, a
same-tick dirty re-evaluation) runs synchronously, and the barrier also runs before any such call
that could occur while commits are pending (none today: they all run outside the END loop).

### 4.4 Receipt validation rows (stage 3)

`replay(observer, prepared)` loops rows: identity checks → `victim(...)`: seed/activation/start checks,
applied capsules, prefix digest, boundary checkpoints (main reads) → `reduce(…, memo(O, row.target))`
→ starts compare → `resolve` (stamped history via the static `STAMPED` cache, `AnimationResolver`
over the Store) → victim or first failure.

UPAR-2: **prologue** per row in order on main (all reads, `accepted/reports` accumulation, the
stamped history built with `STAMPED` lookups but **without** its `put`), stopping at the first
prologue failure; **tasks** per planned row: `reduce` with a memo copy, start compare, `resolve`;
**epilogue** in row order on main: per row apply the `STAMPED` puts, `validateRowNanos`/`validateRows`,
install the memo copy if the row reached `reduce`, and stop at the first failing row (rows after it:
outcomes, memo copies and puts discarded, exactly the rows serial code never ran). EXACT receipts
then commit as today. Rows use distinct target lives (`victim_set`), so no two tasks share a memo.
At 30x9: 9 rows ≈ 6.6 ms → ≈ 2.4–2.8 ms with 3 lanes; at 30x29: 29 rows ≈ 19 ms → ≈ 7 ms.

### 4.5 Snapshot encode and fragmentation (stage 12, WIRE-SERVER)

The encode of a `Prepared` batch (interned, ADR4.2 §5) and its fragmentation/CRC32C are pure
functions of the immutable batch and the immutable generation table.

- **Shared encodings explicit.** Replace the thread-confined `SPLICES` registry with an immutable
  per-tick `SharedEncodings` map built once per tick (one task per target: encode the 11 checkpoint
  components that `AnimSyncStream.share` registers) and passed to every observer's encode. Copies
  repeat the position's type check and the depth bound exactly as `writeShared` does today, so bytes
  are identical by construction; the WIRE gate's `expand(interned) == canonical` differential covers
  it.
- **Pipelining.** Observer loop on main, unchanged order: probe send; END capsules encode + enqueue +
  `delivered` (small; keeps `delivered` before `prepare` as today); `prepare(O)` (folds from §4.2);
  submit `encode(O)`. After the loop: join; for each observer in loop order: enqueue fragments with D
  taken immediately before the last one and the `(D, freeze)` ledger write before it (ADR4.2 §7.2),
  `publish(O)`, `CombatPoseHistory.sent`, `lastTick/lastSendTick`; an encode exception takes the
  original `exact` catch (observer disabled, counted) at O's position. Then `AnimSyncParCool.flush` as
  today.
- **Reorder proof.** Deferred per O: snapshot enqueue, `publish(O)`, `CombatPoseHistory.sent`,
  `lastTick`. They write O's own ledger (`incarnations/sentRefs/seeds/seedLastLive/sent`, `prune(O)`,
  `EVIDENCE(O)`), `target.sent(tick)` (`sentThrough`, read by nobody during the stream phase),
  `SENT_THROUGH` (read only by `CombatPoseHistory.commit` at END-tick capture) and counters. Probe,
  capsules, ParCool wrap and `prepare` of later observers read only their own observer state, the
  targets' history/journals and `Target.next()`; none of the deferred writes. Per-connection order
  (probe → capsules → snapshot) is unchanged; cross-connection order is not part of the contract.
- **Seed round trips ride the encode task (optional).** `issue`/`fold` round-trip the new seed on
  main (≈4.2 ms/tick of fold commits). The seed's id and content digest are minted at the serial
  point; the round trip only produces the decoded value that the batch carries and `publish` pins.
  Moving it into O's encode task (decode first, substitute, then encode) keeps the bytes exact, but
  `prepare` derives the batch's definition closure from the seed before the decode; this is the same
  only if `decode(encode(seed)).equals(seed)`, which `retain` already asserts for checkpoints. Adopt
  only together with that assertion for seeds (a codec defect then disables the observer instead of
  silently sending the decoded value).
- D/freeze stays on main per observer; netty writes stay `connection.send` from main in observer
  order ((d) of the brief: no proof that off-main enqueue would preserve D semantics, and the ledger
  writes are main-only anyway).

### 4.6 Victim evaluation (stage 4)

Evaluation is called per victim from hit resolution (`CombatViews.exact`), interleaved with native
hit logic. `AnimSyncEval.pose` is "the ADR4 §1 equation over decoded values … no live entity read";
its `Dependencies` are lazy suppliers over live/static state (catalog, TaCZ assets, `special` item,
SBW root vehicle, skin).

UPAR-2: at the first `exact(...)` of a stamp for a given `(age, advancing)`, the main thread plans all
validated victims of that stamp: for each, resolve the dependencies **without side effects** —
`LrBodyPose/HeldWeaponPose.assets(gen)` (pure `GENERATIONS.get`, `Unsupported` on a miss),
`TrustedPlayerSkin` only on a `state.skin` key hit, `BodyShape.ofCached` (writes the constant `SINGLE`, done here), `special` (Store
read + frozen registry + `new ItemStack`), each captured as an `Outcome` so a supplier's exception
surfaces only when the composition first asks for it (same order as the lazy supplier). Victims whose
recipe has a SBW root vehicle, or any dependency that would mint/build, are **not** planned: they
evaluate inline at their own call, as today. Tasks: `advance` (projectiles) + `pose` + `Body`. Each
later `exact(...)` call takes its victim's outcome and emits `evals/evalNanos/evalUnavailable` and
`resolved:*` at that call, in call order. `BodyPose`'s wall-clock sample fields are run-variant
metadata (as in the UOPT perf gate). Within one server task no planned dependency can change (asset
generations change at END tick, skins at login).

### 4.7 Capture freeze (stages 5–6): stays main

Rejected for parallelism by §3.7. A future split (UPAR-3, not in UPAR-2): main reads the live inputs
into an immutable raw record (entity fields, blocks, attributes, ParCool attachment, TaCZ operator,
SBW seats), then workers freeze the server PA/LR graphs and run `validateComplete/validateFinite/
closure`. Prerequisites: catalog registration moved out of freeze (pre-registered native clips),
`staged` made per-player, store install deferred to commit. Bound: ≈ half of the ≈21 ms freeze/commit;
unmeasured.

### 4.8 Client

- **Decode (C2)**: ADR4.2 §9 allows it off main. Do assembly + CRC + interned decode on the connection's
  ordered pipeline (a handler after the payload decoder, per-channel FIFO; or a single dedicated
  decode thread fed in arrival order) and hand one complete immutable transaction to the main
  thread's existing handler in arrival order. A transaction whose generation table is not yet in the
  immutable concurrent table registry, or that fails CRC/bounds, is handed over as an error/raw
  outcome at its arrival position, so the main thread takes the same defect/restart path. Capsule
  installation keeps ADR4 §9's zero-added-frames rule (decode happens before the main task exists).
- **Reducer steps (C4)**: per presentation revision, `journal.frame` steps every life to floor(A);
  lives are independent (`Reduction` per life, immutable records). Optional fork-join over lives for
  the `while (reduction.bucket() < bucket) step(...)` loops only; seed activation, capsule `apply`,
  fold `adopt` and C2S `report` stay on the render thread in life order; exceptions are applied as
  `reducer:` presentations/counters in life order. This moves life L+1's activation before life L's
  apply/adopt/report; lives share only the journal counters and the ordered C2S reports, which the
  last phase keeps in life order — to be re-checked against FX-CLIENT-3's journal before adoption.
  Removes most of the ≈3.3 ms bucket-entry spike at 30 remotes. Client knob (default 1 worker on ≤4
  cores, 2 above).
- **Driven presentation (C5)**: install writes live entity fields (`oAttackAnim`, `setPose`,
  `setSwimming` → `SynchedEntityData`), LR/PA/ParCool native state and render-scope registries →
  render thread only. Its pure half (materialising the reduced graph for the finite probe sample at
  frac(A)) could run in a frame-entry fork-join; gain ≤ ~0.4 ms/frame at 30 remotes (UOPT §2:
  0.9–1.2 ms total). Not recommended for UPAR-2.

### 4.9 Cross-tick pipelining

| Stage | Cross-tick? | Reason |
| --- | --- | --- |
| Capture/freeze, retention | no | S[t] needs END-of-t live state and must be published before tick t's batch (§2.3 item 4) |
| Folds | **possible later (UPAR-3)** | inputs usually exist at the start of tick t (bucket ≤ a−1 ≤ H−1 ≤ t−2), so folds could overlap the world tick; speculation keeps it ADR-neutral (the seed still issues in t's batch, ids minted at the serial point). Blocked today: workers would run *while main writes* `Catalog.clips/refs/names` (capture), the Store (`install`) and `HeldWeaponPose/LrBodyPose.GENERATIONS`; these read paths must first become immutable per-generation snapshots |
| Validation | no (intra-task only) | the verdict is consumed synchronously at native admission; an earlier speculative start at C2S intake is possible (same verify-by-inputs rule) but not planned |
| Evaluation | no | synchronous per shot |
| Encode/enqueue | no | snapshot(t) must precede tick t+1's capsules on each connection |
| Client decode | yes (ordered per connection) | pure; application order unchanged |

### 4.10 Pool, lifecycle and memory

- **One pool** `AnimSyncWorkers`, shared by anim-sync and the WIRE encoder; named daemon threads
  `blockfield-anim-sync-<n>`, default stack size (same as the server thread, so recursion limits do
  not differ), created on `SERVER_STARTING`, resized only between ticks, shut down on
  `SERVER_STOPPED` (`shutdown`, bounded `awaitTermination`, `shutdownNow`; tasks only exist inside a
  fork, so the pool is idle then). A rejected submission (pool stopping) runs the remaining indices on
  main: same outcomes.
- **Size knob**: `AdminSettings.netcode.animSyncWorkers` (0–4) with a `-Dblockfield.animSync.workers`
  override; 0 = today's serial path. **Default 2** for the homeserver (4C/8T, load ≈2 from game,
  Workshop, Velocity, site; the main thread, netty IO and GC already use ≥2 threads; 2 workers + main =
  3 lanes leave headroom). Report `parallelEfficiency = Σ task CPU / (lanes × fork wall)` and
  `forkWait` per tick in `/bodypose` diagnostics so the owner can lower it. Open item for the
  coordinator (not checked here: Dokploy was out of this unit's scope): the game container's CPU
  quota/cpuset. A CFS quota below ≈3 CPUs throttles the whole JVM when 3 lanes run; then the default
  must be 1.
- **Prewarm** at pool start on main: `PaGraph.Reflect` handles, `Catalog.firstPerson[0..15]`, class
  initialisation of PA/LR/ParCool classes used by tasks (JVM-synchronized anyway), assert
  `AnimationBuilder.staticThreshold == 8.0f`.
- **Per-worker scratch**: the existing `ThreadLocal`s (codec buffers per nesting level, identity
  memos, `BodyPose.SCRATCH`). No new shared mutable scratch.
- **Memory budget** (analytic; measured in §6.3): per worker ≤ 1 MB stack, codec buffers ≈ 2× the
  largest encoded value (WIRE transactions 0.4–0.6 MB → ≈1.2 MB), identity memos ≤ 1024 entries ×
  6 maps of references to immutable values already retained by the 40-tick history (extra retention
  only for evicted values until the 1024-entry clear). Budget: **≤ 32 MB heap after GC per 2 workers**
  at 30x9; the explicit `SharedEncodings` replaces 8 scopes × ≈0.7 MB of per-thread splice bytes.

## 5. Projection

### 5.1 Prototype measurement [P]

Prototype (not production): branch-local commit on `origin/fulla/as-uopt` `cc428a8e`, patch in
`docs/review/anim-sync-parallel-proto.patch` (two benchmark probes and `AnimSyncParallelProto`).
After every measured tick of `AnimSyncBench` (outside its own timing) it replays that tick's pure work
on the calling thread and on fixed pools of 1–4 threads: every fold reduction (memo-less, i.e. a seed
replay like most production folds), every retention round trip and every observer's snapshot batch
encode (with the tick's shared component encodings registered per thread). Each variant's outcomes
are compared with the calling thread's (values, encoded bytes, exception class/message).

Run: 2026-10-18, bench machine AMD Ryzen 5 7535U (6C/12T), Temurin 21.0.12+8, cohort 30x9,
120 warm-up + 200 measured ticks, heavy lock held; machine load average 3.1–3.9 from other sessions
during the run (no other Gradle job). Wall and CPU are per tick, means (p99 for wall); "pool N" =
N worker threads with the benchmark thread waiting; "caller" = the benchmark thread itself.

**30x9 combat** (13.5 folds, 30 round trips, 30 batch encodes per tick):

| stage | lanes | wall mean / p99 (ms) | CPU all threads (ms) | speedup vs caller |
| --- | --- | --- | --- | --- |
| fold reductions | caller | 35.84 / 128.90 | 31.96 | 1.00 |
| | pool 1 | 37.34 / 104.28 | 33.18 | 0.96 |
| | pool 2 | 20.94 / 62.16 | 34.54 | 1.71 |
| | pool 3 | 13.68 / 49.08 | 34.60 | **2.62** |
| | pool 4 | 12.25 / 48.14 | 36.21 | 2.93 |
| retention round trips | caller | 9.36 / 21.69 | 8.95 | 1.00 |
| | pool 1 | 9.68 / 15.82 | 9.30 | 0.97 |
| | pool 2 | 5.42 / 13.61 | 10.00 | 1.73 |
| | pool 3 | 3.99 / 15.36 | 10.48 | **2.35** |
| | pool 4 | 3.29 / 10.42 | 11.11 | 2.85 |
| batch encodes (per-thread splices) | caller | 9.73 / 26.33 | 8.98 | 1.00 |
| | pool 1 | 15.64 / 31.45 | 13.66 | 0.62 |
| | pool 3 | 8.49 / 20.35 | 22.38 | 1.14 |
| | pool 4 | 8.36 / 25.05 | 27.03 | 1.16 |

**30x9 idle** gives the same picture (fold 33.20 ms caller → 13.89 pool 3 (2.39×) → 10.74 pool 4
(3.09×); round trips 9.21 → 4.02 (2.29×); encodes 9.25 → 8.47).

**Equality:** in both cohorts every one of 2,700 fold outcomes (result value and graph bytes),
6,000 round trips (decoded value, byte count, digest) and 6,000 batch encodes (byte digest) on each
of pools 1–4 equalled the calling thread's; 0 mismatches, 0 exceptions.

Readings:

- Folds and round trips scale well (2.4–2.6× on 3 threads, 2.9–3.1× on 4) with 4–8 % (folds) and
  10–17 % (round trips) extra total CPU. Fold p99 is bounded by bursts (targets seeded together fold
  in the same tick): 129 → 49 ms on 3 threads.
- Encodes with the thread-confined splice registry do **not** scale: each worker re-encodes every
  shared component once per tick (pool 1 is 1.6× slower than the warm caller; CPU 2.5× at pool 3).
  This is the measured reason for the explicit per-tick `SharedEncodings` of §4.5.
- The replay perturbs the benchmark's own production numbers (main-thread caches polluted, 5× the
  allocation churn): its anim-sync CPU/tick rose to 207 ms in this run. Absolute serial costs below
  therefore come from UOPT §2; the prototype contributes only ratios and equality.
- Not measured: validation rows and evaluation (assumed to scale like folds: same reducer/resolver
  and PA materialisation work, but only 9 tasks per receipt), the explicit-shared-encodings encode,
  heap budget, and anything on the homeserver.

### 5.2 Main-thread time per tick

Three lanes (2 workers + the participating main thread) are approximated by the measured pool 3.
Serial composition, 30x9 combat, bench machine (UOPT §2 CPU; JFR shares for the stream split;
round trip from **[P]**): capture 30.6 ms = round trip 9.4 + freeze/seal/stage/commit 21.2; stream
36.6 ms = fold reductions 21.9 + fold commits 7.7 + prepare/encode rest 7.0; validation 3.3;
evaluation 0.9 (UOPT's receipt mix); total 71.4 ms.

| part | serial (ms) | default pool, 2 workers + main (ms) | how |
| --- | --- | --- | --- |
| freeze/seal/stage/commit | 21.2 | 21.2 | main only (§4.7) |
| retention round trips | 9.4 | ≈0.5 | overlapped with the remaining END loop on 2 workers; barrier tail |
| fold reductions | 21.9 | 8.4 | ÷2.62 **[P]** |
| fold commits | 7.7 | 7.7 (3.5 with §4.5 seed round trips in encode tasks) | main |
| prepare/encode rest | 7.0 | 7.0 (conservative; WIRE encode pipelined) | main |
| validation rows | 3.3 | 1.7 | ÷2.4 (assumed) + prologue |
| evaluation | 0.9 | 0.4 | ÷2.4 (assumed) |
| **anim-sync main thread** | **71.4** | **≈47 (≈43)** | −34 % (−40 %) |
| + r10 body pose (not anim-sync) | 26.6 | 26.6 | main |
| **tick** | **98.0** | **≈73** | |

4 workers + main buys ≈1 ms more (fold 2.93× **[P]**): the main-only parts (≈36 ms) dominate.
With every receipt exact at 20 shots/s (after FX-TRUST), serial grows to ≈77 ms and the pool result
to ≈49 ms.

**Homeserver** (i7-4710MQ, Haswell 2.5 GHz, 4C/8T, load ≈2): single-thread costs ×1.5–1.8; lane
speedup assumed 2.0–2.4 instead of 2.62 (fewer free cores, hyper-threads). Main-only 36.4 ms →
55–66 ms; parallel parts 26.1 ms → 39–47 ms serial → 16–23 ms. **Anim-sync main thread ≈71–89 ms
(serial 107–129); with the r10 pose ≈111–137 ms per tick.** The 50 ms tick is not reached at 30x9.

**30x29** (requires WIRE's lifted 256 KiB cap): folds and records scale with pairs (×29/9 = 3.22).
Bench serial ≈ 30.6 + 70.6 + 24.8 + 22.5 + 19 (29-row exact receipt) + 10.7 (29 victims) ≈ 178 ms;
default pool ≈ 69 (main only) + 26.9 + 7.9 + 4.5 ≈ 108 ms; homeserver (same factors) ≈ 166–214 ms. Out of reach
without changing the fold design (ADR4 owner decision) and moving the per-observer commits.

## 6. Verification plan

### 6.1 Serial-vs-parallel differential (merge gate for UPAR-2)

- **Bench differential**: `AnimSyncBench` gains `-PbenchWorkers=0,1,2,4`; with a fixed injected clock
  (`AnimSync.clock`, replacing the raw `System.nanoTime()` reads that feed `needsTimingSeed`,
  `delivered`, seed `issuedNs`, D) and a fixed probe schedule, every run of a cohort must produce
  the same per-tick digest of: every sent transaction's bytes (per observer, in order), every
  retained checkpoint digest, every issued/folded seed digest, every verdict and victim recipe, every
  evaluated `Body` (bones, root, 48 corners as raw bits), the counters map (order included), memo
  states (`resumes` and retained bucket per pair) and the `STAMPED` key set. Cohorts 2, 10, 30x9 and
  30x29 (WIRE lifted cap), idle and combat, ≥300 ticks.
- **Mixed-renderer equality**: the U10 report run (`just test`, `dev/anim_sync_perf_gate.py base after`)
  with workers 0 and 2 on the same commit must be identical (UOPT §4 method: fragments, 6,248 MAIN
  samples, native/server digests); GameTests pass with workers 0 and 2.
- **Failure injection**: forced reducer exception for fold k, forced retain failure for player k,
  forced resolver failure in row k of a receipt, forced evaluation failure for victim k, a forced
  `Error` in a task: end states, counters and thrown classes equal to serial.
- **Unit tests** for `AnimSyncWorkers`: outcome order, exception identity, W=0 inline, rejected
  submission fallback, interrupt during wait, no nesting.

### 6.2 Race stress

Repeat the bench differential 50× per cohort with the pool size drawn uniformly from 1..N per tick
(N = available processors) and random `Thread.yield`/park jitter injected at task start (test-only
hook); run once with `-XX:+UseParallelGC` and once with a 256 MB heap (GC pressure). Any digest
difference fails. Add the U10 `verifyMemo` mode with workers on.

### 6.3 Benchmark protocol

Under the heavy lock, bench machine, quiet as possible, 3 runs each, report mean/p50/p99:
`./gradlew --no-daemon --max-workers=2 :server:oracleTest -PanimSyncBench=30:9,30 -PbenchTicks=300
-PbenchWorkers=0,2,4`. Per cohort: **main-thread CPU ms/tick and main-thread wall ms/tick** (the
tick budget is wall: main waits inside forks), **total process CPU/tick** (all threads; parallel
overhead), fork count/wait, efficiency, allocation per thread, heap after full GC (budget §4.10).
Projection to the homeserver: multiply single-thread costs by 1.5–1.8 (Haswell 2.5 GHz vs Zen3
mobile) and cap lane speedup at the measured 2-lane/3-lane efficiency degraded by the host's load
(≈2 busy threads of 8); confirm with a 30-bot staging run on the homeserver before release (owner
approval needed for any stand).

### 6.4 Worker reachability guard

In the bench and oracle JVMs, a JFR `ExecutionSample` scan of `blockfield-anim-sync-*` threads fails
the run if any frame is in `net.minecraft.server.level.`, `net.minecraft.world.level.Level`,
`net.minecraft.world.entity.` (except value records), `com.tacz.`, `org.luaj.`, `io.netty.channel.`,
`com.alrex.parcool.client.` or `me.xjqsh.lrtactical.` outside `PlayerAnimatorIntegration$Frozen…/
$ForcedFade…`; plus a debug assertion in `AnimSyncWorkers` tasks that `ServerBodyPoses.state`,
`AnimSync.count` and `SnapshotStream.observer` are called on the server thread.

UPAR-3 adds a stricter **capture-stack rule** on top of that reviewed global rule.
A worker sample containing `ServerBodyPoses.compute` or `AnimSyncCapture.capture`
(the per-player capture task body) must have no frame from `com.alrex.parcool.`,
`com.tacz.`, `net.minecraft.world.entity.` or `net.minecraft.server.level.`, including
value-record methods. Class names are mapped to named before either scan. The global
rule still permits native common ParCool scratch geometry in the victim evaluator
(`AnimSyncParCoolEval` → `ParCoolFrame.scratchAttachment` → fresh `Parkourability`),
which accesses no live player attachment. `AnimSyncParallelProof.reachabilitySelfTest`
runs before every proof session: an eval scratch stack passes, each capture entry alone
passes, and adding a ParCool frame to either capture entry is rejected.

## 7. UPAR-2 slicing, ownership and order

| Unit | Files | After | Notes |
| --- | --- | --- | --- |
| **UPAR-2 (server fork-join)** | new `S/combat/AnimSyncWorkers`; `S/combat/AnimSyncStream.fold` (inputs/commit split, plan + lookup); `S/combat/AnimSyncValidation.replay/victim` (row prologue/task/epilogue), `memo` copy; `K/combat/ObserverReducer.Memo` (copy); `S/combat/AnimSyncCapture.commit/retain/staged`, `S/combat/ServerBodyPoses.endTick` (barrier); `S/combat/CombatViews.exact` + `Stamp` (planned outcomes), `S/combat/AnimSyncEval.body` (Dependencies from outcomes); `S/policy/AdminSettings` (knob); bench + tests | UOPT integrated into phase23 | parallel with WIRE-CODEC (no shared files); conflicts with WIRE-SERVER only in different methods of `AnimSyncStream`/`AnimSyncValidation` — land first, WIRE-SERVER rebases |
| **WIRE-SERVER (+encode on pool)** | as ADR4.2 §10, plus §4.5: `SharedEncodings`, encode/fragment/CRC tasks on `AnimSyncWorkers`, pipelined `SnapshotStream.exact` | WIRE-CODEC, UPAR-2 | replaces ADR4.2 §7.5's "per-observer serial sender" contingency; `AnimationSchema` `SPLICES` → explicit map is a WIRE-CODEC hook (owner of `AnimationSchema`) |
| **WIRE-CLIENT (+ordered off-thread decode)** | ADR4.2 §10 files; decode placement §4.8 | WIRE-CODEC | coordinate with FX-CLIENT-3 (journal/buffer intake) |
| **UPAR-2C (client steps, optional)** | `C/combat/ClientAnimationJournal.frame/step`, client knob | FX-CLIENT-3, WIRE-CLIENT | small; only if client frame spikes matter in owner A/B |
| UPAR-3 (later) | immutable per-generation catalog/store snapshots; fold overlap with the world tick; split freeze | UPAR-2 evidence | needs its own audit |

Proposed order: UOPT → UPAR-2 ∥ WIRE-CODEC → WIRE-SERVER → WIRE-CLIENT ∥ FX-CLIENT-3 → UPAR-2C.
One branch/PR per unit into `fulla/phase23` (AGENTS: one task, one PR).

## 8. Risks

**2026-10-19 failure-rule correction:** an escaping Error is permitted to have speculative
end-state differences only after the coordinator's no-continue and crash-shutdown isolation
audits both pass. They have not both passed here. Retention uses per-player joins (§4.3),
so all native work/counters/state before and after the Error retain serial ordering; no reliance
on fatal shutdown hides a mismatch. Caught exceptions keep exact equality in every stage.
The performance loss versus deferred retention must be included in §6.3 measurements.


- **Not enough**: projected 30x9 homeserver anim-sync main-thread time stays above the tick budget;
  the owner needs the §5.2 numbers to decide between more threads (contended host), UPAR-3 overlap,
  or an ADR4 fold change (observer-independent fold seeds / longer fold age, a wire/manifest change).
- **Host contention**: the homeserver also runs Velocity, Workshop and the site; lanes can be
  descheduled, making a fork wait longer than the serial work. Mitigation: knob, efficiency counter,
  default 2.
- **Hidden shared state** in a call path the audit read only at source level (reflection into PA/LR,
  ParCool scratch). Mitigation: §6.4 guard, §6.1 differential under §6.2 stress; any new reach is a
  test failure, not a production race.
- **Reorder proofs** (§4.3, §4.5) depend on today's code; a later change that makes a capture read
  another player's commit, or `prepare` read `publish` state, breaks them silently. Mitigation: the
  differential gate stays in CI-equivalent `just test`; comments at the barrier name the proof.
- **Memory**: per-thread identity memos multiply retention; measured budget gate §6.3.
- **Diagnostics**: per-row/per-eval nanos become worker times; `captureNanos` no longer includes the
  round trip. Report both main and worker times.

## 9. Evidence and reproduction

- Read-only commands: `javap -p -c -cp <pinned jar> <class>` with the pinned JDK; jar SHA-256 above.
- Prototype: `docs/review/anim-sync-parallel-proto.patch` applies to `origin/fulla/as-uopt`
  `cc428a8e` (benchmark-only probes `AnimSyncStream.FOLD_PROBE`/`AnimSyncCapture.RETAIN_PROBE`, null
  in production; `AnimSyncParallelProto`; the `benchParallel` property). Raw results:
  `docs/review/anim-sync-parallel-proto.json`. Reproduce under the heavy lock:
  `./gradlew --no-daemon --max-workers=2 :server:oracleTest -PanimSyncBench=30:9 -PbenchTicks=200
  -PbenchWarmup=120 -PbenchParallel=0,1,2,3,4` (≈6 min), then read
  `server/build/anim-sync-bench/server.json` → `cohorts.*.parallelPrototype`.
- No production code, stand, server, client, release or deploy was touched.

## 10. UPAR-2 checkpoint: shared pool and per-observer folds (2026-10-19)

**Partial implementation, not integrated and not an UPAR-2 gate pass.** Branch
`fulla/as-upar2-cx`, worktree `WS/.dev/worktrees/as-upar2-cx`, base `26d7d160` (UOPT-2c,
WIRE-CODEC and WIRE-CLIENT integrated). `origin/fulla/as-upar` was already an ancestor; its
three review files had disappeared in the subsequent study merge, so this branch restores
those exact files before adding this checkpoint. The cancelled `as-upar2b` worktree/branch
is untouched. No schema, wire codec, fork, tag, release, deployment or live stand changed.

### 10.1 Implemented API for WIRE-SERVER

`AnimSyncWorkers` is the one server-owned bounded pool. `SERVER_STARTING` creates it;
`START_SERVER_TICK` applies configuration changes between ticks; `SERVER_STOPPED` joins and
closes it. `AdminSettings.Netcode.animSyncWorkers` clamps 0..4, defaults to 2 and appears in
`applied.json`. `-Dblockfield.animSync.workers=N` overrides it (invalid values fail). The old
four-argument `Netcode` constructor still means the default two workers.

- `AnimSyncWorkers.shared().run(n, i -> pureResult)` returns indexed `Outcome<R>` values.
  W named daemon workers plus main claim indices dynamically; W=0 runs inline. Tasks cannot
  nest submissions/forks. Every task runs once even when an earlier outcome failed.
- `shared().submit(() -> pureResult)` returns a `Job<R>` for same-phase pipelines. The queue
  holds at most W jobs. Full/stopping pools run the submission on main; no work is dropped.
  `job.outcome()` joins uninterruptibly and restores interrupt status, claiming an unstarted
  queued job on main. `job.get()` also consumes its outcome. Call it at the original serial
  exception point, inside the original catches, and keep subsequent visible effects on main.
- `Outcome.get()` throws the exact original Throwable object (including Error). Outcomes
  carry `nanos`, `cpuNanos` (-1 when unavailable), and `worker` for separate lane accounting.
  `close()` joins outstanding work without consuming its exceptions, then shuts down with a
  10-second executor termination deadline. Jobs must remain bounded pure computations; the
  join deliberately adds no cancellation/timeout failure to the serial computation.
- Inputs must be immutable/prepared on main. Workers may not read live entities or shared
  mutable ledgers, count, log, send, mint IDs or access shared store pin/release without the
  UOPT-2 monitor rule. `submit` permits main-only preparation to overlap a task **only** when
  the caller has proved that preparation cannot write anything the task reads. `run` parks
  all such writers at its barrier.

WIRE-SERVER should use this shared instance, never create a second pool. Its wire inputs and
`AnimationSchema.SharedEncodings` must be prepared before submission; send/D/freeze/publish
stay at their serial points. This is an API checkpoint for coordination, not authorization
to integrate unfinished UPAR-2 work.

### 10.2 Fold checkpoint and proof boundary

`AnimSyncStream.prepare` currently plans **one observer at a time**. The final §4.2
all-observer plan before the observer loop remains outstanding. This narrower batching
already runs each observer's eligible folds concurrently without changing other observers'
program points. Planning reads only existing incarnations/target histories/reports, uses
`peekMemo` (no intake creation/pruning), and copies each memo. Each task receives frozen
seed/activation/checkpoints/applications and generation assets/policy. Catalog first-person
refs are populated on main before the barrier. The existing `ObserverReducer.Memo.copy()`
from UOPT-2 is reused, without a second memo/schema.

At the original `fold` call, main recomputes preconditions and verifies seed/context and
checkpoint identities, application event identity/proof equality, activation, bucket,
asset/policy identities and the memo source. A stale plan reduces inline. A usable plan's
private memo is installed and its outcome consumed inside the existing reducer catch;
ID minting, successor encoding/round trip, store installation, lease and counters stay in
serial target order. Bounds failures are counted only at that original call. No worker
resolver/store lease has been introduced. `foldPlanMainNanos`, `foldForkWallNanos`,
`foldTaskMainNanos/CpuNanos` and `foldWorkerNanos/CpuNanos` are report-only counters; they are
not an efficiency/memory benchmark and there is no performance ceiling.

### 10.3 Checkpoint verification

Pinned JDK Temurin 21.0.12+8, heavy lock held, `--no-daemon --max-workers=2`; workspace
`just status` and memory preflights performed. The successful iteration ran:

```sh
./gradlew --no-daemon --max-workers=2 spotlessApply :server:test \
  --tests '*AnimSyncWorkersTest' --tests '*NetcodeSettingsTest' --tests '*AnimSyncFoldTest' \
  :server:oracleTest -PoracleOnly=AnimSyncWorkersOracleFixture
```

- 20 JUnit cases passed (pool 10, settings 5, folds 5), zero failures/errors/skips.
  Pool tests cover W=0/1/2/4, exactly-once indexed outcomes, original RuntimeException/Error,
  no nesting, bounded-queue/stopped-pool fallback, interrupted join and shutdown ownership.
  Fold tests compare the full reduced result against serial replay for W=1/2/4, prove the
  live memo is unchanged by speculation, commit the private memo, reject a stale memo plan,
  surface an injected Error at the serial fold call and retain the existing exact successor
  receipt test. No fallback is credited as equality.
- All 13 in-process GameTests passed as the existing `:server:testClasses` dependency.
- The new transformed fixture is registered in the server session and exactly one existing
  shard (no timeout changes). `worker-native-scratch` passed: actual pinned PA checkpoint,
  nested live fades and LR native frozen/forced classes, raw scalar/transform comparisons
  and subsequent ticks, eight tasks for each W=0/1/2/4. The helper's native-vs-materialized
  assertions execute inside every task; this is not a comparison of two ports.
- `worker-schedule-stress` passed 50 deterministic random pool sizes 1..4, 127 indexed tasks
  each, yield jitter and original Error identity. This is scheduler stress, **not** the full
  §6.2 benchmark/GC-pressure race gate.
- Evidence: `WS/.dev/coord-20261011/upar2-cx-fold2.log`, JUnit XML under
  `server/build/test-results/test/`, transformed fragments under
  `server/build/anim-sync-oracle/fragments/`. Runtime/provenance and successful production
  transformations are recorded by the existing runner; config application is required.

### 10.4 Exact remaining work; do not integrate this partial family

1. Extend fold planning across the full observer loop (§4.2), preserve failure ordering and
   prove speculative misses at the delivery/prune boundary. Complete native/handle prewarm
   and assert `AnimationBuilder.staticThreshold == 8.0f` at the appropriate production start.
2. Retention round trips: per-player staged refs, async pure round trip, ordered failure/
   reseed tail and END barrier before expiry/stream (§4.3). Capture freeze/r10 pose stay main.
3. Validation rows: main prologue/epilogue, private memo continuation, ordered STAMPED writes,
   first-row failure, and resolver pin/release on the same store monitor (§4.4, UOPT-2 §10).
4. Victim planning/evaluation: frozen dependencies/outcomes, per-worker continuation/scratch,
   serial SBW-root path and call-point counters/exceptions (§4.6).
5. Full injected-clock bench digest differential W=0/1/2/4, 50-run workload race/GC stress,
   forbidden-package reachability/debug guards, all stage failure injections, U10 serial vs
   default comparisons, GameTests at both sizes (§6.1/6.2/6.4). The small new fixtures do not
   replace any of these gates. Wire/server/client schemas and oracle timeouts stay unchanged.
6. Measure §6.3 at 30x9 and 30x29 (three serial/default/4 runs, load, main wall/CPU, total CPU,
   fork wait/efficiency, allocation and after-GC memory ≤32 MB per two workers). No benchmark
   numbers are claimed by this checkpoint. Container quota/cpuset remains coordinator-owned.
7. Fetch/merge current phase23; run the full `just format`, `just check`, `just test`,
   `just java-check`; repeat merge/gates if the integration push races. Only then push HEAD to
   `fulla/phase23`. The branch/worktree stay in place for continuation.

### 10.5 Follow-up full-server check and file inventory

`just check` passed. The full `:server:test` run completed 1,766 cases: 1,765 passed,
one failure, zero errors/skips. The untouched
`server/src/test/java/com/lunares/blockfield/server/combat/SnapshotStreamTest.java:138`
(`observerBytesIncludeEncodedHeadersAndAreRemovedOnDisconnect`) still asserts a fixed
9-byte empty header; it received 10. The same file's earlier empty-header test was already
hardened in `aff39ca1`, but this sibling assertion was not. `Observer.epoch` uses the static
incrementing `nextStream` and its canonical varlong gains a byte at 128; extra observer-creating
fold cases expose the remaining suite-order sensitivity. There is no diff to that file against
`26d7d160`. This is an open item for its U7 owner; the byte-size guard must be derived from
the actual epoch, not removed. No assertion or production path was weakened here.

Raw failure: `WS/.dev/coord-20261011/upar2-cx-checkpoint.log` and the preserved
`upar2-cx-snapshot-failure.xml` beside it. The separate follow-up command
`./gradlew --no-daemon --max-workers=2 :server:oracleTest :server:test --tests '*SnapshotStreamTest'`
passed: all 41 transformed server fixtures/fragments are `pass`, zero fail/unavailable;
all 12 SnapshotStream tests pass in isolation, confirming suite-order sensitivity. All 13
in-process GameTests passed again. The full server oracle includes the native flight workload
(`AnimSyncBench.nativeFixture`), which now uses the configured default pool via `startTick`;
its native/replay checks pass. This is additional checkpoint coverage, not the full §6.1
injected-clock/mixed-client differential. Raw log: `WS/.dev/coord-20261011/upar2-cx-oracles.log`.
Full `just test` / `just java-check` and 30x9/30x29 performance/memory runs remain unperformed.
No phase23 integration was attempted. Worktree is retained, with no task process left running.

Changed-file inventory (repository-relative; main source prefix is
`server/src/main/java/com/lunares/blockfield/server/`):

- `combat/AnimSyncWorkers.java` (new shared pool/API)
- `combat/ServerBodyPoses.java` (three lifecycle/between-tick hooks only)
- `policy/AdminSettings.java` (normalized worker knob)
- `combat/AnimSyncStream.java` (per-observer plan and fold inputs/task/ordered commit)
- `combat/AnimSyncValidation.java` (read-only memo lookup and ordered copy installation only)
- `server/src/test/java/com/lunares/blockfield/server/combat/AnimSyncWorkersTest.java`
- `server/src/test/java/com/lunares/blockfield/server/combat/AnimSyncFoldTest.java`
- `server/src/test/java/com/lunares/blockfield/server/policy/NetcodeSettingsTest.java`
- `server/src/oracleTest/java/com/lunares/blockfield/server/combat/oracle/AnimSyncWorkersOracleFixture.java`
- `server/src/oracleTest/java/com/lunares/blockfield/server/combat/oracle/ServerTransformedOracleSession.java`
  (append-only fixture entry)
- `dev/oracle-shards.json` (same fixture in one existing shard, same timeout)
- `docs/review/anim-sync-parallel.md` (restored contract plus checkpoint/evidence/handoff)
- `docs/review/anim-sync-parallel-proto.patch` (restored exact UPAR-1 artifact)
- `docs/review/anim-sync-parallel-proto.json` (restored exact UPAR-1 artifact)

## 11. UPAR-2 resumed implementation and retention Error conflict (2026-10-19)

This section supersedes the checkpoint's done/remaining list where noted. Coordinator explicitly
assigned the remaining SnapshotStream test assertion to this unit. It now uses
`8 + SnapshotWireMath.sizeOfUvarLong(second.epoch)` with the one-line suite-order comment;
every other assertion is unchanged, and no production static state is reset.

### 11.1 Additional implementation

- `acf4f744`: retention prototype. Live native freeze/seal remains on main; private immutable
  checkpoint round trips use the bounded shared pool. Per-player reference sets survive staging,
  and the END barrier precedes history expiry/stream publication. Main applies the ordered lease,
  commit, generation and RuntimeException missing/reseed tails. Diagnostic counter reports are
  sorted as §4.3 permits. Tests cover workers 0/1/2/4 and injected RuntimeException refusal.
  **The Error rule fails as demonstrated below; retention is not accepted.**
- `17f0b3a2`: validation row prologue/task/epilogue. Main freezes inputs, accepted/report additions
  and prospective stamped-history values. Workers reduce private continuation copies and resolve
  under the same store monitor as UOPT-2. Main installs memos and STAMPED entries in row order,
  stops at the first failure, and discards later speculative effects. The serial path retains its
  original ordering. Four new row cases plus the existing validation/memo cases pass.
- Victim evaluation: main prepares lazy dependency/metadata outcomes; two pure phases advance
  worker-private flight continuations and construct bodies. The stamp's weak-key cache retains
  outcomes for the same age/mode; counters and errors are consumed only when each victim is
  requested. A read-only `peekState` avoids minting lives for unrequested victims. SBW-root and
  cold-skin victims remain on the serial path; no victim is removed. Four new evaluation cases
  compare instant/flight parameter, root and world-bone bits at workers 1/2/4 and prove a later
  failure is precomputed once but counted only at its native consumption point.

The successful retention iteration: `upar2-cx-retain.log` (32 targeted cases and all 13 GameTests).
The successful row iteration: `upar2-cx-rows.log` (validation, memo, row and retention selections;
zero failures). The failure-order iteration: `upar2-cx-failure-order.log` (20 independent JUnit
cases pass; the new required retention Error-order case fails; two existing pool/native fixtures
pass and the new transformed Error-order fixture fails). Logs are under
`WS/.dev/coord-20261011/`. The subsequent full-server follow-up was cancelled while waiting
for the heavy lock: its Gradle child never started, its log is empty, and the owned waiter exited
143. No other session's process was signalled. The final read-only `peekState` planning lookup
and strengthened precomputation-count assertion have not been rerun after that cancellation;
the preceding evaluation implementation passed the 20 independent cases above. Final `just check`
passes (`upar2-cx-final-static.log`), as does `git diff --check`. The full four integration gates
remain incomplete.

### 11.2 Executed counterexample: §4.3 overlap versus §§4.1/6.1 Error ordering

A bounded pure round trip for the first of three players throws the same injected
`AssertionError` object. The serial path throws before entering the later players' capture.
The deferred path captures all three on main before consuming that first task's Error.

| Observed after catching the exact original Error | workers 0 | workers 2 |
| --- | --- | --- |
| `captures` delta | 1 | 3 |
| second player's canonical `State.tick` (transformed END loop) | 100 | 101 |
| third player's canonical `State.tick` (transformed END loop) | 100 | 101 |
| second/third target `sessionTick` (transformed END loop) | 100 / 100 | 101 / 101 |

The small JUnit fixture independently obtains `captures=1, sessionTick=10/10` versus
`captures=3, sessionTick=11/11`. This is a non-timing counter/value difference, not a stack-trace,
clock, map-order, performance or fallback discrepancy. Throwable class/message/**identity** match.

Executable evidence:

- `server/src/test/java/com/lunares/blockfield/server/combat/AnimSyncRetentionFailureOrderTest.java`
  asserts serial/parallel equality and fails. Preserved XML:
  `WS/.dev/coord-20261011/upar2-cx-retention-error.xml`.
- `AnimSyncBench.retentionAbortFixture` executes the actual production
  `ServerBodyPoses.startTick/endTick` on the pinned native pack in the transformed runner.
  `AnimSyncWorkersOracleFixture#retentionErrorsPreserveTheNativeSerialAbortBoundary` wraps it
  through `ServerTransformedOracleSession.runFixture`. The already registered fixture class stays
  in exactly one shard; no timeout changes. It requires successful Blockfield, ParCool and TaCZ
  mixin config application (419 successful transformation rows in this fragment).
- Native fragment `server.blockfield.worker-retention-error-order.json` is **fail**, never
  unavailable/pass; a preserved copy is
  `WS/.dev/coord-20261011/upar2-cx-retention-error-native.json`.
- Compiled `RetentionBatch.close` bytecode is preserved at
  `WS/.dev/coord-20261011/upar2-cx-retention-bytecode.txt`: all task outcomes are gathered before
  the ordered `complete` calls, whose catch table handles `RuntimeException` only.

Why the §4.3 commutation argument is insufficient: later native captures are not pure task
outcomes that can be discarded after the earlier Error. §3.7/§4.7 explicitly keep them on main
because they mutate live/native state and perform shared catalogue/ledger work. Reordering a
caught RuntimeException tail can commute in a completed tick; reordering across an uncaught
Error changes which later captures occur at all. The current barrier joins every task and
rethrows the exact Error, but cannot retroactively make those later native advances un-happen.

**Contract blocker:** this unit cannot certify both the prescribed retention overlap and the
unchanged serial abort/end-state rule. Per the brief's "if the contract is wrong, stop and report",
no gate was relaxed, no Error was swallowed/reclassified, and no integration was attempted.
Coordinator must provide a corrected §4.3 execution/failure rule before this part can complete:
ordered per-player joins preserve the serial Error boundary but relinquish overlap; retaining
overlap needs an explicit, audited rollback/error contract. The implementation agent has not
chosen or presumed either revision. The failing regression remains wired into the ordinary
server test/oracle gates so the conflict cannot disappear silently.

### 11.3 Still required after the contract correction

Global fold planning or measured justification for final per-observer batching; complete native
prewarm/staticThreshold assertion; injected-clock whole-workload serial/parallel digest gate;
50-run workload/GC stress; forbidden-package JFR/debug guard; full stage failure gates; 30x9 and
30x29 CPU/wall/memory measurements; client mixed-renderer differential; full `just format`,
`just check`, `just test`, `just java-check`; latest phase23 merge and verified integration push.
The passing unit/fixture selections above do not replace any of these requirements. WIRE-SERVER's
pool API remains on this unintegrated branch; codec/schema/wire-intake files remain untouched.

### 11.4 Files changed since the coordinator's 2a589794 checkpoint

- `server/src/main/java/com/lunares/blockfield/server/combat/AnimSync.java`
- `server/src/main/java/com/lunares/blockfield/server/combat/AnimSyncCapture.java`
- `server/src/main/java/com/lunares/blockfield/server/combat/AnimSyncEval.java`
- `server/src/main/java/com/lunares/blockfield/server/combat/AnimSyncValidation.java`
- `server/src/main/java/com/lunares/blockfield/server/combat/AnimSyncWorkers.java`
- `server/src/main/java/com/lunares/blockfield/server/combat/CombatViews.java`
- `server/src/main/java/com/lunares/blockfield/server/combat/ServerBodyPoses.java`
- `server/src/oracleTest/java/com/lunares/blockfield/server/combat/AnimSyncBench.java`
- `server/src/oracleTest/java/com/lunares/blockfield/server/combat/oracle/AnimSyncWorkersOracleFixture.java`
- `server/src/test/java/com/lunares/blockfield/server/combat/AnimSyncParallelEvalTest.java`
- `server/src/test/java/com/lunares/blockfield/server/combat/AnimSyncParallelRowsTest.java`
- `server/src/test/java/com/lunares/blockfield/server/combat/AnimSyncRetentionFailureOrderTest.java`
- `server/src/test/java/com/lunares/blockfield/server/combat/AnimSyncRetentionTest.java`
- `server/src/test/java/com/lunares/blockfield/server/combat/SnapshotStreamTest.java`
- `docs/review/anim-sync-parallel.md`

## 12. Coordinator failure-rule continuation (2026-10-19)

The §11 contract blocker is resolved by the coordinator's explicit conservative fallback.
Retention now joins at each original pure-function call; native Error fixtures keep the stronger
serial abort/end-state equality. No Throwable/Error catch is added to game flow. The limited
crash/save audit and its uncertified scope are recorded in §4.3/§8. Full verification and measured
retention cost remain required before integration.

Verification of the ordered-join correction: `upar2-cx-ordered-retain.log`, 16 JUnit tests
(retention 5, Error-boundary 3 at workers 1/2/4, validation rows 4, evaluation 4), all three
transformed worker fixtures and all 13 GameTests pass. This also reruns the final read-only
planning lookup and strengthened evaluation assertion from §11.1. No timeouts changed.

### 12.1 Semantic clock, prewarming and workload instrumentation

`AnimSync.now` isolates semantic times (seed issue/freeze/probes, causal boundary, accepted
weapon action and CombatViews intake) from real cost timers. The transformed proof supplies a
fixed tick-based clock and deterministic probe echoes. No production identity counter is reset.
PA's audited `staticThreshold` is asserted at pool construction; main prewarms PA/LR lazy handles
and all 16 first-person refs before native task batches. Pure main/worker lanes reject canonical
player-state access, counter writes, clock reads and observer creation. JFR scans worker samples,
mapping intermediary names back to named before applying the forbidden-package predicates.

`AnimSyncParallelOracleFixture` is registered in FIXTURES and exactly one unchanged-timeout shard.
It runs the real END capture, stream, validation and victim/flight evaluation on the transformed
pack, records actual packet/retained/seed/recipe bytes, raw body parameters/root/world matrices
and all 48 corners, per-tick non-timing counters, memo resume/bucket state and STAMPED keys.
`AnimSyncBench` now measures the production CombatViews victim-planning path (its previous
benchmark called AnimSyncEval directly, which bypassed that fork). Total process CPU is sampled
around timed server operations, excluding the emulated client reductions between them.

Iteration evidence: `upar2-cx-prewarm.log` passes 24 JUnit cases (workers 11, folds 5, rows 4,
evaluation 4), all three transformed worker fixtures and 13 GameTests. `upar2-cx-proof-smoke.log`
passes the 60-tick native 4x3 combat smoke with 225 worker samples / 7,031 frames / zero forbidden
frames. These are exercised smoke checks, not the remaining §6.1 matrix or §6.2 stress claim.

### 12.2 Strict byte comparison and remaining acceptance work

The fresh-process comparison was intentionally red: independent JVM collection salts reordered
otherwise identical snapshot definitions. The isolated-loader matrix shares the JVM salt while
starting a fresh Knot/production pack and fixture thread at workers 0/1/2/4. No mod/native/JDK
static field or production identity counter is reset. New per-cohort matrix processes have a
20-minute bound for four native sessions (existing client/server shard timeouts are unchanged).
Its serial/serial control then exposed residual LR/Create capsule timestamps: snapshots matched,
but real acceptance/seal clock reads differed. Those semantic reads in AnimSyncCapture now use
AnimSync.now; real cost counters still use System.nanoTime. The ParCool native callback's timestamp
is supplied as a fixture input by replacing the newly accepted instance record in the test target;
no production ParCool code or static state is changed.

`upar2-cx-clock-identity-final.log`: all 300 tick digests and all non-timing counters match serial
at workers 1/2/4 for the native 4x3 combat cohort. This includes actual capsule/snapshot bytes,
retained/seed/recipe bytes, raw body matrices/corners, memo resumes/buckets and STAMPED keys.
The old heap check counted prior isolated loaders and therefore was not a worker-footprint
measurement. The gate now obtains the same 32 MiB worker budget from two fresh benchmark JVMs
at 30x9/30x29; this replacement is pending execution. The native matrix is wired into ordinary
server oracleTest/check (and hence just test/java-check); oracleOnly remains an iteration subset.
The full eight-cohort matrix is not yet accepted or claimed to pass. The comparator's five
negative/control tests pass (single-tick/counter mismatch, unavailable fixture, unexercised worker,
and the fresh-JVM heap budget).

The first new cache regression fixture had a setup-only Mockito nested-stubbing error; corrected
`upar2-cx-identity-unit.log` passes all five evaluation tests plus 13 GameTests. It proves two rays
reuse an action's result and two value-equal but distinct captured actions get private caches.
Weak identity keys replace WeakHashMap<Stamp,...>: Stamp.hashCode walks the entire receipt graph,
so the old lookup repeated that walk for every victim/ray. No Stamp component/schema changed.

Still required: global planning (or final batching measurements), the other seven native matrix
cohorts, fresh-JVM memory gate execution, 50-run native workload race/GC stress, full failure
coverage, post-cache-fix three-run 300-tick benchmarks, mixed-renderer workers 0/2 comparison,
full four gates, phase23 merge and integration. The preliminary measurements below are diagnostic,
not a performance/equality acceptance or an integration claim.


## 13. Tick-wide fold continuation (2026-10-19)

The §10.2 per-observer checkpoint is superseded. The first prepare with a player/server context
(after the unchanged native flush) enumerates existing observer streams in player-list order and
existing subscription ids in sorted order. It flattens their eligible immutable fold inputs and
private memos into one shared-pool barrier. It never creates an observer/intake, mints an id or
publishes a seed. Each observer removes its private outcomes when prepare reaches that observer;
all original fold-input/memo checks still run at the original call. Later capsule delivery/pruning,
subscription changes or a fresh FULL can discard a plan without consuming its exception or memo.
An empty/non-server standalone prepare uses the existing per-observer barrier. The tick cache is
replaced on the next tick/server and cleared with the stream; consumed outcomes are removed.
WIRE-SERVER can keep calling prepare: no sender, packet, schema or wire-intake method changed.

Iteration evidence in `upar2-cx-global-matrix.log`: five fold JUnit cases and 13 GameTests pass.
The completed 300-tick native 2x1 idle/combat and 10x9 idle cases have identical raw tick digests
and non-timing counters at workers 0/1/2/4. The remaining matrix cases and memory tasks are still
running; this checkpoint does not claim the full matrix or integration.

The preceding `upar2-cx-memory-gate.log` passed the 4x3 combat 300-tick raw matrix and the
independent fresh-process 30x9/30x29 budget check. Default extra retained heap was 22.22/15.92 MiB,
below 32 MiB; that pair predates tick-wide batching. The isolated-loader process heap is not used
as a worker-footprint measurement. Profiles are preserved in
`WS/.dev/coord-20261011/upar2-cx-memory-profile-pre-global/`.


### 13.1 Failure effects and batch diagnostics

`upar2-cx-extended-smoke.log` passes 44 targeted JUnit cases (workers 12, folds 8, rows 7,
evaluation 9, retention 5, retention Error ordering 3), plus all 13 GameTests. New cases compare
second-row reducer/resolver RuntimeException and Error effects at workers 1/2/4 with serial:
original Error identity, first-row effects, per-life memo resumes, accepted/report/cache state and
non-timing counter deltas. Fold refusal keeps ids/memos/counters exact; later-victim Error is
consumed only at that victim's call and counts only requested evaluations. These complement the
pending transformed stage-failure matrix, rather than replacing it.

`AnimSyncWorkers.costs()` exports cumulative batch-only ticks/forks, fork wall, main task wall/CPU
and worker task CPU. `diagnostics()` derives forks, wall/wait and lane CPU per completed tick and
parallel efficiency = task CPU / ((workers+1) * fork wall). Wait is fork wall minus main task wall,
including join/scheduling/bookkeeping overhead. Tick ends are deduplicated (the benchmark commits
players separately); resizing starts a new pool accounting interval. Direct submit pipelines keep
their separate stage timings. CombatViews status includes these diagnostics without adding cost
fields to semantic counter/digest comparisons. WIRE-SERVER can consume the same public pool API.

The first full matrix attempt (`upar2-cx-global-matrix.log`) completed the four 2/10-player
idle/combat cohorts at 300 ticks and workers 0/1/2/4 with exact digests/counters. At 30x9 idle,
workers 1/2 also matched serial; workers 4 failed in Mockito/Byte Buddy with Java heap space after
four isolated native sessions accumulated in the 1 GiB harness. No unavailable/Error is counted
as an equality pass. This is a harness resource failure; it does not establish a production
memory failure or a capacity failure. The larger matrix and final budget gate remain required.


## 14. Acceptance continuation and integration (2026-10-19)

Branch `fulla/as-upar2-cl` (new worktree `WS/.dev/worktrees/as-upar2-cl`, from `5183925f`;
`as-upar2-cx`/`as-upar2b` untouched). The coordinator's WIP save `5183925f` was reviewed and kept
(stress/failure stage drivers, Audit store digests, worker allocation/scratch reporting).

### 14.1 Matrix harness: finished native sessions were never released

The 30x9 workers-4 heap failure was not workload size. Every isolated Knot session stayed strongly
reachable after it finished (heap after GC 254 → 409 → 564 → 718 MiB over four 2x1 sessions;
four `KnotClassLoader`s live). Heap dumps traced to GC roots (offline HPROF walk) found, in
order: session shutdown hooks in `ApplicationShutdownHooks` (context loader = Knot), log4j2 JMX
beans in the platform MBeanServer, JFR's lazily created threads/hook (inherited Knot loader and
session protection domains), Minecraft `Bootstrap`'s `LoggerPrintStream` in `System.out/err`,
the JVM Attach Listener (Mockito's self-attach logged through that stream into log4j
thread-locals), inline Mockito's interceptor map (handler → mocked pack class) and its
`DoNotMockEnforcer` type cache. `AnimSyncParallelMatrixMain` now starts JFR, JUL and Mockito's
agent before the first session, restores the standard streams/default handler, releases hooks,
beans and inherited thread contexts registered by a session, and clears Mockito's inline state.
No production code, pack input or game-visible JVM setting changed; the matrix JVMs gain only
`--add-opens java.base/java.lang` (to read `ApplicationShutdownHooks`). The only remaining path
is Kotlin reflection's `ClassValue` cache holding a `SoftReference`, which the JVM clears before
any OutOfMemoryError: after the fix heap after GC returns to ≈246 MiB between sessions and the
30x9 idle cohort passes at workers 0/1/2/4 in the unchanged 1 GiB harness.

A second, harness-only accumulation appeared under 384 MiB stress: the bench's per-world player,
connection, level and gun-item mocks were retained across repeats (stubbed answers capture their
`Sim`/world, stubbings reference the mock, so Mockito's weak-keyed map never releases them; old
gen 99.9 % with ≈10 full GCs/s after 13 repeats). `World.close()` now clears these mocks' inline
state; repeats then stay at ≈19 s with stable old-gen occupancy.

### 14.2 Gate wiring: bounded oracleTest tier and full acceptance gate

`dev/oracle-runner.gradle` registers every UPAR stage through one helper (fresh Knot session per
worker count, existing shard deadlines untouched; since SPEED-PAR the pooled stages of a tier run
concurrently within a memory budget and the memory/collector-variant stages run alone after them,
§14.7). The python gate
(`dev/anim_sync_parallel_gate.py matrix|memory|stress|failure`) rejects missing, unavailable,
unexercised or different evidence; its ten unit tests run in `just test`.

| tier | task | contents | measured wall |
| --- | --- | --- | --- |
| bounded (standalone; required local gate before any phase-3 release or anim-sync integration) | `oracleParallelGate` | matrix `4:3:true`, `10:9:true` at 300 ticks, workers 0/1/2/4; stress 50 runs × 60 ticks `4:3:true` (pool 1..min(4, CPUs) per tick + task-start jitter, `verifyMemo`); the five Error-injection stages (fold/retain/validate/resolve/evaluate, workers 0/1/2/4) | 18 min |
| full (explicit, run for this integration) | `oracleParallelFullGate` | all eight §6.1 cohorts at 300 ticks, workers 0/1/2/4; stress 50 runs × 300 ticks under the default collector, `-XX:+UseParallelGC` and a 384 MiB heap; fresh-JVM worker memory budget (30x9/30x29, 32 MiB) | see §14.3 |

The full-tier stages also exposed two wiring facts. 30x29 snapshots exceed the 256 KiB wire cap
already at workers 0 ("Snapshot capacity" in the serial session), so those two cohorts use the
existing measurement-only jar derivative (`dev/anim_sync_bench_jar.py`, 16 MiB) in every session,
as §6.1 specifies ("WIRE lifted cap"); no other cohort, fixture or distributable jar changes. Four
fresh 30x29 combat sessions took ≈26 min under load ≈5, so the full-tier matrix stage deadline is
45 min (bounded tier 20 min; existing oracle shard deadlines are untouched). UPAR stages run after
`:client:oracleTest` so they never load the client oracle JavaExec near its 5-minute deadline.

The ordinary shard run then exposed a fixture-isolation defect from this unit: `AnimSyncBench.install()`
replaces the production start bounds with a permissive stub, enables the exact transport and
installs a bench manifest, and never restores them. The two UPAR bench fixtures run before
`AnimSyncTrustOracleFixture` in shard 1, so two trust cases failed (any worker count; they pass
alone). `retentionAbortFixture` also cleared a requested `blockfield.animSync.workers` override, so
later fixtures of a `-PanimSyncWorkers=0` run silently used 2. Both fixtures now restore these
hooks; the reproducing order passes. Production code is unchanged by this fix, so the matrix,
stress, memory and benchmark evidence above stands.

### 14.3 Full acceptance results

All proofs compare raw per-tick SHA-256 digests (packet bytes per observer in order, retained
checkpoints and their bytes, seeds, recipes, body matrices and 48 corners as raw bits, memo
resumes/buckets, `STAMPED`, store definitions/pins) plus every non-timing counter with the serial
session of the same JVM; no fallback/unavailable result counts as equal.

| gate | result |
| --- | --- |
| §6.1 matrix, 300 ticks, workers 0/1/2/4 | all eight cohorts exact: `2:1`, `10:9`, `30:9`, `30:29`, idle and combat |
| §6.2 stress, `4:3:true`, 50 runs × 300 ticks, pool drawn 1..4 per tick (uniform: 3719/3774/3773/3734 ticks at 1/2/3/4 workers), task-start yield/park jitter, `verifyMemo` | default G1 1 GiB exact (111,532 worker JFR samples, 0 forbidden); ParallelGC exact (90,034 samples); 384 MiB heap exact (106,401 samples) |
| §6.1 failure injection (one AssertionError object for stage task key 1001 from tick 30 on, workers 0/1/2/4) | fold (Error at tick 42), retain, validate, resolve, evaluate (tick 31) all exact: same Error object, thrown on main, identical partial digests and counters |
| bounded-tier stress, 50 runs × 60 ticks | exact |
| fresh-JVM worker memory (§4.10; 120 warm-up + 40 combat ticks, both slots held) | default pool retains +18.78 MiB (30x29) / +18.79 MiB (30x9) over serial, below 32 MiB |
| §6.4 reachability | every session and every stress repeat scanned; zero forbidden frames |

### 14.4 Benchmark (§6.3), three runs, both Gradle slots held

Temurin 21.0.12+8, Ryzen 5 7535U (6C/12T), 120 warm-up + 300 measured combat ticks, A spread 2.4,
three flight ticks, measurement-only 16 MiB cap on both sides; runs interleaved r1..r3 × workers
0/2 under `gradle-slot --exclusive` (18:25–19:01). Load averages 1.7–7.0 (1 min), mostly the
benchmark JVM itself (≈2.4 cores during default-pool runs) plus a browser (≈0.25 core). Canonical
and legacy SHA, verdicts and every non-timing counter are identical for workers 0 and 2
(`30x9 1cb37cbc…`, `30x29 82ca1b95…`); flight failures 0. Means of the three runs (p50/p99 are
per-run percentiles averaged); raw JSON/load files: `WS/.dev/coord-20261011/upar2-cl-bench/`.

| cohort | workers | main CPU ms/tick mean / p50 / p99 | main wall ms/tick mean / p50 / p99 | process CPU ms/tick | worker lanes CPU ms/tick | heap after GC MiB |
| --- | --- | --- | --- | --- | --- | --- |
| 30x9 | 0 | 169.40 / 157.44 / 325.29 | 177.65 / 165.64 / 353.05 | 273.1 | 0 | 404.2 |
| 30x9 | 2 | 141.63 / 141.38 / 262.59 | 161.78 / 161.67 / 316.55 | 309.7 | 47.81 | 417.9 |
| 30x29 | 0 | 426.62 / 421.50 / 648.18 | 475.16 / 449.11 / 887.81 | 1212.4 | 0 | 584.0 |
| 30x29 | 2 | 312.10 / 307.21 / 449.31 | 416.86 / 377.73 / 760.63 | 1375.7 | 151.48 | 595.9 |

Default pool versus serial: main wall −8.9 % (30x9) / −12.3 % (30x29), main CPU −16.4 % / −26.8 %,
total process CPU +13.4 % / +13.5 %. Per-run main wall: serial 189.9/170.1/172.9 and
516.8/457.7/450.9; default 186.1/148.6/150.7 and 463.6/391.2/395.7 (the first pair ran while the
machine was still warming up). Batch diagnostics (`AnimSyncWorkers.costs()`): 9.4 / 9.5 forks per
tick, fork wall 38.1 / 154.9 ms per tick, main wait inside forks 3.9 / 5.0 ms per tick, parallel
efficiency 0.61 / 0.49. **Retention joins:** main waits 10.99 ms/tick in the per-player joins for
10.69 ms of round-trip work (30x9; 30x29 10.98 / 10.70), i.e. ≈0.3 ms/tick handoff over the serial
inline call and no overlap; deferred retention's projected ≈9 ms/tick saving (§5) is therefore
not realized. Main wall is the tick budget: the default pool does not bring 30x9 under 50 ms on
this mock workload (the r10 pose and native capture stay on main, §4.7), consistent with §0's
projection; this is a comparative bench, not a homeserver prediction.

### 14.5 Deviations, costs and open items

- **Gate placement (done in the phase-3 integration).** The bounded tier moved out of
  `:server:oracleTest` into the standalone `oracleParallelGate` (≈18 min measured): it no longer
  runs in `just test`/`just java-check`/CI, but stays a required local gate before any phase-3
  release or anim-sync integration (see DEVELOPMENT-REFERENCE §5). The complete acceptance set
  takes ≈3 h on this
  machine (eight cohorts ≈1.3 h, three 300-tick stress variants ≈1.75 h, memory ≈5 min) and cannot
  run twice per gate set or inside the CI quality job (`timeout-minutes: 30`; the bounded tier alone
  added ≈18 min to each of `just test` and `just java-check` before it moved out). `oracleParallelFullGate` is one task
  and ran for this integration. Moving it into `oracleTest` is a one-line change if the owner
  accepts that cost; raising or splitting the CI job is outside this unit.
- **Stress heap.** §6.2's 256 MiB heap cannot boot one native pack session (≈260 MiB live after
  GC); 384 MiB gives continuous GC pressure (G1 old gen ≈95 %, concurrent cycles throughout) and
  is the stress variant. Pool sizes are drawn from 1..min(4, CPUs): on a 2-vCPU CI runner the
  bounded tier draws 1..2.
- **Retention joins.** The coordinator's per-player ordered joins submit the round trip and join
  it at once, so the pool overlaps nothing there (≈0.3 ms/tick handoff, §14.4). Running
  the round trip inline would be byte-identical (it is the workers-0 path) and remove the handoff;
  not changed here because it would invalidate the evidence above without an owner request.
- **GameTests** load only the workshop/explosion test mod (the main resources are excluded from
  their classpath), so neither `ServerBodyPoses` nor the pool starts there; workers 0/2 cannot
  differ. They pass in `just java-check`.
- **WIRE-SERVER:** the §10.1 API is unchanged (`AnimSyncWorkers.shared().run/submit`, outcome
  timing, `costs()`/`diagnostics()`); prepare inputs on main, keep send/D/publish at serial points.

### 14.6 Integration gates

On `2321e7ca` (these §14 notes are the only later change), plain Gradle slot, Temurin
21.0.12+8: `just format` (no changes), `just check` pass; `just test` pass in 30 min 46 s (rig 50,
isolated rig 40, room-close policy 80 checks, client and server transformed oracles including the
bounded UPAR tier, U10 report: 162 fragments pass, 0 unavailable/fail); `just java-check` pass in
34 min 5 s (client 546 JUnit with the two existing opt-in skips, server 1,795 JUnit, 13 GameTests,
both oracles and the bounded UPAR tier again). Mixed-renderer workers 0 vs 2 (`-PanimSyncWorkers=0`
run vs the `just test` run, `dev/anim_sync_perf_gate.py`): identical — 162 fragments, 7,952 MAIN
samples bit-identical, native and server sample digests `30c1c420…80ac8` on both. phase23 was
still `26d7d160`, so the integration push is a fast-forward of this branch. Evidence:
`WS/.dev/coord-20261011/upar2-cl-*` (logs, `upar2-cl-evidence/`, `upar2-cl-bench/`,
`upar2-cl-u10/`). No stand, release, tag, deployment, fork, schema or wire file was touched.

### 14.7 Concurrent gate stages (SPEED-PAR, 2026-10-21)

Owner decision 2026-10-21: the gates may run their stage JVMs concurrently while they hold the
exclusive Gradle slot; peak memory may rise to what `MemAvailable` allows, never into swap; sample
counts, ticks, cohorts and assertions stay. Stages were serial only through a `dependsOn previous`
chain; their outputs were already disjoint.

- **Pool.** `ParallelGatePool` (`dev/oracle-runner.gradle`) is sized once, at the first pooled
  stage start: budget = `MemAvailable` − 2 GiB reserve. A stage starts while the measured
  footprints of the running stages plus its own fit the budget, at most four at once, smallest waiting
  footprint first; one stage always runs. A waiting stage holds its Gradle worker, so the gate runs
  with `--max-workers` ≥ its pooled stages (CI 5, full 10; logged warning otherwise): with
  `--max-workers=4` three waiting 2 GiB stages held every worker and the second stress shard never
  reached the pool beside the first. With uniform
  footprints this is N = clamp(⌊budget / footprint⌋, 1, 4). The budget, every start and the running
  total are logged. Each pooled stage waits in its own `<stage>Admit` task, so the stage's hard
  timeout counts only its own run (the first full-tier run waited inside the stage task and timed
  out seven matrix stages before they started). A stage's share returns on any finish of the stage
  (completion listener); after a pooled stage fails no further pooled stage starts. Stages that measure memory or a collector variant
  (heap-pressure and ParallelGC stress, the two fresh-JVM memory stages) run alone, in order, after
  every pooled stage of their tier. The gate script still runs after all stages.
- **Exclusive slot.** Every stage fails at once unless `BF_GRADLE_SLOT_EXCLUSIVE=1` (exported by
  the workspace `gradle-slot --exclusive`).
- **Stress shards.** The default-collector stress stage runs as two pooled stages of 25 contiguous
  runs (`blockfield.parallel.firstRun`; shard k skips the pool-schedule draws of runs 0..k−1, so
  the shards replay exactly the 50-run schedule). The gate checks the union as one session: same
  run count, schedule length, per-run digests/counters, reachability audits and fragments; shards
  must be `shard0..k` and contiguous from run 0. A difference names the shard dir (the stage).
- **Heap and footprint** (exclusive slot, `-Xlog:gc`, `VmHWM` sampled every 2 s): -Xmx = peak live
  heap + 30 %, not below the 384 MiB heap-pressure proof, not above the former 1 GiB. Footprint =
  peak RSS of the stage JVM; non-heap is 0.6–1.2 GiB (Knot sessions, JFR, GC structures), not the
  assumed 300 MiB.

| stage (CI tier) | live heap (max after GC) | -Xmx | peak RSS → footprint | wall alone |
| --- | --- | --- | --- | --- |
| matrix `4:3:true` | ≈740 MiB (mixed GCs, heap full) | 1 GiB (unchanged) | 1.97 GiB → 2000 MiB | 120 s |
| matrix `10:9:true` | ≈870 MiB | 1 GiB (unchanged) | 1.97 GiB → 2000 MiB | 275 s |
| failure (five Error stages) | ≈1020 MiB (at the cap) | 1 GiB (unchanged) | 2.22–2.35 GiB → 2400 MiB | 280 s |
| stress 50 × 60, unsharded | ≈410 MiB after full GC, ≤460 after young | 1 GiB → 640 MiB per shard | 1.4 GiB; shard 1.26–1.31 GiB → 1350 MiB | 377 s; shard ≈205 s alone, ≈260 s paired |

576 MiB shards still passed but hit G1 to-space exhaustion, so the shards use 640 MiB. The
full-tier pooled stages (eight matrix cohorts, default stress shards) reuse these footprints.

Wall time of `:server:oracleParallelGate`, exclusive slot, warm build, same machine (13.3 GiB RAM,
≈5–7 GiB `MemAvailable` before the build; Gradle's own daemon takes ≈2 GiB of it):

| run | budget at first stage | concurrency seen | wall |
| --- | --- | --- | --- |
| before (SPEED-FOLD `ef88217c`, serial chain, `--max-workers=2`) | — | 1 | 1079 s |
| after, `--max-workers=4`, another session's idle Gradle daemon alive | 1898 MiB | 1 (everything alone) | 1125 s |
| after, `--max-workers=4` | 2998 MiB | 1 (waiting stages held every worker) | 1149 s |
| after, `--max-workers=10` | 2754 MiB | 2 (the stress shards) | 962 s |
| after, `--max-workers=10`, admission tasks (final) | 4287 MiB | 2 at a time | 791 s |

`:server:oracleParallelFullGate` (final code, `--max-workers=10`, budget 4805 MiB): 7189 s
(2 h 0 min) against ≈3 h serial (§14.5), all checks green. Pooled part ≈50 min with up to three
stages at once (two matrix cohorts, or a matrix cohort beside both 25 × 300 stress shards); the
alone part ≈69 min (384 MiB stress 2159 s, ParallelGC stress 1821 s, memory 2 × 90 s). Full-tier
peak RSS stayed inside the footprints (matrix cohorts 1.78–1.92 GiB, shards ≤ 1.27 GiB);
`MemAvailable` never fell below 1.92 GiB.

Concurrent stages run ≈15–25 % slower than alone (shared CPU and memory bandwidth), and
`MemAvailable` fell to 1.68 GiB in the final run: Gradle's own daemon keeps growing after the
budget is taken. The gain is bounded by memory, not CPU: on this machine a 1 GiB stage needs ≈2 GiB, so only the two
stress shards fit beside each other. Two 1 GiB stages side by side need a budget of ≈4–4.8 GiB
(≈8 GiB `MemAvailable` before the build); the pool uses that automatically when it is there. Serial cost of sharding: one extra Knot boot (≈40 s) when the
shards cannot pair. Negative check (temporary, uncommitted switch corrupting one tick digest of run
2 in shard 1's random-pool session, shards running concurrently): the gate failed with
`…/ci-stress-default/shard1: stress digests differs from serial`.

### 14.8 Per-stage verdicts and resume (FULLGATE-RESUME)

The last full gate ran 3 h 55 min, was killed in its final stress stage and produced no verdict,
because the equality checks ran only in the final `doLast` after every stage. Every stage now runs
its covering checks through a group task depending on every member (matrix, sharded stress,
failure and memory groups run once, under a verdict-dir file lock) and writes
`build/anim-sync-parallel/verdicts/<stage>.json` (`stage`, `commit`, `inputsHash`, `result`,
checker tail, `finishedAt`) plus one `progress.md` line (time, stage, PASS/FAIL, duration,
stages done/total). A FAIL verdict fails the build at once. The final task passes only when every
stage of the tier holds a PASS verdict for this commit and lists them. A rerun skips (`onlyIf`)
stages whose verdict is PASS for the same commit and `inputsHash` (stage `blockfield.*` args, JVM
args, heap, gate-script hash; code identity rides the commit), with a `SKIPPED (PASS at …)` line;
`-PfullGateFresh=true` forces a clean run, a dirty worktree never skips. The build-start task creates the verdict directory and freezes skip decisions after hashing
runtime classpath files and pack JARs as well as stage arguments. Before any output is changed,
it invalidates every verdict in a group with a rerunning member. A successful JavaExec publishes
an atomic completion receipt last; group checks wait for every member task to succeed, then
verify its receipt, including retained receipts of skipped siblings. A separate atomic group receipt, published after verdicts
and progress writes, binds every PASS to its successful stage completion. Missing receipts,
legacy verdicts, interrupted stages and failed siblings cannot authorize a later all-skip green.
All marker, verdict and progress write errors fail loudly. Stage lists, arguments, timeouts and
assertions are unchanged.

## 15. UPAR-3 measurement: A10+A11 bench (2026-10-22)

## 15. UPAR-3 measurement: A10+A11 bench (2026-10-22)

Branch `fulla/upar3-u3-11` at `906e8344` (after = A10 fork + A11 fork + bench-fix
`a2144b39`) vs `630094e4` (before = U3-1…U3-4 train, no A10/A11 fork, no bench-fix,
emulated stream delivery). Spec §6.3 command, 300 measured + 120 warm-up combat ticks,
3 runs × workers 0/2/4, exclusive Gradle slot; medians below, raw JSON/load/md in
`WS/.dev/coord-20261011/u3-11-bench/` (`analyse.py` reproduces the table).

| cohort | w | main wall mean/p50 (ms) | main CPU mean (ms) | worker CPU (ms) | forks/tick | fork wall (ms) | eff |
| --- | --- | --- | --- | --- | --- | --- | --- |
| after 30x9 | 0 | 146.3 / 134.3 | 138.5 | 0 | 3.00 | 37.4 | 0.94 |
| after 30x9 | 2 | 112.3 / 107.9 | 93.4 | 63.5 | 12.28 | 48.0 | 0.66 |
| after 30x9 | 4 | 107.9 / 104.8 | 84.4 | 85.2 | 12.28 | 43.0 | 0.50 |
| after 30x29 | 0 | 405.2 / 367.2 | 336.5 | 0 | 3.00 | 97.5 | 0.81 |
| after 30x29 | 2 | 339.4 / 287.5 | 197.2 | 187.9 | 12.49 | 215.6 | 0.43 |
| after 30x29 | 4 | 322.3 / 258.8 | 172.8 | 256.1 | 12.49 | 198.0 | 0.32 |
| before 30x9 | 0 | 133.7 / 131.7 | 128.3 | 0 | 0 | — | — |
| before 30x9 | 2 | 121.6 / 122.7 | 106.1 | 35.2 | 9.36 | 29.6 | 0.59 |
| before 30x9 | 4 | 121.3 / 121.4 | 101.2 | 49.0 | 9.36 | 30.2 | 0.41 |
| before 30x29 | 0 | 308.0 / 296.1 | 278.7 | 0 | 0 | — | — |
| before 30x29 | 2 | 262.1 / 247.6 | 198.4 | 118.9 | 9.52 | 114.7 | 0.53 |
| before 30x29 | 4 | 265.3 / 248.4 | 182.9 | 161.4 | 9.52 | 116.9 | 0.35 |

Equality: canonical+legacy SHA stable across workers and repeats within each harness
(after `a0cf4e16` 30x9 / `ad9ad1a3` 30x29; before `007e3d8c` / `2eea76ab`); verdicts
stable per harness (the 2-receipt EXACT→REJECTED shift across harnesses comes from the
one-END retiming, below). Folds/tick 13.5 both sides. Barrier rate 0 in-bench: no
barrier/unavailable counters exist and captures are exactly 30×300 on both sides (no
joins/vehicles mid-tick); the stand (U11) must measure it.

Method caveat — the harnesses differ, so totals are not like-for-like. Bench-fix
`a2144b39` (after only): one END over all 30 players per tick (before: 30 one-player
ENDs, so no cross-player capture fork was possible) and one shared decoded checkpoint
per (target,tick) across viewers; the stream/encode stage exists as a measured
production fork only in after (before: emulated delivery, no `wireEncode*` counters).
Like-for-like: capture/tick 15.5→13.2 ms (after W0) vs 18.7→18.2 (before W0) — the gap
is mostly amortised per-END overhead (harness), not A10; fold totals equal (34 vs 36 ms
30x9 W2; 127 vs 134 30x29 W2 — UPAR-3 did not change folds); encode is new in after
only (≈27 ms 30x9 / ≈123 ms 30x29 total, worker:main ≈ 2:1).

30x29 wall "slowdown" is a bench artefact, not a production regression: main CPU is
equal-or-lower after (197 vs 198 W2; 173 vs 183 W4) while fork wall doubles (216/198 vs
115/117 ms) at lower efficiency (0.43/0.32 vs 0.53/0.35) — after measures the full
production encode per tick that before skipped, with +3 forks/tick (12.49 vs 9.52) and
main-as-lane running large encode tasks (tmain 93/66 vs 60/43 ms). Production always
paid the encode; A11 moves ≈2/3 of it to workers.

Production-equivalent (documented ≈6.3 µs/getter × 185 calls ≈ 1.17 ms/player ≈ 35 ms
stays on main; measured mock 6.2–7.3 µs confirms): 30x9 W2 main-CPU saving 106.1→93.4
(−12.7 ms mean; wall p50 −14.8 ms) is all real work, inside plan §5's projected 13–16
ms — and it absorbs +9 ms of newly-measured encode main work. Absolute homeserver
chain therefore stands: ≈30–43 ms (no B4) / ≈28–39 ms (B4) vs the 15 ms budget.
Recommendation: **B5 still needed** (spec 12–26 ms with B5 brackets the budget only at
the fast end); owner decides after the live 30-bot stand (U11), which must also
measure barrier rate and homeserver lane speedup.
