#!/usr/bin/env python3
"""Печатает RCON-команды блокаута полигона внутри его измерения.

Использование: build-firing-range.py <dimension id, например blockfield_shared:firing_range> [--check]
Пол y=64, +Z — вниз по дистанции, огневой рубеж z=0. Точка появления и мишени берутся из
docs/training/firing_range.json (копия blockfield/maps/firing_range.json).
--check: вместо печати — воксельная симуляция команд: у точки появления и каждой мишени
(и по всему пути strafe) твёрдый пол, свободные ноги/голова, всё внутри bounds.
"""

import json
import math
import pathlib
import sys

check = "--check" in sys.argv[1:]
args = [a for a in sys.argv[1:] if a != "--check"]
if len(args) != 1:
    sys.exit(__doc__)
dim = args[0]
layout = json.loads(
    (
        pathlib.Path(__file__).resolve().parents[2] / "docs/training/firing_range.json"
    ).read_text()
)
training = layout["mode_data"]["training"]

ops = []


def fill(x1, y1, z1, x2, y2, z2, block):
    box = (min(x1, x2), min(y1, y2), min(z1, z2), max(x1, x2), max(y1, y2), max(z1, z2))
    volume = (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1)
    assert volume <= 32768, f"fill больше лимита 32768: {box}"
    ops.append((*box, block))


def setb(x, y, z, block):
    fill(x, y, z, x, y, z, block)


# Основание: камень двумя заливками (лимит fill), пол, площадка техники, свет.
fill(-77, 62, -29, 72, 63, 67, "minecraft:stone")
fill(-77, 62, 68, 72, 63, 164, "minecraft:stone")
fill(-76, 64, -28, 71, 64, 163, "minecraft:polished_andesite")
fill(-76, 64, -28, -29, 64, 163, "minecraft:gray_concrete")
for x in range(-72, 72, 8):
    for z in range(-24, 164, 8):
        setb(x, 64, z, "minecraft:sea_lantern")
# Периметр: кирпич до y68 и барьер до y80 против лазания ParCool.
for x1, z1, x2, z2 in (
    (-77, -29, 72, -29),
    (-77, 164, 72, 164),
    (-77, -29, -77, 164),
    (72, -29, 72, 164),
):
    fill(x1, 64, z1, x2, 68, z2, "minecraft:stone_bricks")
    fill(x1, 69, z1, x2, 80, z2, "minecraft:barrier")
# Огневой рубеж: стойка (проходы x -24 и 31), разделители дорожек A..E у рубежа.
fill(-23, 65, -1, 30, 65, -1, "minecraft:smooth_stone_slab[type=bottom]")
for x in (-17, -9, -1, 7):
    fill(x, 65, 0, x, 65, 6, "minecraft:polished_blackstone_bricks")
# Дистанции A..E: полосы на полу и вал в конце.
for z, color in (
    (10, "white"),
    (25, "yellow"),
    (50, "orange"),
    (100, "red"),
    (150, "purple"),
):
    fill(-24, 64, z, 15, 64, z, f"minecraft:{color}_concrete")
fill(-24, 65, 158, 15, 76, 160, "minecraft:stone_bricks")
# Дорожка F с движущимися мишенями.
fill(16, 64, 25, 31, 64, 25, "minecraft:yellow_concrete")
fill(16, 64, 50, 31, 64, 50, "minecraft:orange_concrete")
fill(16, 65, 60, 31, 72, 60, "minecraft:stone_bricks")
# Таблички (ASCII: шрифт таблички без кириллицы на части клиентов).
for x, label in (
    (-21, "A 10m"),
    (-13, "B 25m"),
    (-5, "C 50m"),
    (3, "D 100m"),
    (11, "E 150m"),
    (23, "F move"),
):
    setb(
        x,
        65,
        -3,
        "minecraft:oak_sign[rotation=8]{front_text:{messages:['\"%s\"','\"\"','\"\"','\"\"']}}"
        % label,
    )
# Площадка техники: стенка с проёмом.
fill(-28, 65, -28, -28, 66, 163, "minecraft:stone_bricks")
fill(-28, 65, -20, -28, 66, -16, "minecraft:air")
# Двор PvP: стенка с воротами, дальняя стенка, укрытия, бочки.
fill(33, 65, -28, 33, 67, 40, "minecraft:stone_bricks")
fill(33, 65, -18, 33, 67, -14, "minecraft:air")
fill(34, 65, 41, 71, 67, 41, "minecraft:stone_bricks")
fill(40, 65, -10, 43, 66, -10, "minecraft:stone_bricks")
fill(64, 65, 22, 67, 66, 22, "minecraft:stone_bricks")
fill(52, 65, -20, 52, 67, -16, "minecraft:mossy_stone_bricks")
fill(55, 65, 30, 55, 67, 34, "minecraft:mossy_stone_bricks")
setb(46, 65, 12, "minecraft:barrel")
setb(61, 65, 0, "minecraft:barrel")
# Пятна под мишенями: лайм — без брони, синий — в броне.
for t in training["targets"]:
    setb(
        math.floor(t["x"]),
        64,
        math.floor(t["z"]),
        "minecraft:%s_concrete" % ("lime" if t["armor"] == "none" else "blue"),
    )

if not check:
    for x1, y1, z1, x2, y2, z2, block in ops:
        if (x1, y1, z1) == (x2, y2, z2):
            print(f"execute in {dim} run setblock {x1} {y1} {z1} {block}")
        else:
            print(f"execute in {dim} run fill {x1} {y1} {z1} {x2} {y2} {z2} {block}")
    sys.exit(0)

world = {}
for x1, y1, z1, x2, y2, z2, block in ops:
    name = block.split("[")[0].split("{")[0]
    for x in range(x1, x2 + 1):
        for y in range(y1, y2 + 1):
            for z in range(z1, z2 + 1):
                world[(x, y, z)] = name


def block(p):
    return world.get(p, "minecraft:air")


def full(p):
    name = block(p)
    return (
        name not in ("minecraft:air", "minecraft:barrier")
        and "slab" not in name
        and "sign" not in name
    )


bounds = layout["bounds"]
errors = []


def spot(label, x, y, z):
    if not (
        bounds["minX"] <= x <= bounds["maxX"] and bounds["minZ"] <= z <= bounds["maxZ"]
    ):
        errors.append(f"{label}: вне bounds")
    bx, by, bz = math.floor(x), math.floor(y), math.floor(z)
    if not full((bx, by - 1, bz)):
        errors.append(f"{label}: пол {block((bx, by - 1, bz))}")
    for dy, part in ((0, "ноги"), (1, "голова")):
        if block((bx, by + dy, bz)) != "minecraft:air":
            errors.append(f"{label}: {part} заняты {block((bx, by + dy, bz))}")


spawn = training["spawn"]
spot("spawn", spawn["x"], spawn["y"], spawn["z"])
for t in training["targets"]:
    spot(t["id"], t["x"], t["y"], t["z"])
    motion = t.get("motion")
    if motion:
        lo, hi = sorted((math.floor(t["x"]), math.floor(motion["to_x"])))
        for bx in range(lo, hi + 1):
            spot(f"{t['id']} путь x={bx}", bx + 0.5, t["y"], t["z"])
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"OK: spawn и {len(training['targets'])} мишеней, {len(ops)} команд")
