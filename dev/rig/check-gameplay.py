#!/usr/bin/env python3
"""Regression checks against the local rig only; lobby check leaves the current match."""

import argparse
import json
import re
import socket
import time

from rcon import Rcon


def bot(command):
    with socket.create_connection(("127.0.0.1", 47777), timeout=30) as connection:
        connection.sendall((command + "\n").encode())
        result = json.loads(connection.makefile().readline())
    if isinstance(result, dict) and "error" in result:
        raise RuntimeError(result["error"])
    return result


def number(response):
    match = re.search(r"data: (-?\d+(?:\.\d+)?)[bdfsL]?$", response)
    if not match:
        raise AssertionError(response)
    return float(match.group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=("lobby", "shot", "armor"))
    args = parser.parse_args()
    state = bot("state")
    assert state.get("server") == "127.0.0.1:25599", "Refusing to change a non-rig game"
    player = state["name"]
    assert re.fullmatch(r"[A-Za-z0-9_]{1,16}", player)
    rcon = Rcon()

    def data(path):
        return rcon.cmd(f"data get entity {player} {path}")

    if args.scenario == "lobby":
        bot("bfaction leave")
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            state = bot("state")
            if (
                state.get("dimension") == "minecraft:overworld"
                and state.get("held") == "blockfield:command_tablet"
            ):
                break
            time.sleep(0.25)
        assert state["slot"] == number(data("SelectedItemSlot")) == 8, state
        inventory = data("Inventory")
        ids = re.findall(r'id: "([^"]+)"', inventory)
        assert ids == ["blockfield:command_tablet"], inventory
        rig = data(
            '"fabric:attachments"."accessories:inventory_holder".accessories_containers.rig.items'
        )
        assert rig.count('id: "blockfield:chest_rig"') == 1, rig
        assert 'id: "tacz:ammo"' not in rig, rig
        print("PASS lobby: synchronized tablet, no combat gear, one empty vest")
    elif args.scenario == "shot":
        assert state["held"] == "tacz:modern_kinetic_gun", (
            "Select a loaded gun before checking"
        )
        assert state["slot"] == number(data("SelectedItemSlot")), state
        path = f'Inventory[{{Slot:{state["slot"]}b}}].components."minecraft:custom_data".GunCurrentAmmoCount'
        before = number(data(path))
        bot("closescreen")
        bot("look 0 -60")
        result = bot("tacz shoot")
        assert result.get("result") == "SUCCESS", result
        deadline = time.monotonic() + 3
        after = before
        while after == before and time.monotonic() < deadline:
            time.sleep(0.1)
            after = number(data(path))
        assert after == before - 1, (before, after)
        print(f"PASS authoritative shot: {before:g} -> {after:g}")
    else:
        path = 'Inventory[{Slot:102b}].components."minecraft:custom_data".ArmorPlate'
        before = number(data(path))
        assert before >= 5, "Wear armor with at least five plate points"
        health = state["health"]
        assert health > 5, "Heal before checking explosion damage"
        response = rcon.cmd(
            f"execute in {state['dimension']} run damage {player} 5 minecraft:explosion"
        )
        assert "damage" in response.lower(), response
        time.sleep(0.3)
        after = number(data(path))
        assert after == before - 5, (before, after)
        assert bot("state")["health"] < health, (
            "Explosion was incorrectly absorbed by the plate"
        )
        print(f"PASS explosion: plate {before:g} -> {after:g}, health also decreased")


if __name__ == "__main__":
    main()
