# Phase-3 RakNet snapshot stream fix

Historical r9 report. The active restart/channel/lifecycle policy is now documented in
[BODY-POSES](../BODY-POSES.md#phase-3-wire-contract-snapshot-interpolation--lag-compensation):
bounded predecessor reordering, repeatable rate-limited recovery, and a full reset on JOIN.
The original claim below that DISCONNECT always runs on the client thread was incorrect:
Fabric networking 4.3.0 can fire it from Netty `channelInactive`; both resets now use
`Minecraft.execute` off-thread and reset synchronously on the client thread. Respawn/Login also repeat the SYNC/START switch on the same connection.

Base: `7e350bdf4fb59d9bb5f057f7f499417923dd0608` (`fulla/phase23`).
Scope: the unreleased client/server protocol pair, still PROTOCOL 5. No proxy changes,
production actions, live-stand writes or live gameplay acceptance.

## Proven transport mechanism

Artifacts (immutable Modrinth versions; tests/build verify SHA-256):

- Fabric `4kUkTS6R`, `raknetify-fabric-0.1.0+alpha.5.196-all.jar`,
  `c7ed7782a940b1e880c1c1d1639a2cc27a176c228ec2a7c99435d5a13edbc741`.
- Velocity `u3LRuC7t`, `raknetify-velocity-0.1.0+alpha.5.196-all.jar`,
  `87fc0ca3ac5614c77f657c2c1337e589438f4d1bf4a8e90523409ea15d6465bd`,
  matching `blockfield-proxy/distribution/Dockerfile.proxy`.

Reproduce the following citations with JDK 21.0.12 `javap -p -c -classpath <jar> <class>`.
Offsets refer to the pinned Fabric artifact unless explicitly marked Velocity.
`RaknetSnapshotSwitchTest.productionVelocityUsesIdenticalCommonSwitchAndOrderingCode`
compares normalized class bytecode (fresh constant pool, no debug/stack-map tables):
codec, FrameOrderIn, its per-channel queue and ReliabilityHandler. Fabric packaging
rebuilds constant pools, changing raw class bytes and some `ldc` instruction widths;
that is not a transport behavior difference.

| Class / method | Bytecode proof |
| --- | --- |
| `fabric.common.connection.RakNetFabricChannelEventListener.write` | `class_2641` (commands) at 55–58, writes `SIGNAL_START_MULTICHANNEL` at 61–65, then forwards the packet. |
| `fabric.mixin.server.MixinPlayerManager1_20_2.postJoin` | Writes the signal at 36–41 after join; the server's snapshot sender runs at END tick after pose commit. The signal can precede the first snapshot without its marker ACK having returned. |
| `common.connection.RakNetSimpleMultiChannelCodec.write` | 0–70: while `queuePendingWrites`, **encode now**, retain FrameData + promise. 109–158: create packet-id 250 (0xfa) marker, set order channel **7**, set `queuePendingWrites=true`, write marker. 163–174: attach completion listener. The caller's signal promise itself already succeeds at 78–84. |
| `RakNetSimpleMultiChannelCodec.encode0` | 7–24: consult overrides; 50–54: only set a selected order channel when `isMultichannelEnabled`. FrameData otherwise defaults to reliable ordered channel 0 (`FrameData.reset`). Thus queued snapshots keep channel 0 even if flushed later. |
| `RakNetSimpleMultiChannelCodec.lambda$write$0` / `flushPendingWrites` | Listener 0–2 enables multichannel, 20–22 flushes pending writes. Flush 22–32 forwards **existing FrameData**, without re-encoding/reselecting its channel. Later S2C custom payloads select channel 3 (`CombatChannelsTest`). |
| `pipeline.ReliabilityHandler.readAck` | 57–94 removes the ACKed FrameSet and invokes `FrameSet.succeed`; `FrameSet.lambda$succeed$2` 0–10 completes each frame promise. The marker future waits for its datagram ACK, not delivery/application of all older or queued channel-0 packets. Queued writes were not even sent when the marker was ACKed. |
| `RakNetSimpleMultiChannelCodec.channelRead` | 55–70 consumes/releases packet 250, without synchronizing other channels. Its alternate `decode` method likewise simply ignores packet 250 at 57–67. |
| `pipeline.FrameOrderIn` | Constructor 5–40 builds eight independent queues; decode 51–62 indexes by channel. `OrderedChannelPacketQueue.decodeOrdered` 0–20 checks only that channel's next order index, 23–75 drains its prefix. No cross-channel fence. |
| Velocity `connection.RakNetVelocityChannelEventListener.write` | 110–121: `AvailableCommandsPacket` triggers the same common start signal. |
| Velocity `connection.RakNetVelocityConnectionUtil.onPlayerLogin` | 160–176 installs common CustomPayloadChannel handler; 180–222 installs common PacketIdBasedOverrideHandler with `VersionMapping.s2c`. Production proxy uses the same common codec/ordering mechanism; backend TCP preserves the proxy's resulting arrival order. |

Package prefixes above omit `com.ishland.raknetify.`; `pipeline` is
`network.ycc.raknet.pipeline`. Raw javap output is retained in the worktree's
`.gradle/raknet-evidence/` for review; regenerate it from the checksum-matched jars.

The permanent deterministic test
`realPinnedSwitchCanDeliver102Before101AndGuardRejectsBeforeDeltaDecode` loads **both
real pinned jars**, holds the marker's write promise until the modeled FrameSet ACK,
queues tick 101 while disabled, completes the promise, then writes tick 102. Actual
FrameData channels are **[0, 7, 0, 3]** for tick100/marker/tick101/tick102. Actual
FrameOrderIn + codec delivery is **100, 102, 101** with each channel in order. Marker
receipt yields no application batch. The fixed buffer detects the missing predecessor
at 102 and leaves H=100 before touching its delta baseline.

The original buffer from the base commit was also compiled unchanged (renamed only)
in a temporary test fixture. Three baseline reproductions exercise that proven delivery
order: empty batches trip **`Unordered snapshot stream`** at delayed 101; a later delta
with an existing baseline first falsely advances H to 102 (1.56 m rather than the correct
1.84 m), then trips the same order branch at 101; a delta overtaking its pairing full
trips **`New pairing needs FULL|RESET`** before advancing H. Those temporary baseline
sources are removed from the final tree; their run log is `.gradle/reproduction.log`.

## Relation to round 4 and competing explanations

Read-only evidence: workspace `.dev/coord-20261011/jobs/p3-live-r4/evidence/`.
The start/after netstate pairs for RTT20/80/150 retain H=9122/19962/30808,
protocolDefects=1, no driven entities; RTT250 stays healthy. These values establish
permanent failure, **not which exception was swallowed**. The original logs contain
neither batch payloads nor the exception reason; datagrams.ndjson records channels
and delay, not snapshot headers/records. It is impossible to retrospectively prove
that a specific catch branch fired in each of those three runs. The proved switch
mechanism reproduces both candidate branches above; the next live run must collect
the newly added WARN/netstate reason. Do not label inferred historical attribution
as observed proof.

- **RakNet→TCP fallback:** server lines explicitly say “logged in via RakNet” for the
  affected play sessions. `RaknetFallback.loginCompleted` clears retry state; the tick
  path only retries a pre-login DisconnectedScreen. The deterministic defect requires
  no fallback, reconnect, static singleton, client thread race, shim or native entity.
- **Reconnect/static-buffer race:** Fabric's play receiver and DISCONNECT lifecycle
  run on the client thread. Disconnect replaces buffer/clock and resets sequence;
  scene reset intentionally preserves ordered decode state. Recovery/disconnect tests
  assert isolation and counter/sequence reset. Historical logs cannot independently
  prove callback interleavings; there is no evidence that reconnect is necessary.
- **Shim:** it relays unchanged datagrams with independent `call_later` delays, and
  can reorder UDP datagrams. Reliable ordered RakNet frames are resequenced inside
  each channel by the pinned FrameOrderIn queues; only cross-channel order is absent.
  The reproduction preserves each channel's order and uses no shim at all.
- **Other branches:** the original empty-batch reproduction has valid settings, zero
  records/tracks and no arithmetic, so only the order condition can fail. The overtaken
  initial-delta fixture isolates FULL|RESET. New tests distinguish SETTINGS,
  DELTA_BASELINE (kind mismatch), CAPACITY and ARITHMETIC. No evidence supports those
  alternative branches in round 4; the old telemetry cannot conclusively exclude them.

## Choice and guarantees

1. **Client reorder buffering only during the switch:** rejected. Server ticks may
   legitimately be omitted before baseline advancement; the old wire cannot tell a
   missing predecessor from a legal sender gap. Sorting only once a lower tick arrives
   is too late: a delta may already have corrupted a baseline and claimed H. A safe
   version needs an explicit predecessor plus bounded buffering, timeout and transport
   switch visibility; restart is smaller and does not hold an unknown damaged prefix.
2. **C2S fresh-stream restart:** implemented, with an explicit predecessor check so
   detection is before delta decode. Exactly one restart request and one accepted
   restart per connection is a hard rate limit. A persistent fault remains undriven
   until reconnect. Server-issued fresh epoch and FULL|RESET re-establish all bases;
   old epoch traffic cannot overwrite it. Normal silence recovery still keeps bases
   and does not request a restart. No arbitrary “latest full” clears a broken stream.
3. **Pin all snapshots to one channel from the first batch:** no supported pinned API
   does this. Before multichannel enables, encode0 ignores the selected order channel.
   After enabling, identifier2channel value **0** falls through to the class handler
   (`getChannelOverride` 38–59), which returns 3 for S2C custom payloads. Nonzero 2/3
   still changes from initial 0; -1/-2 change reliability, not ordered channel 0. A codec
   patch/custom handler on the sender could force 0, but production sender is Velocity,
   not the TCP backend; a client/Fabric-only integration does not fix production.
   This solution needs neither proxy modification nor a Raknetify fork.

Preserve native pairing/subscription identities, target/vehicle epochs, committed pose
history, connection ticket sequence high-water, receipt H high-water and refresh-rate
history on server restart. Clear observer send log/seeds/reset metadata/baselines and
pending ticket/action; next END tick emits FULL|RESET. Preserve the connection's last
send tick to forbid a second batch in the same tick. Advancing launch recipes retain
their existing metadata/history semantics. Client atomically validates every new-stream
record before clearing old decode state, discards retired pending entries before applying the
capacity bound to the fresh set, retains native liveness/kind checks, resets the
clock and rewarms. Delayed retired-epoch packets change neither receipt nor H. An empty
first batch is valid. A corrupt restart cannot borrow old bases or trigger another request.

First protocol defect per connection emits one WARN with branch/detail/epoch/batch tick/
H/predecessor/record count. `netstate.protocolDefectReason` retains the last reason across recovery.
`bf bodypose status` includes client-report counters `snapshotProtocolDefects:<reason>`,
`snapshotRestarts`, `snapshotRestartRejected:stream|disabled|limit`; these count accepted
reports/restart events, not independent server detection of client delivery order.

## Exact r9 amendment text

The following is the proposed normative delta to workspace `p3-light-spec.md` r8;
that coordinator-owned file is not edited by this unit.

> **§2.2(1), header and identity.** Insert `previousTickPlusOne uvarlong` immediately
> after `observerStreamEpoch`. It is zero on the first actual send in a stream, otherwise
> the previous actually sent batch's absolute server tick plus one, including empty
> batches. A sender omission advances neither predecessor nor baseline. The stream
> identity changes on a new play connection or one accepted `snapshot_restart` request;
> respawn/dimension transitions alone do not change it. Ticks remain strictly increasing.
>
> **§2.2(3), prefix validation and restart.** Before decoding any records, require zero
> predecessor for the first batch and `previousTickPlusOne == H + 1` thereafter, as well
> as unchanged stream identity and increasing tick. Failure disables driving without
> advancing H or any baseline. No damaged prefix may be claimed and no baseline guessed.
> The client may send exactly one `blockfield:snapshot_restart` per play connection:
> `observerStreamEpoch uvarlong, reason u8`, exact exhaustion. Epoch is a canonical
> nonnegative uvarlong. Reason values are 0 STREAM_ORDER, 1 SETTINGS, 2 PAIRING_FULL,
> 3 DELTA_BASELINE, 4 CAPACITY, 5 ARITHMETIC; other values are rejected. The server accepts
> only its current epoch, at most once per play connection, and never restarts a stream
> it disabled for capacity/encoding. An accepted request issues a fresh server epoch,
> clears the observer's send log/seeds/reset metadata/baselines and pending ticket/action,
> retains native pairings/history/replay and refresh-rate high-waters, and sends a new
> first batch (predecessor zero), every record FULL|RESET, at the next END tick. Never
> send twice in one tick. The client ignores the retired epoch, accepts the new epoch
> only while awaiting its requested restart, requires first predecessor zero and
> FULL|RESET on every record, applies it atomically, resets presentation and rewarms
> before driving/sending views. The last valid H remains unchanged until that full batch
> applies. A second defect or failed restart remains disabled until a fresh connection.
>
> **§2.4(4)/§2.5(2).** Silence/gap recovery retains valid decode baselines as before;
> only a protocol defect invokes the one-shot restart above. Native pending/lifetime
> handling stays independent of snapshot channel arrival. The old “no resync request
> exists” statements apply only to ordinary silence recovery, not protocol defects.
>
> **§3.1.** CombatView sequence remains strictly increasing for the whole play connection,
> including stream restart; restarting a stream does not reopen replay/refresh budgets.
> Old-stream tickets cannot bind after a restart. The restart payload is control traffic,
> not an attack/refresh and requires no adjacency to combat tickets.
>
> **§4.4/§4.5.** Log one WARN for the first client protocol defect per connection with
> branch/reason, stream epoch, batch tick, last applied H and record count. Expose last
> `protocolDefectReason` through testbot netstate, retaining it after recovery. Count
> `snapshotProtocolDefects:<reason>` (accepted client reports), `snapshotRestarts`, and
> `snapshotRestartRejected:stream|disabled|limit` in `bf bodypose status`.
>
> **§8 gate 7 / §12.** Include a deterministic pinned Fabric AND Velocity startup-switch
> fixture: channel-0 queued snapshots, channel-7 marker ACK, then channel-3 snapshots;
> deliver later-channel batches first while preserving each channel's order. Assert no
> wrong-base decode or damaged-prefix H, one restart, fresh FULL|RESET epoch, rewarming,
> old-epoch rejection and permanent disable on a second defect. Live RakNet reruns must
> capture reason/restart counters and resumed validated views. Header cost increases by
> 1–9 bytes/batch (normally 2–3 bytes, about 40–60 B/s); record codec and bandwidth gate
> are unchanged. PROTOCOL remains 5 because the combined phase-2/3 pair is unreleased.

## Validation

All required commands ran with JAVA_HOME pointing to workspace JDK 21.0.12+8;
Gradle commands held `.dev/coord-20261009/heavy.lock` directly with `flock`, always
`--no-daemon --max-workers=2`. Final results:

| Command | Result / counts |
| --- | --- |
| `just format` | PASS, Spotless applied; 51 Python files already formatted. |
| `just check` | PASS, actionlint/Ruff/shellcheck/format checks; 51 Python files unchanged. |
| `just test` | PASS, 23 rig + 40 isolated Python tests; 80 RoomClosePolicy assertions. |
| `just java-check` | PASS, Spotless/lint/build; 467 client JUnit tests (2 expected skips), 1,475 server JUnit tests, 2 allocation tests, 12 native GameTests; zero failures. |

Baseline-only reproduction: 3/3 passed; focused reproduction/recovery run: 11/11 passed.
Logs: worktree `.gradle/{format,check,test,java-check,reproduction}.log`; original-buffer
JUnit evidence `.gradle/raknet-evidence/baseline-reproduction.xml`. The required Java
check includes the repository's short-lived native GameTest server in the worktree;
it exited normally. No standalone dev server, game client or live stand was started.

Live RakNet ±15 ms profiles and production proxy gameplay are intentionally left to the
coordinator's next owned round. No live-run success is claimed by unit/bytecode tests.
