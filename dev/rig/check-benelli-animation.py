#!/usr/bin/env python3
"""Capture Benelli shell-reload frames on the local rig; start in a safe area with a partial magazine.

Requires the Benelli in the selected slot and reserve ammunition only in the vest.
Moves forward while sprinting for about three seconds, then releases both keys.
Frames stay in the client screenshots folder for visual review; remove them after review.
"""

import json
import re
import socket
import time
from pathlib import Path

from rcon import Rcon


def bot(command):
    with socket.create_connection(("127.0.0.1", 47777), timeout=10) as connection:
        connection.sendall((command + "\n").encode())
        result = json.loads(connection.makefile().readline())
    if isinstance(result, dict) and "error" in result:
        raise RuntimeError(result["error"])
    return result


def count(response):
    match = re.search(r"data: (\d+)$", response)
    if not match:
        raise AssertionError(response)
    return int(match.group(1))


def main():
    state = bot("state")
    assert state.get("server") == "127.0.0.1:25599", "Only the local rig is permitted"
    player = state["name"]
    assert re.fullmatch(r"[A-Za-z0-9_]{1,16}", player)
    rcon = Rcon()
    inventory = rcon.cmd(f"data get entity {player} Inventory")
    assert not re.search(r'id: "tacz:(ammo|ammo_box)"', inventory), (
        "Move reserve ammunition into the vest"
    )
    gun_path = (
        f'Inventory[{{Slot:{state["slot"]}b}}].components."minecraft:custom_data"'
    )
    assert "elitex:benelli_super" in rcon.cmd(
        f"data get entity {player} {gun_path}.GunId"
    )
    before = count(rcon.cmd(f"data get entity {player} {gun_path}.GunCurrentAmmoCount"))
    assert 0 <= before <= 3, (
        "Use a magazine with at most three rounds for a visible loop sequence"
    )
    prefix = f"benelli-reload-{time.time_ns()}"
    frames = []
    bot("closescreen")
    try:
        bot("hold key.forward 80")
        bot("hold key.sprint 80")
        time.sleep(0.4)
        assert bot("state")["sprinting"], "Choose a clear, level area before this check"
        bot("tacz reload")
        started = time.monotonic()
        for index, delay in enumerate((0.15, 0.45, 0.85, 1.25, 1.65, 2.05)):
            time.sleep(max(0, started + delay - time.monotonic()))
            filename = f"{prefix}-{index}.png"
            bot(f"screenshot {filename}")
            sample = bot("state")
            assert sample["sprinting"], ("Sprint stopped during reload", sample)
            frames.append(
                {
                    "file": str(
                        Path.home() / ".cache/bf-test-client/screenshots" / filename
                    ),
                    "elapsed": round(time.monotonic() - started, 2),
                }
            )
    finally:
        bot("hold key.forward 0")
        bot("hold key.sprint 0")
    deadline = time.monotonic() + 6
    after = before
    while time.monotonic() < deadline:
        after = count(
            rcon.cmd(f"data get entity {player} {gun_path}.GunCurrentAmmoCount")
        )
        if after == 8:
            break
        time.sleep(0.2)
    assert after > before, (before, after)
    print(
        json.dumps(
            {
                "functional_reload": [before, after],
                "frames": frames,
                "visual_review_required": "Check shell-loading hand and gun poses across these frames; ammo alone does not prove animation.",
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
