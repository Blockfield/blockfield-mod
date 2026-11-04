# Animation wire client

Contract: ADR4.2 v3 §§5.1, 6, 9, 13 and ADR4.1 timing/lifecycle. Base:
`49d8305a` (WIRE-CODEC), including FX-CLIENT-3 (`106ce145`). U1 schema manifest 4,
layout digest and all validation bounds are unchanged.

`AnimationWireConnectionMixin` installs one `AnimationWireReceiver` before the
connection's `packet_handler` at `channelActive`, independently of the protocol
listener. A narrow common-listener accessor supplies that connection during
configuration READY. The handler survives protocol switches and owns every
assembly, table, partial-transaction table pin and completed-generation watermark.
A Fabric global receiver advertises the physical play channel; any fragment that
bypasses the connection stage invokes recovery there, never main-thread decode.

On the network thread, the shared assembler checks fixed partition, headers,
lengths, order and CRC32C. The wire decoder then expands the named table and
validates the whole logical value and envelope/body identity. Nonfinal fragments
publish nothing. The final fragment becomes an ordinary logical snapshot/event
packet at that same position in the existing packet path. Vanilla bundles keep
that rule and their native packet order too. The existing Fabric callbacks still
apply it on main through `ClientCombatScene.accept`/`capsules`; native carriers
continue through the unchanged validated journal release and `NativePayloads`.
No clock, frame, tick or native side-effect scheduling rule changes.

Assembler/decoder defects emit a local-only ordered packet. Its main-thread
handler invokes the new six-line `ClientCombatScene.wireDefect` intake and the
buffer's existing failure/recovery path. H and baselines do not advance;
recovery retains its original cooldown and fresh-FULL rules. This additive scene
intake is the only scene edit; the independently owned APPLIED sender is untouched.
Malformed physical envelopes remain rejected by the existing registered payload
codec before they can enter the stage. No malformed transaction receives equality
credit.

The effective-resource resolver registers `View.table()` with that connection
before sending a successful READY. It reproduces `wire.ref-table` through the
existing manifest check. Assets publish their generation-retention scan to the
handler. Table G leaves the lookup map only after assets release G and a complete
ordered transaction names a newer table. An already-open family retains the
immutable table it captured at its first fragment: permitted cross-family
interleaving cannot strand it when another family completes a newer generation.
New transactions cannot look up a retired table. Configuration start clears all
tables and assemblies in stream order; main configuration INIT also fences any
old READY callback queued before that network barrier. Disconnect clears them too. JOIN keeps
this backend's configuration-validated table, rather than clearing it after READY.

## Fixtures

- `AnimationWireReceiverTest`: atomic staging, interleaved families/native packet
  position, gaps/repeats/replacement, CRC failure, literal-only handles, missing
  tables, generation overlap, retirement's two conditions, configuration/backend
  switch/disconnect, changed tables, restart cooldown, vanilla bundles and snapshots
  exceeding the old aggregate 256 KiB limit.
- `OracleInjectionTargetTest`: pins the required `Connection.channelActive` tail.
- `AnimationResourcesOracleFixture`: requires the matching connection table to
  exist inside the READY reply callback.
- Transformed `AnimationWireOracleFixture`, registered in the session and shard 4:
  production `Connection` mixin and physical payload codec on a dedicated network
  thread, then main-thread production scene/journal intake. Forced-fragment cold
  JOIN/Create enter/exit reproduces every C2S byte of the FX-CLIENT production
  vector. A fragmented LR carrier runs its native body only at the final fragment,
  revises that same frame, and samples the transformed native model repeatably.
  Complete reordered capsules preserve predecessor release and duplicate-event
  idempotence; generation overlap, retirement, fragment disorder and backend table
  number reuse fail closed.
- The FX-CLIENT production fixture's replay helper has an injectable intake only
  for this differential; its default still calls the same production callbacks.

Focused verification passed: 11 receiver JUnit tests, 9 injection-target tests,
and all 14 transformed fixtures in AnimationResources/FxClientWire/AnimationWire/
NativeCapsule. The resource fixture now bootstraps its own vanilla registries,
so a subset run no longer depends on an earlier fixture. The listener accessor is
a standalone mixin interface, compatible with Byte Buddy's transformed listener
inspection. Full gates on `933f52f8` (merge of `fulla/phase23` `bb773d7e`), 2026-10-19: `just format`,
`just check`, `just test` (6 min 33 s) and `just java-check` incl. the in-process GameTests
(7 min 25 s) all exit 0. No live socket,
stand/client, owner appearance approval, client decode benchmark, production,
release or deploy is claimed. Those acceptance measurements remain with WIRE-GATE.
