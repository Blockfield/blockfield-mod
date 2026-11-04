#!/usr/bin/env python3
"""Снимок карты сверху для планшета и экрана высадки: client/.../textures/gui/map_<id>.png.

Берёт верхний непрозрачный блок каждой колонки внутри bounds карты (мир-эталон в формате Anvil,
чтение -- dev/rig/tools/nbt_find.py) и красит его по таблице ниже. Границы совпадают с bounds из
blockfield/maps/<id>.json, поэтому снимок ложится ровно в зону боя (ZoneMap/SpawnSelectionScreen
растягивают всю текстуру на прямоугольник зоны).

    render-map-snapshot.py <мир-эталон> <blockfield/maps/<id>.json> [выход.png] [пикселей-на-блок]

Нужен ImageMagick (`magick`). Для процедурной арены dm_arena есть свой render-dm-arena.py:
он рисует по командам билдера, а не по миру.
"""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "nbt_find", HERE.parent / "rig/tools/nbt_find.py"
)
nbt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nbt)

# Цвета верхних блоков; неизвестный блок -- пурпурный, чтобы промах было видно сразу.
COLORS = {
    "minecraft:smooth_stone": "#9fa0a0",
    "minecraft:stone": "#7f7f7f",
    "minecraft:black_concrete": "#080a0f",
    "minecraft:blue_concrete": "#2c2e8f",
    "minecraft:gray_concrete": "#36393d",
    "minecraft:light_gray_concrete": "#7d7d73",
    "minecraft:red_concrete": "#8e2121",
    "minecraft:yellow_concrete": "#f1af15",
    "minecraft:polished_blackstone_wall": "#2f2b2f",
    "minecraft:sea_lantern": "#c9e3de",
    "minecraft:barrel": "#7a5230",
    "minecraft:grass_block": "#5d8f3a",
    "superbwarfare:sandbag": "#b6a071",
    "minecraft:dirt": "#79553a",
    "minecraft:water": "#3f5cc4",
}
SKIP = {
    "minecraft:air",
    "minecraft:cave_air",
    "minecraft:void_air",
    "minecraft:barrier",
    "minecraft:light",
    "minecraft:structure_void",
}
MISSING = "#ff00ff"
VOID = "#141312"


def top_blocks(world, min_x, max_x, min_z, max_z):
    """Верхний непрозрачный блок каждой колонки: (x, z) -> (y, id)."""
    top = {}
    for chunk in nbt.region_chunks(world):
        bx, bz = chunk.get("xPos", 0) * 16, chunk.get("zPos", 0) * 16
        if bx + 15 < min_x or bx > max_x or bz + 15 < min_z or bz > max_z:
            continue
        for sec in chunk.get("sections", []):
            states = sec.get("block_states")
            if not states or "data" not in states:
                continue
            palette = states["palette"]
            bits = max(4, (len(palette) - 1).bit_length())
            per = 64 // bits
            mask = (1 << bits) - 1
            data = states["data"]
            base_y = sec["Y"] * 16
            for idx in range(4096):
                x = bx + (idx & 15)
                z = bz + ((idx >> 4) & 15)
                if not (min_x <= x <= max_x and min_z <= z <= max_z):
                    continue
                y = base_y + (idx >> 8)
                if (x, z) in top and top[(x, z)][0] >= y:
                    continue
                word = data[idx // per] & 0xFFFFFFFFFFFFFFFF
                name = palette[(word >> ((idx % per) * bits)) & mask]["Name"]
                if name in SKIP:
                    continue
                top[(x, z)] = (y, name)
    return top


def main():
    world = Path(sys.argv[1])
    config = json.loads(Path(sys.argv[2]).read_text(encoding="utf8"))
    out = (
        Path(sys.argv[3])
        if len(sys.argv) > 3
        else (
            HERE.parent.parent
            / f"client/src/main/resources/assets/blockfield/textures/gui/map_{config['id']}.png"
        )
    )
    px = int(sys.argv[4]) if len(sys.argv) > 4 else 8

    b = config["bounds"]
    min_x, max_x = int(b["minX"]), int(b["maxX"])
    min_z, max_z = int(b["minZ"]), int(b["maxZ"])
    width = (max_x - min_x) * px
    height = (max_z - min_z) * px

    top = top_blocks(world, min_x, max_x, min_z, max_z)
    if not top:
        raise SystemExit(f"в {world} нет блоков внутри bounds карты {config['id']}")
    heights = [y for y, _ in top.values()]
    low, high = min(heights), max(heights)
    unknown = sorted({name for _, name in top.values() if name not in COLORS})

    draw = []
    for (x, z), (y, name) in sorted(top.items()):
        # Тень по высоте: верх вышек светлее пола, силуэт читается без изолиний.
        shade = 0.75 + 0.45 * ((y - low) / (high - low) if high > low else 1.0)
        rgb = COLORS.get(name, MISSING).lstrip("#")
        r, g, bl = (min(255, int(int(rgb[i : i + 2], 16) * shade)) for i in (0, 2, 4))
        left = min(width - px, (x - min_x) * px)
        topp = min(height - px, (z - min_z) * px)
        draw += [
            "-fill",
            f"rgb({r},{g},{bl})",
            "-draw",
            f"rectangle {left},{topp} {left + px - 1},{topp + px - 1}",
        ]

    subprocess.run(
        [
            "magick",
            "-size",
            f"{width}x{height}",
            f"xc:{VOID}",
            *draw,
            "-strip",
            str(out),
        ],
        check=True,
    )
    print(f"{out} {width}x{height} блоков {len(top)} высоты {low}..{high}")
    if unknown:
        print("нет цвета (нарисованы пурпурным): " + ", ".join(unknown))


if __name__ == "__main__":
    main()
