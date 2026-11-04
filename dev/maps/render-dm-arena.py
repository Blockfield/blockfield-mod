#!/usr/bin/env python3
"""Рисует снимок сверху для меню высадки: client/.../textures/gui/map_dm_arena.png.

Геометрия берётся из тех же команд, что строят арену (build-dm-arena.py), поэтому снимок совпадает с миром.
Использование: render-dm-arena.py [выходной png]. Нужен ImageMagick (`magick`).
Масштаб: bounds dm_arena.json (x,z -32..31) растягиваются на весь снимок, как в SpawnSelectionScreen.
"""

import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = (
    Path(sys.argv[1])
    if len(sys.argv) > 1
    else HERE.parent.parent
    / "client/src/main/resources/assets/blockfield/textures/gui/map_dm_arena.png"
)
PX = 8  # пикселей на блок
MIN, SIZE = -33, 66  # внешний контур стен -33..32

COLORS = {
    "polished_andesite": "#8a8d8c",
    "stone": "#6f6f6f",
    "sea_lantern": "#c9e3de",
    "stone_bricks": "#4f4f52",
    "mossy_stone_bricks": "#4c5a42",
    "barrel": "#7a5230",
    "spruce_planks": "#6b4d2c",
    "spruce_log": "#3b2a18",
    "spruce_fence": "#50391f",
    "stone_brick_stairs": "#5d5d60",
}

commands = subprocess.run(
    [sys.executable, str(HERE / "build-dm-arena.py"), "dim"],
    check=True,
    capture_output=True,
    text=True,
).stdout.splitlines()
top = {}  # (x, z) -> (y, block): верхний непрозрачный для взгляда сверху блок
fill_re = re.compile(
    r"fill (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) minecraft:(\w+)"
)
set_re = re.compile(r"setblock (-?\d+) (-?\d+) (-?\d+) minecraft:(\w+)")


def put(x, y, z, block):
    if block == "barrier":
        return
    if block == "air":
        if (x, z) in top and top[(x, z)][0] == y:
            del top[(x, z)]
        return
    if (x, z) not in top or y >= top[(x, z)][0]:
        top[(x, z)] = (y, block)


for line in commands:
    if m := fill_re.search(line):
        x1, y1, z1, x2, y2, z2, block = m.groups()
        x1, y1, z1, x2, y2, z2 = map(int, (x1, y1, z1, x2, y2, z2))
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                for y in range(min(y1, y2), max(y1, y2) + 1):
                    put(x, y, z, block)
    elif m := set_re.search(line):
        x, y, z, block = m.groups()
        put(int(x), int(y), int(z), block)

draw = []
for (x, z), (y, block) in sorted(top.items()):
    px, pz = (x - MIN) * PX, (z - MIN) * PX
    shade = min(1.0, 0.8 + (y - 64) * 0.05)  # выше -- светлее
    rgb = COLORS.get(block, "#ff00ff").lstrip("#")
    r, g, b = (min(255, int(int(rgb[i : i + 2], 16) * shade)) for i in (0, 2, 4))
    draw += [
        "-fill",
        f"rgb({r},{g},{b})",
        "-draw",
        f"rectangle {px},{pz} {px + PX - 1},{pz + PX - 1}",
    ]
subprocess.run(
    [
        "magick",
        "-size",
        f"{SIZE * PX}x{SIZE * PX}",
        "xc:#1b1c1d",
        *draw,
        "-strip",
        str(OUT),
    ],
    check=True,
)
print(OUT)
