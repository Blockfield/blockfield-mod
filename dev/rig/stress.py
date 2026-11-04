#!/usr/bin/env python3
"""Server stress test with Carpet fake players on an isolated copy of the rig server (see README, "Стресс-тест").

  stress.py server init            copy ~/.cache/bf-test-server to /var/tmp/bf-stress-server: ports 25610/RCON 25576,
                                   max-players 64, Carpet, long campaign balance (3000 tickets, 1 per death)
  stress.py server start [jar]     start it through agent-process (optionally with this blockfield-server jar)
  stress.py server stop
  stress.py hub 10,25,50           bots walk around the hub; one metrics line per stage
  stress.py combat [--campaign 32] [--dm 18] [--guns] [--window 120] [--jfr FILE]
                                   campaign room (squads rush capture points) + deathmatch room; bots chase the
                                   nearest enemy, --guns makes them fire TACZ through /bf bot fire; dead bots rejoin
  stress.py jfr FILE [thread]      server-thread CPU by mod and method (intermediary names mapped to Mojang)

Needs a server jar with /bf action and /bf bot (blockfield-server 1.22.1+). Carpet removes a fake player when it
dies, so the driver respawns and re-deploys it; kills and captures are counted from the server log.
"""

import argparse
import collections
import json
import os
import random
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

RIG = Path(__file__).resolve().parent
sys.path.insert(0, str(RIG))
from rcon import Rcon  # noqa: E402
import process_guard  # noqa: E402

SRC = Path.home() / ".cache/bf-test-server"
DIR = Path("/var/tmp/bf-stress-server")
LOG = DIR / "server.out"
NAME = "bf-stress-server"
PORT, RCON_PORT = 25610, 25576
AGENT_PROCESS = str(process_guard.HELPER)
JAVA_HOME = subprocess.run(
    ["mise", "where", "java@temurin-21.0.12+101.0.LTS"], capture_output=True, text=True
).stdout.strip()
CARPET = (
    "https://api.modrinth.com/v2/version/f2mvlGrg",
    "fabric-carpet-1.21-1.4.147+v240613.jar",
)
HZ = os.sysconf("SC_CLK_TCK")
BOT_TAG = "bfbot"


# ------------------------------------------------------------------ server


def set_properties(path, values):
    lines = path.read_text().splitlines()
    keys = {line.split("=", 1)[0] for line in lines}
    lines = [
        f"{k}={values[k]}" if (k := line.split("=", 1)[0]) in values else line
        for line in lines
    ]
    lines += [f"{k}={v}" for k, v in values.items() if k not in keys]
    path.write_text("\n".join(lines) + "\n")


def server_init():
    if DIR.exists():
        sys.exit(
            f"{DIR} exists; remove it to start from a fresh copy of the rig server"
        )
    subprocess.run(["cp", "-a", "--reflink=auto", str(SRC), str(DIR)], check=True)
    for stale in ("world/session.lock", "server.out", ".blockfield-ready.json"):
        (DIR / stale).unlink(missing_ok=True)
    # voicechat port=-1 follows the game port, so a second server does not collide with the rig one.
    set_properties(
        DIR / "server.properties",
        {
            "server-port": PORT,
            "rcon.port": RCON_PORT,
            "enable-rcon": "true",
            "max-players": 64,
            "enable-query": "false",
        },
    )
    meta = json.load(
        urllib.request.urlopen(
            urllib.request.Request(CARPET[0], headers={"User-Agent": "blockfield-rig"})
        )
    )
    jar = urllib.request.urlopen(
        urllib.request.Request(
            meta["files"][0]["url"], headers={"User-Agent": "blockfield-rig"}
        )
    ).read()
    import hashlib

    if hashlib.sha512(jar).hexdigest() != meta["files"][0]["hashes"]["sha512"]:
        sys.exit("Carpet download hash mismatch")
    (DIR / "mods" / CARPET[1]).write_bytes(jar)
    settings = DIR / "blockfield-admin/settings.json"
    if settings.exists():
        data = json.loads(settings.read_text())
        data.setdefault("campaignBalance", {}).setdefault("*", {}).update(
            {
                "min_players_per_team": 1,
                "max_tickets": 3000,
                "death_ticket_cost": 1,
                "preparation_time_seconds": 10,
                "ready_countdown_seconds": 2,
                "ready_timeout_seconds": 20,
                "transition_time_seconds": 30,
                "ticket_bleed_multiplier": 1,
                "ticket_bleed_timer_seconds": 60,
            }
        )
        settings.write_text(json.dumps(data, ensure_ascii=False, indent=1))
    print(f"{DIR}: port {PORT}, RCON {RCON_PORT}, Carpet, long campaign balance")


def registered():
    rows = subprocess.run(
        [AGENT_PROCESS, "list"], capture_output=True, text=True, check=True
    ).stdout.splitlines()[1:]
    return [
        r.split()
        for r in rows
        if len(r.split()) >= 5 and r.split()[1] == "yes" and r.split()[3] == NAME
    ]


def server_pid():
    rows = registered()
    if len(rows) != 1:
        sys.exit(f"expected one live {NAME}, found {len(rows)}")
    return int(rows[0][2])


def server_start(jar):
    if registered():
        sys.exit(f"{NAME} is already running")
    if jar:
        # One fixed name: a copy under its own name would pile up next to the previous one and Fabric loads either.
        for old in (DIR / "mods").glob("blockfield-server*.jar"):
            old.unlink()
        shutil.copy(jar, DIR / "mods" / "blockfield-server-stress.jar")
    LOG.write_text("")
    cmd = [
        f"{JAVA_HOME}/bin/java",
        "-Xms1G",
        "-Xmx2G",
        "-XX:+UseG1GC",
        "-DBLOCKFIELD_MAPS_DIR=maps",
        "-jar",
        "fabric-server-launch.jar",
        "nogui",
    ]
    env = {**os.environ, "BLOCKFIELD_MAPS_DIR": "maps"}
    subprocess.run(
        [
            AGENT_PROCESS,
            "start",
            "--name",
            NAME,
            "--owner",
            process_guard.owner(),
            "--cwd",
            str(DIR),
            "--log",
            str(LOG),
            "--",
            *cmd,
        ],
        check=True,
        env=env,
        stdout=subprocess.DEVNULL,
    )
    for _ in range(120):
        if "Done (" in LOG.read_text(errors="replace"):
            print(f"{NAME} ready: 127.0.0.1:{PORT}, pid {server_pid()}")
            return
        time.sleep(2)
    sys.exit("server did not start in 4 min")


def server_stop():
    for row in registered():
        subprocess.run(
            [AGENT_PROCESS, "stop", "--owner", process_guard.owner(), row[0]],
            check=True,
        )
    for _ in range(
        30
    ):  # the registry may still list the process as live right after stop
        if not registered():
            return
        time.sleep(1)


# ------------------------------------------------------------------ metrics


def meminfo():
    avail = (
        int(
            next(
                line
                for line in open("/proc/meminfo")
                if line.startswith("MemAvailable")
            ).split()[1]
        )
        // 1024
    )
    full = float(
        open("/proc/pressure/memory").read().splitlines()[1].split()[1].split("=")[1]
    )
    return avail, full


def cpu_ticks(pid):
    fields = open(f"/proc/{pid}/stat").read().rsplit(")", 1)[1].split()
    return int(fields[11]) + int(fields[12])


def rss(pid):
    status = open(f"/proc/{pid}/status").read()
    return tuple(
        int(re.search(k + r":\s+(\d+)", status).group(1)) // 1024
        for k in ("VmRSS", "VmSwap")
    )


def heap(pid):
    out = subprocess.run(
        [f"{JAVA_HOME}/bin/jcmd", str(pid), "GC.heap_info"],
        capture_output=True,
        text=True,
    ).stdout
    m = re.search(r"total (\d+)K, used (\d+)K", out)
    return (int(m.group(2)) // 1024, int(m.group(1)) // 1024) if m else (-1, -1)


def tick_query(r):
    """[avg, P50, P95, P99] ms from vanilla /tick query (decimal comma; the line also has 'Target: 50,0ms')."""
    out = r.cmd("tick query") or ""
    found = [
        re.search(label + r"\s*(\d+(?:,\d+)?)ms", out)
        for label in ("per tick:", "P50:", "P95:", "P99:")
    ]
    return [float(m.group(1).replace(",", ".")) for m in found] if all(found) else []


class Window:
    """Samples tick times over a window, then reports server CPU/memory and what the log said meanwhile."""

    def __init__(self, r, pid):
        self.r, self.pid, self.samples = r, pid, []
        self.log_start = len(LOG.read_text(errors="replace").splitlines())
        self.cpu0, self.t0 = cpu_ticks(pid), time.time()

    def run(self, seconds):
        while time.time() - self.t0 < seconds:
            if q := tick_query(self.r):
                self.samples.append(q)
            time.sleep(5)
        return self

    def report(self, **extra):
        new = LOG.read_text(errors="replace").splitlines()[self.log_start :]
        s = self.samples or [[-1] * 4]
        used, total = heap(self.pid)
        resident, swap = rss(self.pid)
        avail, full = meminfo()
        row = dict(
            **extra,
            mspt_avg=round(sum(x[0] for x in s) / len(s), 1),
            p50=max(x[1] for x in s),
            p95=max(x[2] for x in s),
            p99=max(x[3] for x in s),
            cores=round(
                (cpu_ticks(self.pid) - self.cpu0) / HZ / (time.time() - self.t0), 2
            ),
            kills=sum(" убит " in line for line in new),
            captures=sum("захвачена командой" in line for line in new),
            rejected_shots=sum("shot rejected" in line for line in new),
            heap_used=used,
            heap_total=total,
            rss=resident,
            swap=swap,
            host_avail=avail,
            psi_full=full,
        )
        print(json.dumps(row, ensure_ascii=False), flush=True)
        return row


# ------------------------------------------------------------------ bots


def online(r):
    return {
        n.strip().lower()
        for n in (r.cmd("list") or ":").split(":", 1)[1].split(",")
        if n.strip()
    }


def spawn(r, names):
    for b in names:
        r.cmd(f"player {b} spawn")
    time.sleep(2.5)  # Carpet resolves the profile asynchronously
    for b in names:
        r.cmd(f"tag {b} add {BOT_TAG}")


def walk(r, b, sprint):
    for action in ("move forward", "jump interval 40", "attack interval 8") + (
        ("sprint",) if sprint else ()
    ):
        r.cmd(f"player {b} {action}")


def open_room(r, first, mode, map_id):
    """The first bot creates the room with /bf play; its id comes from the server log."""
    before = len(LOG.read_text(errors="replace").splitlines())
    r.cmd(f"execute as {first} run bf play {mode} {map_id}".strip())
    for _ in range(40):
        for line in LOG.read_text(errors="replace").splitlines()[before:]:
            if m := re.search(r"Комната (\S+) открыта", line):
                return m.group(1)
        time.sleep(0.5)
    sys.exit(f"room {mode} {map_id} did not open")


def join(r, names, room):
    for b in names:
        r.cmd(f"bf action {b} join:{room}")
        r.cmd(f"bf action {b} class:assault:0")
        r.cmd(f"execute as {b} run ready")


def dimension(r, b):
    out = r.cmd(f"data get entity {b} Dimension") or ""
    m = re.search(r'"([^"]+)"', out)
    return m.group(1) if m else ""


def points_of(dim):
    """Capture points of the campaign map behind a room dimension like blockfield:berdichev_1."""
    map_id = re.sub(r"_\d+$", "", dim.split(":", 1)[-1])
    data = json.loads((DIR / "blockfield/maps" / f"{map_id}.json").read_text())
    return [(p["x"], p["y"], p["z"]) for p in data["mode_data"]["campaign"]["points"]]


# ------------------------------------------------------------------ scenarios


def hub(stages, settle):
    pid, r = server_pid(), Rcon(port=RCON_PORT)
    bots = []
    stop = threading.Event()

    def turn():
        c = Rcon(port=RCON_PORT)
        while not stop.wait(3):
            for b in list(bots):
                c.cmd(f"player {b} turn {random.choice(['left', 'right', 'back'])}")

    threading.Thread(target=turn, daemon=True).start()
    for n in stages:
        avail, full = meminfo()
        if avail < 700 or full > 25:
            print(
                f"host guard: MemAvailable {avail}M, PSI full {full}% -> stop before {n}"
            )
            break
        new = [f"bot{i:02d}" for i in range(len(bots) + 1, n + 1)]
        spawn(r, new)
        for b in new:
            walk(r, b, sprint=False)
        bots += new
        time.sleep(settle)
        Window(r, pid).run(20).report(scenario="hub", players=len(online(r)))
    stop.set()


def combat(args):
    pid, r = server_pid(), Rcon(port=RCON_PORT)
    camp = [f"bot{i:02d}" for i in range(1, args.campaign + 1)]
    dm = [f"bot{i:02d}" for i in range(args.campaign + 1, args.campaign + args.dm + 1)]
    have = online(r)
    spawn(r, [b for b in camp + dm if b not in have])
    rooms = {}
    if camp:
        rooms["camp"] = open_room(r, camp[0], "room", args.campaign_map)
        join(r, camp[1:], rooms["camp"])
        r.cmd(f"bf action {camp[0]} class:assault:0")
        r.cmd(f"execute as {camp[0]} run ready")
    if dm:
        rooms["dm"] = open_room(r, dm[0], "deathmatch", args.dm_map)
        join(r, dm[1:], rooms["dm"])
        r.cmd(f"bf action {dm[0]} class:assault:0")
    room_of = {
        **{b: rooms.get("camp") for b in camp},
        **{b: rooms.get("dm") for b in dm},
    }
    deadline = time.time() + 90
    camp_dim = ""
    while camp and time.time() < deadline and not camp_dim.startswith("blockfield:"):
        r.cmd("bf action @a spawn:BASE")
        camp_dim = dimension(r, camp[0])
        time.sleep(3)
    points = points_of(camp_dim) if camp_dim.startswith("blockfield:") else []
    print(
        f"rooms {rooms}, campaign in {camp_dim or '-'}, {len(points)} points",
        flush=True,
    )
    for b in camp + dm:
        walk(r, b, sprint=not args.guns)
    stop = threading.Event()
    rejoined = [0]

    def loop():
        c, n = Rcon(port=RCON_PORT), 0
        while not stop.wait(1.5):
            n += 1
            # tp ... facing is computed from the bot's feet: aim feet-to-feet so shots leave the eyes level.
            c.cmd(
                f"execute as @a[tag={BOT_TAG},team=RedTeam] at @s run tp @s ~ ~ ~ facing entity @p[team=BlueTeam] feet"
            )
            c.cmd(
                f"execute as @a[tag={BOT_TAG},team=BlueTeam] at @s run tp @s ~ ~ ~ facing entity @p[team=RedTeam] feet"
            )
            c.cmd(
                f"execute as @a[tag={BOT_TAG},team=!RedTeam,team=!BlueTeam] at @s "
                f"run tp @s ~ ~ ~ facing entity @p[distance=0.5..] feet"
            )
            if n % 2 == 0:
                c.cmd("bf action @a spawn:BASE")
                c.cmd("bf action @a spawn:any")
            if (
                points and n % 7 == 0
            ):  # a quarter of the campaign bots rushes a random point
                x, y, z = random.choice(points)
                for b in random.sample(camp, max(1, len(camp) // 4)):
                    c.cmd(
                        f"execute in {camp_dim} if entity {b} run tp {b} "
                        f"{x + random.uniform(-3, 3):.1f} {y + 1} {z + random.uniform(-3, 3):.1f}"
                    )
            if args.guns and n % 10 == 1:
                c.cmd(f"bf bot fire @a[tag={BOT_TAG}] 20")
            if n % 10 == 0:
                missing = [b for b in camp + dm if b not in online(c)]
                if missing:
                    spawn(c, missing)
                    for b in missing:
                        join(c, [b], room_of[b])
                        walk(c, b, sprint=not args.guns)
                    rejoined[0] += len(missing)

    threading.Thread(target=loop, daemon=True).start()
    if args.jfr:
        subprocess.run(
            [
                f"{JAVA_HOME}/bin/jcmd",
                str(pid),
                "JFR.start",
                "name=stress",
                f"delay={args.warmup}s",
                f"duration={args.window}s",
                "settings=profile",
                f"filename={args.jfr}",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
    time.sleep(args.warmup)
    rejoined[0] = 0
    if args.guns:
        r.cmd("bf bot stats")  # reset counters to the window
    w = Window(r, pid).run(args.window)
    gunner = (r.cmd("bf bot stats") or "").strip() if args.guns else ""
    stop.set()
    w.report(
        scenario="combat",
        guns=args.guns,
        campaign=len(camp),
        deathmatch=len(dm),
        online=len(online(r)),
        rejoined=rejoined[0],
        gunner=gunner,
    )


# ------------------------------------------------------------------ JFR


def mojang_names():
    """intermediary -> named for net.minecraft classes/methods, from the loom mappings of the mod build."""
    tiny = next(
        iter(
            sorted(
                Path.home().glob(".gradle/caches/fabric-loom/1.21.1/*/mappings.tiny")
            )
        ),
        None,
    )
    names = {}
    if tiny:
        for line in tiny.read_text().splitlines():
            f = line.split("\t")
            if f[0] == "c" and len(f) >= 4:
                names[f[2]] = f[3].rsplit("/", 1)[-1]
            elif len(f) >= 6 and f[1] == "m":
                names[f[4]] = f[5]
    return names


def jfr(path, thread):
    names = mojang_names()
    rename = lambda s: re.sub(
        r"(class_\d+|method_\d+)",
        lambda m: names.get(
            m.group(1)
            if m.group(1).startswith("method")
            else f"net/minecraft/{m.group(1)}",
            m.group(1),
        ),
        s,
    )
    data = json.loads(
        subprocess.run(
            [
                f"{JAVA_HOME}/bin/jfr",
                "print",
                "--json",
                "--stack-depth",
                "96",
                "--events",
                "jdk.ExecutionSample",
                path,
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    )
    owner_of = lambda frame: (
        frame.split("/")[2]
        if frame.startswith(("com/", "net/fabricmc", "io/", "me/", "dev/"))
        and frame.count("/") > 2
        else "minecraft"
        if frame.startswith("net/minecraft")
        else frame.split("/")[0]
    )
    self_c, incl, owners, n = (
        collections.Counter(),
        collections.Counter(),
        collections.Counter(),
        0,
    )
    for event in data["recording"]["events"]:
        v = event["values"]
        if (v.get("sampledThread") or {}).get("javaName") != thread:
            continue
        frames = [
            f"{f['method']['type']['name']}.{f['method']['name']}"
            for f in v["stackTrace"]["frames"]
        ]
        if not frames:
            continue
        n += 1
        self_c[frames[0]] += 1
        incl.update(set(frames))
        owners[
            next(
                (
                    owner_of(f)
                    for f in frames
                    if owner_of(f)
                    not in ("minecraft", "java", "jdk", "sun", "fabricmc")
                ),
                "minecraft",
            )
        ] += 1
    print(f"{thread}: {n} samples\n-- by innermost mod frame --")
    for k, c in owners.most_common(10):
        print(f"{100 * c / n:5.1f}%  {k}")
    print("-- self --")
    for k, c in self_c.most_common(12):
        print(f"{100 * c / n:5.1f}%  {rename(k)}")
    print("-- inclusive --")
    skip = (
        "java/lang/Thread",
        "net/minecraft/server/MinecraftServer",
        "net/minecraft/server/dedicated",
    )
    for k, c in incl.most_common(45):
        if not k.startswith(skip):
            print(f"{100 * c / n:5.1f}%  {rename(k)}")


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("server")
    s.add_argument("action", choices=("init", "start", "stop"))
    s.add_argument("jar", nargs="?")
    h = sub.add_parser("hub")
    h.add_argument("stages")
    h.add_argument("--settle", type=int, default=45)
    c = sub.add_parser("combat")
    c.add_argument("--campaign", type=int, default=32)
    c.add_argument("--dm", type=int, default=18)
    c.add_argument("--campaign-map", default="")
    c.add_argument("--dm-map", default="dust2")
    c.add_argument("--guns", action="store_true")
    c.add_argument("--window", type=int, default=120)
    c.add_argument("--warmup", type=int, default=40)
    c.add_argument("--jfr")
    j = sub.add_parser("jfr")
    j.add_argument("file")
    j.add_argument("thread", nargs="?", default="Server thread")
    a = p.parse_args()
    if a.cmd == "server":
        {
            "init": server_init,
            "start": lambda: server_start(a.jar),
            "stop": server_stop,
        }[a.action]()
    elif a.cmd == "hub":
        hub([int(x) for x in a.stages.split(",")], a.settle)
    elif a.cmd == "combat":
        combat(a)
    else:
        jfr(a.file, a.thread)


if __name__ == "__main__":
    main()
