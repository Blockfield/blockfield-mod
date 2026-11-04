# WIRE-SERVER: interned fragmented exact stream (ADR4.2 v3 §10 row 2a)

Contract: [ADR4.2 v3](anim-sync-wire-amendment.md) §§5.1, 5.2, 6, 7, 13, review round 2 P1-A
(server side) and P1-C. Base `fulla/phase23` `beaa9993` (WIRE-CODEC, WIRE-CLIENT, WIRE-REPORTS,
UOPT-2, UPAR-2). Branch `fulla/as-wire-server`. U1 schema manifest 4, layout digest, `VERSION` and
every per-component bound are unchanged. No stand, client, proxy, server config, release or tag.

## Delivered

| Item | Where |
|---|---|
| `prepare` no longer encodes to check its size; `Prepared.bytes` and the `Snapshot capacity` path are gone | `S/combat/AnimSyncStream` |
| §5.1 per-observer table: `table(player)` = READY generation's retained table, else literal-only (0); read once per transaction on main | `AnimSyncStream.table` |
| §5.2/§13.2 shared encodings per (value, mode, table): one pool task per retained checkpoint primes its 11 components in every table mode in use (null = canonical); main merges them into the tick's frozen map | `AnimSyncStream.share`, `stage` |
| Pure transaction encoders (header, fragments, CRC32C, `wire_literal_refs`) | `AnimSyncStream.snapshot`, `events`, `encode` |
| §7.2 D: fragments 0..n-2, then `D = AnimSync.now()`, then the ledger and frozen deadline (`delivered` for capsules, `sent` for seeds), then the last fragment; the batch commits after its enqueue | `AnimSyncStream.enqueue`, `deliver`, `sent`/`commit` |
| §13.1 END tick: §7.4 for every observer first; then per observer on main the probe (sent at once, so its send time stays the enqueue), capsules and ParCool wrap, `prepare`, and a `submit` of its encode to the shared UPAR pool (inline without a pool); then in the same observer order every transaction is enqueued with D, committed, `CombatPoseHistory.sent`, `lastTick` | `S/combat/SnapshotStream.exact` |
| Capacity disable removed; an encoding defect (including the 64 MiB `wire_capacity` failure bound) still stops that observer (`snapshotDefect:*`), releasing unsent seeds and returning a held ParCool batch to the END flush | `SnapshotStream.defect` |
| §7.4 backlog: oldest unechoed probe > 2 s or channel unwritable > 1 s fires (FULL rows withheld, `link_capacity_unavailable`); fired 3 s suspends (nothing enqueued, ledger pruned, no native wrapping); resumes once the oldest unechoed probe is < 0.5 s, on the same stream with every row FULL across the gap; probe-age counters | `SnapshotStream.backlog`, `AnimSyncStream.prepare(..., holdFull)`, `AnimSyncNative.eligible` |
| Game-side writability: accessor mixins for `ServerCommonPacketListenerImpl.connection` and `Connection.channel` | `S/mixin/SnapshotConnectionAccessor`, `SnapshotChannelAccessor` |
| §7.6 mid-tick native path: per recipient in `held` order, the stamped list encoded once with its own table, D before its last fragment; size-check encode and `nativeUnwrapped:capacity` removed | `S/combat/AnimSyncNative.deliver`/`wrap` |
| §7.6 ParCool: no size check or `parcoolBatchUnwrapped:capacity`; the wrapped delivery is fragmented; D per capsule transaction in `exact` | `S/combat/AnimSyncParCool.wrap`, `unserved` |
| Test seams: `AnimSyncStream.sender` (every exact-transport enqueue), `AnimSyncResources.tableForTests`, `AnimSyncValidation.frozen` (evidence lookup) | — |
| Send-time counters: `wire{Snapshot,Event}s/Bytes/Fragments`, `wireEnqueueNanos`, `wireEndPhaseNanos`, `wireEncode{Main,Worker}Nanos`, `wire_literal_refs`, `snapshotBytes` (now wire bytes), `link_capacity_*`, `linkProbeAge{Nanos,Samples}` | `AnimSync.counters()` |

FX-TRUST semantics are unchanged: application authorization, the immutable timing history, deferred
replay, verdict reuse, seed closure and cold reseed read the same evidence; only its D is now the
enqueue of the last fragment (seeds froze after `send` before, capsules after `send`). `freeze`
still stores absence and never widens.

## Fixtures and tests

- `AnimSyncWireServerTest` (9 JUnit cases, real PA/LR server stack): END-tick D and freeze before
  the last fragment for a multi-fragment JOIN FULL, the cold seed → evidence reseed and an END-tick
  LR capsule; per-connection order probe → capsules → snapshot; decoded batches equal the stamped
  S[t] and the seed ledger; send-time counters. Shared pool (2 workers) with observers on table 1,
  table 2 and literal-only in one tick: every transaction equals the serial (thread-local, unshared)
  encoding of its decoded value. Generation overlap: G refs literal to a G-1 observer
  (`wire_literal_refs`, no handle, canonical expansion), handles for G. Mid-tick native with two
  recipients: each recipient's own D after the previous recipient's last fragment. A forced
  17-fragment capsule (two 200 KB carriers): no ledger entry before D. A 3 000-capsule ParCool
  delivery above the old 256 KiB cap: wrapped and fragmented. Backlog fire/withhold/suspend/resume
  and the unwritable-channel trigger. Backend switch: the new connection's first transaction is a
  FULL|RESET at logicalSequence 1 with a fresh seed.
- `AnimSyncNativeTest`: decodes the captured wire transaction (`WireTransactions`).
- Transformed `WireServerOracleFixture` (own server shard 2, 7 min): the WIRE-1b `30:diverse:combat`
  cohort (production-scale ids, generation 1 production table, 8 705 entries) natively captured,
  then production `SnapshotStream.exact` for all 30 observers over 8 ticks, observers split between
  table 1, literal-only and a generation-7 table. Every transaction is decoded by the client stage,
  `expand` equals the canonical bytes of the decoded value and the body equals its serial encoding;
  no observer disabled; every JOIN FULL > 256 KiB (minimum 434 070 B, 18 fragments, 29 FULL
  records each); seed evidence present at each last fragment. Workers 0 and 2: identical
  transactions (472 transactions, 6 384 fragments, SHA-256 `0ced7609…`). Second case: the
  accessor mixins on the transformed classes read a real `EmbeddedChannel`.

## Deviations

- D reads the semantic clock `AnimSync.now()` (production: `System.nanoTime()`), as seed issue and
  probes already did; ParCool acceptance now reads it too (it read `System.nanoTime()` directly,
  which made bench capsules differ between runs).
- §7.4 withholds FULL rows rather than whole transactions: a row without state is already the
  honest unavailable case, and steady rows keep flowing. A resumed observer continues its stream
  (same epoch, actual-send predecessor); the client's own silence hold covers the gap, so no
  unsolicited epoch change is introduced.
- END capsule ledger writes (`delivered`) moved after `prepare` to the enqueue. `prepare` reads no
  capsule of the current tick: folds only consume reported applications below the A bucket, and a
  capsule delivered this tick cannot have been reported.
- A mid-tick encode defect (never a size) keeps the END-tick delivery and sends the native packet
  unchanged (`nativeUnwrapped:encoding`), so the native side effect still happens exactly once.
- `AnimSyncWireBench`: `install()` is package-private and `World.tick()` is split into
  `capture()` and its own delivery, so the fixture can deliver through production code.
- S2C `anim_wire_s2c` rides Raknetify channel 3 through its class mapping of clientbound custom
  payloads (`CombatChannelsTest`); no identifier override is needed.

## Open items

- WIRE-GATE: live enqueue/END-tick share at 30 with UOPT/UPAR (the fixture's `wireEnqueueNanos`
  includes its own verifying sink), Velocity heap/direct memory under §7.4 on a slow link, the
  §7.4 thresholds, `start_late_unavailable` rates; the matrix harness still emulates delivery.
- UPAR's `AnimSyncBench.stream` still emulates the pre-wire order (deliver, prepare, publish); it is
  not production code and its gates are unaffected.
- `docs/BODY-POSES.md` describes the migration wire, whose 256 KiB cap remains for
  the exact transport only (R10-OUT removed the non-exact transport server-side in 3a and its
  codecs and format byte client-side in 3b).
