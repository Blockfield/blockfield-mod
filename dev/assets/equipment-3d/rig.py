"""First-person rig: item bones plus TaCZ hand bones, Bedrock export and animation baking.

All authoring happens in Blockbench space (x east, y up, z south, degrees, Euler order ZYX).
Bedrock files negate x for positions and pivots and negate x/y rotations; TaCZ reads them
the way Blockbench writes them. The camera (`idle_view`) looks north, so item fronts face south.
"""

from dataclasses import dataclass, field
import json
import math

import numpy as np

from kit import FACES, face_quad, raster, rgb

# Hand bones copied from the LR grenade rig, the reference the TaCZ hand renderers expect.
HAND_BONES = {
    "r": ("root", (0, 2, 0)),
    "righthand": ("r", (-6, 19, -4)),
    "righthand_pos": ("righthand", (0, 8, -4)),
    "l": ("root", (-10, 2, 0)),
    "lefthand": ("l", (6, 19, -4)),
    "lefthand_pos": ("lefthand", (0, 8, -4)),
}
# Arm volumes relative to the *_pos pivot when the bone is unrotated (Blockbench space).
ARM_BOX = {
    "righthand_pos": ((-8, 0, -2), (-4, 12, 2)),
    "lefthand_pos": ((4, 0, -2), (8, 12, 2)),
}
SKIN, SLEEVE = "#c69a7c", "#3f4a33"
# Items are placed around the origin; the view sits behind them, looking north.
CAMERA = (0, 0, 16)
SHOULDER = {
    "righthand": np.array([9.0, -17.0, 12.0]),
    "lefthand": np.array([-9.0, -17.0, 12.0]),
}
PALM = {
    "righthand": np.array([-6.0, 10.0, 0.0]),
    "lefthand": np.array([6.0, 10.0, 0.0]),
}


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def reach(hand, point, palm=(0, 0, -1), shoulder=None):
    """Hand frame with the palm centre at `point`, the forearm aimed at the shoulder.

    The arm runs from the bone pivot (shoulder side) along local +y to the hand at y 8..12.
    `palm` hints where the palm (local -z) faces; it is made orthogonal to the forearm.
    """
    shoulder = SHOULDER[hand] if shoulder is None else np.asarray(shoulder, float)
    y = unit(np.asarray(point, float) - shoulder)
    z = -np.asarray(palm, float)
    z = unit(z - y * np.dot(z, y))
    x = np.cross(y, z)
    r = np.column_stack([x, y, z])
    return affine(r, np.asarray(point, float) - r @ PALM[hand])


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler(deg):
    """Blockbench ZYX Euler (degrees) to a rotation matrix."""
    x, y, z = (math.radians(v) for v in deg)
    return rot_z(z) @ rot_y(y) @ rot_x(x)


def to_euler(m, near=None):
    """Inverse of `euler`; picks the solution closest to `near` so baked keys never wrap."""
    sy = -m[2, 0]
    sy = max(-1.0, min(1.0, sy))
    y = math.asin(sy)
    if abs(sy) < 0.99999:
        x = math.atan2(m[2, 1], m[2, 2])
        z = math.atan2(m[1, 0], m[0, 0])
    else:
        x = math.atan2(-m[1, 2], m[1, 1])
        z = 0.0
    out = np.degrees([x, y, z])
    if near is not None:
        alt = np.degrees([x + math.pi, math.pi - y, z + math.pi])
        cands = [out, alt]
        best = None
        for c in cands:
            c = np.array(c, float)
            c = c + np.round((np.asarray(near) - c) / 360) * 360
            if best is None or np.abs(c - near).sum() < np.abs(best - near).sum():
                best = c
        out = best
    return np.asarray(out, float)


def affine(r=None, t=(0, 0, 0)):
    m = np.eye(4)
    if r is not None:
        m[:3, :3] = r
    m[:3, 3] = t
    return m


@dataclass
class Bone:
    name: str
    parent: str | None
    pivot: tuple
    rest: tuple = (0, 0, 0)
    boxes: list = field(default_factory=list)  # (lo, hi, uv dict, rot) in rig units


class Rig:
    def __init__(self, name):
        self.name = name
        self.bones: dict[str, Bone] = {}
        self.atlas = None
        self.add("root", None, (0, 0, 0))
        for bone, (parent, pivot) in HAND_BONES.items():
            self.add(bone, parent, pivot)
        self.add("idle_view", None, CAMERA)
        self.add("camera", None, CAMERA)

    def add(self, name, parent, pivot, rest=(0, 0, 0)):
        self.bones[name] = Bone(name, parent, tuple(map(float, pivot)), tuple(rest))
        return self.bones[name]

    def attach(self, model, scale, offset, parent="root", prefix="item"):
        """Place a kit Model: box `bone` names map to `<prefix>` (body) or `<prefix>_<bone>`."""
        atlas, uv = model.bake()
        self.atlas, self.uv_size = atlas, atlas.shape[0]
        offset = np.asarray(offset, float)

        def place(p):
            return tuple(np.asarray(p, float) * scale + offset)

        names = {"body": prefix}
        lo, hi = model.bounds()
        self.add(prefix, parent, place((lo + hi) / 2))
        for bone, pivot in model.bones.items():
            if bone == "body":
                continue
            names[bone] = f"{prefix}_{bone}"
            self.add(
                names[bone],
                prefix
                if model.parents.get(bone, "body") == "body"
                else names[model.parents[bone]],
                place(pivot),
            )
        for i, b in enumerate(model.boxes):
            faces = {f: uv[(i, f)] for f in FACES if (i, f) in uv}
            rot = None
            if b.rot:
                rot = (b.rot[0], b.rot[1], place(b.rot[2]))
            self.bones[names[b.bone]].boxes.append(
                (place(b.lo), place(b.hi), faces, rot)
            )
        return names

    # Bedrock export --------------------------------------------------------------------

    def geo(self, identifier):
        bones = []
        for b in self.bones.values():
            out = {
                "name": b.name,
                "pivot": [r4(-b.pivot[0]), r4(b.pivot[1]), r4(b.pivot[2])],
            }
            if b.parent:
                out["parent"] = b.parent
            if any(b.rest):
                out["rotation"] = [r4(-b.rest[0]), r4(-b.rest[1]), r4(b.rest[2])]
            cubes = []
            for lo, hi, faces, rot in b.boxes:
                uv = {}
                for face, (x, y, w, h) in faces.items():
                    # Bedrock flips up/down faces on both axes relative to Java.
                    uv[face] = (
                        {"uv": [x + w, y + h], "uv_size": [-w, -h]}
                        if face in ("up", "down")
                        else {"uv": [x, y], "uv_size": [w, h]}
                    )
                cube = {
                    "origin": [r4(-hi[0]), r4(lo[1]), r4(lo[2])],
                    "size": [r4(hi[k] - lo[k]) for k in range(3)],
                    "uv": uv,
                }
                if rot:
                    axis, angle, origin = rot
                    cube["pivot"] = [r4(-origin[0]), r4(origin[1]), r4(origin[2])]
                    cube["rotation"] = {
                        "x": [-angle, 0, 0],
                        "y": [0, -angle, 0],
                        "z": [0, 0, angle],
                    }[axis]
                cubes.append(cube)
            if cubes:
                out["cubes"] = cubes
            bones.append(out)
        return {
            "format_version": "1.12.0",
            "minecraft:geometry": [
                {
                    "description": {
                        "identifier": identifier,
                        "texture_width": self.uv_size,
                        "texture_height": self.uv_size,
                        "visible_bounds_width": 4,
                        "visible_bounds_height": 3,
                        "visible_bounds_offset": [0, 0.5, 0],
                    },
                    "bones": bones,
                }
            ],
        }

    # Posing ----------------------------------------------------------------------------

    def world(self, pose):
        """pose: bone -> (rot deg, pos offset) in Blockbench space; returns bone -> 4x4 matrix."""
        out = {}

        def solve(name):
            if name in out:
                return out[name]
            b = self.bones[name]
            r, d = pose.get(name, ((0, 0, 0), (0, 0, 0)))
            p = np.asarray(b.pivot)
            local = (
                affine(None, p + np.asarray(d))
                @ affine(euler(np.asarray(b.rest) + np.asarray(r)))
                @ affine(None, -p)
            )
            out[name] = (solve(b.parent) @ local) if b.parent else local
            return out[name]

        for name in self.bones:
            solve(name)
        return out

    def hand_values(self, hand, frame):
        """Bone values that put `<hand>_pos` at `frame` (4x4 in root space)."""
        b, pos = self.bones[hand], self.bones[hand + "_pos"]
        q = frame[:3, :3]
        d = (
            frame[:3, 3]
            - np.asarray(b.pivot)
            - q @ (np.asarray(pos.pivot) - np.asarray(b.pivot))
        )
        return q, d

    def hand_frame(self, world, hand):
        """Current frame of `<hand>_pos` in root space (rotation + pivot point)."""
        m = world[hand + "_pos"]
        f = affine(
            m[:3, :3],
            m[:3, :3] @ np.asarray(self.bones[hand + "_pos"].pivot) + m[:3, 3],
        )
        return f


def r4(v):
    return round(float(v), 4)


# Animation authoring ---------------------------------------------------------------------


def smooth(keys):
    """Monotone cubic through (t, vector) keys; flat at the ends, no overshoot."""
    ts = np.array([k[0] for k in keys], float)
    vs = np.array([k[1] for k in keys], float)
    n = len(ts)
    slopes = np.zeros_like(vs)
    for i in range(1, n - 1):
        a = (vs[i] - vs[i - 1]) / (ts[i] - ts[i - 1])
        b = (vs[i + 1] - vs[i]) / (ts[i + 1] - ts[i])
        same = a * b > 0
        slopes[i] = np.where(
            same,
            2
            / np.where(
                same, 1 / np.where(a == 0, 1, a) + 1 / np.where(b == 0, 1, b), 1
            ),
            0,
        )

    def at(t):
        if t <= ts[0]:
            return vs[0].copy()
        if t >= ts[-1]:
            return vs[-1].copy()
        i = int(np.searchsorted(ts, t) - 1)
        h = ts[i + 1] - ts[i]
        u = (t - ts[i]) / h
        h00, h10 = 2 * u**3 - 3 * u**2 + 1, u**3 - 2 * u**2 + u
        h01, h11 = -2 * u**3 + 3 * u**2, u**3 - u**2
        return (
            h00 * vs[i]
            + h10 * h * slopes[i]
            + h01 * vs[i + 1]
            + h11 * h * slopes[i + 1]
        )

    return at


def spring(fn, length, stiffness=170.0, damping=0.62, rate=600):
    """Follow-through: a damped spring chases `fn`, adding lag and a little overshoot."""
    steps = int(length * rate) + 2
    dt = 1 / rate
    w = math.sqrt(stiffness)
    x = np.asarray(fn(0), float).copy()
    v = np.zeros_like(x)
    samples = [x.copy()]
    for k in range(1, steps):
        target = np.asarray(fn(k * dt), float)
        a = stiffness * (target - x) - 2 * damping * w * v
        v += a * dt
        x += v * dt
        samples.append(x.copy())
    samples = np.array(samples)

    def at(t):
        f = min(max(t * rate, 0), steps - 1.001)
        i = int(f)
        return samples[i] * (1 - (f - i)) + samples[i + 1] * (f - i)

    return at


def ease(u):
    u = min(max(u, 0.0), 1.0)
    return u * u * u * (u * (6 * u - 15) + 10)


def slerp_frames(a, b, u):
    """Blend two rigid frames: lerp position, slerp rotation."""
    qa, qb = mat_quat(a[:3, :3]), mat_quat(b[:3, :3])
    if np.dot(qa, qb) < 0:
        qb = -qb
    dot = min(1.0, float(np.dot(qa, qb)))
    if dot > 0.9995:
        q = qa + u * (qb - qa)
    else:
        th = math.acos(dot)
        q = (math.sin((1 - u) * th) * qa + math.sin(u * th) * qb) / math.sin(th)
    q /= np.linalg.norm(q)
    return affine(quat_mat(q), a[:3, 3] * (1 - u) + b[:3, 3] * u)


def mat_quat(m):
    t = np.trace(m)
    if t > 0:
        s = math.sqrt(t + 1) * 2
        return np.array(
            [
                0.25 * s,
                (m[2, 1] - m[1, 2]) / s,
                (m[0, 2] - m[2, 0]) / s,
                (m[1, 0] - m[0, 1]) / s,
            ]
        )
    i = int(np.argmax(np.diag(m)))
    j, k = (i + 1) % 3, (i + 2) % 3
    s = math.sqrt(1 + m[i, i] - m[j, j] - m[k, k]) * 2
    q = np.zeros(4)
    q[0] = (m[k, j] - m[j, k]) / s
    q[1 + i] = 0.25 * s
    q[1 + j] = (m[j, i] + m[i, j]) / s
    q[1 + k] = (m[k, i] + m[i, k]) / s
    return q


def quat_mat(q):
    w, x, y, z = q
    return np.array(
        [
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
        ]
    )


class Clip:
    """One animation: bone channels as functions of time, hands as grips or free poses."""

    def __init__(self, rig, name, length, loop=False):
        self.rig, self.name, self.length, self.loop = rig, name, length, loop
        self.channels = {}  # bone -> (rot fn, pos fn)
        self.hands = {}  # hand -> fn(t, world) -> frame
        self.follows = {}  # bone -> fn(t, world) -> world matrix of the bone's rest space
        self.sounds = {}

    def bone(self, name, rot=None, pos=None):
        zero = lambda t: np.zeros(3)  # noqa: E731
        self.channels[name] = (rot or zero, pos or zero)

    def hand(self, hand, fn):
        self.hands[hand] = fn

    def follow(self, bone, fn):
        self.follows[bone] = fn

    def pose_no_follow(self, t):
        pose = {b: (r(t), p(t)) for b, (r, p) in self.channels.items()}
        world = self.rig.world(pose)
        for hand, fn in self.hands.items():
            q, d = self.rig.hand_values(hand, fn(t, world))
            pose[hand] = (to_euler(q), d)
        return pose

    def pose(self, t):
        pose = {b: (r(t), p(t)) for b, (r, p) in self.channels.items()}
        world = self.rig.world(pose)
        for hand, fn in self.hands.items():
            q, d = self.rig.hand_values(hand, fn(t, world))
            pose[hand] = (to_euler(q), d)
        if self.follows:
            world = self.rig.world(pose)
            for bone, fn in self.follows.items():
                b = self.rig.bones[bone]
                local = np.linalg.inv(world[b.parent]) @ fn(t, world)
                r = local[:3, :3]
                p = np.asarray(b.pivot)
                pose[bone] = (to_euler(r), local[:3, 3] - p + r @ p)
        return pose

    def bake(self, rate=60):
        bones = {}
        frames = max(2, int(round(self.length * rate)) + 1)
        last = {}
        for i in range(frames):
            t = min(i / rate, self.length)
            pose = self.pose(t)
            for bone, (r, p) in pose.items():
                r = np.asarray(r, float)
                if bone in last:
                    r = r + np.round((last[bone] - r) / 360) * 360
                last[bone] = r
                ch = bones.setdefault(bone, {"rotation": {}, "position": {}})
                key = f"{t:.4f}".rstrip("0").rstrip(".") if t else "0.0"
                ch["rotation"][key] = [r4(-r[0]), r4(-r[1]), r4(r[2])]
                ch["position"][key] = [r4(-p[0]), r4(p[1]), r4(p[2])]
        for ch in bones.values():
            for kind in ("rotation", "position"):
                values = list(ch[kind].values())
                if all(v == values[0] for v in values):
                    ch[kind] = values[0]
        out = {"animation_length": r4(self.length), "bones": bones}
        if self.loop:
            out["loop"] = True
        elif self.loop is None:
            out["loop"] = "hold_on_last_frame"
        if self.sounds:
            out["sound_effects"] = {
                f"{t:.4f}": {"effect": s} for t, s in sorted(self.sounds.items())
            }
        return out


def animation_file(clips):
    return {"format_version": "1.8.0", "animations": {c.name: c.bake() for c in clips}}


def grip(world, bone, local):
    """Hand frame fixed to `bone`: `local` is the frame in that bone's rest space."""
    return world[bone] @ local


def frame(pos, rot=(0, 0, 0)):
    return affine(euler(rot), pos)


# Preview ---------------------------------------------------------------------------------


def preview(rig, pose, size=(960, 540), fov=70, background="#9fb7d6"):
    """Camera at idle_view looking north, like TaCZ's first-person positioning."""
    W, H = size[0] * 2, size[1] * 2
    world = rig.world(pose)
    cam = world["idle_view"] @ affine(None, np.asarray(rig.bones["idle_view"].pivot))
    cam_rot = world["camera"][:3, :3] @ cam[:3, :3]
    eye = cam[:3, 3]
    color = np.ones((H, W, 3)) * rgb(background)
    zbuf = np.full((H, W), np.inf)
    f = (H / 2) / math.tan(math.radians(fov / 2))
    tex = rig.atlas
    solid = {}

    def flat(hexcolor):
        if hexcolor not in solid:
            solid[hexcolor] = np.ones((1, 1, 4))
            solid[hexcolor][0, 0, :3] = rgb(hexcolor)
        return solid[hexcolor]

    def draw(pts, n, texture, tuv, bright_base=1.0):
        rel = (pts - eye) @ cam_rot  # camera space: x right, y up, -z forward
        if np.dot(cam_rot.T @ n, -(rel.mean(axis=0))) <= 0:
            return
        depth = -rel[:, 2]
        if np.any(depth <= 0.5):
            return
        sx = W / 2 + rel[:, 0] * f / depth
        sy = H / 2 - rel[:, 1] * f / depth
        light = np.array([-0.3, 0.8, 0.5])
        bright = (
            0.55 + 0.45 * max(0.0, float(np.dot(n, light / np.linalg.norm(light))))
        ) * bright_base
        for tri in ((0, 1, 2), (0, 2, 3)):
            t = list(tri)
            raster(color, zbuf, sx[t], sy[t], depth[t], tuv[t], texture, bright)

    for b in rig.bones.values():
        m = world[b.name]
        for lo, hi, faces, rot in b.boxes:
            for face, (x, y, w, h) in faces.items():
                pts = np.array(face_quad(np.asarray(lo), np.asarray(hi), face), float)
                n = np.array(
                    {
                        "north": (0, 0, -1),
                        "south": (0, 0, 1),
                        "east": (1, 0, 0),
                        "west": (-1, 0, 0),
                        "up": (0, 1, 0),
                        "down": (0, -1, 0),
                    }[face],
                    float,
                )
                if rot:
                    from kit import rot_matrix

                    r = rot_matrix(rot[0], rot[1])
                    pts = (pts - rot[2]) @ r.T + rot[2]
                    n = r @ n
                pts = pts @ m[:3, :3].T + m[:3, 3]
                n = m[:3, :3] @ n
                tuv = np.array([[x, y], [x + w, y], [x + w, y + h], [x, y + h]], float)
                draw(pts, n, tex, tuv)
    for hand, (lo, hi) in ARM_BOX.items():
        fr = rig.hand_frame(world, hand.replace("_pos", ""))
        for part, (y0, y1, c) in {
            "hand": (8, 12, SKIN),
            "sleeve": (0, 8, SLEEVE),
        }.items():
            plo = np.array([lo[0], y0, lo[2]], float)
            phi = np.array([hi[0], y1, hi[2]], float)
            for face in FACES:
                pts = np.array(face_quad(plo, phi, face), float)
                n = np.array(
                    {
                        "north": (0, 0, -1),
                        "south": (0, 0, 1),
                        "east": (1, 0, 0),
                        "west": (-1, 0, 0),
                        "up": (0, 1, 0),
                        "down": (0, -1, 0),
                    }[face],
                    float,
                )
                pts = pts @ fr[:3, :3].T + fr[:3, 3]
                n = fr[:3, :3] @ n
                draw(
                    pts,
                    n,
                    flat(c),
                    np.array([[0, 0], [1, 0], [1, 1], [0, 1]], float) * 0.999,
                )
    img = color.reshape(size[1], 2, size[0], 2, 3).mean(axis=(1, 3))
    return np.clip(img, 0, 1)


def load_bedrock(path, texture=None):
    """Reference rigs (LR grenade): bones and hand poses only, for calibration."""
    geo = json.load(open(path))["minecraft:geometry"][0]
    rig = Rig.__new__(Rig)
    rig.name, rig.bones, rig.atlas, rig.uv_size = "ref", {}, np.ones((1, 1, 4)) * 0.6, 1
    for b in geo["bones"]:
        p = b.get("pivot", [0, 0, 0])
        r = b.get("rotation", [0, 0, 0])
        rig.add(b["name"], b.get("parent"), (-p[0], p[1], p[2]), (-r[0], -r[1], r[2]))
    return rig


def bedrock_pose(anim, t=0.0):
    """Static (non-keyed) values of a Bedrock animation, converted to Blockbench space."""
    pose = {}
    for bone, ch in anim["bones"].items():
        r = ch.get("rotation", [0, 0, 0])
        p = ch.get("position", [0, 0, 0])
        if isinstance(r, dict):
            r = list(r.values())[0]
            r = r["post"] if isinstance(r, dict) else r
        if isinstance(p, dict):
            p = list(p.values())[0]
            p = p["post"] if isinstance(p, dict) else p
        pose[bone] = ((-r[0], -r[1], r[2]), (-p[0], p[1], p[2]))
    return pose
