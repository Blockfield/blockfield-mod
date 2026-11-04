#!/usr/bin/env python3
"""Hit registration vs ping: the rig client shoots a Carpet bot that sprints across its line of fire.

  hitreg.py setup <dimension> [--room 1]  barrier platform at y=150 in a room dimension, client and bot placed on it
                                       (spawns the bot and deploys it in the room if it is missing)
  hitreg.py run [--shots 40] [--still] [--mode track|flick]
                                       track: testbot keeps the crosshair on the bot every tick and fires "tacz shoot"
                                       (a player following the enemy); flick: "aimshoot" turns and fires in one frame.
                                       A hit = the bot lost health; prints the hit rate as one JSON line

The client connects through delay-proxy.py (one-way delay = half the added RTT) to the stress server
(stress.py, RCON 25576); the bot needs no armour so every hit shows in Health. ADS: hold RMB first
(rig.sh x mousedown 3), testbot "tacz aim" does not ADS on the rig.
"""

import argparse
import json
import re
import socket
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import Rcon  # noqa: E402

BOT, CLIENT, Y = "hitbot", "Player115", 150
SHOOTER = (0.5, Y + 1, -19.5)  # looks +z at the lane z=0.5, 20 blocks away
LANE = (-8, 8)


def bot(cmd, port=47777):
    with socket.create_connection(("127.0.0.1", port), timeout=30) as s:
        s.sendall((cmd + "\n").encode())
        return json.loads(s.makefile().readline())


def health(r):
    m = re.search(r"([\d.]+)f", r.cmd(f"data get entity {BOT} Health") or "")
    return float(m.group(1)) if m else -1.0


def setup(r, dim, room):
    if BOT not in (r.cmd("list") or "").lower():
        r.cmd(f"player {BOT} spawn")
        time.sleep(3)
        r.cmd(f"bf action {BOT} join:{room}")
        r.cmd(f"bf action {BOT} class:assault:0")
        for _ in range(20):
            r.cmd(f"bf action {BOT} spawn:any")
            time.sleep(1.5)
            if dim in (r.cmd(f"data get entity {BOT} Dimension") or ""):
                break
    r.cmd(f"execute in {dim} run fill -12 {Y} -24 12 {Y} 6 minecraft:barrier")
    r.cmd(f"execute in {dim} run fill -12 {Y + 1} -24 12 {Y + 4} 6 minecraft:air")
    # Walls behind the lane and at its ends keep the sprinting bot on the platform; the shooter side stays open.
    r.cmd(f"execute in {dim} run fill -11 {Y + 1} 2 11 {Y + 3} 2 minecraft:barrier")
    r.cmd(f"execute in {dim} run fill -11 {Y + 1} -3 -11 {Y + 3} 2 minecraft:barrier")
    r.cmd(f"execute in {dim} run fill 11 {Y + 1} -3 11 {Y + 3} 2 minecraft:barrier")
    r.cmd(
        f"execute in {dim} run tp {CLIENT} {SHOOTER[0]} {SHOOTER[1]} {SHOOTER[2]} 0 0"
    )
    r.cmd(f"execute in {dim} run tp {BOT} 0.5 {Y + 1} 0.5 90 0")
    for slot in ("armor.head", "armor.chest", "armor.legs", "armor.feet"):
        r.cmd(f"item replace entity {BOT} {slot} with minecraft:air")
    r.cmd(f"effect give {BOT} minecraft:resistance infinite 0 true")
    print(f"platform in {dim}; hold ADS: rig.sh x mousedown 3")


def run(r, shots, still, mode):
    stop_bot = ["player hitbot stop"]
    r.cmd(stop_bot[0])
    if not still:
        r.cmd(f"player {BOT} move forward")
        r.cmd(f"player {BOT} sprint")
    hits, fired, misfires, last_turn = 0, 0, 0, time.time()
    bot(f"track {BOT}" if mode == "track" else "track off")
    if mode == "track":
        time.sleep(1)
    while fired < shots:
        if not still and time.time() - last_turn > 1.4:
            r.cmd(f"player {BOT} turn back")
            last_turn = time.time()
        r.cmd(f"effect give {BOT} minecraft:instant_health 1 5 true")
        time.sleep(0.4)
        before = health(r)
        res = bot(f"aimshoot {BOT}") if mode == "flick" else bot("tacz shoot")
        if res.get("result") != "SUCCESS":
            misfires += 1
            if misfires > 20:
                sys.exit(f"aimshoot keeps failing: {res}")
            if res.get("result") == "NO_AMMO":
                bot("tacz reload")
                time.sleep(3.5)
            time.sleep(0.5)
            continue
        fired += 1
        time.sleep(0.9)  # bullet flight + the added round trip
        if health(r) < before:
            hits += 1
    r.cmd(stop_bot[0])
    bot("track off")
    state = bot("state")
    print(
        json.dumps(
            {
                "mode": mode,
                "shots": fired,
                "hits": hits,
                "rate": round(hits / max(fired, 1), 2),
                "still": still,
                "client_ping_ms": state.get("ping"),
                "server": state.get("server"),
            }
        ),
        flush=True,
    )


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("setup")
    s.add_argument("dimension")
    s.add_argument("--room", default="1")
    g = sub.add_parser("run")
    g.add_argument("--shots", type=int, default=40)
    g.add_argument("--still", action="store_true")
    g.add_argument("--mode", choices=("track", "flick"), default="track")
    a = p.parse_args()
    r = Rcon(port=25576)
    setup(r, a.dimension, a.room) if a.cmd == "setup" else run(
        r, a.shots, a.still, a.mode
    )


if __name__ == "__main__":
    main()
