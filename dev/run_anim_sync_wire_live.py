"""Live U11 wire runner (ADR4.2 v3 WIRE-GATE, live part).

Coordinator executes this on the U11 stand; this file only plans and probes.
Nothing here starts a server, client, proxy or stand by itself: every live
step shells out to the workspace stand tooling so ownership stays visible.

Usage:
    python dev/run_anim_sync_wire_live.py --dry-run [--out DIR] [--observers N]
    python dev/run_anim_sync_wire_live.py probe-status --host H --port P
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import subprocess
import sys
from pathlib import Path

# Pass thresholds, all from ADR4.2 v3 §12 / §7.4 (review-2 measurement list (d)).
AGGREGATE_MBPS_CAP = 150.0  # safe share of the 300 Mbit/s uplink
WORST_MEASURED_MBPS = 114.7  # 30 diverse combat, offline model
SLOW_LINKS_MBPS = (3, 4, 5, 10)
BELOW_FLOOR_MBPS = 3  # per-client floor is ~4 Mbit/s; this link must trip §7.4
BACKLOG_HOLD_S = 60
PROBE_FIRE_S = 2.0  # oldest unechoed probe age that fires §7.4
PROBE_SUSPEND_S = 3.0  # continuous fire before suspend
PROBE_RESUME_S = 0.5  # oldest unechoed probe age that resumes FULL
VELOCITY_MEM_MB_CAP = 192  # heap and direct memory each, MEMORY=256m
MAIN_SHARE_MS_CAP = 10.0  # whole END-tick animation share on the main thread
FPS_LEVELS = (30, 60, 144, 240)
COMPRESSION_THRESHOLD = -1  # backend network-compression-threshold, image ENV

# step id -> (title, command, metric source, pass threshold, evidence file)
STEPS: tuple[tuple[str, str, str, str, str, str], ...] = (
    (
        "L0-preconditions",
        "backend compression off; RakNet-only proxy",
        "just server start # U11 stand, game + Workshop behind Velocity; "
        "grep network-compression-threshold /data/server.properties",
        "game log + `docker logs velocity`",
        "threshold -1 on both backends; "
        "'Preventing vanilla compression' once per RakNet login",
        "l0-preconditions.txt",
    ),
    (
        "L1-aggregate",
        "30 observers mixed/diverse vs 150 Mbit/s",
        "just play --headless # 30 observers, mixed then diverse loadouts; "
        "capture per-observer KB/s from Velocity RakNet channel counters",
        "Velocity per-channel byte counters x30, 60 s window",
        "aggregate <= 150 Mbit/s (offline worst 114.7)",
        "l1-aggregate.json",
    ),
    (
        "L2-bursts",
        "FULL and JOIN bursts",
        "JOIN 5 observers at once during combat; record FULL + FULL+3 "
        "timing-seed transactions per observer",
        "client wire log (fragment headers, decodedLength)",
        "JOIN pair ~76 KB on link within 150 ms at 30 mixed; "
        "no gap/repeat defect (r10 only)",
        "l2-bursts.json",
    ),
    (
        "L3-fire300",
        "sustained fire 300/s",
        "just scenario fire --rate 300 --window 60 # or testbot round-robin; "
        "capture capsules per observer tick",
        "client wire log + Velocity counters",
        "per-observer delta vs idle within +15% (model 401->448 KB/s); "
        "capsule completion p95 < 50 ms at 5 Mbit/s",
        "l3-fire300.json",
    ),
    (
        "L4-slow",
        "slow links 4/5/10 Mbit/s HOL + start_late_unavailable",
        "shape one observer link: "
        "agent-sudo tc qdisc replace dev <iface> root tbf rate <R>mbit "
        "burst 32kbit latency 100ms  # R in 4 5 10; repeat per rate",
        "client applied-report log (start_late_unavailable), probe RTT",
        "record start_late_unavailable rate per rate; "
        "fold-burst HOL visible, D unaffected",
        "l4-slow-<rate>mbit.json",
    ),
    (
        "L5-backlog",
        "below-floor link, §7.4 fire/suspend/resume",
        "agent-sudo tc qdisc replace dev <iface> root tbf rate 3mbit "
        "burst 32kbit latency 100ms  # hold 60 s, then `tc qdisc del`",
        "server link_capacity_unavailable counter; `docker stats velocity` heap/direct",
        "fires (>2 s probe age), suspends (3 s), resumes FULL (<0.5 s); "
        "heap and direct each <= 192 MB with 30 observers",
        "l5-backlog.json",
    ),
    (
        "L6-switch",
        "JOIN and server switch with fragments in flight",
        "JOIN one observer mid-fold-burst; switch game<->Workshop backend "
        "mid-burst; check client assembly state",
        "client wire receiver log",
        "in-flight assemblies discarded, r10 restart, "
        "no cross-backend table resolution",
        "l6-switch.json",
    ),
    (
        "L7-fps",
        "native timing at 30/60/144/240 FPS",
        "cap observer client FPS per level; sample native remote-pose "
        "timestamps vs R/A clocks for 60 s each",
        "client timing log (D, freeze, applied reports)",
        "zero added frames (§9); no start_late_unavailable regression vs L1",
        "l7-fps-<fps>.json",
    ),
    (
        "L8-cpu",
        "Velocity + game CPU/heap/direct, compression off",
        "30 mixed combat steady 120 s: `docker stats` + game `jstat -gc` "
        "+ main-thread END-tick animation share from server timings log",
        "docker stats, jstat, server timings",
        "main-thread END-tick share <= 10 ms/tick (else off-thread "
        "enqueue per §7.5); TPS 20.0",
        "l8-cpu.json",
    ),
    (
        "L9-tcp",
        "TCP login refused, TCP status ping answered",
        "python dev/run_anim_sync_wire_live.py probe-status "
        "--host <proxy> --port <mc-status-port>; "
        "attempt TCP login, expect refusal before play",
        "probe stdout + proxy log",
        "status answers; login never reaches play on TCP",
        "l9-tcp.json",
    ),
    (
        "L10-pack",
        "capture during concurrent pack download",
        "start full pack download from the home link during L1 capture; "
        "record exact-stream aggregate in parallel",
        "Velocity counters + download client log",
        "exact stream keeps its 150 Mbit/s share, else pack egress is "
        "rate-limited or moved to R2 (pack owner decides)",
        "l10-pack.json",
    ),
)


def tc_shape_cmd(iface: str, rate_mbit: int) -> list[str]:
    """Exact tc command for a shaped observer link (runs via agent-sudo)."""
    return [
        "tc",
        "qdisc",
        "replace",
        "dev",
        iface,
        "root",
        "tbf",
        "rate",
        f"{rate_mbit}mbit",
        "burst",
        "32kbit",
        "latency",
        "100ms",
    ]


def tc_clear_cmd(iface: str) -> list[str]:
    return ["tc", "qdisc", "del", "dev", iface, "root"]


def evidence_path(out: Path, step: str, rate: int | None = None) -> Path:
    name = step if rate is None else step.replace("<rate>", str(rate))
    return out / name


def build_plan(out: Path, observers: int) -> list[dict[str, str]]:
    plan = []
    for step, title, cmd, source, threshold, evidence in STEPS:
        plan.append(
            {
                "step": step,
                "title": title,
                "command": cmd,
                "metric": source,
                "threshold": threshold,
                "evidence": str(evidence_path(out, evidence)),
                "observers": str(observers),
            }
        )
    return plan


def validate(out: Path, observers: int) -> list[str]:
    errors = []
    if observers < 2 or observers > 64:
        errors.append(f"observers must be 2..64, got {observers}")
    if not STEPS:
        errors.append("no live steps defined")
    ids = [s[0] for s in STEPS]
    if len(set(ids)) != len(ids):
        errors.append("duplicate step ids")
    for want in ("L0", "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10"):
        if not any(i.startswith(want) for i in ids):
            errors.append(f"live item {want} has no step")
    try:
        out.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        errors.append(f"cannot create out dir {out}: {exc}")
    return errors


def probe_status(host: str, port: int, timeout: float = 5.0) -> dict[str, object]:
    """Minimal SLP status ping (stdlib only); True answer means ping served."""
    payload = len(host.encode())
    handshake = (
        b"\x00\x00"
        + _varint(payload)
        + host.encode()
        + struct.pack(">H", port)
        + b"\x02"
    )
    request = b"\x01\x00"
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(_frame(handshake) + _frame(request))
        data = sock.recv(65536)
    return {"answered": len(data) > 0, "bytes": len(data)}


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        bits = value & 0x7F
        value >>= 7
        if value:
            out.append(bits | 0x80)
        else:
            out.append(bits)
            return bytes(out)


def _frame(packet: bytes) -> bytes:
    return _varint(len(packet)) + packet


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--out", default="build/wire-live", type=Path)
    parser.add_argument("--observers", default=30, type=int)
    sub = parser.add_subparsers(dest="probe")
    status = sub.add_parser("probe-status")
    status.add_argument("--host", required=True)
    status.add_argument("--port", required=True, type=int)
    args = parser.parse_args(argv)

    if args.probe == "probe-status":
        print(json.dumps(probe_status(args.host, args.port), indent=2))
        return 0

    errors = validate(args.out, args.observers)
    plan = build_plan(args.out, args.observers)
    if args.dry_run:
        print(f"live U11 wire plan: {len(plan)} steps, {args.observers} observers")
        print(f"backend compression threshold: {COMPRESSION_THRESHOLD}")
        print(f"aggregate cap: {AGGREGATE_MBPS_CAP} Mbit/s")
        for item in plan:
            print(f"== {item['step']}: {item['title']}")
            print(f"   command:   {item['command']}")
            print(f"   metric:    {item['metric']}")
            print(f"   threshold: {item['threshold']}")
            print(f"   evidence:  {item['evidence']}")
        if errors:
            print("ERRORS:", file=sys.stderr)
            for error in errors:
                print(f"  - {error}", file=sys.stderr)
            return 2
        return 0

    # Live execution (coordinator on the U11 stand): run each step's command
    # shell and store stdout as its evidence file.
    args.out.mkdir(parents=True, exist_ok=True)
    failures = 0
    for item in plan:
        dest = Path(item["evidence"])
        dest.parent.mkdir(parents=True, exist_ok=True)
        proc = subprocess.run(
            item["command"], shell=True, capture_output=True, text=True
        )
        dest.write_text(proc.stdout + proc.stderr)
        if proc.returncode != 0:
            failures += 1
            print(f"FAIL {item['step']} (rc={proc.returncode})", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
