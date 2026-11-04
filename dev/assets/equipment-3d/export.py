"""Write the shipped equipment assets: `export.py [resources-root]`.

First person: Bedrock geometry + animations (TaCZ hands, see anims.py) under assets/blockfield.
Inventory, ground and third person: Java item models in the built-in `equipment` pack, which replace
SuperbWarfare's flat sprites. Both use the same atlas, client/.../textures/item/equipment/.
"""

from pathlib import Path
import json
import sys

import numpy as np

import anims
from build import display
import drone
from kit import java_model, save_png, write_json
import medical_kit
from rig import Rig
import scout_drone

HERE = Path(__file__).resolve().parent
RESOURCES = HERE.parents[2] / "client/src/main/resources"
OVERRIDES = {
    "armor_plate": [
        {
            "predicate": {"superbwarfare:armor_plate_infinite": 1},
            "model": "superbwarfare:item/armor_plate_infinite",
        }
    ],
    "monitor": [
        {
            "predicate": {"superbwarfare:monitor_linked": 1},
            "model": "superbwarfare:item/monitor_linked",
        }
    ],
}


def compact(data):
    return json.dumps(data, separators=(",", ":"), ensure_ascii=False) + "\n"


def item_model(path, name, model, uv, size, module):
    lo, hi = model.bounds()
    data = java_model(
        model,
        f"blockfield:item/equipment/{name}",
        uv,
        size,
        16.0 / max(hi - lo),
        display(module),
    )
    data.pop("credit")
    write_json(path, data)


# SuperbWarfare entities, redrawn from the approved items at the size of its own models.
# Flying drones are GeckoLib vehicles: DroneModel spins the four `wing*` bones in code; the payload
# is drawn separately by DroneRenderer, so the strike drone keeps only its empty cradle.
# The thrown medkit (sneak + use) is a Bedrock projectile model.
ENTITIES = {
    # entity: (model, scale, bottom y, geometry, texture) under assets/superbwarfare
    "drone": (
        lambda: drone.variant_a(rocket=False),
        0.4,
        0.0,
        "geo/drone.geo.json",
        "textures/entity/drone.png",
    ),
    "scout_drone": (
        scout_drone.variant_c,
        0.5,
        0.5,
        "geo/scout_drone.geo.json",
        "textures/entity/scout_drone.png",
    ),
    "medical_kit": (
        medical_kit.variant_b,
        0.25,
        0.0,
        "models/bedrock/projectile/medical_kit.geo.json",
        "textures/bedrock/projectile/medical_kit.png",
    ),
}
WINGS = ("wingFL", "wingFR", "wingBL", "wingBR")


def entity_geo(name, model, scale, bottom):
    rig = Rig(name)
    rig.bones.clear()
    rig.add("bone", None, (0, 0, 0))
    # Kit models face +z (south); SuperbWarfare's drones face -z.
    rig.add("0", "bone", (0, 0, 0), rest=(0, 180, 0))
    lo, hi = model.bounds()
    centre = (lo + hi) / 2
    names = rig.attach(
        model,
        scale,
        (-centre[0] * scale, bottom - lo[1] * scale, -centre[2] * scale),
        parent="0",
        prefix="body",
    )
    props = sorted(n for b, n in names.items() if b.startswith("prop"))
    for old, new in zip(props, WINGS if props else (), strict=True):
        bone = rig.bones.pop(old)
        bone.name = new
        rig.bones[new] = bone
    return rig


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else RESOURCES
    assets = root / "assets/blockfield"
    pack = root / "resourcepacks/equipment/assets/superbwarfare/models/item"
    for item, (build_rig, clips) in anims.ITEMS.items():
        rig = build_rig()
        (assets / "geo_models/equipment").mkdir(parents=True, exist_ok=True)
        (assets / f"geo_models/equipment/{item}.json").write_text(
            compact(rig.geo(f"geometry.blockfield.{item}"))
        )
        (assets / "animations/equipment").mkdir(parents=True, exist_ok=True)
        (assets / f"animations/equipment/{item}.animation.json").write_text(
            compact(anims.animation_file(clips(rig)))
        )
        if item not in anims.SOURCES:
            continue  # the tablet keeps its own item model and atlas
        module, model = anims.SOURCES[item]
        textures = assets / "textures/item/equipment"
        textures.mkdir(parents=True, exist_ok=True)
        states = [("", model())] + (
            [("_linked", model(linked=True))] if item == "monitor" else []
        )
        for suffix, variant in states:
            atlas, uv = variant.bake()
            save_png(atlas[..., :3], textures / f"{item}{suffix}.png", atlas[..., 3])
            item_model(
                pack / f"{item}{suffix}.json",
                item + suffix,
                variant,
                uv,
                atlas.shape[0],
                module,
            )
        if item in OVERRIDES:
            data = json.loads((pack / f"{item}.json").read_text())
            data["overrides"] = OVERRIDES[item]
            write_json(pack / f"{item}.json", data)
    entities = root / "resourcepacks/equipment/assets/superbwarfare"
    for name, (model, scale, bottom, geo_path, texture) in ENTITIES.items():
        rig = entity_geo(name, model(), scale, bottom)
        for path in (geo_path, texture):
            (entities / path).parent.mkdir(parents=True, exist_ok=True)
        (entities / geo_path).write_text(compact(rig.geo(f"geometry.{name}")))
        save_png(rig.atlas[..., :3], entities / texture, rig.atlas[..., 3])
    # ponytail: the shared glow texture is blank, so the LEDs do not glow; both atlases would
    # need their LED faces at the same UVs to light them again.
    blank = np.zeros((16, 16, 4))
    save_png(blank[..., :3], entities / "textures/entity/drone_e.png", blank[..., 3])


if __name__ == "__main__":
    main()
