# WIRE-REPORTS: bounded C2S fragments and attack adjacency

Contract: ADR4.2 v3 §8/§10, round-2 P1-D. Base: phase23 `49d8305a`.
No U1 fields, tags, layouts, limits, or logical report validations change.

## Implementation

- `CombatViewC2SPacket.fragments()` and `AnimationAppliedC2SPacket.fragments()` wrap the existing
  canonical bodies in `AnimationWireC2SPacket`. Headers repeat connection/stream/scene, H, and
  ticket sequence (TICKET) or presentation revision (APPLIED); predecessor is zero. No ref table.
- `CombatChannels` maps `blockfield:anim_wire_c2s` to RakNet channel 2.
- `CombatViewSender` exposes the connection mixin's existing `CombatAttackSender` monitor.
  The scene's APPLIED send calls its synchronized `sendApplied`; both that method and `send`
  encode/fragment/enqueue while holding the monitor, with the reentrancy flag set. A ticket's
  final fragment and attack are in that same block. The frame publishes its receipt afterward.
- `CombatViews` registers the new receiver and owns connection-local assembly. It rejects a
  foreign report/attack during assembly, a different wire family, repeated/missing fragments,
  CRC/body/header mismatch, or another report between a completed exact ticket and its attack.
  Malformed intake clears pending/action/assembly, without publishing partial reports.
  Fragments do not consume an action. Completed bodies use the unchanged `accept` or
  `AnimSyncValidation.applied` path. Restart/lifecycle/disconnect discard partial assemblies.
  Envelope validation bounds decoded bytes to 262144 and fragments to 11 before allocation.
  Codec-failure classification includes both bodies and the new envelope classes.
- The isolated r10 migration helpers retain their preexisting direct-call behavior; the registered
  exact codec never decodes that representation. Production exact tickets use the v2 carrier.

## Fixtures

Registered in both session FIXTURES lists and `dev/oracle-shards.json`:

- Client `WireReportsOracleFixture`: real transformed `ClientCommonPacketListenerImpl.send`,
  injected duck, and a mocked socket sink. Pause after the first APPLIED fragment while an
  independent thread sends the pinned native TaCZ shoot packet plus a ticket. Assert all APPLIED
  fragments, then all ticket fragments, then attack; exercise wrapper reentrancy.
- Server `WireReportsOracleFixture`: 400-victim bodies force two fragments per C2S family;
  production payload codec/assembler/body decode round-trips are equal, as are pinned native PA
  samples derived from decoded phase anchors. Maximum 262144-byte/11-fragment assembly succeeds;
  oversize logical reports, size/count lies and CRC corruption fail closed.
- Plain `WireReportsTest`: server-issued seeds/capsules and an honest LR observer start traverse
  APPLIED → TICKET → TaCZ intake and validate EXACT for both victims. Separate cases cover foreign
  legacy reports, attacks, crossed wire families, duplicate fragments and lying headers.
- Existing `CombatAttackSenderTest` (seven tests) and `CombatChannelsTest` (two tests, now including
  the wire id) pass; the latter executes the checksum-matched Raknetify channel override.

Targeted validation passed: three server JUnit tests, nine existing sender/channel tests, and all
three new transformed fixture fragments. Runtime: Temurin 21.0.12+8 Linux amd64, production Fabric
Loader 0.19.3/intermediary namespace/full pinned mod list. Native phase comparison is a focused
transport regression, not live six-box/appearance acceptance. No fallback is counted as equality.

## Integration boundary

Full gate logs and statuses are in `build/wire-reports-verification/`; final results are recorded
in the companion JSON after the full runs. No stand, release, tag or deployment is authorized.

Unowned follow-up: `ClientCombatScene.MAX_TICKET_REPORT_BYTES` remains `32767 - 256` and the
`exactReport` capacity check still describes one custom payload. WIRE-REPORTS owns only its APPLIED
send block, so this preexisting producer restriction is unchanged. The presentation owner should
validate the complete logical ticket against 256 KiB when lifting that producer restriction; never
truncate victims or reserve a guessed header size. The new transport itself supports the full bound.

Full-suite blocker (confirmed by `just test`): `FxClientWireOracleFixture.Replay` mocks
`ClientPlayNetworking.send` and its fixture connection supplies no adjacency sender/sink. Its
recorded C2S result therefore contains zero APPLIED bodies instead of the three expected bodies;
all four canonical tickets remain identical. The real transformed send/duck path is covered by the
new client fixture and passes. The FX owner must initialize the fixture connection's duck and
capture/decode `anim_wire_c2s` from its `send` sink before recording the canonical APPLIED body.
Preserve the three expected logical reports and their timing; removing them from the golden vector
would weaken the start-validation gate. This unit does not edit the FX-owned fixture or its vector.

## Final checks and outcome

- `just format` and `just check`: pass.
- `just test` and `just java-check`: fail on the FX-owned C2S capture described above. Java
  formatting and client/server lint passed inside `java-check`; neither failed command reached
  GameTests. No full gate is relabelled pass.
- Independent locked command: `./gradlew --no-daemon --max-workers=2 spotlessCheck lintJava
  :client:test :server:test :server:runGameTest :client:oracleTest :server:oracleTest
  -PoracleOnly=WireReportsOracleFixture`: pass. Client JUnit 534 tests (two existing opt-in skips),
  server JUnit 1743 tests, zero failures/errors; all 13 GameTests pass. All three selected
  transformed wire checks pass. Each 400-victim body is 38723 bytes (two fragments); native PA
  rotation raw bits are retained in the JSON evidence.
- The independent subset confirms the unit and completes GameTests, but does not replace the
  failed full oracle gates. Integration into phase23 is blocked until the FX fixture owner adapts
  its send sink and the full gates pass. No ADR4/U1 schema deviation.
- Merged: no. Published: implementation branch only. Deployed: no. Live in-game verification: no.
  The required worktree remains in place. No test server, JavaExec, daemon wait or watcher remains
  from this unit's completed commands.

[Machine-readable results](anim-sync-wire-reports.json). Full logs, targeted logs, GameTest XML and
wire fixture fragments are retained under `build/wire-reports-verification/` in the worktree.
