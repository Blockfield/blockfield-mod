#!/usr/bin/env python3
"""Bake the command tablet icon, rugged native item model and material atlas.

    dev/assets/gen-tablet-item.py [icon.png]

With an explicit output path, all three files are written beside that icon.
"""

import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent.parent / "client/src/main/resources/assets/blockfield"
SIZE = 256
REGIONS = {
    "front": (0, 0, 128, 80),
    "back": (128, 0, 256, 80),
    "screen": (0, 96, 144, 192),
    "rubber": (160, 96, 192, 128),
    "body": (192, 96, 224, 128),
    "bevel": (224, 96, 256, 128),
    "button": (160, 144, 192, 176),
    "led": (192, 144, 208, 160),
    "black": (208, 144, 224, 160),
    "screw": (224, 144, 240, 160),
    "grip": (160, 192, 192, 224),
    "strap": (192, 192, 224, 224),
}

# Палитра BfTheme: корпус, кант, экран, янтарь.
C = {
    "K": "#00000000",  # прозрачный
    "B": "#1c1710ff",  # корпус
    "D": "#0d0a07ff",  # тень корпуса
    "R": "#2a2116ff",  # бампер
    "G": "#3a2c1dff",  # блик бампера
    "S": "#070604ff",  # экран
    "A": "#f5a524ff",  # янтарь
    "M": "#8a571cff",  # янтарь приглушённый
    "L": "#82d66bff",  # индикатор питания
    "W": "#5e5040ff",  # ремешок
}

# 16x16: планшет лежит по диагонали не надо -- ровный ландшафтный корпус читается лучше в хотбаре.
ART = [
    "KKKKKKKKKKKKKKKK",
    "KRRRRRRRRRRRRRRK",
    "KRGGGGGGGGGGGGRK",
    "WRGSSSSSSSSSSGRK",
    "WRGSAAAAAMMMSGRW",
    "WRGSSSSSSSSSSGRW",
    "WRGSMSMSMSMSSGRW",
    "WRGSSSSSSSSSSGRW",
    "KRGSMSMSMSMSSGRK",
    "KRGSSSSSSSSSSGRK",
    "KRGSSSSSSSSSSGRK",
    "KRGLSSSSSSSSSGRK",
    "KRGGGGGGGGGGGGRK",
    "KRRRRRRRRRRRRRRK",
    "KDDDDDDDDDDDDDDK",
    "KKKKKKKKKKKKKKKK",
]


def atlas():
    pixels = bytearray(SIZE * SIZE * 3)

    def point(x, y, color):
        start = (y * SIZE + x) * 3
        pixels[start : start + 3] = bytes(color)

    def rect(x, y, width, height, color):
        row = bytes(color) * width
        for py in range(y, y + height):
            start = (py * SIZE + x) * 3
            pixels[start : start + width * 3] = row

    def line(x1, y1, x2, y2, color):
        steps = max(abs(x2 - x1), abs(y2 - y1))
        for n in range(steps + 1):
            t = n / max(1, steps)
            point(round(x1 + (x2 - x1) * t), round(y1 + (y2 - y1) * t), color)

    material = {
        "front": (154, 147, 124),
        "back": (128, 125, 108),
        "rubber": (38, 41, 38),
        "body": (145, 140, 121),
        "bevel": (178, 170, 145),
        "button": (44, 47, 42),
        "led": (104, 169, 60),
        "black": (18, 22, 18),
        "screw": (67, 73, 66),
        "grip": (43, 46, 42),
        "strap": (49, 52, 44),
    }
    for name, color in material.items():
        x1, y1, x2, y2 = REGIONS[name]
        for y in range(y1, y2):
            for x in range(x1, x2):
                grain = ((x // 3 * 17 + y // 3 * 31) % 7) - 3
                point(x, y, tuple(c + grain for c in color))

    rect(134, 6, 116, 68, (104, 104, 90))
    rect(138, 10, 108, 60, (129, 125, 106))
    for y in (16, 22, 28):
        rect(144, y, 21, 2, (64, 70, 62))
    rect(216, 54, 24, 8, (94, 94, 79))
    for x in range(218, 239, 3):
        rect(x, 55, 1, 6, (143, 137, 116))

    for y in range(98, 190):
        for x in range(2, 142):
            contours = (x // 6 + y // 8 + (x * y // 97)) % 13
            point(x, y, (32 + contours, 34 + contours, 22 + contours // 2))
    for x in range(7, 141, 12):
        line(x, 100, x, 187, (79, 72, 37))
    for y in range(103, 190, 12):
        line(5, y, 138, y, (79, 72, 37))
    for x, y, width, height in ((25, 122, 24, 15), (78, 145, 35, 25)):
        rect(x, y, width, height, (67, 63, 35))
        rect(x + 3, y + 3, width - 6, height - 6, (37, 39, 26))
        rect(x + 7, y + 5, 4, 4, (98, 89, 43))
        rect(x + width - 11, y + height - 9, 4, 4, (98, 89, 43))
    route = ((21, 163), (46, 153), (68, 153), (91, 130), (116, 118))
    for start, end in zip(route, route[1:]):
        line(*start, *end, (215, 166, 64))
    for x, y in route[1:-1]:
        rect(x - 2, y - 2, 5, 5, (220, 174, 72))
        rect(x - 1, y - 1, 3, 3, (41, 42, 26))
    line(16, 169, 22, 158, (245, 186, 69))
    line(22, 158, 28, 169, (245, 186, 69))
    line(16, 169, 28, 169, (245, 186, 69))
    for side in (-1, 1):
        line(116 + side * 5, 113, 116 + side * 5, 123, (245, 186, 69))
        line(111, 118 + side * 5, 121, 118 + side * 5, (245, 186, 69))
    rect(163, 147, 26, 2, (74, 79, 69))
    rect(168, 158, 16, 4, (227, 173, 60))
    rect(196, 148, 8, 8, (157, 208, 83))
    rect(228, 148, 8, 8, (111, 119, 106))
    rect(231, 149, 2, 6, (22, 26, 23))
    rect(229, 151, 6, 2, (22, 26, 23))
    for y in range(195, 222, 5):
        rect(163, y, 26, 2, (68, 73, 63))
    for x in range(195, 223, 3):
        rect(x, 194, 1, 28, (71, 74, 63))
    return pixels


def model():
    elements = []

    def box(name, low, high, material, front=None, back=None, rotation=None):
        faces = {}
        for side in ("north", "south", "east", "west", "up", "down"):
            region = front if side == "south" else back if side == "north" else None
            faces[side] = {
                "uv": [v / 16 for v in REGIONS[region or material]],
                "texture": "#body",
            }
        element = {"name": name, "from": low, "to": high, "faces": faces}
        if rotation:
            element["rotation"] = rotation
        elements.append(element)

    box("case", [1.2, 3.7, 7.35], [14.8, 12.3, 8.15], "body", "front", "back")
    for name, low, high, axis, angle, origin in (
        ("top", [1.6, 12.2, 7.6], [14.4, 12.55, 8.1], "x", 22.5, [8, 12.2, 7.6]),
        ("bottom", [1.6, 3.45, 7.6], [14.4, 3.8, 8.1], "x", -22.5, [8, 3.8, 7.6]),
        ("left", [0.95, 4.1, 7.6], [1.3, 11.9, 8.1], "y", -22.5, [1.3, 8, 7.6]),
        ("right", [14.7, 4.1, 7.6], [15.05, 11.9, 8.1], "y", 22.5, [14.7, 8, 7.6]),
    ):
        box(
            name + " bevel",
            low,
            high,
            "bevel",
            rotation={"origin": origin, "axis": axis, "angle": angle},
        )

    box("screen recess", [1.95, 4.3, 8.15], [13.1, 11.7, 8.3], "black")
    box("map glass", [2.25, 4.6, 8.3], [12.8, 11.4, 8.32], "black", "screen")
    for name, low, high in (
        ("screen left lip", [1.95, 4.3, 8.15], [2.23, 11.7, 8.48]),
        ("screen right lip", [12.82, 4.3, 8.15], [13.1, 11.7, 8.48]),
        ("screen bottom lip", [2.23, 4.3, 8.15], [12.82, 4.58, 8.48]),
        ("screen top lip", [2.23, 11.42, 8.15], [12.82, 11.7, 8.48]),
    ):
        box(name, low, high, "rubber")

    for x, sx in ((1.0, 1), (15.0, -1)):
        for y, sy in ((3.55, 1), (12.45, -1)):

            def bounds(offset_x, offset_y, width, height):
                xs = (x + sx * offset_x, x + sx * (offset_x + width))
                ys = (y + sy * offset_y, y + sy * (offset_y + height))
                return [min(xs), min(ys), 7.15], [max(xs), max(ys), 8.63]

            low, high = bounds(-0.2, 0.2, 0.95, 1.8)
            box("corner side bumper", low, high, "rubber")
            low, high = bounds(0.2, -0.25, 1.8, 0.95)
            box("corner top bumper", low, high, "rubber")
            center = [x + sx * 0.22, y + sy * 0.22, 7.9]
            box(
                "chamfered corner",
                [center[0] - 0.35, center[1] - 0.28, 7.15],
                [center[0] + 0.35, center[1] + 0.28, 8.63],
                "rubber",
                rotation={"origin": center, "axis": "z", "angle": 45 * sx * sy},
            )
            screw_x, screw_y = x + sx * 1.25, y + sy * 0.25
            box(
                "recessed corner fastener",
                [screw_x - 0.17, screw_y - 0.17, 8.63],
                [screw_x + 0.17, screw_y + 0.17, 8.65],
                "screw",
            )

    for x in (0.72, 14.56):
        box("rubber grip", [x, 5.8, 7.3], [x + 0.72, 10.2, 8.43], "rubber", "grip")
        for y in (6.1, 6.9, 7.7, 8.5, 9.3):
            box("grip rib", [x - 0.06, y, 7.8], [x + 0.78, y + 0.22, 8.55], "grip")
    for y in (5.15, 7.25, 9.35):
        box("key socket", [13.4, y - 0.15, 8.15], [14.5, y + 1.1, 8.35], "bevel")
        box(
            "sealed command key",
            [13.55, y, 8.35],
            [14.35, y + 0.9, 8.58],
            "rubber",
            "button",
        )
    box("status socket", [13.65, 11.25, 8.15], [14.15, 11.75, 8.25], "black")
    box("power indicator", [13.75, 11.35, 8.25], [14.05, 11.65, 8.28], "led")

    for x in (3.85, 11.8):
        for y in (3.6, 12.1):
            box("lanyard well", [x, y, 8.15], [x + 0.8, y + 0.25, 8.2], "black")
            box(
                "lanyard bridge",
                [x - 0.06, y - 0.07, 8.15],
                [x + 0.86, y + 0.02, 8.39],
                "bevel",
            )
    box("charging cover", [6.6, 3.4, 7.35], [9.4, 3.72, 8.25], "rubber")
    box("rear cushion", [4.3, 4.9, 7.15], [11.7, 11.1, 7.35], "rubber")
    box("rear hand strap", [6.05, 4.6, 6.95], [9.95, 11.4, 7.15], "strap")
    box("strap upper anchor", [5.85, 11.15, 6.9], [10.15, 11.55, 7.35], "bevel")
    box("strap lower anchor", [5.85, 4.45, 6.9], [10.15, 4.85, 7.35], "bevel")

    return {
        "gui_light": "front",
        "textures": {
            "body": "blockfield:item/command_tablet_body",
            "particle": "blockfield:item/command_tablet_body",
        },
        # Minecraft mirrors Y/Z rotation and X translation for left-hand contexts.
        "display": {
            "thirdperson_righthand": {
                "rotation": [0, 0, -8],
                "translation": [0, 2, 0],
                "scale": [0.65, 0.65, 0.65],
            },
            "thirdperson_lefthand": {
                "rotation": [0, 0, -8],
                "translation": [0, 2, 0],
                "scale": [0.65, 0.65, 0.65],
            },
            "firstperson_righthand": {
                "rotation": [-18, -25, -3],
                "translation": [-2.75, 3.75, -3.25],
                "scale": [0.62, 0.62, 0.62],
            },
            "firstperson_lefthand": {
                "rotation": [-18, -25, -3],
                "translation": [-2.75, 3.75, -3.25],
                "scale": [0.62, 0.62, 0.62],
            },
            "gui": {
                "rotation": [12, -12, 0],
                "translation": [0, 0, 0],
                "scale": [1, 1, 1],
            },
            "ground": {
                "rotation": [90, 0, 0],
                "translation": [0, 2, 0],
                "scale": [0.65, 0.65, 0.65],
            },
            "fixed": {
                "rotation": [0, 0, 0],
                "translation": [0, 0, 0],
                "scale": [0.95, 0.95, 0.95],
            },
        },
        "elements": elements,
    }


def main():
    out = (
        Path(sys.argv[1])
        if len(sys.argv) > 1
        else ASSETS / "textures/item/command_tablet.png"
    )
    body = out.with_name(out.stem + "_body.png")
    model_out = (
        out.with_suffix(".json")
        if len(sys.argv) > 1
        else ASSETS / "models/item/command_tablet.json"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    model_out.parent.mkdir(parents=True, exist_ok=True)
    assert len(ART) == 16 and all(len(row) == 16 for row in ART), (
        "иконка должна быть 16x16"
    )
    draw = []
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            color = C[ch]
            if color.endswith("00"):
                continue
            draw += ["-fill", color, "-draw", f"point {x},{y}"]
    tablet = model()
    for element in tablet["elements"]:
        assert all(-16 <= a < b <= 32 for a, b in zip(element["from"], element["to"]))
        assert all(
            0 <= v <= 16 for face in element["faces"].values() for v in face["uv"]
        )
        assert element.get("rotation", {}).get("angle", 0) in (-45, -22.5, 0, 22.5, 45)
    subprocess.run(
        [
            "magick",
            "-size",
            "16x16",
            "xc:none",
            "+antialias",
            *draw,
            "-strip",
            str(out),
        ],
        check=True,
    )
    subprocess.run(
        [
            "magick",
            "-size",
            f"{SIZE}x{SIZE}",
            "-depth",
            "8",
            "rgb:-",
            "-strip",
            str(body),
        ],
        input=atlas(),
        check=True,
    )
    model_out.write_text(json.dumps(tablet, indent=2) + "\n", encoding="utf-8")
    print(out)
    print(body)
    print(model_out)


if __name__ == "__main__":
    main()
