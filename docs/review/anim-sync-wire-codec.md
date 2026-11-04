# WIRE-CODEC: interned envelope-v2 animation wire (ADR4.2 v3 §10 row 1)

Contract: [ADR4.2 v3](anim-sync-wire-amendment.md) §5–§6, §13.2 and review round 2 P1-A. Base:
`origin/fulla/phase23` `f2150793` (UOPT integrated), plus the WIRE-1b history of `fulla/as-wire`.
Branch `fulla/as-wire-codec`. No sender, receiver, stand, server, client, proxy or prod config was
changed or started; WIRE-SERVER, WIRE-CLIENT and WIRE-REPORTS consume this API.

## Delivered

| Item | Where |
|---|---|
| Generation ref table: §5 derivation from the manifest keys and the validated views, literal escape, 1 048 576-entry cap (literal-only above, `wire_table_capacity`), digest, common carrier list | `K/combat/AnimationRefTable` |
| Tag-0 hook: `writeWire` (no per-call 256 KiB count), `readWire` (index bound, class check, canonical depth check), canonical paths reject tag 0 | `K/combat/AnimationSchema` |
| §5.2 shared encodings keyed per mode (canonical or table identity); `write`/`encode`/`digest` force canonical | `AnimationSchema.Splices/Spliced/Encoding` |
| §13.2 frozen per-tick map for encode tasks (`SharedEncodings.builder().share().prime().build()`, `merge`); missing entries written in full, never stored | `AnimationSchema.SharedEncodings` |
| WIRE-GATE byte transcoder (independent of the decoder) | `AnimationSchema.expand`, `AnimationWireS2CPacket.expand` |
| Envelope v2: header, fixed 24 576-byte partition, CRC32C, S2C ≤ 64 MiB, C2S ≤ 262 144 B / 11 fragments, RAW only (ZLIB=1 rejected), C2S never names a table; per-family assembler (gap, repeat, early new transaction, changed header, CRC → defect; memory grows per received fragment; `clear()` for JOIN/backend switch) | `K/combat/AnimationWire` |
| `blockfield:anim_wire_s2c` (snapshot/event bodies keep the `ANIMATION_CODEC`/`CODEC` layouts; `decode` resolves only the header's table; the snapshot header must mirror the body) | `N/AnimationWireS2CPacket` |
| `blockfield:anim_wire_c2s` (applied/ticket bodies keep their layouts; canonical decode) | `N/AnimationWireC2SPacket` |
| Payload registration | `N/NetworkHandler` |
| Manifest: `tacz.gun:<id>` (server gun index → digest of the default display id), `wire.ref-table`, `compat.wire`; `TaczAssets.defaults()` accessor | `K/combat/AnimationManifest`, `K/combat/TaczAssets` |
| Server: table derived at activation, kept exactly as long as the manifest (`AnimSyncResources.table(g)`) | `S/combat/AnimSyncResources` |
| Client: answers `tacz.gun:*` (server value) and `wire.ref-table` (own derivation); `View.table()` for WIRE-CLIENT to register before READY | `C/combat/ClientAnimationResources` |
| `WRAPPED` and the carrier definition derive from the common list | `S/combat/AnimSyncNative` |

## Manifest entries for the U9 owner's review

- `compat.wire` = SHA-256 of `AnimationRefTable.compatibility()`: envelope version 2, body 24 576,
  CRC32C, S2C/C2S bounds, derivation version 1, table cap, the five carrier ids, the four markers,
  `tacz:default`. A pre-WIRE client cannot produce it (null) and is refused (compat message).
- `wire.ref-table` = SHA-256 of the concatenated canonical ref records in handle order. Each peer
  derives it from the manifest keys and its own views; any combat display/clip/config difference
  also changes it (the U9 client fixture's expected mismatch lists now include it).
- `tacz.gun:<id>` = SHA-256 of the default display id, for every gun of the server's native common
  gun index. Server-only data: the client never resolves a gun (checkpoints carry the resolved
  display) and answers the server's value, per §5 ("never a client-side gun scan"); the list itself
  is compared through `wire.ref-table`.
- FX-CLIENT-3's `HeldWeaponPose` diff (`origin/fulla/as-fx-client` 49bb0abd) changes only the SBW
  arm pose; the gun index and the `tacz.gun`/`tacz.display`/`tacz.melee` minting are unchanged, so
  derivation version 1 is frozen against it.

## Evidence

Checks on `3b2d295c` (pre-integration; the integration run is recorded below).

- **Plain JUnit** (`AnimationWireTest`, 14 tests; `WireManifestRefusalTest`; `WireCarrierTest`):
  every registered record type with ref-rich values (23 types with in-table refs) interns,
  expands to its canonical bytes and decodes equal; each ref class: 2-byte handle, 58-byte
  literal escape for out-of-table and life-scoped refs. Snapshot (5 and 46 fragments, fixed
  24 576-byte partition) and event transactions through the registered payload codec, the
  assembler, the expander and the decoder. §5.3: an observer on G-1 and one on G sharing one
  target component in one scope (G refs literal to G-1, handles to G, distinct cache entries per
  table, then canonical; `digest` and `encode` equal a fresh canonical encode before and after);
  the frozen `SharedEncodings` map writes the serial bytes and never stores a missing mode. The
  three wrong-table cases (handle under table 0, dropped table, cleared registry) plus a stale
  holder are rejected. Malicious handles (out of range, `Integer.MAX_VALUE`, wrong type, at a
  non-ref position), tag 0 without a table, unknown tags, truncation and non-canonical varints;
  fragments with a wrong version/family/direction, ZLIB, length 0 or over 64 MiB, a count lie,
  index past count, short/long bodies, every truncated header length, C2S with a table or over
  262 144 bytes; assembler gaps, repeats, early new transactions, changed headers, CRC failures,
  `clear()`; a 64 MiB length claim allocates < 1 MiB. C2S applied/ticket transactions stay under
  32 767 bytes per payload and decode equal. Over-capacity tables send literals only. READY
  refuses a different `wire.ref-table` and a client without `compat.wire`.
- **Transformed server fixture** `WireCodecOracleFixture` (Fabric Loader 0.19.3, production mod
  list; [oracle.json](wire-codec-evidence/oracle.json)): the five carrier ids are the pinned TaCZ
  bf8/LR bf2 payload `TYPE` ids. The bench generation's native views (default pack, 32 diverse
  profiles, LR bat) rebuilt at the blobs' generation 0: the WIRE-1b prototype table reconstructs
  exactly (8 706 entries, sha256 `697eb93d…`); the production derivation has 8 705 (the prototype's
  extra entry is the `DefinitionRef` twin of the LR config, which no state names). All 60 saved
  blobs: canonical literal-only transactions byte-exact through the full path; the production
  encoder with the prototype table writes every saved interned blob byte for byte; production-table
  transactions (9–24 fragments) expand to the canonical blob and read back equal; zero
  generation-scoped literals in all 30 blobs (FULL included). 90 transactions, 2 543 fragments,
  6.3 s. The generation-0 handles are, as required, refused by the transaction decoder (0 means
  literal-only).
- **Transformed client fixture** `AnimationResourcesOracleFixture`: the native client loaders over
  the production packs reproduce `wire.ref-table` (and every entry) of the server reading; its
  combat-override and reload-fence expectations now include `wire.ref-table`.
- **WIRE-1b matrix** (`dev/run_anim_sync_wire_matrix.sh`, bench now at generation 1, production
  differential per snapshot and capsule; [matrix.json](wire-codec-evidence/matrix.json)): 18
  cohorts, 1 769 snapshot transactions, 38 590 equality checks, all passed; the production table
  (8 705 entries) leaves zero generation-scoped literals in every steady cohort.

**Integration** (merge of `origin/fulla/phase23` `6da59256`, which brought FX-CLIENT-3,
FX-TRUST-2 and the sharded oracle runner; checks on `54e5bd52`): `just format`, `just check`,
`just java-check` (server 1 740 and client 534 unit tests, GameTest, 35 client and 16 server
transformed fixtures) and `just test` (rig tests, both oracles, equality report) all passed.
`WireCodecOracleFixture` joins server shard 1 in `dev/oracle-shards.json`; the wire matrix runs
like the bench (first shard only). FX-CLIENT's committed `anim-sync-fx-client-wire.json` was
regenerated by its own procedure: its packets embed the compatibility digest, which `compat.wire`
changes (only those digest fields and the seed contents derived from them differ).

## Deviations and notes

- A generation-0 table can be built (offline evidence); the wire never resolves one, since a
  transaction stamped 0 is literal-only. The saved WIRE-1b blobs are generation 0, so their
  interned transactions are checked with the table given directly (encoder, fragments, assembler,
  expander, reader) and the decoder must refuse them; the complete path including the decoder runs
  on their literal-only form, on the JUnit transactions and on every matrix sample (generation 1).
- The snapshot envelope's `observerStreamEpoch`, `logicalTick` and `previousTickPlusOne` must equal
  the body's header (`decode` refuses a mismatch); `sceneGeneration`, `connectionId` and
  `logicalSequence` are the sender's.
- Touched outside the table's own files only where the contract needs it: `AnimSyncNative`
  (carrier list), `AnimSyncResources` (table lifetime), `ClientAnimationResources` (READY answers,
  `View.table`), `TaczAssets.defaults()`, the U9 client fixture's expectations, the WIRE-1b harness
  (UOPT signatures, production table, production assertions, checkout-relative run script).

## Open items for dependent units

- **WIRE-SERVER:** stamp `tableGeneration = AnimationResourceGate.activated(O)` when
  `AnimSyncResources.table(g)` is non-null, else 0; build bodies with
  `AnimationWireS2CPacket.snapshot/events(packet, table, shared)`, `Header.of(...)` and
  `AnimationWireS2CPacket.packets`; per-tick `SharedEncodings` per (target, table) for the pool
  stage; count `wire_literal_refs`; the RakNet channel-3 mapping of `blockfield:anim_wire_s2c`.
- **WIRE-CLIENT:** register `ClientAnimationResources.View.table()` with the connection's wire
  stage before READY is sent; per-connection `AnimationWire.Assembler(true)` and
  `AnimationWireS2CPacket.decode(transaction, tables)`; table lifetime and configuration-start
  clearing per §5.1.
- **WIRE-REPORTS:** `AnimationWireC2SPacket.applied/ticket`, `Header.of(APPLIED|TICKET, 0, ...)`,
  `packets`; server `AnimationWire.Assembler(false)` in `CombatViews`; channel 2 in
  `CombatChannels.install`.
- **WIRE-GATE:** `AnimationSchema.expand`/`AnimationWireS2CPacket.expand` and
  `WireDifferential` are the differential; the matrix asserts production bytes per sample.
