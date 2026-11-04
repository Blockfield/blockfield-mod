# ADR4.2 v3 wire: interned, fragmented exact animation stream (RakNet-only clients)

Status: WIRE-1c design amendment (v3) on the WIRE-1b measurements, for coordinator review. Not integrated, not release-authorized.
Production base: mod `origin/fulla/phase23` `9cf76d2a06e7d8e4d18501ec526f86ca4fe90667`, merged into
`fulla/as-wire` (merge `8a277631`). Harness `32770107`; evidence in `docs/review/wire-evidence-1b/`.
Normative parents: ADR4/ADR4.1 + errata, light-spec r9/r10/r11, owner decisions 2026-10-15/16, the
coordinator decisions on the WIRE-1 review, and the owner decision of 2026-10-18: **RakNet is the only
client transport; TCP clients are removed** (separate unit). v1 of this document is commit `97ac0bdd`,
v2 is `5b2b1e95`. No production source, server, client, stand, proxy or prod config was touched.

**v3 changes (review round 2, `reviews/p3-anim-sync-wire-amendment-review-2.md`).** Design only; the
measurements of §2–§3 are unchanged (no claim needed a new measurement).

- **P1-A** (§5.1–§5.3): per-observer table selection from READY, a `(value, mode, table)`-keyed
  shared-encoding cache, canonical-only digests and client table lifetime.
- **P1-B** (§0, §4, §10, §12): SERVER-CONFIG moves to itzg `ENV NETWORK_COMPRESSION_THRESHOLD=-1` in
  `blockfield-server` `distribution/Dockerfile.server`.
- **P1-C** (§7.6, §10): the mid-tick native and ParCool capsule paths join the v2 EVENT family. D is
  taken per recipient and the 256 KiB fallbacks go.
- **P1-D** (§8, §10): APPLIED fragments are sent inside `CombatAttackSender`'s monitor.
- **P2s** (§5, §7.4, §7.5, §12): the backlog signal observes the RakNet queue, with a Velocity
  heap budget, release tests, release ordering and docs.
- **Owner directive 2026-10-18** (§13): wire threading. Encoding runs as a fork-join stage on the shared
  pool (UPAR-1), enqueue stays on the main thread, client decode runs off the main thread, and the
  result is bit-identical to the serial path.

## 0. Decisions

| Item | Decision | Measured basis (30 players unless noted) |
|---|---|---|
| Ref interning | **Required.** Manifest-derived generation table, 0-tag handle, literal escape, table digest in `AnimationManifest` | raw −67…−71% in every cohort; RakNet bytes −62% mixed, −66% diverse, −43% all-rifle; byte-exact on all 1 769 transactions |
| Snapshot / event codec | **RAW** (interned) on the wire; Raknetify's continuing per-channel window is the only compression of the client link | §3.4: equal total CPU to the best ZLIB variant, zero compression CPU in the game JVM |
| Backend leg (game → Velocity, same-host TCP) | **Disable vanilla compression**: `ENV NETWORK_COMPRESSION_THRESHOLD=-1` in `blockfield-server` `distribution/Dockerfile.server` (itzg → `network-compression-threshold`; game, Workshop and candidate-test run this image; §12). Proxy `velocity.toml` needs no change | Today's 256 threshold makes RAW cost 5.0 ms/observer-tick (150 ms/tick at 30); off: 2.37 ms, all in Velocity |
| Fallback if the server owner keeps backend compression | ZLIB-6 per transaction (tag reserved, not built unless needed): 2.7 ms/observer-tick, 2.0 ms of it in the game JVM | §3.4 option C |
| Fragmentation | v2 envelope, fixed 24 KiB partition, contiguous per family, CRC32C | interned FULL/timing-seed/fold batches still 440–600 KB at 30 players (≤25 fragments) |
| Server thread | Encode once (drop prepare's size-check encode), per-target shared encodings, D immediately before the last fragment enqueue | serialize 5.42 ms → reuse 0.84 ms per observer |
| C2S | RAW only, channel 2, ≤11 fragments, ≤262 144 B, contiguous inside the adjacency block | tickets up to 25 018 B at 300 shots/s (2 fragments) |
| Uplink | Worst measured 114.7 Mbit/s (38% of 300; 120.5 with 5% transport allowance). Safe share 50% = 150 Mbit/s. **WIRE-DELTA not planned** | §3.8 |

## 1. Invariants (unchanged)

Byte transport changes only. Decoded U1 values (checkpoints, graphs, aliases, decisions, definitions,
seeds, events, reports, raw float/double bits, negative zero, nulls, ancestry) are identical to the
current schema; evaluation, R/A, start validation, native remote animation every frame, local player,
policy, admission and six-box geometry are unchanged. One logical END-tick commit per observer
(empty included), actual-send predecessor, atomic H, 40-tick history, 4096-record/node and depth-64
limits, complete self-contained tickets and every validator rule stay. Transport chunking never
truncates; a handle is never a client-authoritative clip, seed, victim or start.

## 2. Method (WIRE-1b)

The harness (`AnimSyncWireBench`, `WireMeasurements`) runs the pinned transformed native fixture JVM
offline: production capture, `AnimSyncStream.prepare` per target merged into one complete batch (cap
bypass, measurement only), the unchanged schema serializer, publish, and an emulated observer
reproducing `ClientAnimationJournal`'s reduction protocol. No packet is sent. Changes since WIRE-1:

- **Current base** `9cf76d2a`, including FX-TRUST's timing-evidence seeds.
- **Real JOIN timing.** No probes before the first FULL; one probe per END tick from FULL, echoed
  after 3 ticks (150 ms RTT). `needsTimingSeed` then re-issues every seed: the measured `TIMING_SEED`
  burst at FULL+3.
- **Emulation fix.** The observer now holds capsules while a newer seed is pending and ignores folds
  not naming its active seed (`ClientAnimationJournal.release`/`seed`). Without it the re-issue made
  the server reject applied reports and suppressed folds; with it: 0 rejections, one fold per target
  per 20 ticks (as WIRE-1).
- **Fixtures.** All 54 displays of the pinned TaCZ default pack registered as `HeldWeaponPose.reload`
  does (three PA clip sets), plus 32 synthetic profiles. *rifle*: everyone holds an AK-47. *mixed*:
  AK-47, Glock 17, Minigun (fixed hand), Desert Eagle, M870, MP5A5 interleaved by entity order, each with
  a different attachment set (0–5 attachments, fire mode, ammo, level), plus one LR bat actor (index 1,
  attack every 25 ticks) and one ParCool roller (index 3, 40-tick roll cycle). *diverse*: as mixed, but
  every gun holder has its own clip profile (all keyframe values nudged) so every clip, part and
  metadata digest differs. Idle = stationary; combat = 4.3 m/s circles plus N aggregate FIRE/s
  round-robin (one shot per gun per tick at most).
- **Production-scale identities** in every cohort except the `-small` control: server tick 51 840 000
  (30 days), entity ids from 24 000 000, connection/stream 18 437, subscription 4 211 903, life epoch
  2 538 771, observer frame 259 200, attack sequence 4 800.
- **Interning prototype**: a writer hook at `ResourceRef` record positions and a schema-guided
  expander back to canonical bytes (§5). Per transaction: canonical bytes equal the production codec
  (≤256 KiB) or the production codec rejects them; `expand(interned) == canonical`; the decoded batch
  `.equals` the original; the per-target splice equals canonical; every capsule round-trips.
- **Transport models.** Each observer's channel-3 messages in END-tick order (probe, capsules, snapshot
  fragments, 26-byte framing allowance): Raknetify model = one continuing `Deflater()` per channel with
  `SYNC_FLUSH` per message (bytecode in `wire-evidence/raknet-limits.javap.txt`); whole-transaction
  zlib-6 and zlib-1; vanilla per-packet zlib at threshold 256 (backend leg). Inflate round trips checked.
- **CPU**: thread CPU on the fixture JVM (AMD Ryzen 5 7535U, Temurin 21.0.12+8), phase23 reflective
  codec; UOPT's generated accessors are faster and not included.

Totals: 18 cohorts, 1 769 snapshot transactions, 32 592 byte/value equality assertions, 60 saved
canonical+interned blobs with digests (`manifest.json`). Every steady receipt had all N−1 victims; the
only `rowUnavailable` are the two warming frames per observer. Limits: no socket, no Velocity process,
no real RTT/jitter/loss, no MAIN-frame rendering, fan-out to N observers is arithmetic. TCP-client
columns in `summary.md` are historical comparators, out of scope after the 2026-10-18 decision.

## 3. Results

### 3.1 Interning

Table: 8 706 generation refs (765 clip, 765 metadata, 7 159 definition, 16 first-person, 1 config;
504 948 canonical bytes, handle ≤ 3 bytes). Steady cohorts used **zero** generation-scoped literals;
literals left are life-scoped (Equipment, Item, Attachment, life Config, Input, Seed).

| 30 players, steady bytes/tick | records | of which graphs | decisions | seeds | definitions |
|---|---:|---:|---:|---:|---:|
| mixed combat, canonical → interned | 688 387 → 199 071 | 478 307 → 70 563 | 131 966 → 59 443 | 58 084 → 14 395 | 11 171 → 11 171 |
| rifle combat | 719 310 → 204 214 | 502 965 → 73 800 | 137 645 → 61 234 | 60 822 → 14 858 | 11 491 → 11 491 |

Graphs shrink 85%; remaining bytes are placement/scalars, part-name strings (17 KB/tick), life-scoped
literals and per-tick InputRef/SeedRef. Interning does not remove fragmentation: at 20+ players FULL,
timing-seed, warm FULL and fold ticks stay above 256 KiB interned (440–600 KB at 30).

### 3.2 Per-observer steady S2C on RakNet

Raknetify stream model, KB/s decimal, probe and capsules included.

| Cohort | Raw canonical → interned | RakNet, no interning | **RakNet RAW interned** KB/s (Mbit/s) | RakNet ZLIB interned |
|---|---|---:|---:|---:|
| 10 rifle idle / combat | 4 510 → 1 376 / 5 065 → 1 510 | 121 / 210 | **58 (0.46) / 114 (0.91)** | 64 / 106 |
| 20 rifle idle / combat | 9 519 → 2 904 / 10 544 → 3 106 | 253 / 379 | **126 (1.01) / 216 (1.73)** | 97 / 176 |
| 30 rifle idle / combat | 14 529 → 4 433 / 15 968 → 4 691 | 383 / 541 | **192 (1.54) / 311 (2.49)** | 134 / 241 |
| 10 mixed idle / combat | 3 879 → 1 268 / 4 335 → 1 371 | 231 / 358 | **80 (0.64) / 138 (1.10)** | 96 / 150 |
| 20 mixed idle / combat | 8 887 → 2 795 / 9 859 → 2 989 | 477 / 694 | **182 (1.46) / 273 (2.18)** | 152 / 249 |
| 30 mixed idle / combat | 13 895 → 4 321 / 15 294 → 4 574 | 748 / 1 051 | **282 (2.25) / 401 (3.21)** | 209 / 343 |
| 30 diverse idle / combat | 13 896 → 4 321 / 15 295 → 4 575 | 1 000 / 1 389 | **337 (2.70) / 478 (3.82)** | 275 / 433 |

Mixed values average the gun-holder and LR observers (they differ by <4%).

### 3.3 Bursts (30 players, max over observers)

| Cohort | FULL cold | timing-seed re-issue (FULL+3) | warm FULL | steady max (fold) |
|---|---|---|---|---|
| mixed combat raw → interned → RakNet RAW | 1 153 → 439 → 36.8 KB | 1 663 → 517 → 39.6 KB | 1 976 → 563 KB | 1 997 → 583 → 57.5 KB |
| diverse combat | 1 154 → 440 → 44.2 KB | 1 663 → 517 → 47.3 KB | 1 977 → 563 KB | 1 997 → 583 → 66.7 KB |
| rifle combat | 1 190 → 451 → 28.6 KB | 1 717 → 531 → 30.6 KB | 2 043 → 578 KB | 2 063 → 599 → 47.4 KB |

A real JOIN therefore sends two FULL-sized transactions within 150 ms (≈ 76 KB on the link at 30 mixed).
Cheap optional reduction (FX-TRUST/U8, not required): complete one probe before the first FULL so its
seeds already carry evidence. Fold bursts coincide for all targets that were seeded together;
staggering fold age by `entityId mod 8` spreads them without changing the mean.

### 3.4 Codec on the RakNet-only pair: client stream + backend TCP leg

Raknetify on Velocity drops vanilla `SetCompression` on RakNet player connections once streaming
compression is active ("Preventing vanilla compression as streaming compression is enabled",
`wire-evidence-1b/raknetify-velocity-compression.javap.txt`). The client link is therefore compressed
exactly once. The backend leg is compressed by the game server for every packet ≥ 256 B
(`network-compression-threshold=256`) and inflated again by Velocity. CPU per observer per steady tick
(ms; Velocity inflate estimated from the measured inflate of the same bytes; ×30 = ms per server tick):

| Cohort | A: RAW, backend 256 (today) | **B: RAW, backend off** | C: ZLIB-6, backend 256 | D: ZLIB-6, backend off | RakNet RAW vs ZLIB KB/s |
|---|---|---|---|---|---|
| 30 rifle combat | game 2.23 + Velocity ≈0.27+2.08 = 4.58 (138) | game 0 + Velocity 2.08 = **2.08 (62)** | game 1.58+0.22 + Velocity ≈0.05+0.46 = 2.30 (69) | 1.58 + 0.46 = 2.04 (61) | 311 vs 241 |
| 30 mixed combat | 2.33 + ≈0.30+2.37 = 5.00 (150) | **2.37 (71)** | 1.75+0.29 + ≈0.05+0.62 = 2.71 (81) | 1.75 + 0.62 = 2.37 (71) | 401 vs 343 |
| 30 diverse combat | 2.61 + ≈0.36+2.83 = 5.80 (174) | **2.83 (85)** | 2.14+0.43 + ≈0.05+0.82 = 3.44 (103) | 2.14 + 0.82 = 2.96 (89) | 478 vs 433 |
| 30 mixed, 300 shots/s | 2.51 + ≈0.37+2.83 = 5.71 (171) | **2.83 (85)** | 1.85+0.32 + ≈0.05+0.88 = 3.10 (93) | 1.85 + 0.88 = 2.73 (82) | 448 vs 383 |

Choice: **B**. Its total equals the best ZLIB variant (D) within ±5%, puts zero compression in the
game JVM (C/D put 1.6–2.6 ms per observer there, 48–77 ms per tick at 30), needs no app codec, inflate
bounds or compression executor, and also stops the double compression of all other backend traffic.
It costs 10–30% more client-link bytes than ZLIB at 20–30 players (ZLIB on the continuing window finds
cross-tick matches in the compressed bytes); at 10 players the two are within ±20% either way. With today's threshold (A),
RAW costs 2× B: the threshold change is part of the decision, not an optional tweak. zlib-1 would
cut app deflate to 0.66 ms at +21% transaction bytes; it only matters for the fallback C.
Not measured: kernel cost of ~4.6 MB/s per observer (≈ 137 MB/s at 30) of uncompressed internal
traffic over the same-host Docker bridge.

### 3.5 Server-thread cost per observer (30 mixed combat, steady)

| Step | ms | ×30 per tick |
|---|---:|---:|
| `prepare` p50 / mean (mean includes fold reductions; includes its own size-check encode) | 7.9 / 72 | 237 / 2 162 |
| of which reference-closure walk | 2.0 | 60 |
| full batch encode, no reuse | 5.42 | 163 |
| reuse: per-observer splice + per-target rest (2.7 once per tick, /30) | 0.75 + 0.09 | 25 |
| SHA-256 / CRC32C of the transaction | 0.37 / 0.036 | 11 / 1.1 |
| capsule encode at 20 / 300 shots/s | 0.14 / 0.32 | 4 / 10 |

The prototype interning hook (reflective) costs about the same as canonical encode; a production hook
is one branch per record value. Today `prepare` encodes each batch to check its size and the send
encodes it again: dropping the size check is half of today's encode cost. Fold reductions and the
closure walk belong to UOPT (memoized there). Client: Raknetify inflate 0.30 ms, reflective decode
3.0–4.5 ms per 30-player tick.

### 3.6 Sustained fire (30 mixed combat)

| Aggregate FIRE/s | 20 | 100 | 200 | 300 |
|---|---:|---:|---:|---:|
| capsules per observer tick | 1.05 | 4.9 | 9.9 | 14.8 |
| capsule bytes canonical → interned per tick | 7 006 → 4 030 | 22 401 → 13 236 | 27 032 → 16 934 | 30 789 → 20 150 |
| RakNet RAW interned KB/s per observer | 401 | 442 | 442 | 448 |
| capsule completion after END tick at 5 Mbit/s, p50/p95/max ms | 2/20/44 | 3/26/45 | 3/29/47 | 3/28/48 |
| same at 10 Mbit/s | 1/1/2 | 1/2/3 | 1/3/3 | 1/3/4 |

The continuing window absorbs repeated capsules (+12% at 15× the fire rate). Head-of-line delay comes
from fold/FULL bursts, not from capsules. Diverse combat at 5 Mbit/s: p95 39, max 59 ms. At 2 Mbit/s a
30-player combat stream exceeds the link and the queue diverges: the per-client floor is ≈ 4 Mbit/s
(diverse 3.8 Mbit/s mean) and backlog recovery (§7) is required.

### 3.7 Production-scale ids and C2S

30 rifle combat, production vs small ids: U1 raw bytes identical apart from header varints
(fixed-width longs); RakNet RAW interned 311 vs 309 KB/s (+0.6%); ticket mean 15 061 vs 11 836 B (+27%),
max 15 550 vs 12 627 B. C2S at 30 players: tickets max 15.2–15.6 KB at 20 shots/s, 18.4 / 21.7 /
25.0 KB at 100/200/300 shots/s (2 fragments at 300); applied reports 12.4 KB/s raw per observer at
20 shots/s, 47.8 KB/s at 300. C2S records contain no `ResourceRef`: interning does not apply.

### 3.8 Uplink (Velocity → clients) against ≥ 300 Mbit/s

Fan-out arithmetic: N × mean per-observer steady RakNet RAW interned rate (not a 30-socket capture).
"+5%" is an allowance for RakNet/UDP/IP headers, ACKs and retransmission, not a measurement.

| Players | rifle idle / combat | mixed idle / combat | diverse idle / combat |
|---|---|---|---|
| 10 | 4.6 / 9.1 Mbit/s | 6.4 / 11.0 | — |
| 20 | 20.2 / 34.5 | 29.2 / 43.6 | — |
| 30 | 46.2 / 74.7 | 67.6 / 96.3 (32%) | 81.0 / **114.7 (38%; 120.5 with +5%)** |
| 30, mixed 100/200/300 FIRE/s | — | 106.0 / 106.1 / 107.5 (36%) | — |
| 30 without interning | 91.8 / 130.0 | 179.4 / 252.3 | 240.1 / 333.3 (111%) |

Safe share: **50% of 300 Mbit/s = 150 Mbit/s** for the exact stream; the rest covers chunks/entities
(not measured here) and home-hosted pack downloads (`docs/SPEEDTEST.md`: the pack is served only from
the home link). Worst measured cohort: 114.7 Mbit/s → margin 35 Mbit/s (29 with the allowance). An
unmeasured additive estimate for diverse loadouts at 300 FIRE/s is ≈ 126 Mbit/s (132 with allowance),
margin ≥ 18 Mbit/s. Interning is what makes this fit: without it the same cohorts need 252–333 Mbit/s.
WIRE-DELTA is not planned; revisit only if a live capture exceeds 150 Mbit/s or player counts above 30
are targeted (bytes scale ≈ N²).

## 4. Final codec per path

| Path | Logical content | Wire codec | Physical |
|---|---|---|---|
| S2C snapshot (channel 3) | interned U1 Batch + header | RAW | v2 fragments ≤ 24 576 B, contiguous |
| S2C event capsules (channel 3) | interned `AnimationEvent` list, unchanged per-target granularity and END-tick position | RAW | v2, normally 1 fragment |
| S2C probe | unchanged | unchanged | unchanged |
| C2S applied report / ticket (channel 2) | unchanged §3.4 layout | RAW only | 1 payload ≤ 24 576 B, else ≤ 11 fragments |
| Backend leg (game → Velocity) | all packets | vanilla compression **off** (image `ENV NETWORK_COMPRESSION_THRESHOLD=-1`, §12) | — |
| Client link | all channels | Raknetify streaming compression (unchanged) | RakNet MTU 1400 |

`codec:u8` stays in the header with RAW = 0; ZLIB = 1 is reserved and rejected unless the fallback C
is adopted.

## 5. Generation-ref interning (required)

**Interned values.** Only generation-scoped refs (`targetEpoch == 0`) present in the table named by the
transaction's `tableGeneration`. Life-scoped refs and any generation ref outside the table (an older
retained generation during activation, a runtime-registered native clip) stay literal: their exact
58-byte U1 record.

**Table derivation, both peers, no bytes on the wire.** For generation G the sorted, de-duplicated list of:

1. the definition refs reachable from the server manifest's named displays (`tacz.display:<id>`,
   `lr.display:<id>`): display definitions, clip refs, each clip's `MetadataRef` and part
   `DefinitionRef`s (pure functions of the clip digest), LR config refs, the 16 `FirstPersonRef` masks;
2. the named ids a capture mints: `tacz.gun:<gun>` for every gun-index id, `tacz.display:<id>` for
   every named display and `tacz:default`, `tacz.melee:<key>` for every display clip key starting with
   `melee`, `lr.melee-display:<id>` for every LR display;
3. the closed native carriers: `native.payload:<TYPE id>` per wrapped carrier,
   `native.batched`, `native.parcool.batch`, `native.parcool.batched`, `native.unwrapped`.

Table inputs are shared, versioned code (review 2 P2-5). The carrier list moves from the server-only
`AnimSyncNative.WRAPPED` class-name set (`:41-47`) into common `AnimationRefTable` as the payload
`type().id()` strings the capsule actually names (`AnimSyncNative.java:187`). The server derives
`WRAPPED` from that list, and `compat.wire` hashes it. The `tacz.gun:*` source is the manifest entry
list (above), never a client-side gun scan. WIRE-CODEC checks the gun-index enumeration against
FX-CLIENT-3's `HeldWeaponPose` diff before freezing the derivation version.

Order: U1 tag, `resourceGeneration`, `targetEpoch`, `contentId`, digest words. Handle = position.
`AnimationManifest` gains `wire.ref-table` (SHA-256 of the concatenated canonical ref records, a
resource entry compared by READY) and `compat.wire` (envelope + derivation version). The gun index
enters the manifest as `tacz.gun:<id>` entries (digest of the default display id) so item 2 uses only
compared data; a client-only extra display never enters the table. Cap 1 048 576 entries; a larger
generation sends literals only and counts `wire_table_capacity`. Measured: the prototype derivation
covered every generation ref the stream used.

**Encoding.** U1 tag 0 is unassigned. At a record position whose value is a table ref the writer emits
`uvarint 0, uvarint index`; anything else is the canonical record. The reader resolves tag 0 to the
table's immutable ref after checking `index < size` and that the entry's class is assignable to the
declared type; unknown tags and out-of-range handles are rejected. The hook is one branch in the
writer and reader of `AnimationSchema` on top of UOPT's rewrite. It is active only in the S2C wire
writer and in the S2C wire reader given a table (review 2 P2-6). C2S readers reject tag 0. Canonical
`write`/`encode`/`digest` never emit it. Only the batch wire writer lifts UOPT `write()`'s per-call
256 KiB `"animation payload"` count (`as-uopt AnimationSchema.java:453`); the canonical paths keep it.
A handle names the table of the transaction header (§5.1). The table depends on the generation the
observer confirmed, so two observers in one tick can receive different bytes for the same value.

**Equality proof.** Production decodes through the hooked reader. The prototype expander becomes the
WIRE-GATE differential: for every fixture batch, capsule and saved blob,
`expand(interned) == canonical bytes` and `decode(interned).equals(decode(canonical))`. At runtime
READY proves table agreement once per generation and CRC32C covers each decoded logical transaction.
A per-transaction canonical digest is not required: it would need a second encode or CRC combination
across shared encodings and adds no assurance beyond the table digest plus the gate.

### 5.1 Per-observer table selection (P1-A)

- **Rule.** For every S2C transaction to observer O, `tableGeneration = g` if
  `g = AnimationResourceGate.activated(O) > 0` and the server still holds the table of g. Otherwise it
  is `0`: a literal-only transaction with no tag-0 handle. A handle in a `tableGeneration = 0`
  transaction is a protocol defect.
  - Generation numbers start at 1 (`HeldWeaponPose.resourceEpoch++` before the first
    `AnimSyncResources.activate`, `HeldWeaponPose.java:826-830`), so 0 is never a real generation.
  - Refs of any other generation, including O's previous one, stay literal. Membership already
    requires the ref's own `resourceGeneration == tableGeneration`.
- **Confirmation needs no new bytes.**
  - The existing READY answer carries it. JOIN uses `AnimationReadyC2SPacket` in configuration. A
    play-phase announce uses `played`, which stores `READY.put(uuid, Ready(profile, generation))` and
    keeps only the newest answer (`AnimationResourceGate.java:185-198`).
  - `verdict` already compares every manifest resource entry, so the new `wire.ref-table` entry makes
    READY prove table agreement.
  - The client builds the immutable table of G and registers it (§9) **before** it sends READY(G).
    Every transaction the server stamps with G after it reads that READY therefore finds the table.
  - The server reads `activated(O)` once per transaction, on the thread that builds it (END tick:
    once per observer per tick; mid-tick native: at that recipient's encode). It writes the value
    into every fragment header of that transaction.
- **Generation overlap (up to `TIMEOUT_MS = 30 000`, `AnimationResourceGate.java:43`).**
  - After `announce(G)` an observer that has not answered stays on its previous table G-1. Its stream
    is still stamped with G identities (`AnimSyncStream.java:153-161, 571, 681`), so G refs travel
    literal to it until READY(G). This costs bytes but changes no value. It is counted as
    `wire_literal_refs` per observer.
  - A timeout or mismatch disconnects as today (`verdict`/`refuse`).
  - A target on G and observers on G-1 and G in one tick receive different bytes for the same value,
    and the shared cache keeps them apart (§5.2).
- **Retirement.**
  - *Server:* a table lives exactly as long as its manifest in `AnimSyncResources.MANIFESTS` and is
    dropped by the same `retire` pass (`AnimSyncResources.java:101-115`). An observer whose
    `activated` generation was retired gets `tableGeneration = 0`.
  - *Client:* tables are standalone immutable ref lists, independent of the asset views. Table g is
    dropped when (a) `ClientAnimationAssets` no longer retains g **and** (b) a complete transaction
    with `tableGeneration > g` has arrived on the ordered channel, so no in-flight g transaction
    can follow.
  - All tables and assemblies are cleared at every configuration start: first JOIN and every proxy
    backend switch, the point where `ClientAnimationResources` already calls `forget()` and
    `ClientAnimationAssets.clear()` (`ClientAnimationResources.java:72-78` on FX-CLIENT). Generation
    numbers restart per backend (game vs Workshop), so the same number can never resolve through a
    stale table.
  - A transaction naming a table the client does not hold is a protocol defect and goes through the
    r10 restart path.

### 5.2 Shared-encoding cache keyed by `(value, mode, table)` (P1-A)

UOPT today (`origin/fulla/as-uopt` cc428a8e, `AnimationSchema.java:449-503, 540-592`):

- A thread-local `Splices` holds `IdentityHashMap<Object, Spliced>`, where `Spliced = {type, bytes,
  depth, scope}`.
- `shareEncoding(scope, value)` registers values; `AnimSyncStream.java:796-809` registers the 11
  checkpoint components per tick.
- `write()` passes the splices to `writeValue`, and `writeShared` copies `bytes` when `type` and the
  depth bound match.
- `encode()` → `encodeBounded(write)` and `digest()` → `Digest.hash(encode(value))` go through the
  same `write()` and therefore the same splices.

Exact change (WIRE-CODEC, reviewed by the UOPT owner):

1. **Key.** A `Spliced` keeps one entry per mode key: `CANONICAL`, or the identity of an immutable
   `AnimationRefTable`. Each entry holds its own `type`, `depth` and `bytes`. The map stays
   `IdentityHashMap<Object, Spliced>`, and lookup selects the entry for the writer's current key. An
   interned write with no table (`tableGeneration = 0`) is written in canonical mode, so it shares
   the canonical entry: the bytes are identical by construction because the hook never fires.
   During overlap a value carries at most two interned entries (G-1, G).
2. **Writers.** `write(buf, value)` stays the canonical writer: key `CANONICAL`, no hook, 256 KiB
   count. New `writeWire(buf, value, table)` sets the thread's current key to `table` for that call
   only, in a try/finally. It enables the tag-0 hook and has no per-call 256 KiB count (§6's 64 MiB
   transaction bound applies). Nested writes inherit the current key.
3. **Digests and validation stay canonical.** `encode`, `encodeBounded`, `digest`,
   `rememberedReferences` and every validator call only `write`. They read and fill only
   `CANONICAL` entries, so interned bytes never reach a digest preimage, a seed/graph/presentation
   digest or a ticket check. The client never digests wire bytes: it decodes to values and digests
   those canonically.
4. **Scope/eviction** is unchanged (`SPLICE_SCOPES = 8`); a table's entries also die when the table
   is retired (identity key, no reuse).

### 5.3 Tests added to WIRE-CODEC

- **Two observers on G-1 and G in the same tick, sharing one target whose components are registered
  with `shareEncoding`.** Assert:
  - the G-1 transaction expands with the G-1 table to canonical bytes and contains no G handle;
  - the G transaction contains G handles;
  - the cache holds distinct entries for the two tables;
  - `digest(value)` equals `Digest.hash` of a fresh canonical encode both before and after the wire
    writes in that scope;
  - both decode `.equals` the logical batch.
- **Wrong-table cases.** A handle in a `tableGeneration = 0` transaction; a transaction naming a
  table the client dropped; a client cleared at configuration start receiving an old-backend table
  number. All three are rejected and handled by r10, never decoded to a different value.

## 6. Envelope v2 and fragmentation

```text
wireVersion:u8=2
family:u8                 # SNAPSHOT=0, EVENT=1, APPLIED=2, TICKET=3
codec:u8                  # RAW=0 (ZLIB=1 reserved, rejected)
tableGeneration:uvarlong  # §5.1: observer's READY generation, 0 = literal-only
connectionId:uvarlong, observerStreamEpoch:uvarlong, sceneGeneration:uvarlong
logicalSequence:uvarlong  # per (direction, family) transaction number
logicalTick:uvarlong, previousTickPlusOne:uvarlong
decodedLength:uvarint, crc32c:u32
fragmentIndex:uvarint, fragmentCount:uvarint
body:bytes                # fixed partition, <= 24576
```

No per-fragment codec digest and no SHA-256 (CRC32C: 0.036 ms vs 0.37 ms per observer tick). Header
≈ 100 B maximum. `fragmentCount = ceil(decodedLength/24576)`; every body is 24 576 bytes except the
last. All S2C families share RakNet channel 3 in order, so the receiver keeps at most one in-progress
transaction per family, requires indices 0..count-1 in order with identical headers, and treats a gap,
repeat or a new same-family transaction before completion as a protocol defect handled by the r10
restart path (never a permanent disable). Other families may interleave; JOIN or a connection change
discards assemblies. S2C bound: `decodedLength ≤ 64 MiB` (≈ 110× the largest measured interned
transaction; a failure bound, never truncation), with buffers grown per received fragment. U1
per-component limits stay; the aggregate 256 KiB cap and the capacity disable go.

## 7. Server thread and ordering (WIRE-SERVER)

1. **END tick, server thread.** Capture, `prepare` (without its size-check encode) and `publish` of the
   immutable logical batch, seeds and sent ledger stay as today. Then the probe, the tick's capsules
   and the snapshot are encoded once (interned with the observer's table, §5.1; shared encodings per
   §5.2; on the pool stage of §13) and enqueued by the main thread in that order. With RAW and backend compression off there is no compression in the game JVM.
2. **D and the freeze.** For each capsule packet and snapshot transaction, `D = System.nanoTime()`
   immediately before enqueuing the last fragment, after all earlier fragments; the ledger stores
   `(D, AnimationTiming.freeze(D, tick, probes))` before that enqueue, so no report can reference an
   entry without D. The error versus the true enqueue is one call (µs).
3. **No byte retention.** Encoded arrays are dropped after enqueue; history keeps decoded values.
4. **Backlog: an end-to-end signal that sees the RakNet queue (review 2 P2-1).**
   - *Why not unwritten bytes.* The game-side count measures only the same-host TCP leg to Velocity.
     The bottleneck is the RakNet queue in Velocity, and propagating its unwritability back to the
     backend read is unverified.
   - *Signal.* The server already sends one probe per END tick on channel 3, ahead of that tick's
     capsules and snapshot (`SnapshotStream.exact`). It records the send time in `anim.probes` (≤ 64
     entries, `AnimSyncStream.java:801-821`), and the client echoes on receipt. A probe queued behind a
     burst is echoed only after the burst has drained through Raknetify and RakNet. So
     `age = now − sent` of the **oldest unechoed probe** measures the whole path: game socket, kernel,
     Velocity, RakNet queue and link. It needs no Velocity change.
   - *Rule.*
     - **Fired:** oldest unechoed probe age > 2 s, or the game-side channel unwritable for 1 s. While
       fired, no new FULL/restart transaction is enqueued for that observer. Count
       `link_capacity_unavailable`.
     - **Suspended:** fired for 3 s continuously. The observer goes through the r10 restart path:
       nothing more is enqueued, and the client holds/snaps per r10 with rows honestly unavailable.
       It resumes with a FULL once the oldest unechoed probe is younger than 0.5 s.
     - Rows inside a sent transaction are never skipped. A missing echo counts as backlog
       (fail-safe).
   - *Velocity heap budget (`MEMORY=256m`, `Dockerfile.proxy:16`; heap and default direct memory).*
     Raknetify deflates before the RakNet queue, so queued bytes are compressed. One observer can grow
     its queue for at most 2 s + 3 s at its compressed steady rate before suspension stops new bytes.
     At the worst measured 478 KB/s that is ≤ 2.4 MB per observer, 72 MB with all 30 on slow links:
     under one third of 256 MB, leaving the rest for Velocity itself. WIRE-GATE verifies the bound
     (§10). If heap or direct memory exceeds 192 MB in that run, the thresholds are lowered before
     release; the 256m limit is not raised.
5. *ponytail:* enqueue stays on the server thread. Encoding moves to the shared pool (§13); the main
   thread keeps only enqueue and D.
   - The trigger for revisiting is the **whole END-tick animation share** on the main thread at 30
     players (capture + prepare + publish + wait for the encode stage + enqueue, review 2 P2-3), not
     the send share alone. It is measured with UOPT and UPAR in place. If it stays above 10 ms per
     tick, the next step is an off-thread enqueue.
   - That step requires one serial sender per observer that carries **every** S2C packet for that
     observer: probe, END-tick capsules, mid-tick native-riding capsules, unwrapped native packets and
     snapshots (review 2 P2-7). Otherwise families interleave differently from today.
   - 30-player deliverability is UOPT's: phase23 `prepare` is 237 / 2 162 ms per tick p50/mean at 30
     (§3.5), and WIRE-GATE's 30-player numbers count only after UOPT's land.
6. **Mid-tick native and ParCool capsule paths (P1-C).** Both paths join the v2 EVENT family:
   interned, fragmented when needed, encoded once per recipient. Their logical values are unchanged.
   - **`AnimSyncNative.deliver`** (`AnimSyncNative.java:143-162`) runs mid-tick on the server thread
     from the native broadcast hooks.
     - Today D is taken once, before the recipient loop (`:149`), and `delivered(..., now)` runs after
       `send` (`:154-159`).
     - In v3, per recipient in the existing `held` order: build the stamped list (`wrap`), encode it
       once with that recipient's `tableGeneration` (§5.1), and enqueue fragments `0..n-2`. Then take
       `D = System.nanoTime()`, store `delivered(observer, packet, D)` (ledger + freeze), and enqueue
       the last fragment.
     - The size-check encode and both `nativeUnwrapped:capacity` returns (`:213-219`) are removed.
     - The non-capacity `wrap` nulls (no observer, disabled, no incarnation, epoch mismatch) and the
       no-capsule case still send the native payload unchanged, as today.
   - **`AnimSyncParCool.wrap`** (`AnimSyncParCool.java:416-468`) is END-tick, called from
     `SnapshotStream.exact:423`.
     - The size-check encode and the `parcoolBatchUnwrapped:capacity` fallback (`:452-462`) are
       removed. A held batch with ParCool capsules for the observer is always wrapped and fragmented
       when large.
     - `exact` then applies rule 2: per capsule packet, D is taken and `delivered` stored immediately
       before its last fragment. Today it stores after `send` with a later `System.nanoTime()`
       (`SnapshotStream.java:426-429`).
   - **Client.** The carried native payload of a decoded EVENT transaction is dispatched through
     `C/combat/NativePayloads` unchanged, and only after the whole transaction validates (§9).

## 8. C2S rules (WIRE-REPORTS)

- APPLIED and TICKET keep their logical layout and validation, RAW only.
- One `blockfield:anim_wire_c2s` payload when ≤ 24 576 B, otherwise fragments (measured: 2 at
  300 FIRE/s). Register the id on RakNet channel 2 in `CombatChannels.install` beside
  `blockfield:combat_view`.
- **Every C2S transaction is one contiguous block under the `CombatAttackSender` monitor.**
  - Tickets: every fragment plus the attack are sent inside `CombatAttackSender.send`'s synchronized
    block (`CombatAttackSender.java:103-123`).
  - **APPLIED (P1-D):** today it is sent outside the monitor, from the main thread
    (`ClientCombatScene.java:603-604`; `:642` on FX-CLIENT). It moves to a new
    `CombatAttackSender.sendApplied(fragments, original)`. That method is `synchronized` on the same
    instance and sets the existing re-entrancy flag `sending`, so the inner `Connection.send` calls
    pass straight through. It sends all fragments back to back.
    - The per-`Connection` sender (`CombatViewSendMixin`'s `@Unique blockfield$combat`) is reached
      through a duck interface on that mixin.
    - The TaCZ thread's ticket+attack therefore waits for the APPLIED block or precedes it whole,
      never splitting it.
  - APPLIED still flushes before the attack that uses it.
- Server assembly in `CombatViews`: contiguous and in order; at most one in-flight transaction per
  family; `fragmentCount ≤ 11`; `decodedLength ≤ 262144`; identical headers. **No combat payload of
  any family (ticket, APPLIED, `combat_view`, the attack) may arrive between fragments of one C2S
  transaction**, nor between a ticket's last fragment and its attack. Any violation is `malformed` → `clearPending`
  (`CombatViews.java:346`), never reorder or hold. Fragment receipt never consumes a pending action.

## 9. Client rules (WIRE-CLIENT)

- **Intake.** Assemble per family, check CRC and lengths, and decode with the table named by
  `tableGeneration` (§5.1). Then pass one complete immutable transaction to the unchanged
  `ClientSnapshotBuffer`/`ClientAnimationJournal` entry points in arrival order. Assembly and decode
  run off the main thread (§13).
- **Atomicity.** Nothing (records, definitions, seeds, H, applied prefix, carried native payloads
  through `NativePayloads`) is published before the whole transaction validates.
- **Timing.** Capsule installation keeps ADR4 §9's zero-added-frames rule.
- **Tables.**
  - The client builds G's immutable ref list from the validated manifest. It registers that list
    with the connection's wire stage (§13) before sending READY(G).
  - Lifetime and clearing follow §5.1. Configuration start (JOIN or backend switch) drops every
    table and every partial assembly.

## 10. Implementation units (ownership slices of the one mod PR, plus one server-repo config change)

Manual gates (the coordinator checks each before the unit starts; "after X" means X is integrated
into `fulla/phase23`, not just pushed):

- **WIRE-CODEC** starts only after **UOPT** is integrated. It rebases onto UOPT's `AnimationSchema`,
  and the UOPT owner reviews the §5.2 cache change.
- **WIRE-SERVER** and **WIRE-REPORTS** start after **WIRE-CODEC**.
- **WIRE-CLIENT** starts only after **FX-CLIENT-3** *and* WIRE-CODEC. FX-CLIENT-3 touches
  `ClientAnimationJournal`, `ClientSnapshotBuffer`, `ClientCombatScene`, `ClientAnimationResources`,
  `ClientAnimationAssets`, `NativePayloads` and server `HeldWeaponPose`.
- The APPLIED part of **WIRE-REPORTS** (`ClientCombatScene`) is also sequenced after FX-CLIENT-3.
- The §13 pool comes from UPAR-1. Until UPAR integrates it, WIRE-SERVER runs the stage serially through
  the same interface, with identical bytes.
- **WIRE-GATE offline** runs after WIRE-SERVER, WIRE-CLIENT and WIRE-REPORTS are all integrated.
- **WIRE-GATE live** is the coordinator's U11 stand run, after SERVER-CONFIG is on that stand.

| # | Unit | Files (mod repo unless noted) | Order | Deliverable |
|---|---|---|---|---|
| 1 | **WIRE-CODEC** | `K/combat/AnimationSchema` (tag-0 hook, §5.2 `(value, mode, table)` splice key, `writeWire`), new `K/combat/AnimationRefTable` (derivation, common carrier list, §5), `K/combat/AnimationManifest` (`wire.ref-table`, `compat.wire`, `tacz.gun:*`), new `K/combat/AnimationWire` (envelope v2, fragmenter/assembler, CRC32C, bounds), new `N/AnimationWireS2CPacket`, `N/AnimationWireC2SPacket`, `NetworkHandler` | After UOPT (gate); U9 owner reviews manifest entries | Differential gate on fixtures and the 60 saved blobs; §5.3 two-table and wrong-table tests; canonical-digest invariance; malicious handles/tags/fragments; manifest mismatch refusal |
| 2a | **WIRE-SERVER** | `S/combat/AnimSyncStream` (drop size-check encode, interned shared encodings, D/freeze hand-off, per-observer `tableGeneration`), `S/combat/SnapshotStream.exact`, **`S/combat/AnimSyncNative`**, **`S/combat/AnimSyncParCool`**, `S/combat/AnimSyncResources` (table lifetime = manifest), `S/AnimationResourceGate` (read-only use of `activated`), timing fields in `S/combat/AnimSyncValidation` (FX-TRUST owner reviews) | After CODEC; parallel with REPORTS | Remove capacity disable and both native capacity fallbacks; §7.4 backlog rule with probe-age counters; §7.6 per-recipient D tests (native mid-tick, ParCool, END tick: D after fragments 0..n-2 and before n-1); no byte retention; §13 encode stage bit-identical to serial |
| 2b | **WIRE-REPORTS** | `N/CombatViewC2SPacket`, `N/AnimationAppliedC2SPacket`, `C/.../CombatChannels`, `C/.../CombatAttackSender` (`sendApplied`), `C/mixin/CombatViewSendMixin` (duck accessor), **`C/combat/ClientCombatScene`** (APPLIED send only), server intake in `S/combat/CombatViews` | After CODEC; the `ClientCombatScene` part after FX-CLIENT-3 | §8 rules; forced-fragment tests (one >24 KiB transaction per family); an APPLIED/ticket interleaving test from two threads (never split); malformed on any foreign payload between fragments |
| 3 | **WIRE-CLIENT** | new `C/combat/AnimationWireReceiver` (§13 per-connection stage), intake in `ClientSnapshotBuffer`/`ClientAnimationJournal`, table build/registration in `C/combat/ClientAnimationResources`, table retention hook in `C/combat/ClientAnimationAssets`, **`C/combat/NativePayloads`** (dispatch of carried payloads after validation) | After FX-CLIENT-3 and CODEC (gate) | Atomic staging; table lifetime and configuration-start clearing; unchanged ADR4 §9 timing; carried-native dispatch equality |
| 4 | **WIRE-GATE** | offline: fixtures, this harness (`dev/run_anim_sync_wire_matrix.sh`), pinned Velocity+Raknetify codec fixture; live: U11 runner | Offline after 2a, 2b and 3; live = coordinator's U11 stand run | See §12 release tests |
| — | **SERVER-CONFIG** | `blockfield-server` `distribution/Dockerfile.server`: `ENV NETWORK_COMPRESSION_THRESHOLD=-1`; `docs/DEPLOY.md` rollback note (owner: server repo; deploy needs explicit owner confirmation) | Before WIRE-SERVER is enabled on the U11 stand; ships in the paired release, server first | §12 stand check |
| — | **WIRE-DOCS** | `WS/docs/ARCHITECTURE.md` "RakNet по умолчанию"; `blockfield-proxy` `README.md` transport contract | With the paired release | §12 |

`blockfield-proxy` `proxy/velocity.toml` (`compression-threshold = 256`, `compression-level = -1`) needs
no change: it only governs vanilla compression of player connections, which Raknetify suppresses.
WIRE-DELTA: not planned. Common/shared changes keep the paired release, server first; PROTOCOL stays 5
only while this pair is unreleased.

## 11. Delivery record

Branch `fulla/as-wire`: harness `32770107`, evidence `1847cd73`, this document at the branch head.
v3 is a design edit: it adds no measurement and changes no harness. The checks below are v2's.
Checks: all 18 cohorts and 32 592 equality assertions passed; `just check` passed; locked
`./gradlew --no-daemon --max-workers=2 --offline spotlessCheck :server:lintJava -x :server:runGameTest` passed. Not run: GameTest, live sockets, Velocity, 30 concurrent observers, MAIN-frame
six-box acceptance (no stand authorized). Outcomes: **merged — no; published — measurement/design
branch only; deployed — no; verified in game — no.**

Reproduce: `bash dev/run_anim_sync_wire_matrix.sh` then
`python3 dev/anim_sync_wire_report.py OUT server/build/anim-sync-wire-runs/*.json`.

## 12. Server config, release tests and ordering

### 12.1 SERVER-CONFIG (P1-B)

- **Where.** `blockfield-server` `distribution/Dockerfile.server`, in the existing `ENV` block (`:8-14`):
  `NETWORK_COMPRESSION_THRESHOLD=-1`. The game, the Workshop and candidate-test all run this image.
  `start-container.sh` only switches `BLOCKFIELD_PROFILE`, so one line covers every backend.
- **The variable name is verified against the itzg sources.**
  - The image is pinned at `itzg/minecraft-server:java21@sha256:a123cb95…fa28` (`Dockerfile.server:4`,
    pinned 2026-09-27 in `953279e`).
  - itzg's `files/property-definitions.json` maps `"network-compression-threshold": {"env":
    "NETWORK_COMPRESSION_THRESHOLD"}`. This mapping is present unchanged from `f15cbb4` (2023-10-07)
    through the last edit before the pin, `a4719f2` (2026-05-31), and on master `2ae1097`
    (2026-10-17). It is also listed in `docs/configuration/server-properties.md`.
  - `scripts/start-setupServerProperties` applies it on every start through `mc-image-helper
    set-properties --definitions /image/property-definitions.json`, because
    `OVERRIDE_SERVER_PROPERTIES` defaults to `true`.
  - Limit: the digest-to-commit link was not read from the image label, because no registry access was
    allowed. The mapping is unchanged across that whole window.
- **Dokploy.** Dokploy env must not set `NETWORK_COMPRESSION_THRESHOLD`; it would override the image.
  The image line is preferred because it is versioned with the release.
- **Rollback.** itzg persists the value in `/data/server.properties`.
  Removing the ENV therefore leaves -1 in place. A rollback must set
  `NETWORK_COMPRESSION_THRESHOLD=256` explicitly, and the server `docs/DEPLOY.md` gets that note.
- **Unchanged files.**
  - `distribution/server.properties` stays as it is: the standalone zip serves direct remote TCP
    players (`SERVER-README.txt:5`), and -1 would send them uncompressed chunks.
  - `server/lobby/server.properties` is not deployed (`build-distribution.py:307-333`) and is not the
    lever.
- **Same host only.** `velocity.toml:18-22` addresses backends by Docker service name on one host.
  Moving a backend off-host requires turning backend compression back on (ARCHITECTURE note, §12.4).
- **Dev stand.** `WS/scripts/dev/devstack.py` runs a direct-TCP backend without Velocity
  (`disable_proxy_mods`, `SERVER_PROPERTIES` without the key). There the backend link *is* the client
  link, so the stand keeps the default 256 and no key is added. Dev-stand bytes and CPU are therefore
  not the production transport. Wire acceptance happens only on the U11 stand.
- **Stand check (U11, before WIRE-SERVER is enabled there).** This was not bytecode-verified, because
  no Velocity 3.5.1 jar is cached. On both the game and the Workshop backend built from the image:
  - `/data/server.properties` shows `network-compression-threshold=-1`;
  - a login through Velocity modern forwarding (FabricProxy-Lite) succeeds;
  - the Velocity log shows "Preventing vanilla compression as streaming compression is enabled" once
    per RakNet login;
  - the backend sends no SetCompression.

### 12.2 Release tests (WIRE-GATE)

**Offline:**

- the differential gate and §5.3;
- forced fragments on every family;
- §7.6 D tests;
- §13 serial-versus-pool bit identity on all 18 cohorts.

**Live:** RakNet through Velocity only.

- **Load:**
  - 30 observers, mixed and diverse, measured against 150 Mbit/s;
  - FULL and JOIN bursts;
  - fire at 300/s;
  - JOIN and server switch with fragments in flight.
- **Slow links** at 3, 4, 5 and 10 Mbit/s:
  - HOL delay;
  - the `start_late_unavailable` rate at 4/5/10 Mbit/s (fold-burst HOL is not in D);
  - one link below the ≈ 4 Mbit/s floor, held for 60 s. §7.4 must fire and suspend, and Velocity
    heap plus direct memory must stay ≤ 192 MB with 30 observers.
- **Velocity:**
  - total CPU at 30 (Raknetify deflate is estimated at about 1.4 cores, plus about 137 MB/s raw relay);
  - heap and direct memory;
  - the docker-bridge kernel cost.
- **Login:** the per-login "Preventing vanilla compression" log line, checked on each RakNet login.
- **Game:**
  - main-thread END-tick animation share with UOPT and UPAR (§7.5, §13);
  - TPS at 30;
  - native timing at 30/60/144/240 FPS;
  - six-box equality.
- **Pack download:** one capture during a concurrent pack download from the home link
  (`docs/SPEEDTEST.md`, greedy TCP). If the exact stream then loses its 150 Mbit/s share, pack egress
  is rate-limited or moved to the R2 mirror before release; that is the pack owner's decision.

### 12.3 Release ordering

1. **RAKNET-ONLY.** The proxy's TCP-login refusal ships and is deployed **before** WIRE is enabled. A
   TCP player costs 670 KB/s against 401 on RakNet (30 mixed), and 30 TCP players would need about
   160 Mbit/s, over the 150 share.
2. **SERVER-CONFIG** goes in the paired server release, server first.
3. The mod pair goes out with WIRE enabled. PROTOCOL stays 5 only while the pair is unreleased.

Tags and deploys need explicit owner confirmation.

### 12.4 Docs and proxy follow-ups (WIRE-DOCS)

- **`WS/docs/ARCHITECTURE.md` "RakNet по умолчанию":**
  - backend compression is off (image ENV);
  - backends must be on the same host;
  - Raknetify is the only client-link compression;
  - TCP clients are refused.
- **`blockfield-proxy` README transport contract:** the same three facts, plus the pinned Raknetify
  version that suppresses vanilla compression.
- **Server `docs/DEPLOY.md`:** the explicit rollback to 256.
- **For the proxy owner, not WIRE (review 2 P2-10).** Raknetify inflates C2S with no output bound
  (`MultiChannelingStreamingCompression.channelRead` @296-324). This is a pre-existing Velocity
  memory risk under 256 MB. §8's caps protect the game, not Velocity.

## 13. Threading (wire stages; owner directive 2026-10-18)

The pool, its size and the anim-sync thread-safety audit belong to UPAR-1 (`fulla/as-upar`,
`p3-anim-sync-parallel.md`). This section fixes only the wire's use of that pool. Every result is
bit-identical to the serial path, because each stage is a pure function of immutable inputs and the
main thread consumes the results in the serial order.

1. **Server encode stage (fork-join on the shared pool).**
   - **Inputs (built on main).** After `prepare`/`publish` and the capsule stamping (`stamped`,
     native/ParCool `wrap`, built as values on main), the main thread has, per observer, the immutable
     list `(probe, capsule packets, snapshot batch)`, plus the observer's `tableGeneration`.
   - **Stage.** The main thread submits one task per observer and waits for all of them. Each task
     does the interned encode (`writeWire`), fragmentation and CRC32C, and returns immutable fragment
     arrays. Tasks touch no ledger, connection or mutable server state.
   - **Enqueue stays on main.** After the join, the main thread enqueues every recipient's fragments
     itself, in today's order: observers in `exact`'s order, and per observer probe → capsules →
     snapshot. It takes D and stores the freeze immediately before each last fragment (§7.2).
   - **Why off-main enqueue was rejected.** It is not proven safe. Native and vanilla packets are
     enqueued by the main thread on the same connection, and `Connection.send` from another thread
     reaches netty through `execute`, so its order relative to main-thread sends is not guaranteed.
     That would break both the per-connection order and the native capsule adjacency.
   - **The mid-tick native path (§7.6) stays serial on main.** It is normally one fragment of a few
     KB, and a fork-join round trip costs more than the encode.
2. **Shared-encoding cache under the stage.** UOPT's `SPLICES` is thread-local, so worker threads
   would see no shared entries. That is correct but loses the reuse. The rule is therefore
   publish-on-main, in two phases per tick:
   - **(a)** one fork-join task per `(target component value, mode, table)` actually in use encodes
     it once;
   - **(b)** the main thread freezes the results into an immutable `SharedEncodings` map, keyed by
     identity plus the §5.2 mode key, and passes it read-only to the per-observer tasks.
   - Every entry is the pure function `encode(value, mode, table)` with UOPT's position type check and
     depth bound, so the bytes equal the serial path's. No worker writes the map.
   - `digest()` and validation stay canonical on main (§5.2). Per-observer table selection (§5.1)
     happens on main before submission and is unchanged.
3. **Client.**
   - `AnimationWireReceiver` is a per-connection netty pipeline handler, inserted before
     `packet_handler`. It survives protocol switches, and its state is fields of the handler, not
     globals.
   - On the connection's network thread it assembles fragments, checks lengths and CRC32C, and
     decodes/expands with the table.
   - It swallows non-final fragments. At the position of the last fragment it emits one decoded
     transaction into the normal packet path. The main thread therefore receives it in exact arrival
     order relative to every other packet, as today. Today's S2C payload codecs already decode on
     that thread.
   - Application, arrival stamps and ADR4 §9 timing are unchanged on main.
   - Tables are registered by main into the handler's concurrent map before READY. The handler clears
     tables and assemblies when it sees the configuration-start packet in stream order (§5.1).
4. **C2S.** Fragmenting stays on the sending thread (main for APPLIED, the TaCZ thread for tickets),
   inside the `CombatAttackSender` monitor (§8). Transactions are ≤ 11 fragments of ≤ 24 KB, and
   enqueueing them is µs.

**Expected main-thread saving** (30 mixed combat, steady, phase23 reflective codec, §3.5/§3.6):

| Work moved off main | ms per tick |
|---|---:|
| Per-observer splice/encode (0.75 ms × 30) | 22.5 |
| Per-target shared encodings (2.7 ms once per tick) | 2.7 |
| CRC32C (0.036 ms × 30) | 1.1 |
| END-tick capsule encode, 20 / 300 FIRE/s | 4 / 10 |
| **Total** | **≈ 30 / 36** |

- **What main keeps:** the fork/join hand-off, the fragment enqueue (30–750 fragments) and D/freeze.
  This is not measured; the estimate is ≤ 3 ms, and WIRE-GATE measures it.
- **Wall time:** the encode stage takes ≈ 30–36 ms / W on W pool workers.
- UOPT's generated accessors shrink all of these numbers. `prepare`'s own cost belongs to UOPT/UPAR,
  not the wire.
