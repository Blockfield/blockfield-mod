"""Medical kit variants. Front (cross patch) faces south, +z; up is +y."""

from kit import BRAND, Mat, Model, rod

COYOTE = Mat("#8d7756", "fabric", seed=1)
COYOTE_DARK = Mat("#715f44", "fabric", seed=2)
WEB = Mat("#7c6849", "fabric", noise=0.05, bevel=0.1, edge=0.4, seed=3)
LOOP = Mat("#6b6f4c", "foam", noise=0.07, seed=4)
RED = Mat("#b3272b", "fabric", seed=5)
RED_DARK = Mat("#8a1c21", "fabric", seed=6)
NYLON = Mat("#2c2d2b", "fabric", seed=7)
TQ_STRAP = Mat("#3b3c38", "fabric", noise=0.05, seed=17)
BUCKLE = Mat("#232322", "plastic", noise=0.02, seed=8)
TQ_RED = Mat("#c32b2b", "plastic", seed=9)
ZIP = Mat("#55524a", "metal", noise=0.05, wear=0.3, seed=10)
OLIVE = Mat("#58623f", "plastic", noise=0.03, wear=0.16, seed=11)
OLIVE_DARK = Mat("#475033", "plastic", noise=0.03, wear=0.1, seed=12)
LATCH = Mat("#353733", "metal", noise=0.04, wear=0.3, seed=13)
RUBBER = Mat("#1f201e", "rubber", noise=0.03, bevel=0.06, seed=14)
REFLECT = Mat("#b9bdbd", "flat", seed=15)
LABEL = Mat("#ddd7c3", "flat", seed=16)
WHITE = "#ece9df"
INJECTOR = Mat("#c9c24a", "plastic", noise=0.02, seed=32)
CROSS = Mat("#e9e6dc", "plastic", noise=0.015, edge=0.12, bevel=0.05, seed=41)


def cross_patch(c):
    c.rect(0, 0, c.w, c.h, WHITE)
    c.cross(c.w // 2, c.h // 2, c.w // 2 - 1, max(2, c.w // 3), "#c3282b")
    c.frame(0, 0, c.w, c.h, "#3a3a34", 0.55)


def zipper(y):
    def paint(c):
        for x in range(c.w):
            c.px(x, y, "#1e1d1a")
            c.px(x, y + 1, "#6b665a" if x % 2 else "#2c2a25")

    return paint


def side_zipper(c):
    zipper(2)(c)
    c.stitch(1)


def webbing_lines(c):
    for x in range(0, c.w, 6):
        c.vline(x, 0, c.h, "#4f4230", 0.6)


def variant_a():
    """Rip-away IFAK: coyote pouch on a MOLLE panel, red pull handle, tourniquet on top."""
    m = Model("medical_kit_a", seed=11)
    m.box(
        "panel",
        (-11, -8, -6),
        (11, 7, -4),
        COYOTE_DARK,
        decals={"north": lambda c: c.stitch(1)},
    )
    for i, y in enumerate((-6, -2, 2)):
        m.box(
            f"molle{i}",
            (-10, y, -7),
            (10, y + 2, -6),
            WEB,
            decals={"north": webbing_lines},
            skip=("south",),
        )
    m.box(
        "pouch",
        (-10, -8, -4),
        (10, 4, 4),
        COYOTE,
        decals={
            "south": lambda c: c.stitch(1),
            "east": lambda c: c.stitch(1),
            "west": lambda c: c.stitch(1),
        },
    )
    # Clamshell lid: the zipper runs round its lower seam.
    m.box(
        "lid",
        (-10.4, 4, -4.4),
        (10.4, 7, 4.4),
        COYOTE,
        decals={
            "south": zipper(1),
            "east": zipper(1),
            "west": zipper(1),
            "up": lambda c: c.stitch(1),
        },
    )
    m.box(
        "loop_field",
        (-6, -6, 4),
        (6, 3, 4.5),
        LOOP,
        decals={"south": lambda c: c.stitch(0, "#4b4f36", 0.8)},
        skip=("north",),
    )
    m.box(
        "patch",
        (-3.5, -4.5, 4.5),
        (3.5, 2.5, 5),
        LOOP,
        decals={"south": cross_patch},
        skip=("north",),
    )
    # Red rip-away handle on the upper edge of the panel.
    m.box("pull_l", (-3, 7, -6), (-2, 9.5, -4.5), RED_DARK)
    m.box("pull_r", (2, 7, -6), (3, 9.5, -4.5), RED_DARK)
    m.box(
        "pull_top",
        (-3, 9.5, -6),
        (3, 10.5, -4.5),
        RED,
        decals={"south": lambda c: c.hline(0, 0, c.w, "#d9554f", 0.6)},
    )
    # Tourniquet held across the lid by two elastic keepers.
    m.box(
        "tq_strap",
        (-9, 7, -1.5),
        (8, 9, 2.5),
        TQ_STRAP,
        decals={
            "up": lambda c: (
                c.hline(0, 1, c.w, "#55564f", 0.8, 2),
                c.hline(0, c.h - 2, c.w, "#55564f", 0.8, 2),
            )
        },
    )
    m.box("tq_band", (-6, 9, -1), (5, 9.6, 2), TQ_STRAP)
    rod(m, "tq_windlass", "z", (-0.5, 10.2, 0.5), 8, 0.7, BUCKLE)
    m.box("tq_clip", (-4, 8.8, -2.2), (-2, 10.4, 3.2), BUCKLE)
    m.box(
        "tq_tab",
        (8, 7.2, -1.2),
        (11, 8.8, 2.2),
        TQ_RED,
        decals={"up": lambda c: c.text(1, 1, "TQ", "#f2e9e4")},
    )
    for i, x in enumerate((-7, 4)):
        m.box(
            f"keeper{i}",
            (x, 6.8, -2.2),
            (x + 2, 9.4, 3.2),
            Mat("#262622", "rubber", seed=20 + i),
        )
    # Zipper pulls with paracord tails.
    for i, x in enumerate((-9.5, 8.5)):
        m.box(f"zip{i}", (x, 4.2, 4.3), (x + 1, 5, 5.3), ZIP)
        m.box(f"cord{i}", (x, 2.5, 4.6), (x + 1, 4.2, 5.4), NYLON)
    return m


def variant_b():
    """Hard polymer case with a rubber seal, two latches, moulded ribs and a carry handle.

    Bones: `lid` hinges at the back, `latch0`/`latch1` swing out from their lower edge,
    `injector` lies in the foam under the lid until the use animation takes it out.
    """
    m = Model("medical_kit_b", seed=21)

    def lid_badge(c):
        c.logo(2, 2, c.h - 4)
        c.label(
            c.h + 1, c.h // 2 - 4, "Blockfield", 8, "#2a2a28", width=c.w - 2 * c.h - 4
        )
        c.cross(c.w - c.h // 2 - 1, c.h // 2, 6, 4, "#b52a2a")

    def front_label(c):
        c.logo(2, 2, c.h - 6)
        c.label(c.h - 1, 3, "Block", 7, "#2a2a28", width=c.w - c.h - 1)
        c.label(c.h - 1, 12, "field", 7, "#2a2a28", width=c.w - c.h - 1)
        c.rect(0, c.h - 3, c.w, 3, BRAND)

    def lid_plate(c):
        c.logo(c.w // 2 - c.h // 2, 2, c.h - 4, "#9a9c90")

    def front(c):
        c.text(2, c.h - 6, "1-04", "#2a2e22", 0.55)

    def foam(c):
        c.rect(0, 0, c.w, c.h, "#2b2d2b")
        c.noise(0, 0, c.w, c.h, 0.08)
        c.frame(0, 0, c.w, c.h, "#3f4a2e")
        # Cut-outs: bandage roll, tourniquet and the injector bay.
        c.rect(4, 4, 10, 8, "#1a1b1a")
        c.rect(5, 5, 8, 6, "#e6e1d2")
        c.hline(5, 7, 8, "#c9c3b2")
        c.rect(c.w - 16, 4, 12, 7, "#1a1b1a")
        c.rect(c.w - 15, 5, 10, 5, "#2d2e2c")
        c.rect(c.w - 15, 6, 3, 3, "#c32b2b")
        c.rect(18, c.h - 9, 18, 5, "#141514")

    def lid_inside(c):
        c.rect(0, 0, c.w, c.h, "#343633")
        c.noise(0, 0, c.w, c.h, 0.06)
        c.rect(c.w // 2 - 9, c.h // 2 - 4, 18, 8, "#2a2b29")

    m.bone("lid", (0, 2, -6))
    m.bone("latch0", (-6.5, -1.5, 6.5))
    m.bone("latch1", (6.5, -1.5, 6.5))
    m.bone("injector", (2, 2.5, 0.5))
    m.box("base", (-11, -7, -6), (11, 1, 6), OLIVE, decals={"south": front})
    m.box("seal", (-11.3, 1, -6.3), (11.3, 2, 6.3), RUBBER, decals={"up": foam})
    m.box(
        "lid",
        (-11, 2, -6),
        (11, 6, 6),
        OLIVE,
        decals={"down": lid_inside},
        bone="lid",
    )
    m.box(
        "lid_badge",
        (-7.5, 6, 2),
        (7.5, 6.15, 5.5),
        LABEL,
        decals={"up": lid_badge},
        skip=("down",),
        bone="lid",
        density=3,
    )
    m.box(
        "lid_plate",
        (-4, 1.85, -3),
        (4, 2, 1),
        Mat("#2a2b29", "flat", seed=17),
        decals={"down": lid_plate},
        skip=("up",),
        bone="lid",
        density=3,
    )
    for i, x in enumerate((-10, 8)):
        m.box(f"rib{i}", (x, 6, -5), (x + 2, 7, 5), OLIVE_DARK, bone="lid")
    for i, (x, z) in enumerate(((-11.6, -6.6), (9.6, -6.6), (-11.6, 4.6), (9.6, 4.6))):
        m.box(f"bumper{i}", (x, -7.4, z), (x + 2, 1, z + 2), OLIVE_DARK)
        m.box(f"bumper_top{i}", (x, 2, z), (x + 2, 6.4, z + 2), OLIVE_DARK, bone="lid")
    for i, x in enumerate((-8, 5)):
        m.box(
            f"latch{i}",
            (x, -1.5, 6),
            (x + 3, 4, 7),
            LATCH,
            decals={"south": lambda c: c.hline(0, 1, c.w, "#6a6c64")},
            bone=f"latch{i}",
        )
        m.box(f"latch_hook{i}", (x + 0.5, 3, 6), (x + 2.5, 5, 6.6), LATCH, bone="lid")
        m.box(f"hinge{i}", (x, 1, -7), (x + 3, 3, -6), LATCH)
    m.box("handle_post_l", (-5, 6, -1), (-3, 9, 1), OLIVE_DARK, bone="lid")
    m.box("handle_post_r", (3, 6, -1), (5, 9, 1), OLIVE_DARK, bone="lid")
    m.box(
        "handle",
        (-5, 9, -1.5),
        (5, 10.5, 1.5),
        RUBBER,
        decals={
            "up": lambda c: [c.vline(x, 0, c.h, "#333431") for x in range(1, c.w, 2)]
        },
        bone="lid",
    )
    m.box(
        "label",
        (-4, -5.5, 6),
        (4, -1.5, 6.3),
        LABEL,
        decals={"south": front_label},
        skip=("north",),
        density=3,
    )
    m.box("foot_l", (-10, -8, -5), (-6, -7, 5), RUBBER)
    m.box("foot_r", (6, -8, -5), (10, -7, 5), RUBBER)
    m.box(
        "injector_body",
        (-1, 2, 0),
        (5, 3, 1),
        INJECTOR,
        bone="injector",
        decals={"north": lambda c: c.text(1, -1, "AUTO", "#2b2b20", 0.7)},
    )
    m.box(
        "injector_tip",
        (5, 2.1, 0.1),
        (6.2, 2.9, 0.9),
        Mat("#e07a2a", "plastic", seed=33),
        bone="injector",
    )
    m.box(
        "injector_cap",
        (-2, 2.1, 0.1),
        (-1, 2.9, 0.9),
        Mat("#2f62b8", "plastic", seed=34),
        bone="injector",
    )
    return m


def variant_c():
    """Red trauma bag: MOLLE front, reflective band, zipped side pockets and a grab handle."""
    m = Model("medical_kit_c", seed=31)

    def front(c):
        c.stitch(1)

    m.box(
        "bag",
        (-10, -7, -5),
        (10, 6, 5),
        RED,
        decals={"south": front, "north": front, "east": front, "west": front},
    )
    m.box(
        "trim_top",
        (-10.4, 5, -5.4),
        (10.4, 7, 5.4),
        NYLON,
        decals={"up": zipper(4), "south": lambda c: c.stitch(0, "#4b4b47", 0.7)},
    )
    m.box("trim_bottom", (-10.4, -8, -5.4), (10.4, -7, 5.4), NYLON)
    m.box("band", (-10.3, -4, -5.3), (10.3, -3, 5.3), REFLECT, skip=("up", "down"))
    m.box(
        "cross_field",
        (-4, -2, 5),
        (4, 5, 5.5),
        Mat("#1f1f1d", "foam", seed=40),
        skip=("north",),
    )
    m.box("cross_v", (-1, -1.5, 5.5), (1, 4.5, 5.8), CROSS, skip=("north",))
    m.box("cross_h", (-3, 0.5, 5.5), (-1, 2.5, 5.8), CROSS, skip=("north",))
    m.box("cross_h2", (1, 0.5, 5.5), (3, 2.5, 5.8), CROSS, skip=("north",))
    for side, x0, x1 in (("l", -13, -10), ("r", 10, 13)):
        face = "west" if side == "l" else "east"
        m.box(
            f"pocket_{side}",
            (x0, -6, -3.5),
            (x1, 2, 3.5),
            RED_DARK,
            decals={face: side_zipper},
        )
        m.box(
            f"pocket_tab_{side}",
            (x0 + (0 if side == "r" else 0), 0.5, 3.5),
            (x1, 1.5, 4.2),
            ZIP,
        )
    m.box("handle_l", (-4, 7, -1), (-3, 9, 1), NYLON)
    m.box("handle_r", (3, 7, -1), (4, 9, 1), NYLON)
    m.box(
        "handle",
        (-4, 9, -1.2),
        (4, 10.3, 1.2),
        NYLON,
        decals={"up": lambda c: c.stitch(0, "#5a5a53", 0.8)},
    )
    m.box(
        "handle_wrap",
        (-1.5, 8.8, -1.6),
        (1.5, 10.6, 1.6),
        Mat("#3c3d38", "foam", seed=42),
    )
    for i, x in enumerate((-10.6, 9.6)):
        m.box(f"d_ring{i}", (x, 3, -0.5), (x + 1, 5.5, 0.5), ZIP)
    return m


VARIANTS = {
    "A": ("Рвущийся IFAK с турникетом", variant_a),
    "B": ("Жёсткий кейс", variant_b),
    "C": ("Красная тактическая сумка", variant_c),
}
