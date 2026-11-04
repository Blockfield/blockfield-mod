#!/usr/bin/env python3
"""Живая проверка зацепа и ползания ParCool на карте: parkour-test.py <attempts.json> [--map id] [--limit N] [--out f]

attempts.json -- из parcool-seal.py --attempts (blockfield-modpack): передний край карты как есть, каждый ход --
{kind: grab|gap|crawl, start: [x, y, z], target: [x, y, z], yaw}. Скрипт открывает комнату deathmatch с картой на
локальном сервере стенда (server.sh start, rig.sh start 127.0.0.1:25599), ставит игрока на start через RCON и
отдаёт боту "attempt <kind> <yaw>": зацеп, подъём, прыжок с разбега, ползком -- настоящими клавишами ParCool.
Итог: reached -- игрок постоял на клетке target, near -- на соседней с ней на её высоте, no -- нет. На карте без барьеров (положительный контроль) доля дошедших
показывает, насколько модель и бот совпадают с игрой; на закрытой барьерами карте дойти не должна ни одна.

    ./parkour-test.py seal/train-attempts.json --limit 60 --out /tmp/train-live.json
    ./parkour-test.py seal/train-attempts.json --only reached --from /tmp/train-live.json   # повторить дошедшие
"""

import argparse
import json
import os
import random
import re
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon  # noqa: E402

SERVER_OUT = os.path.expanduser("~/.cache/bf-test-server/server.out")


class Bot:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=40)
        self.file = self.sock.makefile("r", encoding="utf8")

    def __call__(self, command):
        self.sock.sendall((command + "\n").encode())
        reply = json.loads(self.file.readline())
        if isinstance(reply, dict) and "error" in reply:
            raise RuntimeError(f"{command}: {reply['error']}")
        return reply


def log_tail(start):
    with open(SERVER_OUT, encoding="utf8", errors="replace") as f:
        f.seek(start)
        return f.read()


def open_room(bot, rcon, map_id):
    """Комната deathmatch с картой: закрыть открытую, войти через меню, дождаться измерения."""
    with open(SERVER_OUT, encoding="utf8", errors="replace") as f:
        text = f.read()
    rooms = re.findall(r"Комната (\d+) открыта", text)
    if rooms:
        rcon.cmd(f"bf stop room {rooms[-1]}")
        time.sleep(2)
    mark = os.path.getsize(SERVER_OUT)
    bot("closescreen")
    bot(f"bfaction play:deathmatch:{map_id}")
    time.sleep(3)
    bot("bfaction class:assault:0")
    for _ in range(60):
        found = re.findall(
            rf"измерение ({re.escape(map_id)}_\d+) открыто", log_tail(mark)
        )
        if found:
            return found[-1]
        time.sleep(1)
    raise SystemExit(f"комната с {map_id} не открылась, см. {SERVER_OUT}")


def settle(bot, x, y, z):
    """Ждёт, пока клиент окажется на start и встанет на пол (телепорт идёт пакетом)."""
    for _ in range(60):
        s = bot("state")
        px, py, pz = s["pos"]
        if (
            abs(px - x) < 0.05
            and abs(py - y) < 0.1
            and abs(pz - z) < 0.05
            and s.get("onGround")
        ):
            return True
        time.sleep(0.05)
    return False


def judge(start, target, ground):
    """ground -- блоки, на которых игрок стоял ("x z y"). reached: клетка target на её высоте; near: соседняя с ней
    клетка (не стартовая) не ниже target - 0.6 -- модель берёт верх решётки или люка, игрок встаёт рядом на уступ."""
    x, _, z = start
    tx, ty, tz = target
    stood = [
        (int(gx), int(gz), float(gy)) for gx, gz, gy in (g.split() for g in ground)
    ]
    if any((gx, gz) == (tx, tz) and abs(gy - ty) < 0.02 for gx, gz, gy in stood):
        return "reached"
    if any(
        max(abs(gx - tx), abs(gz - tz)) <= 1 and (gx, gz) != (x, z) and gy >= ty - 0.6
        for gx, gz, gy in stood
    ):
        return "near"
    return "no"


def load_nodes(path, key):
    """reach/roof из parcool-seal.py --attempts: {(x, z): [полы]}."""
    with open(path) as f:
        index = {}
        for x, z, t in json.load(f).get(key, ()):
            index.setdefault((x, z), []).append(t)
    return index


def on_roof(ground, roof):
    """Главный итог на закрытой карте: игрок стоял в колонке, которая на карте без барьеров была крышей (roof из
    attempts.json), на её высоте или выше -- живая лазейка."""
    hits = []
    for g in ground:
        gx, gz, gy = g.split()
        if any(float(gy) >= t - 0.1 for t in roof.get((int(gx), int(gz)), ())):
            hits.append(g)
    return hits


def start_reachable(start, reach):
    """Старт достижим от спавнов на карте как есть (reach закрытой карты): после барьеров часть стартов лежит в
    отрезанной зоне, куда игрока ставит только RCON, -- крыша рядом с таким стартом не лазейка."""
    x, y, z = start
    return any(abs(t - y) < 0.1 for t in reach.get((x, z), ()))


def run_attempt(bot, rcon, dim, player, a):
    x, y, z = a["start"]
    rcon.cmd(
        f"execute in blockfield:{dim} run tp {player} {x + 0.5} {y} {z + 0.5} {a['yaw']} 0"
    )
    if not settle(bot, x + 0.5, y, z + 0.5):
        return {"result": "not-placed", "pos": bot("state")["pos"]}
    bot(f"attempt {a['kind']} {a['yaw']}")
    while True:
        time.sleep(0.25)
        r = bot("attempt get")
        if r["done"]:
            break
    return {
        "result": judge(a["start"], a["target"], r["ground"]),
        "cling": r["clingAt"] >= 0,
        "climb": r["climbAt"] >= 0,
        "crawl": r["crawlTicks"] > 0,
        "maxY": r["maxY"],
        "ground": r["ground"],
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("attempts")
    ap.add_argument("--map", help="id карты (по умолчанию из attempts.json)")
    ap.add_argument("--kinds", default="grab,gap,crawl")
    ap.add_argument("--limit", type=int, help="случайная выборка из N попыток (--seed)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument(
        "--only",
        choices=["reached", "near"],
        help="только попытки с таким итогом в --from",
    )
    ap.add_argument(
        "--from", dest="previous", help="результаты прошлого прогона для --only"
    )
    ap.add_argument("--out", help="результаты в JSON")
    ap.add_argument(
        "--reach",
        help="attempts.json закрытой карты (parcool-seal.py --attempts по ней): крыша считается, "
        "только если старт попытки достижим от спавнов",
    )
    ap.add_argument(
        "--judge", help="не играть: пересчитать итог сохранённых результатов"
    )
    ap.add_argument("--bot-port", type=int, default=47777)
    ap.add_argument("--rcon", default="127.0.0.1:25575")
    ap.add_argument("--password", default="leaks")
    args = ap.parse_args()
    reach = load_nodes(args.reach, "reach") if args.reach else None
    roof = load_nodes(args.attempts, "roof")
    if args.judge:
        with open(args.judge) as f:
            done = json.load(f)
        roofs = cut = 0
        for r in done["results"]:
            a, hits = r["attempt"], on_roof(r.get("ground", []), roof)
            if reach is not None and not start_reachable(a["start"], reach):
                cut += 1
                continue
            if hits:
                roofs += 1
                print(
                    f"{a['kind']:5} start {a['start']} -> {a['target']}: НА КРЫШЕ {hits}"
                )
        print(
            f"{done['map']}: {len(done['results'])} попыток, старт отрезан барьерами {cut}, на крыше {roofs}"
        )
        return
    with open(args.attempts) as f:
        data = json.load(f)
    map_id = args.map or data["map"]
    kinds = set(args.kinds.split(","))
    todo = [a for a in data["attempts"] if a["kind"] in kinds]
    if args.only:
        with open(args.previous) as f:
            keep = {
                json.dumps(r["attempt"], sort_keys=True)
                for r in json.load(f)["results"]
                if r["result"] == args.only
            }
        todo = [a for a in todo if json.dumps(a, sort_keys=True) in keep]
    if args.limit and len(todo) > args.limit:
        todo = random.Random(args.seed).sample(todo, args.limit)
    host, port = args.rcon.rsplit(":", 1)
    rcon = Rcon(host, int(port), args.password)
    bot = Bot(args.bot_port)
    player = bot("state")["name"]
    dim = open_room(bot, rcon, map_id)
    rcon.cmd(f"gamemode adventure {player}")
    for effect in ("resistance", "saturation", "regeneration"):
        rcon.cmd(f"effect give {player} minecraft:{effect} infinite 255 true")
    results, counts = [], {}
    started = time.time()
    for i, a in enumerate(todo, 1):
        r = run_attempt(bot, rcon, dim, player, a)
        r["attempt"] = a
        if reach is not None and not start_reachable(a["start"], reach):
            r["start_cut"] = True
        elif hits := on_roof(r.get("ground", []), roof):
            r["roof"] = hits
            counts[("roof", "")] = counts.get(("roof", ""), 0) + 1
        results.append(r)
        key = (a["kind"], r["result"])
        counts[key] = counts.get(key, 0) + 1
        flags = "".join(
            c
            for c, on in (
                ("c", r.get("cling")),
                ("u", r.get("climb")),
                ("p", r.get("crawl")),
            )
            if on
        )
        print(
            f"{i}/{len(todo)} {a['kind']:5} start {a['start']} -> {a['target']}: {r['result']} {flags}"
            + (f" НА КРЫШЕ {r['roof']}" if r.get("roof") else "")
            + (" (старт отрезан)" if r.get("start_cut") else ""),
            flush=True,
        )
    summary = {f"{k}:{v}": n for (k, v), n in sorted(counts.items())}
    print(
        f"{map_id} ({dim}), {len(todo)} попыток за {time.time() - started:.0f} с: {summary}"
    )
    if args.out:
        with open(args.out, "w") as f:
            json.dump(
                {"map": map_id, "dim": dim, "summary": summary, "results": results},
                f,
                indent=1,
            )


if __name__ == "__main__":
    main()
