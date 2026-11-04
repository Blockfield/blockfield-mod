"""Write the review resource pack: `build.py <pack-dir>`.

Each SuperbWarfare item keeps its 2D model; `custom_model_data` 1, 2, 3 selects
variant A, B, C, so the variants can be compared in an isolated client.
"""

import importlib
import json
from pathlib import Path
import shutil
import sys

from kit import java_model, save_png, write_json

ASSETS = ("medical_kit", "armor_plate", "monitor", "drone", "scout_drone")
ORIGINAL = {
    "medical_kit": {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "superbwarfare:item/medical_kit"},
    },
    "armor_plate": {
        "parent": "item/generated",
        "textures": {"layer0": "superbwarfare:item/armor_plate"},
    },
    "monitor": {
        "parent": "item/generated",
        "textures": {"layer0": "superbwarfare:item/monitor"},
    },
    "drone": {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "superbwarfare:item/drone"},
    },
    "scout_drone": {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "superbwarfare:item/scout_drone"},
    },
}
# Fit to roughly 16 Java units; display transforms then work alike for every variant.
FIT = 16.0


def display(module):
    base = {
        "gui": {
            "rotation": [25, -150, 0],
            "translation": [0, 0, 0],
            "scale": [0.72] * 3,
        },
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4] * 3},
        "fixed": {
            "rotation": [0, 180, 0],
            "translation": [0, 0, 0],
            "scale": [0.8] * 3,
        },
        "head": {"rotation": [0, 180, 0], "translation": [0, 6, 0], "scale": [0.8] * 3},
        "thirdperson_righthand": {
            "rotation": [0, 180, 0],
            "translation": [0, 3, 1.5],
            "scale": [0.45] * 3,
        },
        "thirdperson_lefthand": {
            "rotation": [0, 180, 0],
            "translation": [0, 3, 1.5],
            "scale": [0.45] * 3,
        },
        "firstperson_righthand": {
            "rotation": [12, -38, 0],
            "translation": [-3, 5, -1],
            "scale": [0.32] * 3,
        },
        "firstperson_lefthand": {
            "rotation": [12, 38, 0],
            "translation": [-3, 5, -1],
            "scale": [0.32] * 3,
        },
    }
    for key, value in getattr(module, "DISPLAY", {}).items():
        base[key] = {**base[key], **value}
    return base


def model_files(name, model, pack, module):
    atlas, uv = model.bake()
    size = atlas.shape[0]
    tex = pack / "assets/blockfield_review/textures/item" / f"{name}.png"
    tex.parent.mkdir(parents=True, exist_ok=True)
    save_png(atlas[..., :3], tex, atlas[..., 3])
    lo, hi = model.bounds()
    scale = FIT / max(hi - lo)
    data = java_model(
        model, f"blockfield_review:item/{name}", uv, size, scale, display(module)
    )
    write_json(pack / "assets/blockfield_review/models/item" / f"{name}.json", data)


def main():
    pack = Path(sys.argv[1]).resolve()
    if pack.exists():
        shutil.rmtree(pack)
    (pack / "assets").mkdir(parents=True)
    write_json(
        pack / "pack.mcmeta",
        {"pack": {"pack_format": 34, "description": "Blockfield 3D equipment review"}},
    )
    for asset in ASSETS:
        module = importlib.import_module(asset)
        overrides = []
        for index, (key, (_, build)) in enumerate(module.VARIANTS.items(), start=1):
            name = f"{asset}_{key.lower()}"
            model_files(name, build(), pack, module)
            overrides.append(
                {
                    "predicate": {"custom_model_data": index},
                    "model": f"blockfield_review:item/{name}",
                }
            )
            if asset == "monitor":
                model_files(name + "_linked", build(linked=True), pack, module)
        if asset == "monitor":
            overrides.append(
                {
                    "predicate": {"superbwarfare:monitor_linked": 1},
                    "model": "superbwarfare:item/monitor_linked",
                }
            )
            for index, key in enumerate(module.VARIANTS, start=1):
                overrides.append(
                    {
                        "predicate": {
                            "custom_model_data": index,
                            "superbwarfare:monitor_linked": 1,
                        },
                        "model": f"blockfield_review:item/monitor_{key.lower()}_linked",
                    }
                )
        write_json(
            pack / "assets/superbwarfare/models/item" / f"{asset}.json",
            {**ORIGINAL[asset], "overrides": overrides},
        )
    print(json.dumps({"pack": str(pack)}))


if __name__ == "__main__":
    main()
