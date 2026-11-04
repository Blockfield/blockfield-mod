"""Strike FPV drone variants. The nose (camera, warhead) points south (+z)."""

import math

from kit import Mat, Model, rod

CARBON = Mat("#262729", "carbon", seed=1)
CARBON_EDGE = Mat("#1c1d1e", "carbon", seed=2)
MOTOR = Mat("#4a4c4f", "metal", noise=0.04, wear=0.25, seed=3)
BELL = Mat("#8a3a2a", "metal", noise=0.04, wear=0.2, seed=4)
PROP = Mat("#2c2f33", "plastic", noise=0.02, edge=0.2, seed=5)
PROP_TIP = Mat("#d2a02a", "plastic", noise=0.02, seed=6)
BATTERY = Mat("#2f3a46", "plastic", noise=0.02, seed=7)
STRAP = Mat("#b02a2a", "fabric", seed=8)
OLIVE = Mat("#5d6444", "plastic", noise=0.018, edge=0.2, wear=0.1, seed=9)
OLIVE_DARK = Mat("#4a5036", "plastic", noise=0.018, edge=0.2, wear=0.08, seed=10)
BRASS = Mat("#a88a45", "metal", noise=0.04, wear=0.1, seed=11)
CAMERA = Mat("#18191a", "plastic", noise=0.02, seed=12)
LENS = Mat("#203845", "screen", seed=13)
TIE = Mat("#121212", "plastic", noise=0.01, seed=14)
SPOOL = Mat("#d9d6cc", "plastic", noise=0.02, seed=15)
FIBER = Mat("#e7e3a4", "flat", seed=16)
ANT = Mat("#151515", "rubber", seed=17)
GRENADE = Mat("#556040", "metal", noise=0.04, wear=0.3, seed=18)
MOUNT = Mat("#2f3133", "plastic", noise=0.025, seed=22)
STRAP_BLACK = Mat("#1f201f", "fabric", seed=23)
WIRE = Mat("#b33a2e", "plastic", noise=0.01, seed=24)
SERVO = Mat("#2b4a8a", "plastic", noise=0.02, seed=19)


def battery_label(c):
    c.text(1, 1, "6S", "#e6e2d6")
    c.hline(0, c.h - 2, c.w, "#d6b23a")


def lens(c):
    c.rect(0, 0, c.w, c.h, "#0b1216")
    c.px(c.w // 2, c.h // 2, "#4aa3c8")
    c.px(c.w // 2 - 1, c.h // 2 - 1, "#9fd8ee")


def x_frame(m, reach, y=0.0, arm_w=2.0, plate=None):
    """Two crossing arms at 45 degrees; returns motor centres (x, z)."""
    half = reach
    for i, angle in enumerate((45, -45)):
        m.box(
            f"arm{i}",
            (-arm_w / 2, y, -half),
            (arm_w / 2, y + 1, half),
            CARBON,
            rot=("y", angle, (0, y, 0)),
        )
    d = reach * math.sqrt(0.5)
    return [(sx * d, sz * d) for sx, sz in ((1, 1), (-1, 1), (1, -1), (-1, -1))]


def motor(m, i, x, z, y=1.0, prop_len=13.0, tip=True, spin=0):
    m.box(
        f"motor{i}",
        (x - 1.5, y, z - 1.5),
        (x + 1.5, y + 2, z + 1.5),
        MOTOR,
        decals={"up": lambda c: c.frame(0, 0, c.w, c.h, "#2a2b2c")},
    )
    m.box(f"bell{i}", (x - 1.2, y + 2, z - 1.2), (x + 1.2, y + 2.6, z + 1.2), BELL)
    angle = (22.5, -22.5, -22.5, 22.5)[i % 4] if spin == 0 else spin
    h = prop_len / 2
    m.bone(f"prop{i}", (x, y + 2.8, z))
    m.box(
        f"prop{i}",
        (x - h, y + 2.6, z - 0.7),
        (x + h, y + 3.0, z + 0.7),
        PROP,
        rot=("y", angle, (x, y, z)),
        bone=f"prop{i}",
        decals={
            "up": (
                lambda c: (
                    c.rect(0, 0, 3, c.h, "#d2a02a"),
                    c.rect(c.w - 3, 0, 3, c.h, "#d2a02a"),
                )
            )
            if tip
            else (lambda c: None)
        },
    )
    m.box(
        f"nut{i}",
        (x - 0.5, y + 3.0, z - 0.5),
        (x + 0.5, y + 3.6, z + 0.5),
        MOTOR,
        bone=f"prop{i}",
    )


def stack(m, length=12, top=3.0):
    m.box("plate_bottom", (-3, 0, -length / 2), (3, 1, length / 2), CARBON_EDGE)
    m.box(
        "fc",
        (-2, 1, -3),
        (2, top - 0.5, 2),
        Mat("#1d3a2a", "plastic", seed=20),
        decals={"up": lambda c: (c.px(1, 1, "#4cff6a"), c.px(3, 1, "#3a8cff"))},
    )
    for i, (x, z) in enumerate(((-2.6, -4.6), (2.6, -4.6), (-2.6, 4.6), (2.6, 4.6))):
        m.box(f"standoff{i}", (x - 0.3, 1, z - 0.3), (x + 0.3, top, z + 0.3), MOTOR)
    m.box("plate_top", (-3, top, -length / 2), (3, top + 0.5, length / 2), CARBON_EDGE)


def fpv_camera(m, z, y=1.2, tilt=-22.5):
    m.box("cam_cage_l", (-2.4, y - 0.4, z - 2.5), (-1.8, y + 3, z + 0.5), CARBON)
    m.box("cam_cage_r", (1.8, y - 0.4, z - 2.5), (2.4, y + 3, z + 0.5), CARBON)
    m.box(
        "cam",
        (-1.7, y, z - 2),
        (1.7, y + 2.8, z + 1),
        CAMERA,
        rot=("x", tilt, (0, y + 1.4, z - 0.5)),
    )
    m.box(
        "cam_lens",
        (-1, y + 0.6, z + 1),
        (1, y + 2.2, z + 1.8),
        CAMERA,
        decals={"south": lens},
        rot=("x", tilt, (0, y + 1.4, z - 0.5)),
    )


def warhead(m, y, z0, z1, rocket=True):
    """PG-7VL head: wide ogive forward, piezo spike and fuse; the narrow rear sits in a printed cradle.

    Without `rocket` only the empty cradle stays (the flying drone shows SuperbWarfare's own payload)."""
    rear = z0 + (z1 - z0) * 0.55
    adapter(m, y, z0, rear)
    if not rocket:
        return
    rod(m, "wh_rear", "z", (0, y, (z0 + rear) / 2), rear - z0, 1.35, OLIVE_DARK)
    rod(m, "wh_taper", "z", (0, y, rear + 0.5), 1.0, 1.9, OLIVE)
    rod(m, "wh_ogive", "z", (0, y, (rear + 1 + z1) / 2), z1 - rear - 1, 2.5, OLIVE)
    m.box(
        "wh_band",
        (-2.55, y - 2.55, z1 - 1.4),
        (2.55, y + 2.55, z1 - 0.9),
        Mat("#c9b04a", "plastic", noise=0.01, seed=25),
        skip=("north", "south"),
    )
    rod(m, "wh_cone", "z", (0, y, z1 + 0.7), 1.4, 1.6, OLIVE)
    rod(m, "wh_spike", "z", (0, y, z1 + 2.6), 2.4, 0.7, OLIVE_DARK)
    rod(m, "wh_fuse", "z", (0, y, z1 + 4.2), 0.8, 0.5, BRASS)
    rod(m, "wh_tail", "z", (0, y, z0 - 0.8), 1.6, 0.9, OLIVE_DARK)
    for i, z in enumerate((z0 + 0.6, rear - 1.6)):
        m.box(f"strap{i}", (-1.9, y - 1.6, z + 0.1), (1.9, y, z + 0.9), STRAP_BLACK)
    m.box("fuse_wire", (0.9, y + 1.0, rear - 1), (1.25, -0.8, rear - 0.65), WIRE)
    m.box("fuse_wire_run", (0.9, y + 2.5, rear - 1), (1.25, y + 2.85, z1 + 1.6), WIRE)


def adapter(m, y, z0, rear):
    m.box(
        "adapter",
        (-2.2, -0.8, z0 + 0.2),
        (2.2, 0, rear),
        MOUNT,
        decals={"down": lambda c: c.screws(1, "#111111", "#6d6f72")},
    )
    for i, z in enumerate((z0 + 0.6, rear - 1.6)):
        m.box(f"saddle_l{i}", (-1.9, y, z), (-1.4, -0.8, z + 1.0), MOUNT)
        m.box(f"saddle_r{i}", (1.4, y, z), (1.9, -0.8, z + 1.0), MOUNT)


def variant_a(rocket=True):
    """Seven-inch X frame with a PG-7 warhead under the nose and a 6S pack on top."""
    m = Model("drone_a", seed=101)
    for i, (x, z) in enumerate(x_frame(m, 13)):
        motor(m, i, x, z)
    stack(m)
    fpv_camera(m, 5.5, y=1.0)
    m.box(
        "battery",
        (-2.5, 3.5, -5),
        (2.5, 6.5, 3),
        BATTERY,
        decals={
            "east": battery_label,
            "west": battery_label,
            "up": lambda c: c.logo(2, c.h - 20, 16, turned=True),
        },
        density=3,
    )
    m.box("strap", (-2.8, 3.4, -1.5), (2.8, 6.8, -0.5), STRAP)
    m.box("lead", (2.5, 5, -6), (3.3, 5.8, -3), Mat("#1c1c1c", "rubber", seed=21))
    m.box(
        "vtx_ant",
        (-0.3, 3.5, -7.5),
        (0.3, 8, -6.9),
        ANT,
        rot=("x", -22.5, (0, 3.5, -7)),
    )
    m.box(
        "vtx_cap",
        (-0.8, 7.6, -8.8),
        (0.8, 8.8, -7.4),
        ANT,
        rot=("x", -22.5, (0, 3.5, -7)),
    )
    warhead(m, -3.0, -6, 4, rocket)
    return m


def variant_b():
    """Ten-inch H-frame bomber with a servo drop and two grenades."""
    m = Model("drone_b", seed=111)
    m.box("rail_l", (-4, 0, -9), (-2.5, 1, 9), CARBON)
    m.box("rail_r", (2.5, 0, -9), (4, 1, 9), CARBON)
    for i, z in enumerate((-8, 8)):
        m.box(f"beam{i}", (-14, 0, z - 1), (14, 1, z + 1), CARBON)
    motors = [(13, 8), (-13, 8), (13, -8), (-13, -8)]
    for i, (x, z) in enumerate(motors):
        motor(m, i, x, z, prop_len=17, tip=False, spin=(22.5, -22.5, -22.5, 22.5)[i])
    stack(m, length=10)
    fpv_camera(m, 7.5, y=1.0, tilt=-22.5)
    m.box(
        "battery",
        (-3, 3.5, -6),
        (3, 7, 3),
        BATTERY,
        decals={"east": battery_label, "west": battery_label},
    )
    m.box("strap0", (-3.3, 3.4, -4), (3.3, 7.3, -3), STRAP)
    m.box("strap1", (-3.3, 3.4, 0), (3.3, 7.3, 1), STRAP)
    m.box(
        "servo",
        (-2, -2.5, -2),
        (2, 0, 2),
        SERVO,
        decals={"south": lambda c: c.text(1, 1, "S", "#d8dde8")},
    )
    m.box("drop_arm", (-4.5, -2, -0.5), (4.5, -1.2, 0.5), MOTOR)
    for i, x in enumerate((-3.2, 3.2)):
        rod(m, f"gren{i}", "y", (x, -4.3, 0), 4.2, 1.3, GRENADE)
        rod(m, f"gren_fuse{i}", "y", (x, -1.6, 0), 1.2, 0.7, BRASS)
        m.box(f"fin{i}", (x - 1.5, -7.6, -0.2), (x + 1.5, -6.4, 0.2), OLIVE_DARK)
    for i, x in enumerate((-12, 12)):
        m.box(f"leg{i}", (x - 0.5, -4, -8.5), (x + 0.5, 0, -7.5), CARBON)
        m.box(f"legf{i}", (x - 0.5, -4, 7.5), (x + 0.5, 0, 8.5), CARBON)
        m.box(f"skid{i}", (x - 0.6, -4.6, -9.5), (x + 0.6, -4, 9.5), ANT)
    m.box(
        "rx_ant_l",
        (-4, 1, -9.5),
        (-3.5, 6, -9),
        ANT,
        rot=("z", 22.5, (-3.75, 1, -9.25)),
    )
    m.box(
        "rx_ant_r", (3.5, 1, -9.5), (4, 6, -9), ANT, rot=("z", -22.5, (3.75, 1, -9.25))
    )
    return m


def variant_c():
    """Fibre-optic FPV: X frame, warhead and a fibre spool on the tail."""
    m = Model("drone_c", seed=121)
    for i, (x, z) in enumerate(x_frame(m, 12)):
        motor(m, i, x, z, prop_len=12)
    stack(m, length=11)
    fpv_camera(m, 5, y=1.0)
    m.box(
        "battery",
        (-2.5, 3.5, -2),
        (2.5, 6.5, 5),
        BATTERY,
        decals={"east": battery_label, "west": battery_label},
    )
    m.box("strap", (-2.8, 3.4, 1), (2.8, 6.8, 2), STRAP)
    # Spool axis runs across the frame; the fibre exits through a guide at the rear.
    rod(m, "spool_core", "x", (0, 6.2, -7.5), 7, 2.8, SPOOL)
    rod(m, "spool_fibre", "x", (0, 6.2, -7.5), 5.6, 3.6, FIBER)
    rod(m, "spool_flange_l", "x", (-3.5, 6.2, -7.5), 0.6, 4.4, SPOOL)
    rod(m, "spool_flange_r", "x", (3.5, 6.2, -7.5), 0.6, 4.4, SPOOL)
    m.box("spool_mount", (-1, 3, -8.5), (1, 3.6, -6.5), CARBON)
    m.box("guide", (-0.6, 5.6, -12.5), (0.6, 6.8, -11), MOTOR)
    warhead(m, -3.0, -5, 4)
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
    "A": ("X-рама с ПГ-7", variant_a),
    "B": ("Бомбер со сбросом", variant_b),
    "C": ("Оптоволоконный с катушкой", variant_c),
}
