"""FPV control station variants. The screen faces the operator on the south (+z) side.

Every builder takes `linked`: the unlinked screen shows no signal and a red lamp,
the linked one shows the drone feed with a green lamp.
"""

from kit import Mat, Model, rod

BODY = Mat("#2f3132", "plastic", noise=0.025, seed=1)
BODY_DARK = Mat("#232526", "plastic", noise=0.02, seed=2)
GRIP = Mat("#1d1e1f", "rubber", noise=0.04, bevel=0.05, seed=3)
METAL = Mat("#6d6f6e", "metal", noise=0.04, wear=0.2, seed=4)
OLIVE = Mat("#55603f", "plastic", noise=0.03, wear=0.18, seed=5)
OLIVE_DARK = Mat("#444d32", "plastic", noise=0.03, wear=0.12, seed=6)
TAN = Mat("#9c8a68", "plastic", noise=0.03, seed=7)
ANTENNA = Mat("#1b1c1c", "rubber", noise=0.02, seed=8)
BEZEL = Mat("#151617", "plastic", noise=0.015, edge=0.15, seed=9)
SCREEN = Mat("#0c1214", "screen", seed=10)
LCD = Mat("#8fa08a", "screen", seed=11)


def feed(linked):
    def paint(c):
        w, h = c.w, c.h
        if linked:
            for y in range(h):
                t = y / max(h - 1, 1)
                sky = (0.42 + 0.2 * t, 0.55 + 0.12 * t, 0.62 - 0.05 * t)
                ground = (0.28 - 0.08 * t, 0.33 - 0.08 * t, 0.2 - 0.05 * t)
                c.hline(0, y, w, sky if t < 0.55 else ground)
            c.noise(0, h // 2, w, h - h // 2, 0.12)
            cx, cy = w // 2, h // 2
            c.hline(cx - 4, cy, 3, "#e6f2e0")
            c.hline(cx + 2, cy, 3, "#e6f2e0")
            c.vline(cx, cy - 4, 3, "#e6f2e0")
            c.vline(cx, cy + 2, 3, "#e6f2e0")
            c.text(1, 1, "LINK", "#7cf07a")
            c.text(w - 9, 1, "87", "#e6f2e0")
            c.hline(1, h - 2, w // 3, "#7cf07a")
        else:
            c.noise(0, 0, w, h, 0.25)
            c.text((w - 23) // 2, h // 2 - 2, "NO SIG", "#5b6a6e", 0.8)

    return paint


def lamp(linked):
    return Mat("#58e05a" if linked else "#c8312c", "flat", seed=12)


def gimbal(m, name, x, y, z, length=3.5):
    m.box(
        f"{name}_ring",
        (x - 2.5, y - 2.5, z),
        (x + 2.5, y + 2.5, z + 0.5),
        BODY_DARK,
        decals={
            "south": lambda c: (
                c.frame(0, 0, c.w, c.h, "#4a4d50"),
                c.frame(2, 2, c.w - 4, c.h - 4, "#121314"),
            )
        },
    )
    m.box(
        f"{name}_boot", (x - 1.2, y - 1.2, z + 0.5), (x + 1.2, y + 1.2, z + 1.1), GRIP
    )
    rod(m, f"{name}_stick", "z", (x, y, z + 1.1 + length / 2), length, 0.5, METAL)
    m.box(
        f"{name}_cap",
        (x - 1.1, y - 1.1, z + 1.1 + length),
        (x + 1.1, y + 1.1, z + 2.6 + length),
        GRIP,
        decals={
            "south": lambda c: [
                c.hline(0, yy, c.w, "#34363a") for yy in range(0, c.h, 2)
            ]
        },
    )


def back_panel(c):
    c.frame(2, 2, c.w - 4, c.h - 4, "#1a1b1c")
    for x in range(4, c.w - 4, 3):
        c.vline(x, 4, 3, "#151617")


def variant_a(linked=False):
    """Gimbal transmitter with angled grips, flip antennas and a hooded monitor on a mount."""
    m = Model("monitor_a" + ("_linked" if linked else ""), seed=71)
    m.box(
        "tx",
        (-11, -6, -3),
        (11, 4, 3),
        BODY,
        decals={"south": lambda c: c.stitch(1, "#3e4143", 0.6), "north": back_panel},
    )
    m.box("tx_lower", (-9, -8, -2.5), (9, -6, 2.5), BODY_DARK)
    for side, x0, x1 in (("l", -12, -7), ("r", 7, 12)):
        m.box(
            f"grip_{side}",
            (x0, -10, -7),
            (x1, -3, -1),
            GRIP,
            rot=("x", -22.5, ((x0 + x1) / 2, -3, -1)),
            decals={
                "west" if side == "l" else "east": lambda c: [
                    c.hline(1, yy, c.w - 2, "#2b2c2d") for yy in range(2, c.h - 1, 3)
                ]
            },
        )
    gimbal(m, "left", -6.5, -1, 3)
    gimbal(m, "right", 6.5, -1, 3)
    m.box(
        "lcd",
        (-2, -5, 3),
        (2, -2, 3.2),
        LCD,
        decals={"south": lambda c: c.text(1, 0, "TX", "#2b332a")},
        skip=("north",),
    )
    m.box("lamp", (-0.5, 1.5, 3), (0.5, 2.5, 3.3), lamp(linked), skip=("north",))
    for i, x in enumerate((-10, -8.5, 7.5, 9)):
        m.box(f"switch_base{i}", (x - 0.2, 4, -1), (x + 1.2, 4.6, 1), BODY_DARK)
        m.box(f"switch{i}", (x + 0.2, 4.6, -0.3), (x + 0.8, 6.4, 0.3), METAL)
    m.box("mount", (-1, 4, -1.5), (1, 7.5, 0.5), METAL)
    m.box("mount_clamp", (-2.5, 7, -2), (2.5, 8, 1), BODY_DARK)
    m.box("monitor", (-9, 8, -2), (9, 20, 1), BODY_DARK, decals={"north": back_panel})
    m.box(
        "screen",
        (-8, 9, 1),
        (8, 19, 1.15),
        SCREEN,
        decals={"south": feed(linked)},
        skip=("north",),
    )
    m.box("hood_top", (-9.4, 20, -2), (9.4, 20.6, 5), BODY)
    m.box("hood_l", (-9.4, 9, 1), (-9, 20, 5), BODY)
    m.box("hood_r", (9, 9, 1), (9.4, 20, 5), BODY)
    m.box("monitor_ant", (8, 20.6, -1.5), (9, 25, -0.5), ANTENNA)
    m.box(
        "antenna_l",
        (-12.5, 3, -2),
        (-10.5, 11, -1.4),
        ANTENNA,
        rot=("z", 22.5, (-11.5, 3, -1.7)),
    )
    m.box(
        "antenna_r",
        (10.5, 3, -2),
        (12.5, 11, -1.4),
        ANTENNA,
        rot=("z", -22.5, (11.5, 3, -1.7)),
    )
    return m


def brand_plate(c):
    c.logo(4, 3, c.h - 6)
    c.label(36, c.h // 2 - 5, "Blockfield", 10, "#d9d4c4", width=c.w - 64)
    c.label(c.w - 22, c.h // 2 - 4, "FPV", 8, "#f0a429", width=18)


def variant_b(linked=False):
    """Rugged olive ground station: wide screen, side handles, sticks under the display."""
    m = Model("monitor_b" + ("_linked" if linked else ""), seed=81)
    m.box(
        "body",
        (-13, -8, -2.5),
        (13, 8, 2.5),
        OLIVE,
        decals={
            "south": lambda c: c.screws(2, "#2b3022", "#9aa184"),
            "north": back_panel,
        },
    )
    m.box(
        "screen",
        (-8, -2, 2.5),
        (8, 7, 2.65),
        SCREEN,
        decals={"south": feed(linked)},
        skip=("north",),
    )
    m.box("bezel_b", (-8.5, -2.6, 2.5), (8.5, -2, 2.9), BEZEL)
    m.box("bezel_t", (-8.5, 7, 2.5), (8.5, 7.6, 2.9), BEZEL)
    gimbal(m, "left", -10.5, -4.5, 2.5, 2.5)
    gimbal(m, "right", 10.5, -4.5, 2.5, 2.5)
    for i, x in enumerate((-6, -3.5, 2.5, 5)):
        m.box(f"key{i}", (x, -6.5, 2.5), (x + 1.5, -5, 3.1), BODY_DARK)
    m.box("lamp", (-0.5, -6, 2.5), (0.5, -5, 2.9), lamp(linked), skip=("north",))
    for i, (x, y) in enumerate(
        ((-13.6, -8.6), (11.6, -8.6), (-13.6, 6.6), (11.6, 6.6))
    ):
        m.box(f"bumper{i}", (x, y, -3), (x + 2, y + 2, 3), GRIP)
    for side, x in (("l", -16), ("r", 14)):
        m.box(
            f"handle_{side}",
            (x, -5, -1),
            (x + 2, 5, 1),
            GRIP,
            decals={
                "west" if side == "l" else "east": lambda c: [
                    c.hline(0, y, c.w, "#2e2f30") for y in range(1, c.h, 3)
                ]
            },
        )
        x0, x1 = (-16, -13) if side == "l" else (13, 16)
        m.box(f"arm_{side}_t", (x0, 3, -0.8), (x1, 5, 0.8), OLIVE_DARK)
        m.box(f"arm_{side}_b", (x0, -5, -0.8), (x1, -3, 0.8), OLIVE_DARK)
    m.bone("antenna_l", (-10.5, 8, -0.5))
    m.bone("antenna_r", (10.5, 8, -0.5))
    m.box("antenna_l", (-11, 8, -1), (-10, 13, 0), ANTENNA, bone="antenna_l")
    m.box("antenna_r", (10, 8, -1), (11, 13, 0), ANTENNA, bone="antenna_r")
    m.box("sun_visor", (-9, 8, -0.5), (9, 8.6, 6), OLIVE_DARK)
    m.box(
        "brand_plate",
        (-9, -6.5, -2.7),
        (9, -1.5, -2.5),
        Mat("#1e1f20", "flat", seed=83),
        decals={"north": brand_plate},
        skip=("south",),
        density=3,
    )
    return m


def variant_c(linked=False):
    """Compact gamepad controller with a phone clamp and fold-down antennas."""
    m = Model("monitor_c" + ("_linked" if linked else ""), seed=91)
    m.box(
        "pad",
        (-9, -5, -3),
        (9, 0, 3),
        TAN,
        decals={"up": lambda c: c.stitch(1, "#7b6c51", 0.5)},
    )
    m.box("grip_l", (-11, -9, -3), (-5, -3, 2), TAN, rot=("z", -22.5, (-8, -3, 0)))
    m.box("grip_r", (5, -9, -3), (11, -3, 2), TAN, rot=("z", 22.5, (8, -3, 0)))
    for x in (-5, 5):
        m.box(f"stick_well{x}", (x - 1.6, 0, -1.6), (x + 1.6, 0.4, 1.6), BODY_DARK)
        rod(m, f"stick{x}", "y", (x, 1.4, 0), 2, 0.45, METAL)
        m.box(f"stick_cap{x}", (x - 1, 2.4, -1), (x + 1, 3.2, 1), GRIP)
    m.box("lamp", (-0.5, 0, 2), (0.5, 0.4, 2.8), lamp(linked))
    m.box("clamp_base", (-2, 0, -2.5), (2, 3, -0.5), BODY_DARK)
    m.box("clamp_arm", (-9.5, 3, -2.5), (9.5, 4, -0.5), BODY_DARK)
    m.box("clamp_l", (-10.5, 3, -2.5), (-9.5, 13, 0.5), BODY_DARK)
    m.box("clamp_r", (9.5, 3, -2.5), (10.5, 13, 0.5), BODY_DARK)
    m.box(
        "phone",
        (-9.5, 4, -2),
        (9.5, 13, -0.6),
        BODY_DARK,
        decals={
            "north": lambda c: (c.rect(3, 3, 6, 6, "#151617"), c.px(5, 5, "#2f6d8c"))
        },
    )
    m.box(
        "phone_screen",
        (-9, 4.5, -0.6),
        (9, 12.5, -0.45),
        SCREEN,
        decals={"south": feed(linked)},
        skip=("north",),
    )
    m.box(
        "antenna_l", (-8, -6, 3), (-7, -1, 4), ANTENNA, rot=("x", 22.5, (-7.5, -1, 3))
    )
    m.box("antenna_r", (7, -6, 3), (8, -1, 4), ANTENNA, rot=("x", 22.5, (7.5, -1, 3)))
    m.box("shoulder_l", (-8, -1, -3.4), (-5, 0, -2.4), GRIP)
    m.box("shoulder_r", (5, -1, -3.4), (8, 0, -2.4), GRIP)
    return m


DISPLAY = {
    "firstperson_righthand": {
        "rotation": [18, -12, 0],
        "translation": [-5, 4.5, -2],
        "scale": [0.4] * 3,
    },
    "firstperson_lefthand": {
        "rotation": [18, 12, 0],
        "translation": [-5, 4.5, -2],
        "scale": [0.4] * 3,
    },
}


VARIANTS = {
    "A": ("Передатчик с монитором", variant_a),
    "B": ("Защищённая наземная станция", variant_b),
    "C": ("Компактный пульт со смартфоном", variant_c),
}
