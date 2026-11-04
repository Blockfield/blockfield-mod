"""Reconnaissance drone variants. The camera points south (+z)."""

from kit import Mat, Model, ring_y, rod

GREY = Mat("#6f7376", "plastic", noise=0.02, edge=0.22, seed=1)
GREY_DARK = Mat("#4a4e51", "plastic", noise=0.02, seed=2)
GREY_LIGHT = Mat("#8d9194", "plastic", noise=0.02, seed=3)
BLACK = Mat("#1f2022", "plastic", noise=0.02, seed=4)
MOTOR = Mat("#3a3c3e", "metal", noise=0.04, wear=0.2, seed=5)
PROP = Mat("#2a2c2f", "plastic", noise=0.02, seed=6)
GLASS = Mat("#16242c", "screen", seed=7)
TAN = Mat("#a08c69", "plastic", noise=0.03, wear=0.12, seed=8)
TAN_DARK = Mat("#7f6d4f", "plastic", noise=0.03, seed=9)
RANGER = Mat("#4b5440", "plastic", noise=0.03, wear=0.15, seed=10)
ORANGE = Mat("#d0782c", "plastic", noise=0.02, seed=11)
CAGE = Mat("#2b2d2f", "plastic", noise=0.02, seed=12)
ANT = Mat("#161616", "rubber", seed=13)


def lens(c):
    c.rect(0, 0, c.w, c.h, "#0a1116")
    c.rect(c.w // 2 - 1, c.h // 2 - 1, 2, 2, "#2f6d8c")
    c.px(c.w // 2 - 1, c.h // 2 - 1, "#a9dcf0")


def gimbal_cam(m, z, y, body=GREY_DARK, size=2.2):
    m.box(
        "gimbal_yoke",
        (-size - 0.6, y - 0.6, z - 1.5),
        (size + 0.6, y + 0.6, z + 0.5),
        body,
    )
    m.box(
        "camera", (-size, y - size - 0.4, z - 1), (size, y + size - 1.4, z + 1.8), BLACK
    )
    m.box(
        "camera_lens",
        (-1.3, y - 2.2, z + 1.8),
        (1.3, y + 0.1, z + 2.3),
        BLACK,
        decals={"south": lens},
    )


def vents(c):
    for x in range(2, c.w - 2, 3):
        c.vline(x, 2, c.h - 4, "#3b3f42", 0.8)


def variant_a():
    """Folded consumer quad: arms tucked along the body, gimbal camera on the nose."""
    m = Model("scout_drone_a", seed=201)
    m.box("body", (-4, -1.5, -8), (4, 2.5, 7), GREY, decals={"up": vents})
    m.box(
        "nose",
        (-3, -1, 7),
        (3, 2, 9),
        GREY,
        decals={
            "south": lambda c: (c.px(1, 1, "#9fd0ff"), c.px(c.w - 2, 1, "#9fd0ff"))
        },
    )
    m.box("top_shell", (-3, 2.5, -6), (3, 3.3, 5), GREY_LIGHT)
    m.box(
        "battery",
        (-3.2, -1.2, -10),
        (3.2, 2.2, -8),
        GREY_DARK,
        decals={
            "north": lambda c: [
                c.px(x, c.h // 2, "#58e05a") for x in range(2, c.w - 2, 3)
            ]
        },
    )
    for side, x0 in (("l", -5.5), ("r", 4)):
        sx = -1 if side == "l" else 1
        # Front arms fold back over the body sides, rear arms fold forward under them.
        m.box(f"arm_front_{side}", (x0, 0.5, -5), (x0 + 1.5, 1.7, 7), GREY_DARK)
        m.box(f"motor_front_{side}", (x0 - 0.2, 1.7, -6.5), (x0 + 1.7, 3, -4.5), MOTOR)
        m.box(
            f"prop_front_{side}",
            (x0 + (0 if sx < 0 else 0), 3, -6),
            (x0 + 1.5, 3.4, 6),
            PROP,
        )
        m.box(f"arm_rear_{side}", (x0, -1.4, -9), (x0 + 1.5, -0.2, 4), GREY_DARK)
        m.box(f"motor_rear_{side}", (x0 - 0.2, -2.7, 3), (x0 + 1.7, -1.4, 5), MOTOR)
        m.box(f"prop_rear_{side}", (x0, -3.1, -7), (x0 + 1.5, -2.7, 4.5), PROP)
    gimbal_cam(m, 9, -1.2, GREY_DARK, 1.8)
    m.box("sensor", (-1.5, -1.8, -2), (1.5, -1.5, 2), BLACK)
    return m


def variant_b():
    """Military ISR quad: tan X frame, EO/IR ball, landing skids and whip antennas."""
    m = Model("scout_drone_b", seed=211)
    m.box(
        "body",
        (-4, -1, -6),
        (4, 3, 6),
        TAN,
        decals={
            "up": lambda c: (
                c.stitch(1, "#6a5a40", 0.5),
                c.text(2, 3, "ISR", "#5a4b33", 0.7),
            )
        },
    )
    m.box("nose", (-3, -0.5, 6), (3, 2.5, 7.5), TAN_DARK)
    m.box(
        "battery",
        (-3, 3, -4),
        (3, 4.5, 3),
        RANGER,
        decals={"up": lambda c: c.hline(1, 1, c.w - 2, "#58e05a", 0.8)},
    )
    for i, ang in enumerate((45, -45)):
        m.box(
            f"arm{i}", (-1, 0.5, -11), (1, 1.8, 11), TAN_DARK, rot=("y", ang, (0, 1, 0))
        )
    d = 11 * 0.7071
    for i, (x, z) in enumerate(((d, d), (-d, d), (d, -d), (-d, -d))):
        m.box(f"motor{i}", (x - 1.4, 1.8, z - 1.4), (x + 1.4, 3.6, z + 1.4), MOTOR)
        m.box(
            f"prop{i}",
            (x - 5.5, 3.6, z - 0.6),
            (x + 5.5, 4, z + 0.6),
            PROP,
            rot=("y", 22.5 if i % 3 == 0 else -22.5, (x, 3.6, z)),
        )
    # EO/IR ball under the nose.
    m.box("ball_mount", (-1, -2, 3), (1, -1, 5), TAN_DARK)
    rod(m, "ball", "x", (0, -4, 4), 4.4, 2.2, RANGER)
    m.box(
        "ball_window",
        (-1.6, -5.2, 6.1),
        (1.6, -2.8, 6.3),
        GLASS,
        decals={"south": lens},
    )
    for i, x in enumerate((-3.5, 3.5)):
        m.box(f"leg_f{i}", (x - 0.4, -4, 3.6), (x + 0.4, -1, 4.4), BLACK)
        m.box(f"leg_r{i}", (x - 0.4, -4, -4.4), (x + 0.4, -1, -3.6), BLACK)
        m.box(f"skid{i}", (x - 0.5, -4.6, -6), (x + 0.5, -4, 6), BLACK)
    m.box(
        "ant_l", (-3, 3, -6), (-2.5, 9, -5.5), ANT, rot=("z", 22.5, (-2.75, 3, -5.75))
    )
    m.box("ant_r", (2.5, 3, -6), (3, 9, -5.5), ANT, rot=("z", -22.5, (2.75, 3, -5.75)))
    return m


def variant_c():
    """Ducted micro quad: four guarded rotors around a compact pod, orange markings."""
    m = Model("scout_drone_c", seed=221)
    m.box(
        "pod",
        (-3.5, -1.5, -5),
        (3.5, 2.5, 5),
        BLACK,
        decals={"up": lambda c: c.rect(0, c.h - 3, c.w, 2, "#d0782c")},
    )
    m.box(
        "pod_top",
        (-2.5, 2.5, -4),
        (2.5, 3.5, 3),
        GREY_DARK,
        decals={"up": lambda c: c.logo(3, 4, 22, "#d0782c", turned=True)},
        density=3,
    )
    m.box("stripe", (-3.6, 0.5, -5.1), (3.6, 1.2, 5.1), ORANGE, skip=("up", "down"))
    for i, (x, z) in enumerate(((7, 6), (-7, 6), (7, -6), (-7, -6))):
        ring_y(m, f"duct{i}", (x, -0.5, z), 4.2, 2.2, 0.7, CAGE)
        x0, x1 = (3.5, x - 3.4) if x > 0 else (x + 3.4, -3.5)
        m.box(f"strut{i}", (x0, 0, z - 0.4), (x1, 0.8, z + 0.4), CAGE)
        m.box(f"motor{i}", (x - 1, 0, z - 1), (x + 1, 1.6, z + 1), MOTOR)
        m.bone(f"prop{i}", (x, 1.75, z))
        m.box(
            f"prop{i}",
            (x - 3.4, 1.6, z - 0.5),
            (x + 3.4, 1.9, z + 0.5),
            PROP,
            rot=("y", 45 if i % 3 == 0 else -45, (x, 1.6, z)),
            bone=f"prop{i}",
        )
        m.box(
            f"tip{i}",
            (x - 0.6, -0.5, z + (3.6 if z > 0 else -4.2)),
            (x + 0.6, 1.7, z + (4.2 if z > 0 else -3.6)),
            ORANGE,
        )
    gimbal_cam(m, 5.2, 0.8, BLACK, 1.6)
    return m


DISPLAY = {
    "firstperson_righthand": {
        "rotation": [22, 150, 0],
        "translation": [-3, 5, -3],
        "scale": [0.38] * 3,
    },
    "firstperson_lefthand": {
        "rotation": [22, 210, 0],
        "translation": [-3, 5, -3],
        "scale": [0.38] * 3,
    },
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.0] * 3},
}


VARIANTS = {
    "A": ("Складной квадрокоптер", variant_a),
    "B": ("Армейский разведчик с ОЭС", variant_b),
    "C": ("Микродрон в защитных кольцах", variant_c),
}
