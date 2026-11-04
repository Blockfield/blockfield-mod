#!/usr/bin/env python3
"""Печатает RCON-команды блокаута арены dm_arena внутри измерения матча.

Использование: build-dm-arena.py <dimension id, например blockfield:dm_arena_1>
Площадка 64x64 (x,z в -32..31), пол y=64, стены до y=71, барьерный потолок y=72 против
лазания ParCool, укрытия, центральная двухуровневая платформа с лестницами, свет в полу.
"""

import sys

dim = sys.argv[1]
cmds = []


def fill(x1, y1, z1, x2, y2, z2, block, mode=""):
    cmds.append(
        f"execute in {dim} run fill {x1} {y1} {z1} {x2} {y2} {z2} {block}{(' ' + mode) if mode else ''}"
    )


def setb(x, y, z, block):
    cmds.append(f"execute in {dim} run setblock {x} {y} {z} {block}")


# Основание и пол.
fill(-33, 62, -33, 32, 63, 32, "minecraft:stone")
fill(-32, 64, -32, 31, 64, 31, "minecraft:polished_andesite")
for x in range(-28, 32, 8):
    for z in range(-28, 32, 8):
        setb(x, 64, z, "minecraft:sea_lantern")
# Стены по периметру и барьерный потолок.
fill(-33, 65, -33, 32, 71, -33, "minecraft:stone_bricks")
fill(-33, 65, 32, 32, 71, 32, "minecraft:stone_bricks")
fill(-33, 65, -33, -33, 71, 32, "minecraft:stone_bricks")
fill(32, 65, -33, 32, 71, 32, "minecraft:stone_bricks")
fill(-33, 72, -33, 32, 72, 32, "minecraft:barrier")
# Центральная платформа 2 уровня (x,z -6..5, верх y=68) с проходом под ней.
fill(-6, 65, -6, 5, 67, 5, "minecraft:air")
fill(-6, 68, -6, 5, 68, 5, "minecraft:spruce_planks")
for x, z in ((-6, -6), (5, -6), (-6, 5), (5, 5)):
    fill(x, 65, z, x, 67, z, "minecraft:spruce_log")
for i in range(3):  # лестницы с запада и востока
    setb(-9 + i, 65 + i, 0, "minecraft:stone_brick_stairs[facing=east]")
    setb(8 - i, 65 + i, -1, "minecraft:stone_brick_stairs[facing=west]")
# Ограждение по краю платформы (outline на слое толщиной 1 заполнил бы весь верх), проёмы у лестниц.
fill(-6, 69, -6, 5, 69, -6, "minecraft:spruce_fence")
fill(-6, 69, 5, 5, 69, 5, "minecraft:spruce_fence")
fill(-6, 69, -5, -6, 69, 4, "minecraft:spruce_fence")
fill(5, 69, -5, 5, 69, 4, "minecraft:spruce_fence")
fill(-6, 69, -1, -6, 69, 0, "minecraft:air")
fill(5, 69, -1, 5, 69, 0, "minecraft:air")
# Укрытия: ящики и низкие стенки в четырёх секторах (симметрично).
for sx in (-1, 1):
    for sz in (-1, 1):
        fill(
            sx * 18,
            65,
            sz * 12,
            sx * 18 + sx * 3,
            66,
            sz * 12,
            "minecraft:stone_bricks",
        )
        fill(
            sx * 12,
            65,
            sz * 20,
            sx * 12,
            66,
            sz * 20 + sz * 3,
            "minecraft:stone_bricks",
        )
        fill(sx * 24, 65, sz * 24, sx * 24 + sx, 66, sz * 24 + sz, "minecraft:barrel")
        setb(sx * 10, 65, sz * 8, "minecraft:barrel")
        fill(sx * 26, 65, 0, sx * 26, 67, sz * 4, "minecraft:mossy_stone_bricks")
print("\n".join(cmds))
