"""Cuboid modelling kit: painted per-face atlas, Java/Bedrock export and an offline preview.

Geometry is authored in Java/Blockbench space (x east, y up, z south) in texel units:
one model unit is one texture pixel, so detail density is uniform across every part.
"""

from dataclasses import dataclass, field
import functools
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FACES = ("north", "south", "east", "west", "up", "down")
NORMALS = {
    "north": (0, 0, -1),
    "south": (0, 0, 1),
    "east": (1, 0, 0),
    "west": (-1, 0, 0),
    "up": (0, 1, 0),
    "down": (0, -1, 0),
}


def rgb(value):
    value = value.lstrip("#")
    return np.array([int(value[i : i + 2], 16) / 255 for i in (0, 2, 4)])


# Painting --------------------------------------------------------------------------


@dataclass
class Mat:
    color: str
    kind: str = "plastic"  # plastic, fabric, metal, rubber, carbon, flat, screen, foam
    noise: float = 0.035
    edge: float = 0.28
    bevel: float = 0.14
    wear: float = 0.0  # chipped light edge pixels, metal and hard cases
    seed: int = 0


def paint_face(w, h, mat: Mat, rng):
    base = rgb(mat.color)
    img = np.ones((h, w, 3)) * base
    y, x = np.mgrid[0:h, 0:w]
    shade = np.ones((h, w))
    if mat.kind == "fabric":
        shade += ((x + y) % 2 - 0.5) * 0.045 + ((x // 2 + y) % 3 == 0) * -0.02
        shade += rng.normal(0, mat.noise * 0.45, (h, w))
    elif mat.kind == "carbon":
        shade += (((x // 2) + (y // 2)) % 2 - 0.5) * 0.16 + rng.normal(0, 0.015, (h, w))
    elif mat.kind == "foam":
        shade += rng.normal(0, mat.noise * 1.0, (h, w))
    elif mat.kind == "screen":
        shade += (y / max(h - 1, 1) - 0.5) * -0.18
    elif mat.kind != "flat":
        shade += rng.normal(0, mat.noise * 0.55, (h, w))
        # Large soft blotches keep plastic and paint from looking like flat fill.
        blot = rng.normal(0, 1, (max(h // 4, 1) + 1, max(w // 4, 1) + 1))
        shade += np.kron(blot, np.ones((4, 4)))[:h, :w] * mat.noise * 0.6
    if mat.kind not in ("flat", "screen") and min(w, h) >= 2:
        shade[0, :] += mat.bevel
        shade[:, 0] += mat.bevel * 0.5
        shade[-1, :] -= mat.edge
        shade[:, -1] -= mat.edge * 0.6
        if min(w, h) >= 4:
            shade[1, 1:-1] += mat.bevel * 0.35
    img *= shade[..., None]
    if mat.wear and min(w, h) >= 2:
        ring = np.zeros((h, w), bool)
        ring[0, :] = ring[-1, :] = ring[:, 0] = ring[:, -1] = True
        chips = ring & (rng.random((h, w)) < mat.wear)
        img[chips] = img[chips] * 0.4 + np.array([0.62, 0.6, 0.55]) * 0.6
    return np.clip(img, 0, 1)


class Canvas:
    """Face-local decal painter; (0, 0) is the face's texture top-left."""

    def __init__(self, img, rng):
        self.img, self.rng = img, rng
        self.h, self.w = img.shape[:2]

    def px(self, x, y, color, alpha=1.0):
        if 0 <= x < self.w and 0 <= y < self.h:
            c = rgb(color) if isinstance(color, str) else np.asarray(color)
            self.img[y, x] = self.img[y, x] * (1 - alpha) + c * alpha

    def rect(self, x, y, w, h, color, alpha=1.0):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.px(xx, yy, color, alpha)

    def frame(self, x, y, w, h, color, alpha=1.0):
        for xx in range(x, x + w):
            self.px(xx, y, color, alpha)
            self.px(xx, y + h - 1, color, alpha)
        for yy in range(y, y + h):
            self.px(x, yy, color, alpha)
            self.px(x + w - 1, yy, color, alpha)

    def shade(self, x, y, w, h, factor):
        x0, y0 = max(x, 0), max(y, 0)
        self.img[y0 : y + h, x0 : x + w] *= factor

    def hline(self, x, y, w, color, alpha=1.0, dash=0):
        for xx in range(x, x + w):
            if not dash or (xx - x) % (dash * 2) < dash:
                self.px(xx, y, color, alpha)

    def vline(self, x, y, h, color, alpha=1.0, dash=0):
        for yy in range(y, y + h):
            if not dash or (yy - y) % (dash * 2) < dash:
                self.px(x, yy, color, alpha)

    def stitch(self, inset=1, color="#00000000", alpha=0.45, dash=1):
        c = color if color != "#00000000" else None
        col = c or tuple(np.clip(self.img[self.h // 2, self.w // 2] * 1.35, 0, 1))
        self.hline(inset, inset, self.w - 2 * inset, col, alpha, dash)
        self.hline(inset, self.h - 1 - inset, self.w - 2 * inset, col, alpha, dash)
        self.vline(inset, inset, self.h - 2 * inset, col, alpha, dash)
        self.vline(self.w - 1 - inset, inset, self.h - 2 * inset, col, alpha, dash)

    def cross(self, cx, cy, arm, thick, color, outline=None):
        if outline:
            self.rect(
                cx - arm - 1,
                cy - thick // 2 - 1,
                arm * 2 + 2 + (thick % 2 == 0) * 0,
                thick + 2,
                outline,
            )
            self.rect(
                cx - thick // 2 - 1, cy - arm - 1, thick + 2, arm * 2 + 2, outline
            )
        self.rect(cx - arm, cy - thick // 2, arm * 2, thick, color)
        self.rect(cx - thick // 2, cy - arm, thick, arm * 2, color)

    def text(self, x, y, s, color, alpha=1.0):
        for ch in s.upper():
            glyph = FONT.get(ch)
            if glyph:
                for gy, row in enumerate(glyph):
                    for gx, bit in enumerate(row):
                        if bit == "#":
                            self.px(x + gx, y + gy, color, alpha)
            x += 4

    def logo(self, x, y, h, color=None, alpha=1.0, turned=False):
        """Blockfield mark `h` px tall at (x, y), antialiased; `color` tints it.

        `turned` rotates it 180 degrees, for top faces read from the item's back."""
        mark = brand_logo(h)[::-1, ::-1] if turned else brand_logo(h)
        rgb_ = (
            mark[..., :3]
            if color is None
            else np.broadcast_to(rgb(color), mark[..., :3].shape)
        )
        self.blend(x, y, rgb_, mark[..., 3] * alpha)

    def label(self, x, y, s, h, color, alpha=1.0, width=None):
        """Text in the brand typeface with cap height `h` px, squeezed to `width` if given."""
        mask = brand_text(s, h, width)
        self.blend(x, y, np.broadcast_to(rgb(color), mask.shape + (3,)), mask * alpha)

    def blend(self, x, y, color, alpha):
        h, w = alpha.shape
        x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + w, self.w), min(y + h, self.h)
        if x0 >= x1 or y0 >= y1:
            return
        a = alpha[y0 - y : y1 - y, x0 - x : x1 - x, None]
        region = self.img[y0:y1, x0:x1]
        self.img[y0:y1, x0:x1] = (
            region * (1 - a) + color[y0 - y : y1 - y, x0 - x : x1 - x] * a
        )

    def screws(self, inset, color="#1a1a1a", glint="#9a9a96"):
        for x, y in (
            (inset, inset),
            (self.w - 1 - inset, inset),
            (inset, self.h - 1 - inset),
            (self.w - 1 - inset, self.h - 1 - inset),
        ):
            self.px(x, y, color)
            self.px(x, y - 1, glint, 0.5)

    def noise(self, x, y, w, h, amount):
        self.img[y : y + h, x : x + w] *= (
            1
            + self.rng.normal(0, amount, self.img[y : y + h, x : x + w].shape[:2])[
                ..., None
            ]
        )


BRAND = "#f0a429"  # Blockfield amber (site logo, tablet accent)
BRAND_ASSETS = (
    Path(__file__).resolve().parents[3] / "client/src/main/resources/assets/blockfield"
)


@functools.cache
def brand_logo(h):
    """The shipped GUI logo cropped to its mark, scaled to `h` px: float RGBA."""
    im = Image.open(BRAND_ASSETS / "textures/gui/logo.png").convert("RGBA")
    im = im.crop(im.getbbox())
    w = max(1, round(im.width * h / im.height))
    return np.asarray(im.resize((w, h), Image.LANCZOS), float) / 255


@functools.cache
def brand_text(s, h, width=None):
    """Alpha mask of `s` in IBM Plex Sans Condensed SemiBold (the mod's UI face)."""
    scale = 8  # supersample, then box-filter down for clean small text
    font = ImageFont.truetype(
        str(BRAND_ASSETS / "font/plex-sans-condensed-semibold.ttf"),
        round(h * scale / 0.7),
    )
    x0, y0, x1, y1 = font.getbbox(s.upper())
    big = Image.new("L", (x1 - x0, y1 - y0))
    ImageDraw.Draw(big).text((-x0, -y0), s.upper(), font=font, fill=255)
    w = round(big.width / scale) if width is None else width
    return np.asarray(big.resize((max(w, 1), h), Image.BOX), float) / 255


FONT = {
    "A": [".#.", "#.#", "###", "#.#", "#.#"],
    "B": ["##.", "#.#", "##.", "#.#", "##."],
    "C": [".##", "#..", "#..", "#..", ".##"],
    "D": ["##.", "#.#", "#.#", "#.#", "##."],
    "E": ["###", "#..", "##.", "#..", "###"],
    "F": ["###", "#..", "##.", "#..", "#.."],
    "G": [".##", "#..", "#.#", "#.#", ".##"],
    "H": ["#.#", "#.#", "###", "#.#", "#.#"],
    "I": ["###", ".#.", ".#.", ".#.", "###"],
    "K": ["#.#", "#.#", "##.", "#.#", "#.#"],
    "L": ["#..", "#..", "#..", "#..", "###"],
    "M": ["#.#", "###", "###", "#.#", "#.#"],
    "N": ["##.", "#.#", "#.#", "#.#", "#.#"],
    "O": [".#.", "#.#", "#.#", "#.#", ".#."],
    "P": ["##.", "#.#", "##.", "#..", "#.."],
    "R": ["##.", "#.#", "##.", "#.#", "#.#"],
    "S": [".##", "#..", ".#.", "..#", "##."],
    "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "U": ["#.#", "#.#", "#.#", "#.#", "###"],
    "V": ["#.#", "#.#", "#.#", "#.#", ".#."],
    "W": ["#.#", "#.#", "###", "###", "#.#"],
    "X": ["#.#", "#.#", ".#.", "#.#", "#.#"],
    "Y": ["#.#", "#.#", ".#.", ".#.", ".#."],
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["##.", "..#", ".#.", "#..", "###"],
    "3": ["##.", "..#", ".#.", "..#", "##."],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "##.", "..#", "##."],
    "6": [".##", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "##."],
    "-": ["...", "...", "###", "...", "..."],
    "+": ["...", ".#.", "###", ".#.", "..."],
    ".": ["...", "...", "...", "...", ".#."],
    "/": ["..#", "..#", ".#.", "#..", "#.."],
    " ": ["...", "...", "...", "...", "..."],
}


# Geometry --------------------------------------------------------------------------


@dataclass
class Box:
    name: str
    lo: tuple
    hi: tuple
    mat: object  # Mat or {face: Mat}
    decals: dict = field(default_factory=dict)  # face -> callable(Canvas)
    rot: tuple | None = (
        None  # (axis, degrees, origin); Java allows one axis in 22.5 steps
    )
    bone: str = "body"
    skip: tuple = ()
    inflate: float = 0.0
    density: float = 1.0  # extra texels per unit, for labels and logos

    def mat_for(self, face):
        return self.mat[face] if isinstance(self.mat, dict) else self.mat


def face_dims(box, face):
    sx, sy, sz = (box.hi[i] - box.lo[i] for i in range(3))
    return {
        "north": (sx, sy),
        "south": (sx, sy),
        "east": (sz, sy),
        "west": (sz, sy),
        "up": (sx, sz),
        "down": (sx, sz),
    }[face]


class Model:
    def __init__(self, name, seed=1, density=2):
        self.name, self.seed, self.density = name, seed, density
        self.boxes: list[Box] = []
        self.bones: dict[str, tuple] = {"body": (0, 0, 0)}
        self.parents: dict[str, str] = {}

    def box(self, name, lo, hi, mat, bone="body", **kw):
        b = Box(
            name, tuple(map(float, lo)), tuple(map(float, hi)), mat, bone=bone, **kw
        )
        for i in range(3):
            if b.hi[i] < b.lo[i]:
                raise ValueError(f"{self.name}/{name}: inverted axis {i}")
        if b.rot:
            axis, angle, _ = b.rot
            if axis not in "xyz" or angle not in (-45, -22.5, 0, 22.5, 45):
                raise ValueError(f"{self.name}/{name}: Java-illegal rotation {b.rot}")
        self.boxes.append(b)
        return b

    def bone(self, name, pivot, parent="body"):
        self.bones[name] = tuple(pivot)
        self.parents[name] = parent

    def bounds(self):
        lo = np.min([b.lo for b in self.boxes], axis=0)
        hi = np.max([b.hi for b in self.boxes], axis=0)
        return lo, hi

    # Z-fighting ----------------------------------------------------------------------

    def coplanar_pairs(self, tol=0.01):
        """Pairs of same-facing faces in one plane that overlap; these flicker in game."""
        faces = []
        for i, b in enumerate(self.boxes):
            for face in FACES:
                if face in b.skip:
                    continue
                axis, sign = FACE_AXES[face][:2]
                if b.rot and "xyz".index(b.rot[0]) != axis:
                    continue  # tilted face: never coplanar with an axis-aligned one
                plane = b.hi[axis] if sign > 0 else b.lo[axis]
                others = [k for k in range(3) if k != axis]
                corners = np.array(
                    [
                        [b.lo[0], b.lo[1], b.lo[2]],
                        [b.hi[0], b.hi[1], b.hi[2]],
                        [b.lo[0], b.hi[1], b.hi[2]],
                        [b.hi[0], b.lo[1], b.lo[2]],
                        [b.lo[0], b.lo[1], b.hi[2]],
                        [b.hi[0], b.hi[1], b.lo[2]],
                        [b.lo[0], b.hi[1], b.lo[2]],
                        [b.hi[0], b.lo[1], b.hi[2]],
                    ],
                    float,
                )
                if b.rot:
                    r = rot_matrix(b.rot[0], b.rot[1])
                    o = np.array(b.rot[2], float)
                    corners = (corners - o) @ r.T + o
                lo2, hi2 = (
                    corners[:, others].min(axis=0),
                    corners[:, others].max(axis=0),
                )
                area = float(np.prod(hi2 - lo2))
                faces.append((i, face, axis, sign, plane, lo2, hi2, area))
        pairs = []
        for n, a in enumerate(faces):
            for c in faces[n + 1 :]:
                if (
                    a[0] == c[0]
                    or a[2] != c[2]
                    or a[3] != c[3]
                    or abs(a[4] - c[4]) > tol
                ):
                    continue
                overlap = np.minimum(a[6], c[6]) - np.maximum(a[5], c[5])
                if np.all(overlap > 1e-3):
                    pairs.append((a, c))
        return pairs

    def separate_coplanar(self, eps=0.06):
        """Pull the smaller of two coplanar same-facing faces back by `eps` (model units)."""
        for _ in range(8):
            pairs = self.coplanar_pairs()
            if not pairs:
                return
            for a, c in pairs:
                i, face, axis, sign = min(a, c, key=lambda f: (f[7], -f[0]))[:4]
                b = self.boxes[i]
                lo, hi = list(b.lo), list(b.hi)
                if sign > 0:
                    hi[axis] = min(hi[axis], (a[4] if a[0] == i else c[4]) - eps)
                else:
                    lo[axis] = max(lo[axis], (a[4] if a[0] == i else c[4]) + eps)
                if hi[axis] - lo[axis] > 0.05:
                    b.lo, b.hi = tuple(lo), tuple(hi)
        raise ValueError(
            f"{self.name}: coplanar faces remain: {len(self.coplanar_pairs())}"
        )

    # Atlas -------------------------------------------------------------------------

    def contact_shadow(self, index, face, tile):
        """Darken a soft ring where other parts stand on this face (baked ambient occlusion)."""
        b = self.boxes[index]
        axis, sign, (ua, uhi), (va, vhi) = FACE_AXES[face]
        plane = b.hi[axis] if sign > 0 else b.lo[axis]
        h, w = tile.shape[:2]
        cy, cx = (np.mgrid[0:h, 0:w] + 0.5) / self.density
        occl = np.zeros((h, w))
        for j, o in enumerate(self.boxes):
            if j == index or o.rot:
                continue
            if sign > 0 and not (
                o.lo[axis] <= plane + 0.05 and o.hi[axis] > plane + 0.05
            ):
                continue
            if sign < 0 and not (
                o.hi[axis] >= plane - 0.05 and o.lo[axis] < plane - 0.05
            ):
                continue

            def span(a, from_hi):
                lo_, hi_ = (
                    (b.hi[a] - o.hi[a], b.hi[a] - o.lo[a])
                    if from_hi
                    else (o.lo[a] - b.lo[a], o.hi[a] - b.lo[a])
                )
                return lo_, hi_

            x0, x1 = span(ua, uhi)
            y0, y1 = span(va, vhi)
            if x1 <= 0 or y1 <= 0 or x0 >= w / self.density or y0 >= h / self.density:
                continue
            reach = min(2.5, 1 + 0.25 * (o.hi[axis] - o.lo[axis])) / self.density**0.5
            dx = np.maximum(np.maximum(x0 - cx, cx - x1), 0)
            dy = np.maximum(np.maximum(y0 - cy, cy - y1), 0)
            d = np.hypot(dx, dy)
            occl = np.maximum(occl, np.clip(1 - d / reach, 0, 1))
        tile *= (1 - 0.38 * occl)[..., None]

    def bake(self):
        """Pack and paint every visible face; returns (RGBA image, {(box, face): rect})."""
        self.separate_coplanar()
        rng = np.random.default_rng(self.seed)
        rects = []
        for i, b in enumerate(self.boxes):
            for face in FACES:
                if face in b.skip:
                    continue
                w, h = face_dims(b, face)
                d = self.density * b.density
                pw, ph = (
                    max(1, math.ceil(w * d - 1e-6)),
                    max(1, math.ceil(h * d - 1e-6)),
                )
                if w <= 0 or h <= 0:
                    continue
                rects.append((i, face, pw, ph))
        size = 32
        while True:
            placed = shelf_pack(rects, size)
            if placed is not None:
                break
            size *= 2
        img = np.zeros((size, size, 4))
        uv = {}
        for (i, face, pw, ph), (x, y) in placed.items():
            b = self.boxes[i]
            mat = b.mat_for(face)
            face_rng = np.random.default_rng(rng.integers(1 << 30) + mat.seed)
            tile = paint_face(pw, ph, mat, face_rng)
            if face not in ("up", "down") and mat.kind not in ("flat", "screen"):
                tile *= (1 + (0.5 - (np.arange(ph) + 0.5) / ph) * 0.14)[:, None, None]
            if not b.rot:
                self.contact_shadow(i, face, tile)
            if face in b.decals:
                fns = b.decals[face]
                for fn in fns if isinstance(fns, (list, tuple)) else [fns]:
                    fn(Canvas(tile, face_rng))
            img[y : y + ph, x : x + pw, :3] = np.clip(tile, 0, 1)
            img[y : y + ph, x : x + pw, 3] = 1
            uv[(i, face)] = (x, y, pw, ph)
        return img, uv


FACE_AXES = {
    # face: (normal axis, outward sign, pixel-x (axis, from hi?), pixel-y (axis, from hi?))
    "north": (2, -1, (0, True), (1, True)),
    "south": (2, 1, (0, False), (1, True)),
    "east": (0, 1, (2, True), (1, True)),
    "west": (0, -1, (2, False), (1, True)),
    "up": (1, 1, (0, False), (2, False)),
    "down": (1, -1, (0, False), (2, True)),
}


def shelf_pack(rects, size):
    order = sorted(rects, key=lambda r: (-r[3], -r[2]))
    x = y = shelf = 0
    out = {}
    for r in order:
        _, _, w, h = r
        if w > size:
            return None
        if x + w > size:
            x, y, shelf = 0, y + shelf, 0
        if y + h > size:
            return None
        out[r] = (x, y)
        x += w
        shelf = max(shelf, h)
    return out


# Export ----------------------------------------------------------------------------


def java_model(model, texture_ref, uv, tex_size, scale, display):
    """Java item model; geometry is scaled by `scale` and centred in the 0..16 cube."""
    lo, hi = model.bounds()
    center = (lo + hi) / 2
    offset = np.array([8, 8, 8]) - center * scale
    elements = []
    for i, b in enumerate(model.boxes):
        frm = np.array(b.lo) * scale + offset
        to = np.array(b.hi) * scale + offset
        if np.any(frm < -16) or np.any(to > 32):
            raise ValueError(
                f"{model.name}/{b.name}: outside Java bounds, lower the scale"
            )
        faces = {}
        for face in FACES:
            if (i, face) not in uv:
                continue
            x, y, w, h = uv[(i, face)]
            k = 16 / tex_size
            faces[face] = {
                "uv": [
                    round(x * k, 4),
                    round(y * k, 4),
                    round((x + w) * k, 4),
                    round((y + h) * k, 4),
                ],
                "texture": "#0",
            }
        el = {
            "name": b.name,
            "from": [round(v, 4) for v in frm],
            "to": [round(v, 4) for v in to],
            "faces": faces,
        }
        if b.rot:
            axis, angle, origin = b.rot
            el["rotation"] = {
                "angle": angle,
                "axis": axis,
                "origin": [round(v, 4) for v in np.array(origin) * scale + offset],
            }
        elements.append(el)
    return {
        "credit": "Blockfield",
        "texture_size": [tex_size, tex_size],
        "textures": {"0": texture_ref, "particle": texture_ref},
        "elements": elements,
        "display": display,
    }


def bedrock_cubes(model, uv, bone):
    """Per-face Bedrock cubes for one bone, mirroring Blockbench's Bedrock codec (x is negated)."""
    cubes = []
    for i, b in enumerate(model.boxes):
        if b.bone != bone:
            continue
        faces = {}
        for face in FACES:
            if (i, face) not in uv:
                continue
            x, y, w, h = uv[(i, face)]
            # Bedrock flips up/down faces on both axes relative to Java.
            faces[face] = (
                {"uv": [x + w, y + h], "uv_size": [-w, -h]}
                if face in ("up", "down")
                else {"uv": [x, y], "uv_size": [w, h]}
            )
        cube = {
            "origin": [round(-b.hi[0], 4), round(b.lo[1], 4), round(b.lo[2], 4)],
            "size": [round(b.hi[k] - b.lo[k], 4) for k in range(3)],
            "uv": faces,
        }
        if b.inflate:
            cube["inflate"] = b.inflate
        if b.rot:
            axis, angle, origin = b.rot
            cube["pivot"] = [
                round(-origin[0], 4),
                round(origin[1], 4),
                round(origin[2], 4),
            ]
            cube["rotation"] = {
                "x": [-angle, 0, 0],
                "y": [0, -angle, 0],
                "z": [0, 0, angle],
            }[axis]
        cubes.append(cube)
    return cubes


# Offline preview ---------------------------------------------------------------------


def rot_matrix(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def face_quad(lo, hi, face):
    """Corners ordered texture top-left, top-right, bottom-right, bottom-left (Java UV rules)."""
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }[face]


def quads(model, uv, pose=None):
    """World-space textured quads; `pose` maps bone -> 4x4 matrix for animated previews."""
    out = []
    for i, b in enumerate(model.boxes):
        rot = None
        if b.rot:
            axis, angle, origin = b.rot
            rot = (rot_matrix(axis, angle), np.array(origin, float))
        bone = (pose or {}).get(b.bone)
        lo = np.array(b.lo) - b.inflate
        hi = np.array(b.hi) + b.inflate
        for face in FACES:
            if (i, face) not in uv:
                continue
            pts = np.array(face_quad(lo, hi, face), float)
            n = np.array(NORMALS[face], float)
            if rot:
                pts = (pts - rot[1]) @ rot[0].T + rot[1]
                n = rot[0] @ n
            if bone is not None:
                pts = pts @ bone[:3, :3].T + bone[:3, 3]
                n = bone[:3, :3] @ n
            out.append((pts, uv[(i, face)], n))
    return out


def render(
    model,
    atlas,
    uv,
    yaw=30,
    pitch=22,
    size=512,
    fov=22,
    pose=None,
    background=True,
    light=None,
    frame=None,
):
    """Perspective preview; yaw 0 faces the south (front) side, positive pitch looks from above."""
    ss = 2
    W = H = size * ss
    lo, hi = frame or model.bounds()
    center = (np.array(lo) + np.array(hi)) / 2
    radius = np.linalg.norm(np.array(hi) - np.array(lo)) / 2
    dist = radius / math.tan(math.radians(fov / 2)) * 1.08
    y, p = math.radians(yaw), math.radians(pitch)
    eye = center + dist * np.array(
        [math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p)]
    )
    fwd = (center - eye) / np.linalg.norm(center - eye)
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    f = (H / 2) / math.tan(math.radians(fov / 2))
    color = np.zeros((H, W, 3))
    if background:
        gy = np.linspace(0, 1, H)[:, None, None]
        color[:] = rgb("#1d2427") * (1 - gy) + rgb("#0e1214") * gy
    zbuf = np.full((H, W), np.inf)
    lights = light or [
        (np.array([-0.45, 0.8, 0.55]), 0.6),
        (np.array([0.7, 0.25, -0.5]), 0.2),
    ]
    for pts, (ux, uy, uw, uh), n in quads(model, uv, pose):
        rel = pts - eye
        if np.dot(n, eye - pts.mean(axis=0)) <= 0:
            continue
        wn = n / np.linalg.norm(n)
        bright = 0.45 + sum(
            max(0.0, float(np.dot(wn, d / np.linalg.norm(d)))) * k for d, k in lights
        )
        depth = rel @ fwd
        sx = W / 2 + (rel @ right) * f / depth
        sy = H / 2 - (rel @ up) * f / depth
        tuv = np.array(
            [[ux, uy], [ux + uw, uy], [ux + uw, uy + uh], [ux, uy + uh]], float
        )
        for tri in ((0, 1, 2), (0, 2, 3)):
            t = list(tri)
            raster(
                color, zbuf, sx[t], sy[t], depth[t], tuv[t], atlas, min(bright, 1.15)
            )
    img = color.reshape(size, ss, size, ss, 3).mean(axis=(1, 3))
    mask = np.isfinite(zbuf).reshape(size, ss, size, ss).mean(axis=(1, 3))
    return np.clip(img, 0, 1), mask


def raster(color, zbuf, xs, ys, zs, tuv, tex, bright):
    H, W = zbuf.shape
    x0, x1 = max(int(xs.min()), 0), min(int(xs.max()) + 1, W - 1)
    y0, y1 = max(int(ys.min()), 0), min(int(ys.max()) + 1, H - 1)
    if x0 > x1 or y0 > y1:
        return
    py, px = np.mgrid[y0 : y1 + 1, x0 : x1 + 1] + 0.5
    (ax, bx, cx), (ay, by, cy) = xs, ys
    det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    if abs(det) < 1e-9:
        return
    l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / det
    l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / det
    l3 = 1 - l1 - l2
    inside = (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6)
    if not inside.any():
        return
    iz = l1 / zs[0] + l2 / zs[1] + l3 / zs[2]
    z = 1 / iz
    u = (l1 * tuv[0, 0] / zs[0] + l2 * tuv[1, 0] / zs[1] + l3 * tuv[2, 0] / zs[2]) * z
    v = (l1 * tuv[0, 1] / zs[0] + l2 * tuv[1, 1] / zs[1] + l3 * tuv[2, 1] / zs[2]) * z
    th, tw = tex.shape[:2]
    ui = np.clip(u.astype(int), 0, tw - 1)
    vi = np.clip(v.astype(int), 0, th - 1)
    sample = tex[vi, ui]
    sub = zbuf[y0 : y1 + 1, x0 : x1 + 1]
    ok = inside & (z < sub) & (sample[..., 3] > 0.5)
    sub[ok] = z[ok]
    color[y0 : y1 + 1, x0 : x1 + 1][ok] = sample[..., :3][ok] * bright


def save_png(arr, path, alpha=None):
    data = (np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8)
    if alpha is not None:
        data = np.dstack([data, (np.clip(alpha, 0, 1) * 255 + 0.5).astype(np.uint8)])
    Image.fromarray(data).save(path)


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


# Shape helpers -------------------------------------------------------------------------


def rod(m, name, axis, center, length, radius, mat, bone="body", **kw):
    """Rounded bar: a plus-shaped section reads as a cylinder at item scale."""
    cx, cy, cz = center
    r, s, h = radius, radius * 0.62, length / 2
    spans = {
        "x": [
            ((cx - h, cy - s, cz - r), (cx + h, cy + s, cz + r)),
            ((cx - h, cy - r, cz - s), (cx + h, cy + r, cz + s)),
        ],
        "y": [
            ((cx - s, cy - h, cz - r), (cx + s, cy + h, cz + r)),
            ((cx - r, cy - h, cz - s), (cx + r, cy + h, cz + s)),
        ],
        "z": [
            ((cx - s, cy - r, cz - h), (cx + s, cy + r, cz + h)),
            ((cx - r, cy - s, cz - h), (cx + r, cy + s, cz + h)),
        ],
    }[axis]
    return [
        m.box(f"{name}{i}", lo, hi, mat, bone=bone, **kw)
        for i, (lo, hi) in enumerate(spans)
    ]


def ring_y(m, name, center, radius, height, thick, mat, bone="body"):
    """Octagonal duct around the y axis built from four straight and four 45 degree bars."""
    cx, cy, cz = center
    side = radius * 0.8284  # octagon side for the given apothem
    out = []
    for i, rot in enumerate((None, ("y", 45, (cx, cy, cz)))):
        for j, (dx, dz, wide_x) in enumerate(
            ((0, -1, True), (0, 1, True), (-1, 0, False), (1, 0, False))
        ):
            if wide_x:
                lo = (cx - side / 2, cy, cz - radius if dz < 0 else cz + radius - thick)
                hi = (cx + side / 2, cy + height, lo[2] + thick)
            else:
                lo = (cx - radius if dx < 0 else cx + radius - thick, cy, cz - side / 2)
                hi = (lo[0] + thick, cy + height, cz + side / 2)
            out.append(m.box(f"{name}{i}{j}", lo, hi, mat, bone=bone, rot=rot))
    return out


def prism_z(m, name, center, length, radius, mat, bone="body", **kw):
    """Octagon-like prism along z: a square plus the same square turned 45 degrees."""
    cx, cy, cz = center
    h, side = length / 2, radius * 0.86
    return [
        m.box(
            f"{name}0",
            (cx - radius, cy - radius, cz - h),
            (cx + radius, cy + radius, cz + h),
            mat,
            bone=bone,
            **kw,
        ),
        m.box(
            f"{name}1",
            (cx - side, cy - side, cz - h + 0.01),
            (cx + side, cy + side, cz + h - 0.01),
            mat,
            bone=bone,
            rot=("z", 45, (cx, cy, cz)),
            **kw,
        ),
    ]
