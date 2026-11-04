#!/usr/bin/env python3
"""Contact sheets of baked uniform quads from several angles; stdlib + ImageMagick only.

    just uniform-dump temp/quads
    just uniform-preview temp/quads temp/sheet.png sniper_blue sniper_red --skin path/to/skin.png

Entity lighting and alpha cutout follow vanilla, projection is orthographic: in-game frames stay the proof.
"""

import argparse
import math
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = ROOT / "client/src/main/resources/assets/blockfield/textures/models/uniform"
VIEWS = [("front", 0, 8), ("3/4", 35, 10), ("side", 90, 6), ("back", 180, 8)]
VIEWS = [
    v
    for v in VIEWS
    if v[0] in os.environ.get("BF_VIEWS", "front,3/4,side,back").split(",")
]
SCALE = float(os.environ.get("BF_SCALE", 14))
W, H = round(SCALE * 300 / 14), round(SCALE * 560 / 14)
LIGHTS = [(0.2, 1, -0.7), (-0.2, 1, 0.7)]
LIGHTS = [
    tuple(c / math.sqrt(sum(v * v for v in light)) for c in light) for light in LIGHTS
]


def texture(path):
    size = subprocess.run(
        ["magick", "identify", "-format", "%w %h", str(path)],
        capture_output=True,
        text=True,
        check=True,
    )
    w, h = map(int, size.stdout.split())
    data = subprocess.run(
        ["magick", str(path), "-depth", "8", "rgba:-"], capture_output=True, check=True
    ).stdout
    return w, h, data


def quads(path):
    for line in Path(path).read_text().splitlines():
        n = list(map(float, line.split()))
        yield [n[i : i + 5] for i in range(0, 20, 5)]


def render(layers, yaw, pitch):
    color = [(58, 58, 54)] * (W * H)
    depth = [1e9] * (W * H)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    for faces, (tw, th, tex) in layers:
        for quad in faces:
            a, b, c = (q[:3] for q in quad[:3])
            u = [b[i] - a[i] for i in range(3)]
            v = [c[i] - a[i] for i in range(3)]
            n = [
                u[1] * v[2] - u[2] * v[1],
                u[2] * v[0] - u[0] * v[2],
                u[0] * v[1] - u[1] * v[0],
            ]
            length = math.sqrt(sum(x * x for x in n)) or 1
            # Model space is y-down, x-mirrored against the world the vanilla lights live in.
            world = (-n[0] / length, -n[1] / length, n[2] / length)
            shade = min(
                1,
                0.4
                + 0.6
                * sum(
                    max(0, sum(w * component for w, component in zip(world, light)))
                    for light in LIGHTS
                ),
            )
            pts = []
            for x, y, z, tu, tv in quad:
                rx, rz = x * cy - z * sy, x * sy + z * cy
                ry, rz = y * cp - rz * sp, y * sp + rz * cp
                pts.append(
                    (
                        W / 2 + rx * 16 * SCALE,
                        H / 2 + (ry * 16 - 7.5) * SCALE,
                        rz,
                        tu,
                        tv,
                    )
                )
            for tri in (pts[0], pts[1], pts[2]), (pts[0], pts[2], pts[3]):
                raster(tri, color, depth, shade, tw, th, tex)
    return color


def raster(tri, color, depth, shade, tw, th, tex):
    (x0, y0, z0, u0, v0), (x1, y1, z1, u1, v1), (x2, y2, z2, u2, v2) = tri
    area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    if abs(area) < 1e-9:
        return
    for py in range(max(0, int(min(y0, y1, y2))), min(H, int(max(y0, y1, y2)) + 1)):
        for px in range(max(0, int(min(x0, x1, x2))), min(W, int(max(x0, x1, x2)) + 1)):
            sx, sy = px + 0.5, py + 0.5
            w1 = ((sx - x0) * (y2 - y0) - (x2 - x0) * (sy - y0)) / area
            w2 = ((x1 - x0) * (sy - y0) - (sx - x0) * (y1 - y0)) / area
            w0 = 1 - w1 - w2
            if w0 < 0 or w1 < 0 or w2 < 0:
                continue
            z = w0 * z0 + w1 * z1 + w2 * z2
            i = py * W + px
            if z >= depth[i]:
                continue
            tx = min(tw - 1, max(0, int((w0 * u0 + w1 * u1 + w2 * u2) * tw)))
            ty = min(th - 1, max(0, int((w0 * v0 + w1 * v1 + w2 * v2) * th)))
            t = (ty * tw + tx) * 4
            if tex[t + 3] < 26:
                continue
            depth[i] = z
            color[i] = (
                round(tex[t] * shade),
                round(tex[t + 1] * shade),
                round(tex[t + 2] * shade),
            )


def main(dump, out, names, skin):
    skin = texture(skin)
    skin_quads = list(quads(Path(dump, "skin.quads")))
    tiles = []
    for name in names:
        layers = [
            (skin_quads, skin),
            (
                list(quads(Path(dump, name + ".quads"))),
                texture(TEXTURES / f"{name}.png"),
            ),
        ]
        for label, yaw, pitch in VIEWS:
            tile = Path(out).with_name(f".{name}-{label.replace('/', '')}.ppm")
            pixels = render(layers, yaw, pitch)
            tile.write_bytes(
                f"P6 {W} {H} 255\n".encode() + bytes(c for p in pixels for c in p)
            )
            tiles.append(str(tile))
    subprocess.run(
        [
            "magick",
            "montage",
            *tiles,
            "-tile",
            f"{len(VIEWS)}x",
            "-geometry",
            "+2+2",
            "-background",
            "#222",
            str(out),
        ],
        check=True,
    )
    for tile in tiles:
        Path(tile).unlink()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("names", nargs="+")
    parser.add_argument(
        "--skin", type=Path, required=True, help="64x64 player skin PNG"
    )
    args = parser.parse_args()
    if not args.skin.is_file():
        parser.error(f"skin file does not exist: {args.skin}")
    if not args.dump.is_dir():
        parser.error(f"quad directory does not exist: {args.dump}")
    if not shutil.which("magick"):
        parser.error("ImageMagick 7 must provide magick on PATH")
    main(args.dump, args.out, args.names, args.skin)
