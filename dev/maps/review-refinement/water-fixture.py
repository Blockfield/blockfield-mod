#!/usr/bin/env python3
"""Create an isolated 128² Anvil test map. Usage: python water-fixture.py TEST_SERVER_DIR."""

import json
import math
import struct
import sys
import zlib
from pathlib import Path

root = Path(sys.argv[1])
world = root / "maps/water_review/region"
world.mkdir(parents=True, exist_ok=True)
assert not (world / "r.0.0.mca").exists(), "Refusing to overwrite a fixture"


def string(s):
    b = s.encode()
    return struct.pack(">H", len(b)) + b


def payload(t, v):
    if t == 1:
        return struct.pack(">b", v)
    if t == 3:
        return struct.pack(">i", v)
    if t == 4:
        return struct.pack(">q", v)
    if t == 8:
        return string(v)
    if t == 9:
        kind, items = v
        return (
            bytes([kind])
            + struct.pack(">i", len(items))
            + b"".join(payload(kind, item) for item in items)
        )
    if t == 10:
        return b"".join(bytes([k]) + string(n) + payload(k, x) for n, k, x in v) + b"\0"
    if t == 12:
        return struct.pack(">i", len(v)) + b"".join(struct.pack(">Q", x) for x in v)
    raise ValueError(t)


names = [
    "air",
    "stone",
    "grass_block",
    "sand",
    "water",
    "white_concrete",
    "black_concrete",
    "white_wool",
    "iron_block",
    "gravel",
]
palette = [[("Name", 8, "minecraft:" + name)] for name in names]
palette[4].append(("Properties", 10, [("level", 8, "0")]))
header = bytearray(8192)
body = bytearray()
for cz in range(8):
    for cx in range(8):
        sections = []
        for sy in range(-4, 20):
            data = [0] * 256
            for i in range(4096):
                x, z, y = (
                    cx * 16 + (i & 15),
                    cz * 16 + ((i >> 4) & 15),
                    sy * 16 + (i >> 8),
                )
                distance = math.hypot((x - 72) / 1.05, z - 70)
                depth = max(1, min(20, int((39 - distance) / 1.7) + 1))
                if distance < 39:
                    block = (
                        1
                        if y < 64 - depth
                        else 3
                        if y == 64 - depth
                        else 4
                        if y <= 64
                        else 0
                    )
                else:
                    block = (
                        1
                        if y < 64
                        else (3 if distance < 42 else 9 if 18 <= x <= 21 else 2)
                        if y == 64
                        else 0
                    )
                if 5 <= x <= 15 and 12 <= z <= 90:
                    roof = (z - 12) // 20
                    if (z - 12) % 20 < 13 and 65 <= y <= 69:
                        block = [5, 6, 7, 8][roof]
                data[i // 16] |= block << ((i % 16) * 4)
            sections.append(
                [
                    ("Y", 1, sy),
                    (
                        "block_states",
                        10,
                        [("palette", 9, (10, palette)), ("data", 12, data)],
                    ),
                    ("biomes", 10, [("palette", 9, (8, ["minecraft:plains"]))]),
                ]
            )
        chunk = [
            ("DataVersion", 3, 3955),
            ("xPos", 3, cx),
            ("zPos", 3, cz),
            ("yPos", 3, -4),
            ("Status", 8, "minecraft:full"),
            ("LastUpdate", 4, 0),
            ("InhabitedTime", 4, 0),
            ("sections", 9, (10, sections)),
            ("block_entities", 9, (10, [])),
            ("Heightmaps", 10, []),
        ]
        compressed = zlib.compress(b"\x0a\0\0" + payload(10, chunk))
        record = struct.pack(">I", len(compressed) + 1) + b"\x02" + compressed
        sectors = (len(record) + 4095) // 4096
        index = (cx + cz * 32) * 4
        header[index : index + 4] = ((2 + len(body) // 4096) << 8 | sectors).to_bytes(
            4, "big"
        )
        body += record + bytes(sectors * 4096 - len(record))
(world / "r.0.0.mca").write_bytes(header + body)
base = {"x": 25, "y": 65, "z": 60, "yaw": -90, "pitch": 0}
config = {
    "id": "water_review",
    "name": "Water depth QA",
    "supported_modes": ["campaign", "deathmatch"],
    "bounds": {"minX": 0, "maxX": 128, "minZ": 0, "maxZ": 128},
    "mode_data": {
        "campaign": {
            "points": [{"id": "shore", "name": "Shore", "x": 25, "y": 65, "z": 80}],
            "bases": {"RedTeam": base, "BlueTeam": dict(base, z=100)},
            "lobbies": {"RedTeam": base, "BlueTeam": dict(base, z=100)},
        }
    },
}
config["mode_data"]["deathmatch"] = {"spawn_points": [base, dict(base, z=100)]}
(root / "blockfield/maps/water_review.json").write_text(json.dumps(config, indent=2))
print(world)
