# AnimSync wire gate (ADR4.2 v3)

Release gate for the interned, fragmented exact animation stream (§12).
Sections are owned per part: «Offline harness» (G1), «Raknetify codec
fixture» (G2), «Live U11 runner» (G3, below).

## Offline harness

Owned by G1.

Method: `dev/run_anim_sync_wire_matrix.sh` (7 fixture JVMs, 18 cohorts,
60 ticks each) against the integrated production code. The harness drives
production delivery: one bulk `AnimSyncStream.prepare` over the whole eligible
set per observer per tick, the tick's frozen shared encodings via
`AnimSyncStream.share`, and one production `AnimSyncStream.encode` whose
transactions are the measured wire bytes and fragment counts
(`SnapshotStream.exact` order, without network sends). The removed
harness-only emulation is the old per-target prepare+merge order. The
prototype interning hook stays as the differential oracle: every production
body must equal it byte for byte (`WireDifferential`), and every capsule,
fragment batch, expansion and decode is equality-checked.

Gate: `dev/anim_sync_wire_gate.py`, run by `just test` after the U10 report.
It checks fresh `server/build/anim-sync-wire-runs/*.json` (HEAD source only;
stale runs fall back to pinned) or the pinned
`docs/review/wire-gate-evidence.json.gz`, and fails on any steady/burst
regression against the ADR4.2 bounds, on `byteEqualityChecks == 0`, on missing
or boundless cohorts, on >25 fragments, on C2S >11 fragments / 30 000 B, on a
30-player uplink share >140 Mbit/s, or on an END-tick encode p50 >3.0 ms.
Re-pin after a full matrix re-run: `dev/anim_sync_wire_gate.py --pin`.

Re-measured on `fulla/as-wire-gate-h` (source `7622484f`) next to the WIRE-1b
numbers (ADR4.2 v3 §3). 40 359 byte/value equality assertions, 0 rejections.

| 30 players, steady per-observer KB/s | WIRE-1b | re-measured (obs0/obs1) |
|---|---|---|
| rifle idle / combat | 192 / 311 | 192 / 310 |
| mixed idle / combat | 282 / 401 | 281/284 / 400/401 |
| diverse idle / combat | 337 / 478 | 334/341 / 473/481 |
| mixed combat, 100/200/300 FIRE/s | 442 / 442 / 448 | 441 / 441 / 447/448 |

| 30 players, burst link KB (max over observers) | WIRE-1b | re-measured |
|---|---|---|
| mixed FULL cold / timing-seed / fold max | 36.8 / 39.6 / 57.5 | 36.4 / 39.0 / 57.1 |
| diverse FULL cold / timing-seed / fold max | 44.2 / 47.3 / 66.7 | 43.8 / 46.6 / 66.2 |
| rifle FULL cold / timing-seed / fold max | 28.6 / 30.6 / 47.4 | 28.1 / 29.8 / 47.0 |
| warm FULL interned mixed / diverse / rifle | 563 / 563 / 578 | 562.6 / 563.0 / 578.5 |

Fragments per snapshot transaction: steady max 24–25, timing-seed 22,
FULL 18–19, warm FULL 23–24 (24 KiB partition, §6 bound 25).

END-tick production encode per observer: p50 0.25–0.43 ms at 30 players
(§3.5 reuse reference 0.84 ms, no-reuse 5.42 ms); max 6–11 ms on fold ticks.

Uplink share (30 x mean steady): rifle 74.5, mixed 96.1, diverse 114.5,
mixed-300 107.4 Mbit/s — worst within the 150 Mbit/s share (§3.8: 114.7).

C2S tickets: max 18 412 / 21 718 / 25 018 B at 100/200/300 shots/s
(§3.7: 18.4 / 21.7 / 25.0 KB), 2 fragments at 300 (§8 bound 11).

Schema 5 re-record (`fulla/as-stance-ease`, source `916b6516`; STANCE-EASE c2 crouch
triple, +12 B per stance record): the 60 saved blobs re-recorded through the same matrix
harness at generation 1 (fixture `wire-codec-saved-blobs` at `views(1)`; prototype
reconstruction 8706 entries, sha `be90c405…`; recording table 8705, sha `d49d0ebe…`).
Delta vs the WIRE-1b column above: canonical/interned +365 B/tick exact on every 30-player
cohort; steady per-observer stream ±0.5 KB/s (rifle 192.3/310.8, mixed 280.9/284.3 /
400.6/401.5, diverse 334.3/341.8 / 473.7/481.6); bursts −0.2…−0.7 KB; warm FULL +0.7 KB;
fragments identical (steady 24–25, FULL 18–19, warm 23–24); END-tick encode p50 0.26–0.30 ms;
uplink worst 114.6 Mbit/s; C2S tickets identical. Gate `dev/anim_sync_wire_gate.py` green on
the fresh runs.

Fixture assertion review (STANCE-EASE c2c): before c2b, `WireDifferential.snapshot`
compared production interned bytes with each saved blob using `views(0).prototype()`;
afterward the same `Arrays.equals(body, saved)` check uses `views(1).table()` for all 30 pairs.
Canonical expansion and logical decode remain checked; prototype digest/count and its sole unused
LR DefinitionRef twin remain asserted. Verdict: no weaker byte check; only the recording table changed.

## Raknetify codec fixture

Pinned artifacts: `raknetify-velocity-0.1.0+alpha.5.196-all.jar`
(`https://cdn.modrinth.com/data/5IovSY3u/versions/u3LRuC7t/…`,
sha256 `87fc0ca3…0d68` — the exact URL+checksum `distribution/Dockerfile.proxy`
ships) and Velocity 3.5.1 build 615 (proxy `Dockerfile.proxy`). The jar is a test-only
dependency (`server/build.gradle`, through the checksum-verifying `pinnedMod` cache; no network
beyond the build's pinned downloads, no latest/main).

Fixture: `WireGateCodecOracleFixture` (+ `WireGateCodecCapture`), server transformed oracle,
shard 2. It drives the real `MultiChannelingStreamingCompression` handler from the pinned jar —
constructor `(253, 237)` taken from `RakNetConnectionUtil.initChannel` bytecode, real
SERVER_HANDSHAKE/CHANNEL_START exchange over two `EmbeddedChannel`s — with S2C fragments on
order channel 3 and C2S on channel 2, the channels production assigns them
(`CombatChannels.CHANNEL`, `anim-sync-wire-server.md` §channel 3).

- S2C: the 30-player diverse combat cohort (8 END ticks from JOIN FULL, observers on the stream
  table / literal-only / another generation, delivered by production `SnapshotStream.exact`)
  through the continuing channel-3 stream. Every fragment asserts codec byte identity and the
  compressed packet id (237, never the small-message passthrough), including JOIN FULLs above
  the old 256 KiB cap (≥11 fragments). Every completed transaction re-decodes and re-encodes to
  the exact canonical bytes of its source value — identical geometry inputs, i.e. six-box
  equality.
- C2S: production `CombatViewC2SPacket` + `AnimationAppliedC2SPacket` fragments
  (`WireReportVectors.view(7, 400)`, multi-fragment) through channel 2, reassembled and decoded
  back to the equal source view.
- Measured: 472 transactions / 6384 fragments S2C (min JOIN FULL 434 070 B, 18 fragments),
  TICKET+APPLIED 38 723 B each C2S; `codecOutBytesRaw` equals pushed bytes, all pass.

## Live U11 runner

Coordinator-run checklist on the U11 stand. Runner:
`python dev/run_anim_sync_wire_live.py --dry-run` prints the plan and
validates config; without `--dry-run` it executes each step and stores
stdout as the evidence file. Preconditions: paired release deployed server
first with `NETWORK_COMPRESSION_THRESHOLD=-1` on game and Workshop backends
(SERVER-CONFIG), RAKNET-ONLY refusal deployed, RakNet clients through
Velocity only. Out dir: `build/wire-live/`.

| Step | Command | Metric source | Pass threshold (§12) | Evidence |
|---|---|---|---|---|
| L0 preconditions | `just server start` (U11 stand); `grep network-compression-threshold /data/server.properties` on both backends | game log, `docker logs velocity` | -1 on both; "Preventing vanilla compression as streaming compression is enabled" once per RakNet login | l0-preconditions.txt |
| L1 aggregate | `just play --headless`, 30 observers, mixed then diverse | Velocity per-channel counters x30, 60 s | aggregate ≤ 150 Mbit/s (offline worst 114.7) | l1-aggregate.json |
| L2 bursts | JOIN 5 observers at once mid-combat | client wire log (fragment headers) | JOIN pair ~76 KB within 150 ms at 30 mixed; no gap/repeat defect | l2-bursts.json |
| L3 fire 300/s | `just scenario fire --rate 300 --window 60` (or testbot round-robin) | client wire log + Velocity counters | per-observer ≤ +15% vs idle; capsule p95 < 50 ms at 5 Mbit/s | l3-fire300.json |
| L4 slow 4/5/10 | `agent-sudo tc qdisc replace dev <iface> root tbf rate <R>mbit burst 32kbit latency 100ms` per rate | applied-report log (`start_late_unavailable`), probe RTT | rate recorded per link; fold-burst HOL visible, D unaffected | l4-slow-\<rate\>mbit.json |
| L5 backlog §7.4 | same tc at 3 Mbit/s, hold 60 s, then `tc qdisc del` | `link_capacity_unavailable` counter, `docker stats velocity` | fires (> 2 s probe age), suspends (3 s), resumes FULL (< 0.5 s); heap and direct each ≤ 192 MB at 30 observers | l5-backlog.json |
| L6 switch | JOIN mid-fold-burst; game↔Workshop switch mid-burst | client wire receiver log | in-flight assemblies discarded, r10 restart, no cross-backend table resolution | l6-switch.json |
| L7 FPS timing | cap observer FPS at 30/60/144/240, 60 s each | client timing log (D, freeze, applied reports) | zero added frames (§9); no `start_late_unavailable` regression vs L1 | l7-fps-\<fps\>.json |
| L8 CPU/mem | 30 mixed combat steady 120 s: `docker stats`, game `jstat -gc`, server timings | docker stats, jstat, server timings log | main-thread END-tick share ≤ 10 ms/tick (else §7.5 off-thread enqueue); TPS 20.0 | l8-cpu.json |
| L9 TCP | `python dev/run_anim_sync_wire_live.py probe-status --host <proxy> --port <port>`; attempt TCP login | probe stdout, proxy log | status answers; TCP login never reaches play | l9-tcp.json |
| L10 pack | full pack download from home link during L1 capture | Velocity counters + download log | exact stream keeps 150 Mbit/s share, else rate-limit pack or R2 mirror (pack owner) | l10-pack.json |
