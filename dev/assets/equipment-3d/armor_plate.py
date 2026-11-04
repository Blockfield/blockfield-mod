"""Armour plate variants. Strike face looks south (+z); the plate stands upright."""

from kit import BRAND, Mat, Model

COVER = Mat("#2e2f2d", "fabric", noise=0.03, seed=1)
COVER_EDGE = Mat("#252624", "fabric", noise=0.03, seed=2)
STICKER = Mat("#e3dfd2", "flat", seed=3)
COAT = Mat("#8a7658", "foam", noise=0.03, edge=0.3, bevel=0.1, seed=4)
COAT_DARK = Mat("#6f5e45", "foam", noise=0.05, seed=5)
STEEL_EDGE = Mat("#4b4c4a", "metal", noise=0.04, wear=0.35, seed=6)
PE = Mat("#c9c7bf", "plastic", noise=0.02, edge=0.22, seed=7)
PE_SIDE = Mat("#b4b2aa", "plastic", noise=0.02, seed=8)
GUARD = Mat("#232422", "rubber", noise=0.03, bevel=0.07, seed=9)
INK = "#232320"


def barcode(c, x, y, w, h):
    for i in range(w):
        if (i * 7 + 3) % 5 < 3:
            c.vline(x + i, y, h, INK)


def sapi_label(c):
    c.logo(c.w // 2 - 11, 2, 19, INK)
    c.label(3, 24, "Blockfield", 6, INK, width=c.w - 6)
    c.label(3, 33, "Level IV", 5, INK)
    c.rect(c.w - 11, 32, 8, 7, BRAND)
    for i in range(c.w - 6):
        if (i * 7 + 3) % 5 < 3:
            c.vline(3 + i, 42, c.h - 44, INK)


def cover_front(c):
    c.stitch(1)
    c.stitch(4, "#3b3c39", 0.5)


def back_text(c):
    c.text(2, 6, "BODY", "#77766f", 0.7)
    c.text(2, 12, "SIDE", "#77766f", 0.7)


def shooters_cut(m, mat, z0, z1, decals=None, back=None, top=12, width=9):
    """Upper corners stepped in a 45 degree shooter's cut."""
    m.box(
        "plate",
        (-width, -12, z0),
        (width, top - 4, z1),
        mat,
        decals={**(decals or {}), **(back or {})},
    )
    m.box(
        "cut1", (-width + 2, top - 4, z0), (width - 2, top - 2, z1), mat, skip=("down",)
    )
    m.box("cut2", (-width + 4, top - 2, z0), (width - 4, top, z1), mat, skip=("down",))


def variant_a():
    """Ceramic SAPI in a black ballistic cover with factory labels."""
    m = Model("armor_plate_a", seed=41)
    shooters_cut(m, COVER, -1.5, 1.5, {"south": cover_front}, {"north": back_text})
    m.box(
        "label",
        (-6, -3, 1.5),
        (3, 5, 1.7),
        STICKER,
        decals={"south": sapi_label},
        skip=("north",),
        density=3,
    )
    m.box("pull_tab", (-2, 11, -0.5), (2, 13, 0.5), COVER_EDGE)
    m.box("edge_l", (-9.3, -12.3, -1.8), (-8.5, 8, 1.8), COVER_EDGE)
    m.box("edge_r", (8.5, -12.3, -1.8), (9.3, 8, 1.8), COVER_EDGE)
    m.box("edge_b", (-9.3, -12.6, -1.8), (9.3, -11.8, 1.8), COVER_EDGE)
    return m


def variant_b():
    """Multi-curve steel with a coyote spall coating; side wings fold back 22.5 degrees."""
    m = Model("armor_plate_b", seed=51)

    def stencil(c):
        c.text(2, c.h - 12, "III+", "#4a3c2b", 0.65)

    m.box("center", (-5, -12, -1), (5, 9, 1), COAT, decals={"south": stencil})
    m.box("cut", (-3, 9, -1), (3, 12, 1), COAT, skip=("down",))
    m.box("wing_l", (-9, -12, -1), (-5, 7, 1), COAT, rot=("y", -22.5, (-5, 0, 0)))
    m.box("wing_r", (5, -12, -1), (9, 7, 1), COAT, rot=("y", 22.5, (5, 0, 0)))
    m.box("shoulder_l", (-7, 7, -1), (-5, 9, 1), COAT, rot=("y", -22.5, (-5, 0, 0)))
    m.box("shoulder_r", (5, 7, -1), (7, 9, 1), COAT, rot=("y", 22.5, (5, 0, 0)))
    for x in (-5.3, 4.7):
        m.box(f"seam{x}", (x, -12, -1.05), (x + 0.6, 9, 1.05), COAT_DARK)
    m.box("rim_top", (-3, 12, -1.15), (3, 12.5, 1.15), STEEL_EDGE)
    m.box("rim_cut_l", (-5, 9, -1.15), (-3, 9.5, 1.15), STEEL_EDGE)
    m.box("rim_cut_r", (3, 9, -1.15), (5, 9.5, 1.15), STEEL_EDGE)
    m.box("rim_bottom", (-5, -12.5, -1.15), (5, -12, 1.15), STEEL_EDGE)
    m.box(
        "tag",
        (-5, 3, 1),
        (5, 7, 1.2),
        STICKER,
        decals={
            "south": lambda c: (
                c.text(1, 1, "AR500", INK),
                barcode(c, 1, 6, c.w - 2, 1),
            )
        },
        skip=("north",),
    )
    return m


def variant_c():
    """Polyethylene swimmer-cut plate with a black rubber edge guard."""
    m = Model("armor_plate_c", seed=61)

    def face(c):
        c.text(2, 2, "UHMWPE", "#7b7a73", 0.8)
        c.text(2, 8, "III", "#7b7a73", 0.8)
        barcode(c, 2, c.h - 6, 10, 4)

    m.box("plate", (-8, -9, -1.25), (8, 8, 1.25), PE, decals={"south": face})
    m.box("top", (-5, 8, -1.25), (5, 11, 1.25), PE, skip=("down",))
    m.box("bottom", (-6, -12, -1.25), (6, -9, 1.25), PE, skip=("up",))
    # Edge guard follows the cuts.
    for name, lo, hi in (
        ("g_l", (-8.6, -9, -1.6), (-8, 8, 1.6)),
        ("g_r", (8, -9, -1.6), (8.6, 8, 1.6)),
        ("g_t", (-5, 11, -1.6), (5, 11.6, 1.6)),
        ("g_b", (-6, -12.6, -1.6), (6, -12, 1.6)),
        ("g_tl", (-8.6, 8, -1.6), (-5, 8.6, 1.6)),
        ("g_tr", (5, 8, -1.6), (8.6, 8.6, 1.6)),
        ("g_tl2", (-5.6, 8.6, -1.6), (-5, 11, 1.6)),
        ("g_tr2", (5, 8.6, -1.6), (5.6, 11, 1.6)),
        ("g_bl", (-8.6, -9.6, -1.6), (-6, -9, 1.6)),
        ("g_br", (6, -9.6, -1.6), (8.6, -9, 1.6)),
        ("g_bl2", (-6.6, -12, -1.6), (-6, -9.6, 1.6)),
        ("g_br2", (6, -12, -1.6), (6.6, -9.6, 1.6)),
    ):
        m.box(name, lo, hi, GUARD)
    m.box(
        "sticker",
        (-6, -6, 1.25),
        (2, 0, 1.45),
        STICKER,
        decals={
            "south": lambda c: (c.text(1, 0, "LOT", INK), barcode(c, 1, 6, c.w - 2, 0))
        },
        skip=("north",),
    )
    return m


DISPLAY = {
    "firstperson_righthand": {
        "rotation": [6, -24, 0],
        "translation": [-2, 4, -2],
        "scale": [0.36] * 3,
    },
    "firstperson_lefthand": {
        "rotation": [6, 24, 0],
        "translation": [-2, 4, -2],
        "scale": [0.36] * 3,
    },
}


VARIANTS = {
    "A": ("Керамика SAPI в чехле", variant_a),
    "B": ("Изогнутая сталь с покрытием", variant_b),
    "C": ("Полиэтилен с окантовкой", variant_c),
}
