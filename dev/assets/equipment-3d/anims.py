"""First-person choreography for the approved equipment models.

Items move by keyed poses with spring follow-through; hands either hold a grip on a bone
(contact point + palm direction, forearm aimed at the shoulder) or travel between grips
along a lifted arc, so contact is exact and transitions stay natural.
"""

import math

import numpy as np

import armor_plate
import drone
import medical_kit
import monitor
import scout_drone
import tablet
from rig import (  # noqa: F401  (animation_file is re-exported for export.py)
    PALM,
    Clip,
    Rig,
    affine,
    animation_file,
    ease,
    euler,
    reach,
    slerp_frames,
    smooth,
    spring,
    to_euler,
    unit,
)


class Grip:
    """A contact on `bone`: palm centre at `point` with the palm facing `palm` (rest space)."""

    def __init__(self, bone, point, palm, shoulder=None):
        self.bone, self.point, self.palm, self.shoulder = (
            bone,
            np.asarray(point, float),
            np.asarray(palm, float),
            shoulder,
        )

    def frame(self, hand, world):
        m = world[self.bone]
        return reach(
            hand,
            m[:3, :3] @ self.point + m[:3, 3],
            m[:3, :3] @ self.palm,
            self.shoulder,
        )


def hand_track(hand, keys, lift=1.5):
    """keys: [(t, Grip)]; repeated grips hold, different grips blend along an arc."""

    def at(t, world):
        if t <= keys[0][0]:
            return keys[0][1].frame(hand, world)
        for (t0, a), (t1, b) in zip(keys, keys[1:]):
            if t <= t1:
                fa = a.frame(hand, world)
                if a is b:
                    return fa
                u = ease((t - t0) / (t1 - t0))
                fb = b.frame(hand, world)
                f = slerp_frames(fa, fb, u)
                # The palm travels the straight path; blending the arm origins would swing it wide.
                palm = (fa[:3, :3] @ PALM[hand] + fa[:3, 3]) * (1 - u) + (
                    fb[:3, :3] @ PALM[hand] + fb[:3, 3]
                ) * u
                palm += np.array([0, 1, 0.6]) * math.sin(math.pi * u) * lift
                f[:3, 3] = palm - f[:3, :3] @ PALM[hand]
                return f
        return keys[-1][1].frame(hand, world)

    return at


def keyed(keys, length, follow=True, stiffness=220.0, damping=0.62):
    """(t, vec) keys -> smooth curve, optionally chased by a spring for follow-through."""
    curve = smooth(keys)
    return spring(curve, length, stiffness, damping) if follow else curve


def breathe(length, amp_pos=(0, 0.18, 0), amp_rot=(0.5, 0, 0.25)):
    w = 2 * math.pi / length
    return (
        lambda t: np.array(amp_rot) * math.sin(w * t),
        lambda t: np.array(amp_pos) * math.sin(w * t + 0.6),
    )


def camera_follow(item_rot, base, gain=0.05):
    """Camera answers the item's motion faintly, like the body bracing against it."""
    return lambda t: (item_rot(t) - base) * gain


# Medical kit (hard case, variant B) ---------------------------------------------------


MEDKIT_SCALE, MEDKIT_AT = 0.54, np.array([6.0, -8.0, -4.0])


def mk(x, y, z):
    """Medical kit model coordinates (texel units) to rig space."""
    return np.array([x, y, z], float) * MEDKIT_SCALE + MEDKIT_AT


def medical_kit_rig():
    rig = Rig("medical_kit")
    rig.attach(medical_kit.variant_b(), MEDKIT_SCALE, MEDKIT_AT)
    return rig


MEDKIT_BASE = np.array([16.0, -14.0, 0.0])


def medical_kit_grips():
    return {
        # The case rests on the right palm; the left hand stays low until it works.
        "r_side": Grip("item", mk(3.0, -8.0, 1.0) + (0, -2.0, 0), (0, 1, 0)),
        "l_side": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
        "l_tap": Grip("item", mk(11.8, -2.0, 0.0) + (1.9, 0, 0), (-1, 0, 0)),
        "l_latch0": Grip("item", mk(-6.5, -4.5, 7.0) + (0, -1.0, 1.6), (0, 0.5, -1)),
        "l_latch1": Grip("item", mk(6.5, -4.5, 7.0) + (0, -1.0, 1.6), (0, 0.5, -1)),
        "l_lid": Grip("item_lid", mk(0, 6.0, 6.0) + (0, 1.6, 0.6), (0, -1, -0.3)),
        "l_inside": Grip("item_injector", mk(2.0, 2.5, 0.5) + (0, 1.9, 0), (0, -1, 0)),
        "l_low": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
    }


def draw_clip(
    rig,
    base,
    grips,
    right,
    left,
    length=0.8,
    put_away=False,
    low=(6, -15, 5),
    tilt=(28, -22, -16),
    suffix="",
    item_pos=(0, 0, 0),
):
    """Shared draw / put-away: the view rises from the lower right, the left hand joins late."""
    name = ("put_away" if put_away else "draw") + suffix
    clip = Clip(rig, name, length, loop=None)
    hidden_p, hidden_r = np.array(low, float), np.array(tilt, float)
    zero = np.zeros(3)
    if put_away:
        # Eases out of the hold rather than leaving at full speed.
        pos = keyed(
            [(0, zero), (length, hidden_p)], length, stiffness=260, damping=0.85
        )
        rot = keyed(
            [(0, zero), (length * 0.9, hidden_r)], length, stiffness=260, damping=0.85
        )
        lkeys = [(0, grips[left]), (length * 0.9, grips["l_low"])]
        cam = keyed(
            [(0, zero), (length * 0.5, np.array([0.6, -0.2, -0.4])), (length, zero)],
            length,
            follow=False,
        )
    else:
        pos = keyed(
            [(0, hidden_p), (length * 0.65, zero)], length, stiffness=170, damping=0.68
        )
        rot = keyed(
            [(0, hidden_r), (length * 0.72, zero)], length, stiffness=140, damping=0.66
        )
        lkeys = [
            (0, grips["l_low"]),
            (length * 0.3, grips["l_low"]),
            (length * 0.85, grips[left]),
        ]
        cam = keyed(
            [
                (0, zero),
                (length * 0.4, np.array([-0.9, 0.3, 0.5])),
                (length * 0.9, zero),
            ],
            length,
            follow=False,
        )
    clip.bone("root", rot=rot, pos=pos)
    clip.bone(
        "item", rot=lambda t: base.copy(), pos=lambda t: np.array(item_pos, float)
    )
    clip.bone("camera", rot=cam)
    clip.hand("righthand", hand_track("righthand", [(0, grips[right])]))
    clip.hand("lefthand", hand_track("lefthand", lkeys))
    return clip


def held(clip, hand, bone, pick, drop=None, away=None, local=None):
    """`bone` rides in `hand` from `pick` on; after `drop` it waits at `away` (rest space offset).

    `local` (hand space) fixes the hold, e.g. taken from another clip; by default it is where the
    hand meets the bone at `pick`.
    """
    cache = {} if local is None else {"local": local}

    def at(t, world):
        rest = world[clip.rig.bones[bone].parent] @ np.eye(4)
        if t < pick:
            return rest
        if drop is not None and t >= drop:
            return rest @ affine(None, away)
        if "local" not in cache:
            w = clip.rig.world(clip.pose_no_follow(pick))
            hand_world = clip.rig.hand_frame(w, hand)
            cache["local"] = np.linalg.inv(hand_world) @ w[clip.rig.bones[bone].parent]
        w = clip.rig.world(clip.pose_no_follow(t))
        return clip.rig.hand_frame(w, hand) @ cache["local"]

    return at


def medical_kit_clips(rig):
    g = medical_kit_grips()
    B = MEDKIT_BASE
    clips = []
    zero = np.zeros(3)

    idle = Clip(rig, "static_idle", 3.0, loop=True)
    r, p = breathe(3.0)
    idle.bone("item", rot=lambda t: B + r(t), pos=p)
    idle.hand("righthand", hand_track("righthand", [(0, g["r_side"])]))
    idle.hand("lefthand", hand_track("lefthand", [(0, g["l_side"])]))
    clips.append(idle)

    clips.append(draw_clip(rig, B, g, "r_side", "l_side"))
    clips.append(draw_clip(rig, B, g, "r_side", "l_side", length=0.5, put_away=True))

    L = 3.4
    insp = Clip(rig, "inspect", L)
    rot = keyed(
        [
            (0, B),
            (0.55, np.array([8, 58, 6])),
            (1.35, np.array([10, 62, 4])),
            (1.9, np.array([58, -4, -2])),
            (2.75, np.array([54, -8, -2])),
            (3.1, B),
        ],
        L,
        stiffness=150,
        damping=0.6,
    )
    pos = keyed(
        [
            (0, zero),
            (0.55, np.array([-2.5, 2.2, 1.5])),
            (1.35, np.array([-2.2, 2.4, 1.6])),
            (1.9, np.array([-1.5, 1.2, 2.5])),
            (2.75, np.array([-1.4, 1.0, 2.4])),
            (3.1, zero),
        ],
        L,
    )
    insp.bone("item", rot=rot, pos=pos)
    insp.bone(
        "item_latch1",
        rot=keyed(
            [
                (0, zero),
                (2.05, zero),
                (2.2, np.array([78, 0, 0])),
                (2.42, np.array([78, 0, 0])),
                (2.55, zero),
            ],
            L,
            stiffness=400,
            damping=0.45,
        ),
    )
    insp.bone("camera", rot=camera_follow(rot, B, 0.04))
    insp.hand("righthand", hand_track("righthand", [(0, g["r_side"])]))
    insp.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_side"]),
                (0.3, g["l_low"]),
                (1.55, g["l_low"]),
                (2.0, g["l_latch1"]),
                (2.6, g["l_latch1"]),
                (3.05, g["l_side"]),
            ],
            lift=2.0,
        ),
    )
    clips.append(insp)

    U = 1.95  # Authored at 2 s; both healing clips are retimed together below.
    use = Clip(rig, "use", U, loop=None)
    up = np.array([30, -6, 0])
    # The thumb sweeps both latches open, lifts the lid, takes the injector and jabs it
    # below the view; a flick of the right wrist then drops the lid shut.
    rot = keyed(
        [
            (0, B),
            (0.35, up),
            (1.2, up + np.array([3, 2, 0])),
            (1.5, up + np.array([-3, 3, 2])),
            (1.62, up + np.array([-3, 3, 2])),
            (1.72, up + np.array([-12, 0, 0])),
            (1.95, B),
        ],
        U,
        stiffness=170,
        damping=0.74,
    )
    pos = keyed(
        [
            (0, zero),
            (0.35, np.array([-2.6, 1.6, 0.6])),
            (1.2, np.array([-2.6, 1.8, 0.7])),
            (1.5, np.array([-2.3, 1.0, 0.5])),
            (1.72, np.array([-2.4, 2.0, 0.6])),
            (1.95, zero),
        ],
        U,
    )
    use.bone("item", rot=rot, pos=pos)

    def flip(at, shut):
        # The thumb pushes the latch open; it falls back when the lid closes over it.
        return keyed(
            [
                (0, zero),
                (at, zero),
                (at + 0.14, np.array([80.0, 0, 0])),
                (shut, np.array([80.0, 0, 0])),
                (shut + 0.14, zero),
            ],
            U,
            stiffness=220,
            damping=0.8,
        )

    use.bone("item_latch0", rot=flip(0.36, 1.8))
    use.bone("item_latch1", rot=flip(0.58, 1.8))
    use.bone(
        "item_lid",
        rot=keyed(
            [
                (0, zero),
                (0.8, zero),
                (1.02, np.array([-105.0, 0, 0])),
                (1.68, np.array([-105.0, 0, 0])),
                (1.84, zero),
            ],
            U,
            stiffness=200,
            damping=0.8,
        ),
    )
    use.bone(
        "camera",
        rot=lambda t: (
            camera_follow(rot, B, 0.04)(t)
            + kick(t, 1.6, np.array([-1.4, 0.3, 0.7]), 0.5)
        ),
    )
    use.hand("righthand", hand_track("righthand", [(0, g["r_side"])]))
    jab = Grip("root", (-2.0, -22.0, 9.0), (0.2, -1, -0.3))
    use.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_side"]),
                (0.32, g["l_latch0"]),
                (0.36, g["l_latch0"]),
                (0.58, g["l_latch1"]),
                (0.62, g["l_latch1"]),
                (0.82, g["l_lid"]),
                (1.04, g["l_lid"]),
                (1.3, g["l_inside"]),
                (1.33, g["l_inside"]),
                (1.6, jab),
                (1.64, jab),
                (1.94, g["l_side"]),
            ],
            lift=1.0,
        ),
    )
    use.follow(
        "item_injector", held(use, "lefthand", "item_injector", 1.3, 1.66, (0, -60, 0))
    )
    clips.append(use)

    end = Clip(rig, "use_end", 0.25)
    end.bone("item", rot=lambda t: B.copy())
    end.hand("righthand", hand_track("righthand", [(0, g["r_side"])]))
    end.hand("lefthand", hand_track("lefthand", [(0, g["l_side"])]))
    clips.append(end)

    clips += ground_clips(rig, g, use)
    # Ground grips are calibrated against the authored pose; retime after that calibration.
    for clip in clips:
        if clip.name in ("use", "ground_use"):
            authored = clip.pose
            clip.length = 0.95
            clip.pose = lambda t, authored=authored: authored(t * U / 0.95)
    return clips


# Healing from a thrown kit (MedkitAnimations): the case stays on the ground, in the world under
# the crosshair, so the rig's case is parked out of view and only the hands and the injector show.
GROUND_HIDE = np.array([0.0, -120.0, 0.0])
GROUND_USE = 1.95  # Authored timeline, retimed with the held use to fit a 20-tick heal.
GROUND_END = 0.3  # MedkitAnimations.KIT_END_NS


def ground_clips(rig, g, use):
    r_low = Grip("root", (9.0, -19.0, 6.0), (-0.3, 0, -1))
    # Down and forward toward the kit; the forearm's far end stays below the view.
    l_reach = Grip("root", (-2.5, -11.0, -9.0), (0, -1, -0.5))
    l_dig = Grip("root", (-1.0, -12.0, -10.0), (0.2, -1, -0.4))
    r_lid = Grip("root", (3.0, -11.5, -9.5), (0, -1, -0.6))
    l_show = Grip("root", (-3.0, -6.5, -5.0), (0.8, -0.3, -0.5))
    jab = Grip("root", (-2.0, -22.0, 9.0), (0.2, -1, -0.3))
    # The injector sits in the left hand as when it is taken from the held case.
    w = rig.world(use.pose_no_follow(1.3))
    inject_hold = (
        np.linalg.inv(rig.hand_frame(w, "lefthand"))
        @ w[rig.bones["item_injector"].parent]
    )
    hidden = (lambda t: np.zeros(3), lambda t: GROUND_HIDE.copy())

    L = GROUND_USE
    ground = Clip(rig, "ground_use", L, loop=None)
    ground.bone("item", rot=hidden[0], pos=hidden[1])
    # Reach, the right hand flips the lid up, the left digs out the injector, shows it and jabs.
    ground.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_low"]),
                (0.42, l_reach),
                (0.62, l_reach),
                (0.82, l_dig),
                (0.92, l_dig),
                (1.05, l_reach),
                (1.3, l_show),
                (1.38, l_show),
                (1.6, jab),
                (1.64, jab),
                (L, jab),
            ],
            lift=0.8,
        ),
    )
    ground.hand(
        "righthand",
        hand_track(
            "righthand",
            [
                (0, r_low),
                (0.2, r_low),
                (0.5, r_lid),
                (0.66, r_lid),
                (0.95, r_low),
            ],
            lift=0.8,
        ),
    )
    ground.follow(
        "item_injector",
        held(ground, "lefthand", "item_injector", 1.0, 1.66, (0, -60, 0), inject_hold),
    )
    zero = np.zeros(3)
    look = keyed(
        [
            (0, zero),
            (0.42, np.array([2.5, 0, 0])),
            (1.2, np.array([2.0, 0.5, 0])),
            (1.4, np.array([-1.0, 0, 0])),
            (L, zero),
        ],
        L,
        follow=False,
    )
    ground.bone(
        "camera",
        rot=lambda t: look(t) + kick(t, 1.6, np.array([-1.4, 0.3, 0.7]), 0.5),
    )

    end = Clip(rig, "ground_end", GROUND_END, loop=None)
    end.bone("item", rot=hidden[0], pos=hidden[1])
    end.hand("lefthand", hand_track("lefthand", [(0, g["l_low"])]))
    end.hand("righthand", hand_track("righthand", [(0, r_low)]))
    return [ground, end]


def kick(t, at, amount, length):
    """A short camera jolt that decays: amount at `at`, gone after `length`."""
    if t < at:
        return np.zeros(3)
    u = (t - at) / length
    return amount * (1 - math.exp(-30 * u)) * math.exp(-3.2 * u) * math.cos(5 * u)


def about(base, axis, angle, center, pivot):
    """Item (rot, pos offset) for `base` turned by `angle` about `axis` through `center`."""
    a = unit(axis)
    k = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    th = math.radians(angle)
    ra = np.eye(3) + math.sin(th) * k + (1 - math.cos(th)) * k @ k
    c, p = np.asarray(center, float), np.asarray(pivot, float)
    m = (
        affine(ra, c - ra @ c)
        @ affine(None, p)
        @ affine(euler(base))
        @ affine(None, -p)
    )
    r = m[:3, :3]
    return to_euler(r, base), m[:3, 3] - p + r @ p


def placer(scale, at):
    at = np.asarray(at, float)
    return lambda x, y, z: np.array([x, y, z], float) * scale + at


def idle_clip(rig, base, grips, right, left, length=3.2):
    clip = Clip(rig, "static_idle", length, loop=True)
    r, p = breathe(length)
    clip.bone("item", rot=lambda t: base + r(t), pos=p)
    clip.hand("righthand", hand_track("righthand", [(0, grips[right])]))
    clip.hand("lefthand", hand_track("lefthand", [(0, grips[left])]))
    return clip


def spin(t, start, turns, decay=1.6):
    """Prop flicked at `start`: fast spin slowing to a stop (degrees about y)."""
    if t < start:
        return np.zeros(3)
    u = t - start
    return np.array([0, turns * 360 * (1 - math.exp(-decay * u)), 0])


# Armour plate (SAPI in a cover, variant A) ----------------------------------------------

PLATE_AT = (0.54, (7.0, -10.0, -4.0))
PLATE = placer(*PLATE_AT)
PLATE_BASE = np.array([4.0, 168.0, -3.0])  # body side faces the player, strike face out


def armor_plate_rig():
    rig = Rig("armor_plate")
    rig.attach(armor_plate.variant_a(), *PLATE_AT)
    return rig


def armor_plate_grips():
    p = PLATE
    return {
        "r_edge": Grip("item", p(-9.3, -5.0, 0.0) - (1.7, 0, 0), (1, 0, 0)),
        "l_edge": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
        "l_top": Grip("item", p(0.0, 13.0, 0.0) + (0, 1.8, 0), (0, -1, 0.2)),
        "l_knock": Grip("item", p(-2.0, 1.0, 1.8) + (0, 0, 1.9), (0, 0, -1)),
        "l_low": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
        "r_low": Grip("root", (12.0, -20.0, 6.0), (-0.3, 0, -1)),
    }


def armor_plate_clips(rig):
    g = armor_plate_grips()
    B = PLATE_BASE
    zero = np.zeros(3)
    pivot = rig.bones["item"].pivot
    clips = [idle_clip(rig, B, g, "r_edge", "l_edge")]
    clips.append(draw_clip(rig, B, g, "r_edge", "l_edge"))
    clips.append(draw_clip(rig, B, g, "r_edge", "l_edge", length=0.5, put_away=True))

    # Inspect: the wrist swings the plate around the grip to show the strike face,
    # the knuckles check it for cracks twice, then it swings back.
    L = 3.6
    insp = Clip(rig, "inspect", L)
    p0 = np.asarray(pivot)
    hinge = p0 + euler(B) @ (g["r_edge"].point - p0)
    turn = keyed(
        [
            (0, np.zeros(1)),
            (0.3, np.zeros(1)),
            (1.1, np.array([-150.0])),
            (2.85, np.array([-150.0])),
            (3.5, np.zeros(1)),
        ],
        L,
        stiffness=120,
        damping=0.72,
    )
    lift = keyed(
        [
            (0, zero),
            (0.35, np.array([-2.0, 1.6, 0.0])),
            (1.1, np.array([-11.5, 2.2, -3.0])),
            (2.85, np.array([-11.5, 2.2, -3.0])),
            (3.5, zero),
        ],
        L,
    )
    knocks = (1.85, 2.3)

    def item_at(t):
        r, d = about(B, (0, 1, 0), float(turn(t)[0]), hinge, pivot)
        recoil = sum(
            (kick(t, k, np.array([0, 0, -0.22]), 0.3) for k in knocks), np.zeros(3)
        )
        return r, d + lift(t) + recoil

    insp.bone("item", rot=lambda t: item_at(t)[0], pos=lambda t: item_at(t)[1])
    knock = Grip("item", g["l_knock"].point + (0, 0, 1.4), g["l_knock"].palm)
    insp.hand("righthand", hand_track("righthand", [(0, g["r_edge"])]))
    insp.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_low"]),
                (1.15, g["l_low"]),
                (1.6, knock),
                (1.85, g["l_knock"]),
                (2.07, knock),
                (2.3, g["l_knock"]),
                (2.52, knock),
                (3.0, g["l_low"]),
            ],
            lift=1.0,
        ),
    )
    swing = lambda u: np.array([0, float(turn(u)[0]), 0])  # noqa: E731
    insp.bone(
        "camera",
        rot=lambda t: (
            camera_follow(swing, zero, 0.025)(t)
            + sum(
                (kick(t, k, np.array([0.3, 0, 0.15]), 0.3) for k in knocks), np.zeros(3)
            )
        ),
    )
    clips.append(insp)

    # Use: the plate tips forward and is driven down into the carrier on the chest.
    U = 0.95  # native use: 1.0 s
    use = Clip(rig, "use", U, loop=None)
    rot = keyed(
        [
            (0, B),
            (0.25, B + np.array([-22, 0, 4])),
            (0.52, B + np.array([-60, 0, 2])),
            (0.95, B + np.array([-68, 0, 0])),
        ],
        U,
        stiffness=240,
        damping=0.75,
    )
    pos = keyed(
        [
            (0, zero),
            (0.25, np.array([-3.2, 2.0, 0.5])),
            (0.52, np.array([-3.6, -7.0, 2.0])),
            (0.75, np.array([-3.6, -17.0, 3.0])),
            (0.95, np.array([-3.6, -19.0, 3.0])),
        ],
        U,
        stiffness=260,
        damping=0.78,
    )
    use.bone("item", rot=rot, pos=pos)
    seat = keyed(
        [(0, zero), (0.6, zero), (0.7, np.array([0, -0.6, 0])), (0.9, zero)], U
    )
    use.bone("root", pos=seat)
    tip = keyed(
        [
            (0, zero),
            (0.3, zero),
            (0.62, np.array([-4.0, 0, 0])),
            (0.95, np.array([-1.0, 0, 0])),
        ],
        U,
        follow=False,
    )
    use.bone(
        "camera", rot=lambda t: tip(t) + kick(t, 0.68, np.array([-1.6, 0.3, 0.6]), 0.4)
    )
    use.hand("righthand", hand_track("righthand", [(0, g["r_edge"])]))
    use.hand(
        "lefthand",
        hand_track("lefthand", [(0, g["l_edge"]), (0.48, g["l_top"])], lift=0.8),
    )
    clips.append(use)

    # The next plate comes up into the hand after a successful insert.
    redraw = draw_clip(rig, B, g, "r_edge", "l_edge", length=0.7)
    redraw.name = "use_end"
    clips.append(redraw)
    return clips


# FPV control station (variant B) ---------------------------------------------------------

MON_AT = (0.6, (1.2, -6.5, -3.0))
MON = placer(*MON_AT)
MON_BASE = np.array([-24.0, 0.0, 0.0])


def monitor_rig():
    rig = Rig("monitor")
    rig.attach(monitor.variant_b(), *MON_AT)
    return rig


def monitor_grips():
    m = MON
    return {
        "r_handle": Grip("item", m(15.0, -1.0, 0.0) + (0.4, 0, 0.6), (-1, 0, 0)),
        "l_handle": Grip("item", m(-15.0, -1.0, 0.0) + (-0.4, 0, 0.6), (1, 0, 0)),
        "l_key": Grip("item", m(-5.2, -5.8, 3.1) + (0, -0.6, 1.9), (0, 0.3, -1)),
        "l_screen": Grip("item", m(-3.0, 3.0, 2.65) + (0, 0, 2.0), (0, 0, -1)),
        "l_low": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
        # Thumbs over the gimbal sticks, fingers round the handle arms.
        "r_stick": Grip(
            "item", m(12.6, -4.5, 2.5) + (0.9, -0.6, 1.4), (-0.45, 0.15, -1)
        ),
        "l_stick": Grip(
            "item", m(-12.6, -4.5, 2.5) + (-0.9, -0.6, 1.4), (0.45, 0.15, -1)
        ),
    }


# Linked: held higher and more upright to watch the feed, thumbs on the sticks, antennas spread.
MON_LINKED = np.array([-12.0, 0.0, 0.0])
MON_LIFT = np.array([0.0, 2.2, 1.8])
ANTENNA_SPREAD = 24.0
# Looking into the screen just before and after FPV control: upright, close to the eyes.
MON_FACE = np.array([0.0, 0.0, 0.0])
MON_FACE_POS = np.array([-1.2, 4.7, 13.9])


def shifted(track, offset):
    """A hand track displaced by `offset(t)` in view space."""

    def at(t, world):
        f = track(t, world).copy()
        f[:3, 3] += offset(t)
        return f

    return at


def antennas(clip, spread):
    """Antennas tilt outward by `spread(t)` degrees (0 = upright as modelled)."""
    clip.bone("item_antenna_l", rot=lambda t: np.array([0, 0, float(spread(t)[0])]))
    clip.bone("item_antenna_r", rot=lambda t: np.array([0, 0, -float(spread(t)[0])]))


def linked_monitor_clips(rig, g):
    B, L0 = MON_LINKED, MON_LIFT
    zero = np.zeros(3)
    spread = lambda t: np.array([ANTENNA_SPREAD])  # noqa: E731
    clips = []

    idle = Clip(rig, "static_idle_linked", 3.2, loop=True)
    r, p = breathe(3.2)
    idle.bone("item", rot=lambda t: B + r(t), pos=lambda t: L0 + p(t))
    antennas(idle, spread)
    # The thumbs keep making small corrections on the sticks.
    nudge_r = lambda t: np.array(
        [0.25 * math.sin(2.1 * t), 0.2 * math.sin(3.3 * t + 1), 0]
    )  # noqa: E731
    nudge_l = lambda t: np.array(
        [0.2 * math.sin(2.7 * t + 2), 0.25 * math.sin(1.9 * t), 0]
    )  # noqa: E731
    idle.hand(
        "righthand", shifted(hand_track("righthand", [(0, g["r_stick"])]), nudge_r)
    )
    idle.hand("lefthand", shifted(hand_track("lefthand", [(0, g["l_stick"])]), nudge_l))
    clips.append(idle)

    draw = draw_clip(rig, B, g, "r_stick", "l_stick", suffix="_linked", item_pos=L0)
    antennas(draw, spread)
    clips.append(draw)
    away = draw_clip(
        rig,
        B,
        g,
        "r_stick",
        "l_stick",
        length=0.5,
        put_away=True,
        suffix="_linked",
        item_pos=L0,
    )
    antennas(away, spread)
    clips.append(away)

    # Link: the thumb presses the pairing key, the antennas swing out and the station comes up.
    K = 1.3
    link = Clip(rig, "link", K, loop=None)
    rot = keyed(
        [
            (0, MON_BASE),
            (0.25, MON_BASE),
            (0.55, MON_BASE + np.array([-4, 0, 0])),
            (1.15, B),
        ],
        K,
        stiffness=150,
        damping=0.74,
    )
    pos = keyed([(0, zero), (0.55, np.array([0, 0.4, 0.3])), (1.15, L0)], K)
    link.bone("item", rot=rot, pos=pos)
    antennas(
        link,
        keyed(
            [(0, np.zeros(1)), (0.5, np.zeros(1)), (0.85, np.array([ANTENNA_SPREAD]))],
            K,
            stiffness=260,
            damping=0.55,
        ),
    )
    press = Grip("item", g["l_key"].point + (0, 0, 0.9), g["l_key"].palm)
    link.hand(
        "righthand",
        hand_track(
            "righthand",
            [(0, g["r_handle"]), (0.6, g["r_handle"]), (1.05, g["r_stick"])],
        ),
    )
    link.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_handle"]),
                (0.32, press),
                (0.48, g["l_key"]),
                (0.64, press),
                (1.1, g["l_stick"]),
            ],
            lift=0.6,
        ),
    )
    link.bone("camera", rot=lambda t: kick(t, 0.48, np.array([-0.4, 0, 0.15]), 0.35))
    clips.append(link)

    # Into FPV: the station comes up in front of the eyes before the view cuts to the drone
    # (EquipmentAnimations delays SBW's camera switch by TO_FACE); out of FPV it comes back down.
    T = 0.35
    face = Clip(rig, "to_face", T, loop=None)
    face.bone("item", rot=glide(B, MON_FACE, 0, T), pos=glide(L0, MON_FACE_POS, 0, T))
    antennas(face, spread)
    face.bone(
        "camera",
        rot=keyed(
            [(0, zero), (T * 0.6, np.array([-0.5, 0, 0])), (T, zero)], T, follow=False
        ),
    )
    face.hand("righthand", hand_track("righthand", [(0, g["r_stick"])]))
    face.hand("lefthand", hand_track("lefthand", [(0, g["l_stick"])]))
    clips.append(face)
    E = 0.6
    end = Clip(rig, "control_end", E, loop=None)
    end.bone(
        "item", rot=glide(MON_FACE, B, 0.05, E), pos=glide(MON_FACE_POS, L0, 0.05, E)
    )
    antennas(end, spread)
    end.hand("righthand", hand_track("righthand", [(0, g["r_stick"])]))
    end.hand("lefthand", hand_track("lefthand", [(0, g["l_stick"])]))
    clips.append(end)

    # Inspect: check the link -- tilt the station to watch the feed, straighten an antenna.
    L = 3.4
    insp = Clip(rig, "inspect_linked", L)
    rot = keyed(
        [
            (0, B),
            (0.55, B + np.array([-4, 14, -6])),
            (1.2, B + np.array([-4, 12, -5])),
            (1.75, B + np.array([-2, -10, 6])),
            (2.95, B + np.array([-2, -9, 5])),
            (3.35, B),
        ],
        L,
        stiffness=120,
        damping=0.74,
    )
    pos = keyed(
        [
            (0, L0),
            (0.55, L0 + np.array([0.6, 0.5, 0.6])),
            (1.75, L0 + np.array([-0.4, 0.4, 0.6])),
            (2.95, L0 + np.array([-0.4, 0.4, 0.6])),
            (3.35, L0),
        ],
        L,
    )
    insp.bone("item", rot=rot, pos=pos)
    insp.bone("camera", rot=camera_follow(rot, B, 0.03))
    tip = Grip("item_antenna_l", MON(-10.5, 12.0, -0.5) + (-1.4, 0, 0), (1, 0, 0))
    bend = keyed(
        [
            (0, np.array([ANTENNA_SPREAD])),
            (1.95, np.array([ANTENNA_SPREAD])),
            (2.2, np.array([ANTENNA_SPREAD + 12])),
            (2.45, np.array([ANTENNA_SPREAD - 6])),
            (2.7, np.array([ANTENNA_SPREAD])),
        ],
        L,
        stiffness=220,
        damping=0.6,
    )
    insp.bone("item_antenna_l", rot=lambda t: np.array([0, 0, float(bend(t)[0])]))
    insp.bone("item_antenna_r", rot=lambda t: np.array([0, 0, -ANTENNA_SPREAD]))
    insp.hand(
        "righthand",
        hand_track(
            "righthand",
            [
                (0, g["r_stick"]),
                (1.4, g["r_stick"]),
                (1.75, g["r_handle"]),
                (2.9, g["r_handle"]),
                (3.3, g["r_stick"]),
            ],
        ),
    )
    insp.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_stick"]),
                (1.6, g["l_stick"]),
                (1.95, tip),
                (2.7, tip),
                (3.1, g["l_stick"]),
            ],
            lift=1.0,
        ),
    )
    clips.append(insp)
    return clips


def monitor_clips(rig):
    g = monitor_grips()
    B = MON_BASE
    zero = np.zeros(3)
    clips = [idle_clip(rig, B, g, "r_handle", "l_handle")]
    clips.append(draw_clip(rig, B, g, "r_handle", "l_handle"))
    clips.append(
        draw_clip(rig, B, g, "r_handle", "l_handle", length=0.5, put_away=True)
    )

    # Inspect: lift the station, thumb two keys, tap the screen twice, look at the other side.
    L = 3.8
    insp = Clip(rig, "inspect", L)
    rot = keyed(
        [
            (0, B),
            (0.6, np.array([-12, 20, -5])),
            (2.5, np.array([-10, 18, -4])),
            (3.0, np.array([-14, -22, 5])),
            (3.35, np.array([-14, -20, 4])),
            (3.75, B),
        ],
        L,
        stiffness=120,
        damping=0.72,
    )
    pos = keyed(
        [
            (0, zero),
            (0.6, np.array([1.5, 2.2, 2.0])),
            (2.5, np.array([1.4, 2.0, 2.0])),
            (3.0, np.array([-0.5, 1.8, 2.2])),
            (3.35, np.array([-0.5, 1.6, 2.0])),
            (3.75, zero),
        ],
        L,
    )
    insp.bone("item", rot=rot, pos=pos)
    insp.bone("camera", rot=camera_follow(rot, B, 0.035))
    press = Grip("item", g["l_key"].point + (0, 0, 0.9), g["l_key"].palm)
    tap = Grip("item", g["l_screen"].point + (0, 0, 1.2), g["l_screen"].palm)
    insp.hand("righthand", hand_track("righthand", [(0, g["r_handle"])]))
    insp.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_handle"]),
                (0.65, g["l_handle"]),
                (1.0, press),
                (1.2, g["l_key"]),
                (1.42, press),
                (1.62, g["l_key"]),
                (1.85, press),
                (2.15, tap),
                (2.35, g["l_screen"]),
                (2.57, tap),
                (2.77, g["l_screen"]),
                (3.0, tap),
                (3.45, g["l_handle"]),
            ],
            lift=0.8,
        ),
    )
    clips.append(insp)
    return clips + linked_monitor_clips(rig, g)


# Strike FPV drone (variant A) --------------------------------------------------------------

DRONE_AT = (0.57, (5.0, -10.4, -6.5))
DRONE = placer(*DRONE_AT)
DRONE_BASE = np.array([12.0, 196.0, -4.0])  # nose and warhead point away


def drone_rig():
    rig = Rig("drone")
    rig.attach(drone.variant_a(), *DRONE_AT)
    return rig


def drone_grips():
    d = DRONE
    return {
        # Palm up under the rear of the frame, behind the warhead; left hand steadies the near arm.
        "r_under": Grip("item", d(1.5, -1.0, -7.5) + (0, -2.0, 0), (0, 1, 0)),
        "l_arm": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
        "l_prop": Grip("item", d(9.2, 4.0, -9.2) + (2.4, 0.4, 0), (-1, -0.2, 0)),
        "l_low": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
    }


def drone_clips(rig):
    g = drone_grips()
    B = DRONE_BASE
    zero = np.zeros(3)
    clips = [idle_clip(rig, B, g, "r_under", "l_arm")]
    clips.append(draw_clip(rig, B, g, "r_under", "l_arm"))
    clips.append(draw_clip(rig, B, g, "r_under", "l_arm", length=0.5, put_away=True))

    # Inspect: turn the drone to check the warhead and its fuse, tilt it to look over the
    # frame, then spin a prop with a finger to check the motor runs free.
    L = 3.9
    insp = Clip(rig, "inspect", L)
    rot = keyed(
        [
            (0, B),
            (0.75, B + np.array([-8, -78, 16])),
            (1.75, B + np.array([-6, -82, 14])),
            (2.35, B + np.array([16, -10, 0])),
            (3.0, B + np.array([15, -8, 0])),
            (3.75, B),
        ],
        L,
        stiffness=120,
        damping=0.72,
    )
    pos = keyed(
        [
            (0, zero),
            (0.75, np.array([-2.0, 2.4, 1.5])),
            (1.75, np.array([-2.2, 2.6, 1.5])),
            (2.35, np.array([-1.0, 2.2, -0.5])),
            (3.0, np.array([-1.0, 2.0, -0.5])),
            (3.75, zero),
        ],
        L,
    )
    insp.bone("item", rot=rot, pos=pos)
    insp.bone("item_prop2", rot=lambda t: spin(t, 2.75, 3.0, 1.3))
    insp.bone("camera", rot=camera_follow(rot, B, 0.03))
    flick = Grip("item", g["l_prop"].point + (0, 0, 2.6), g["l_prop"].palm)
    insp.hand("righthand", hand_track("righthand", [(0, g["r_under"])]))
    insp.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_arm"]),
                (2.0, g["l_low"]),
                (2.5, g["l_prop"]),
                (2.75, flick),
                (2.9, flick),
                (3.4, g["l_low"]),
            ],
            lift=1.4,
        ),
    )
    clips.append(insp)
    return clips


# Ducted scout drone (variant C) ------------------------------------------------------------

SCOUT_AT = (0.52, (4.5, -8.8, -5.5))
SCOUT = placer(*SCOUT_AT)
SCOUT_BASE = np.array([14.0, 186.0, -3.0])


def scout_drone_rig():
    rig = Rig("scout_drone")
    rig.attach(scout_drone.variant_c(), *SCOUT_AT)
    return rig


def scout_drone_grips():
    s = SCOUT
    return {
        "r_under": Grip("item", s(0.0, -1.5, 0.0) + (0, -2.0, 0), (0, 1, 0)),
        "l_arm": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
        "l_lens": Grip("item", s(0.0, -0.4, 7.6) + (0, 0, 2.0), (0, 0, -1)),
        # Fingertip at the outer end of the rear-left rotor, flicking it round from the side.
        "l_prop": Grip("item", s(9.6, 2.6, -6.0) + (1.6, 0.6, 0), (-1, -0.3, 0)),
        "l_low": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
    }


def scout_drone_clips(rig):
    g = scout_drone_grips()
    B = SCOUT_BASE
    zero = np.zeros(3)
    pivot = rig.bones["item"].pivot
    clips = [idle_clip(rig, B, g, "r_under", "l_arm")]
    clips.append(draw_clip(rig, B, g, "r_under", "l_arm"))
    clips.append(draw_clip(rig, B, g, "r_under", "l_arm", length=0.5, put_away=True))

    # Inspect: the wrist turns the drone on the palm until the camera faces the player, the
    # thumb wipes the lens twice; then the top tips toward the player to check the rotors in
    # their ducts and a fingertip spins the nearest one.
    L = 4.4
    insp = Clip(rig, "inspect", L)
    p0 = np.asarray(pivot)
    palm = p0 + euler(B) @ (g["r_under"].point - p0)
    turn = keyed(
        [
            (0, np.zeros(1)),
            (0.2, np.zeros(1)),
            (0.95, np.array([-165.0])),
            (2.05, np.array([-165.0])),
            (2.75, np.array([-12.0])),
            (3.6, np.array([-12.0])),
            (4.25, np.zeros(1)),
        ],
        L,
        stiffness=110,
        damping=0.74,
    )
    tilt = keyed(
        [
            (0, zero),
            (0.95, np.array([10.0, 0, 0])),
            (2.05, np.array([10.0, 0, 0])),
            (2.75, np.array([-46.0, 0, 0])),
            (3.6, np.array([-44.0, 0, 0])),
            (4.25, zero),
        ],
        L,
        stiffness=110,
        damping=0.74,
    )
    lift = keyed(
        [
            (0, zero),
            (0.95, np.array([-3.0, 3.0, 1.0])),
            (2.05, np.array([-3.0, 3.0, 1.0])),
            (2.75, np.array([-2.0, 2.4, 0.0])),
            (3.6, np.array([-2.0, 2.4, 0.0])),
            (4.25, zero),
        ],
        L,
    )

    def item_at(t):
        r, d = about(B + tilt(t), (0, 1, 0), float(turn(t)[0]), palm, pivot)
        return r, d + lift(t)

    insp.bone("item", rot=lambda t: item_at(t)[0], pos=lambda t: item_at(t)[1])
    insp.bone("item_prop2", rot=lambda t: spin(t, 3.12, 2.5, 1.2))
    swing = lambda u: np.array([float(tilt(u)[0]), float(turn(u)[0]), 0])  # noqa: E731
    insp.bone("camera", rot=camera_follow(swing, zero, 0.025))
    wipe_l = Grip("item", g["l_lens"].point + (1.6, 0, 0), g["l_lens"].palm)
    wipe_r = Grip("item", g["l_lens"].point - (1.6, 0, 0), g["l_lens"].palm)
    flick = Grip("item", g["l_prop"].point + (0, 0, 2.6), g["l_prop"].palm)
    insp.hand("righthand", hand_track("righthand", [(0, g["r_under"])]))
    insp.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_arm"]),
                (0.8, g["l_low"]),
                (1.15, wipe_l),
                (1.42, wipe_r),
                (1.69, wipe_l),
                (1.96, wipe_r),
                (2.4, g["l_low"]),
                (2.85, g["l_prop"]),
                (3.12, flick),
                (3.25, flick),
                (3.7, g["l_low"]),
            ],
            lift=0.9,
        ),
    )
    clips.append(insp)
    return clips


# Command tablet (the Blockfield menu) -------------------------------------------------------

TAB_AT = (1.35, (0.4, -6.2, -2.0))
TAB = placer(*TAB_AT)
TAB_BASE = np.array([-30.0, 0.0, 0.0])  # screen tipped up toward the eyes
# Read the menu: the tablet comes up upright in front of the eyes, its glass filling most of the
# view, so the flat menu can take over exactly where the glass is (TabletScreen).
TAB_FACE_POS = np.array([0.25, 6.2, 12.6])
TAB_FACE = np.array([0.0, 0.0, 0.0])
# Centre of the map glass in the item model (tablet.py coordinates), just in front of it.
TAB_GLASS = (-0.475, 0.0, 0.43)


def tablet_rig():
    rig = Rig("tablet")
    rig.attach(tablet.model(), *TAB_AT)
    # TabletAnimations draws the live menu on this bone.
    rig.add("item_screen", "item", TAB(*TAB_GLASS))
    return rig


def glide(a, b, t0, t1):
    """a -> b between t0 and t1 with smootherstep: no overshoot, so the glass settles exactly."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    return lambda t: a + (b - a) * ease(min(max((t - t0) / (t1 - t0), 0.0), 1.0))


def tablet_grips():
    t = TAB
    return {
        # Palms on the rubber side grips, thumbs over the front bumpers.
        "r_side": Grip("item", t(7.6, 0.0, 0.2) + (0.9, 0, 0.4), (-1, 0, -0.15)),
        "l_side": Grip("item", t(-7.6, 0.0, 0.2) + (-0.9, 0, 0.4), (1, 0, -0.15)),
        "l_screen": Grip("item", t(-2.0, 1.0, 0.45) + (0, 0, 1.9), (0, 0, -1)),
        "l_low": Grip("root", (-9.0, -19.0, 6.0), (0.3, 0, -1)),
    }


def tablet_clips(rig):
    g = tablet_grips()
    B = TAB_BASE
    zero = np.zeros(3)
    pivot = rig.bones["item"].pivot
    clips = [idle_clip(rig, B, g, "r_side", "l_side")]
    clips.append(draw_clip(rig, B, g, "r_side", "l_side"))
    clips.append(draw_clip(rig, B, g, "r_side", "l_side", length=0.5, put_away=True))

    def hold(name, length, rot, pos, loop=None):
        clip = Clip(rig, name, length, loop=loop)
        clip.bone("item", rot=rot, pos=pos)
        clip.hand("righthand", hand_track("righthand", [(0, g["r_side"])]))
        clip.hand("lefthand", hand_track("lefthand", [(0, g["l_side"])]))
        return clip

    # Open/close: from the chest hold up in front of the eyes and back.
    RISE = 0.4
    up = hold(
        "open", RISE, glide(B, TAB_FACE, 0, RISE), glide(zero, TAB_FACE_POS, 0, RISE)
    )
    up.bone(
        "camera",
        rot=keyed(
            [(0, zero), (RISE * 0.5, np.array([-0.5, 0, 0])), (RISE, zero)],
            RISE,
            follow=False,
        ),
    )
    clips.append(up)
    # Held still while the menu is open: the flat menu sits on the glass.
    clips.append(
        hold(
            "open_idle",
            1.0,
            lambda t: TAB_FACE.copy(),
            lambda t: TAB_FACE_POS.copy(),
            loop=True,
        )
    )
    clips.append(
        hold(
            "close", 0.4, glide(TAB_FACE, B, 0, 0.4), glide(TAB_FACE_POS, zero, 0, 0.4)
        )
    )

    # Raise/lower: the tablet comes straight from the vest to the eyes when another item was held.
    low_p, low_r = np.array([2.0, -18.0, -2.0]), np.array([40.0, -10.0, -8.0])
    R = 0.45
    raise_ = hold(
        "raise",
        R,
        glide(TAB_FACE + low_r, TAB_FACE, 0, R),
        glide(TAB_FACE_POS + low_p, TAB_FACE_POS, 0, R),
    )
    raise_.bone(
        "camera",
        rot=keyed(
            [(0, zero), (R * 0.45, np.array([-0.6, 0.15, 0.2])), (R, zero)],
            R,
            follow=False,
        ),
    )
    clips.append(raise_)
    W = 0.4
    clips.append(
        hold(
            "lower",
            W,
            glide(TAB_FACE, TAB_FACE + low_r, 0, W),
            glide(TAB_FACE_POS, TAB_FACE_POS + low_p, 0, W),
        )
    )

    # Inspect: the right wrist turns the tablet to check the back strap, the left hand
    # steadies it again and taps the map twice.
    L = 3.4
    insp = Clip(rig, "inspect", L)
    p0 = np.asarray(pivot)
    hinge = p0 + euler(B) @ (g["r_side"].point - p0)
    turn = keyed(
        [
            (0, np.zeros(1)),
            (0.3, np.zeros(1)),
            (1.0, np.array([-140.0])),
            (1.75, np.array([-140.0])),
            (2.35, np.zeros(1)),
        ],
        L,
        stiffness=120,
        damping=0.74,
    )
    lift = keyed(
        [
            (0, zero),
            (1.0, np.array([-6.0, 2.0, -1.0])),
            (1.75, np.array([-6.0, 2.0, -1.0])),
            (2.35, zero),
        ],
        L,
    )

    def item_at(t):
        r, d = about(B, (0, 1, 0), float(turn(t)[0]), hinge, pivot)
        return r, d + lift(t)

    insp.bone("item", rot=lambda t: item_at(t)[0], pos=lambda t: item_at(t)[1])
    swing = lambda u: np.array([0, float(turn(u)[0]), 0])  # noqa: E731
    insp.bone("camera", rot=camera_follow(swing, zero, 0.02))
    tap = Grip("item", g["l_screen"].point + (0, 0, 1.2), g["l_screen"].palm)
    insp.hand("righthand", hand_track("righthand", [(0, g["r_side"])]))
    insp.hand(
        "lefthand",
        hand_track(
            "lefthand",
            [
                (0, g["l_side"]),
                (0.3, g["l_side"]),
                (0.75, g["l_low"]),
                (2.1, g["l_low"]),
                (2.5, tap),
                (2.68, g["l_screen"]),
                (2.88, tap),
                (3.06, g["l_screen"]),
                (3.35, g["l_side"]),
            ],
            lift=1.0,
        ),
    )
    clips.append(insp)
    return clips


ITEMS = {
    "medical_kit": (medical_kit_rig, medical_kit_clips),
    "armor_plate": (armor_plate_rig, armor_plate_clips),
    "monitor": (monitor_rig, monitor_clips),
    "drone": (drone_rig, drone_clips),
    "scout_drone": (scout_drone_rig, scout_drone_clips),
    "command_tablet": (tablet_rig, tablet_clips),
}
SOURCES = {
    "medical_kit": (medical_kit, medical_kit.variant_b),
    "armor_plate": (armor_plate, armor_plate.variant_a),
    "monitor": (monitor, monitor.variant_b),
    "drone": (drone, drone.variant_a),
    "scout_drone": (scout_drone, scout_drone.variant_c),
}
