#!/usr/bin/env python3
"""Full-pack PvP regression: two rig clients in the hub, a stone test platform and temporary SBW entities."""

import json
import re
import socket
import time

from rcon import Rcon


def bot(client, command):
    with socket.create_connection(
        ("127.0.0.1", 47776 + client), timeout=5
    ) as connection:
        connection.sendall((command + "\n").encode())
        return json.loads(connection.makefile().readline())


def main():
    players = [bot(n, "state") for n in (1, 2)]
    for state in players:
        assert (
            state.get("server") == "127.0.0.1:25599"
            and state.get("dimension") == "minecraft:overworld"
        ), state
        assert state["health"] == 20, "Heal both rig players first"
        assert re.fullmatch(r"[A-Za-z0-9_]{1,16}", state["name"])
    victim, driver = (state["name"] for state in players)
    rcon = Rcon()
    rcon.s.settimeout(5)
    position = [-250.5, 1.0, 630.5]
    evidence = {}
    tag = "bf_consent_regression"
    try:
        rcon.cmd("fill -256 0 624 -244 0 636 minecraft:stone")
        rcon.cmd(f"tp {victim} -250.5 1 630.5")
        rcon.cmd(f"tp {driver} -255.5 1 625.5")
        bot(1, "bfaction pvp:off")
        time.sleep(1)
        for scenario, entity, offset in (
            ("mine", "claymore", -1.5),
            ("vehicle", "lav_150", -3),
        ):
            rcon.cmd(
                f"execute at {victim} run summon superbwarfare:{entity} ~ ~ ~{offset} "
                f'{{Tags:["{tag}"],Rotation:[0f,0f]}}'
            )
            if scenario == "vehicle":
                rcon.cmd(f"ride {driver} mount @e[tag={tag},limit=1]")
            samples = []
            for i in range(25):
                if scenario == "vehicle" and i < 5:
                    rcon.cmd(
                        f"data merge entity @e[tag={tag},limit=1] {{Motion:[0d,0d,0.8d]}}"
                    )
                time.sleep(0.15)
                samples.append(bot(1, "state"))
            evidence[scenario] = samples
            if scenario == "mine":
                assert "FOUND" not in rcon.cmd(
                    f"execute if entity @e[tag={tag}] run say FOUND"
                ), "Mine did not detonate"
            assert all(s["health"] == 20 and s["pos"] == position for s in samples), (
                scenario
            )
            rcon.cmd(f"ride {driver} dismount")
            rcon.cmd(f"kill @e[tag={tag}]")
        print(
            "PASS: passive player retains 20 HP and exact position after mine and occupied vehicle impacts"
        )
    finally:
        print(json.dumps(evidence))
        rcon.cmd(f"ride {driver} dismount")
        rcon.cmd(f"kill @e[tag={tag}]")
        for state in players:
            rcon.cmd(f"tp {state['name']} " + " ".join(map(str, state["pos"])))


if __name__ == "__main__":
    main()
