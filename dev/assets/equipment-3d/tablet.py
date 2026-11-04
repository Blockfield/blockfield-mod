"""The command tablet's shipped item model, read as a kit-shaped model for the hand rig.

The tablet keeps its approved look: geometry and UVs come from
assets/blockfield/models/item/command_tablet.json and its 256 px atlas, centred on the origin.
"""

from dataclasses import dataclass
import json

import numpy as np
from PIL import Image

from kit import BRAND_ASSETS

MODEL = BRAND_ASSETS / "models/item/command_tablet.json"
TEXTURE = BRAND_ASSETS / "textures/item/command_tablet_body.png"
CENTRE = np.array([8.0, 8.0, 7.9])


@dataclass
class Element:
    lo: tuple
    hi: tuple
    rot: tuple | None
    faces: dict
    bone: str = "body"


class JavaModel:
    def __init__(self):
        data = json.loads(MODEL.read_text())
        self.bones, self.parents, self.boxes = {}, {}, []
        for e in data["elements"]:
            rot = None
            if "rotation" in e and e["rotation"]["angle"]:
                r = e["rotation"]
                rot = (r["axis"], r["angle"], tuple(np.array(r["origin"]) - CENTRE))
            self.boxes.append(
                Element(
                    tuple(np.array(e["from"]) - CENTRE),
                    tuple(np.array(e["to"]) - CENTRE),
                    rot,
                    e["faces"],
                )
            )
        image = Image.open(TEXTURE).convert("RGBA")
        self.atlas = np.asarray(image, float) / 255
        self.size = image.width

    def bounds(self):
        return np.min([b.lo for b in self.boxes], axis=0), np.max(
            [b.hi for b in self.boxes], axis=0
        )

    def bake(self):
        k = self.size / 16
        uv = {}
        for i, b in enumerate(self.boxes):
            for face, f in b.faces.items():
                u0, v0, u1, v1 = f["uv"]
                uv[(i, face)] = (u0 * k, v0 * k, (u1 - u0) * k, (v1 - v0) * k)
        return self.atlas, uv


def model():
    return JavaModel()
