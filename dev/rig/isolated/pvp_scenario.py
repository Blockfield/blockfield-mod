"""Actual client attack input; RCON only arranges fixtures and reads server evidence."""

import json
import shutil
import time


HUB_SPAWN = (0.5, -59.0, 0.5)
# Melee is knife-only (releases#13) and this isolated pack has no LR Tactical knife, so consent is
# exercised with a real vanilla bow shot: arrows take the same projectile token path as TaCZ bullets.
# Keep the attacker away from the respawn destination. Vanilla entity collision
# otherwise pushes a successfully respawned defender outside the strict tolerance.
COMBAT_POSITIONS = ((8.5, -59.0, 8.5), (8.5, -59.0, 10.5))


def at_position(state, expected, tolerance=0.25):
    position = state.get("pos")
    return (
        isinstance(position, (list, tuple))
        and len(position) == 3
        and all(
            type(value) in (int, float) and abs(value - target) < tolerance
            for value, target in zip(position, expected)
        )
    )


def run(bot, console, wait, note, server, output):
    names = ("RigPlayer", "RigOpponent")

    # Vanilla saturation regeneration can erase a one-HP hit between client polls.
    # Stabilize this fixture only; the real five-second Hub.tickRegen stays enabled.
    natural = console("gamerule naturalRegeneration false")
    if "false" not in natural:
        raise AssertionError("could not stabilize the health measurement fixture")
    note("fixture-natural-regeneration-off", natural)

    def state(i):
        return bot("state", i)

    def query(i, command="/pvp"):
        bot("clearmessages", i)
        bot("chat " + command, i)
        return wait(
            lambda: next(
                (s for s in reversed(bot("messages 8", i)) if "PvP:" in s), None
            ),
            15,
        )

    def move():
        for i, (x, y, z) in enumerate(COMBAT_POSITIONS):
            bot("closescreen", i)
            console(f"tp {names[i]} {x} {y} {z} 0 0")
            bot("slot 0", i)
        wait(
            lambda: all(
                at_position(state(i), position)
                for i, position in enumerate(COMBAT_POSITIONS)
            )
        )
        time.sleep(1)  # Let the teleport settle, not readiness proof.

    def shoot():
        # A full draw from the real client; the defender stands 2 blocks ahead on the attacker's look line.
        bot("hold key.use 22")
        time.sleep(1.6)

    console("item replace entity RigPlayer hotbar.0 with minecraft:bow")
    console("give RigPlayer minecraft:arrow 64")
    bot("slot 0")  # The reconnect leaves the tablet slot (8) selected.
    wait(lambda: state(0).get("held") == "minecraft:bow")

    def attack_unchanged(label):
        move()
        before = state(1)["health"]
        shoot()
        time.sleep(0.8)
        after = state(1)["health"]
        if after != before:
            raise AssertionError(label + ": protected player took damage")
        note(label, {"before": before, "after": after})

    for i in (0, 1):
        if "ВЫКЛ" not in query(i):
            raise AssertionError("login must default OFF")
    note("both-default-off")
    attack_unchanged("off-off-blocked")

    # Real MenuAction C2S packet, not a server admin command.
    bot("bfaction pvp:on")
    wait(lambda: "ВКЛ" in query(0))
    attack_unchanged("on-off-blocked")
    # A launched arrow is a confirmed attack and refreshes the attacker's combat mark even when the
    # defender's consent blocks the hit, so turning off waits out the cooldown before off/on is real.
    query(0, "/pvp off")
    wait(lambda: "ВЫКЛ" in query(0), 25)
    query(1, "/bf pvp on")
    attack_unchanged("off-on-blocked")
    query(0, "/pvp on")
    move()
    before = state(1)["health"]
    shoot()
    wait(lambda: state(1)["health"] < before)
    note(
        "on-on-real-arrow-applies",
        {
            "before": before,
            "after": state(1)["health"],
            "serverHealth": console("data get entity RigOpponent Health"),
        },
    )

    pending = query(1, "/pvp off")
    if "выключение через" not in pending or "ВКЛ" not in pending:
        raise AssertionError(
            "recently hurt defender became invulnerable immediately: " + pending
        )
    before = state(1)["health"]
    move()
    shoot()
    wait(lambda: state(1)["health"] < before)
    if "выключение через" not in query(1, "/pvp off"):
        raise AssertionError("repeat off bypassed the combat cooldown")
    note("pending-disable-still-vulnerable")
    wait(lambda: "ВЫКЛ" in query(1), 25)
    note("disable-after-quiet-interval")

    # On again does not imply permission for an untracked/old ranged source.
    query(1, "/pvp on")
    console("effect give RigOpponent minecraft:instant_health 1 10 true")
    wait(lambda: state(1)["health"] == 20)
    before = state(1)["health"]
    console("damage RigOpponent 3 minecraft:arrow by RigPlayer")
    time.sleep(0.5)
    if state(1)["health"] != before:
        raise AssertionError(
            "untracked player-attributed arrow source bypassed consent adapter"
        )
    note("untracked-ranged-source-rejected")

    console("item replace entity RigOpponent hotbar.0 with minecraft:bread 7")
    inventory = console("data get entity RigOpponent Inventory")
    console("damage RigOpponent 19 minecraft:generic")
    wait(lambda: state(1)["health"] <= 2)
    move()
    # One arrow from the real client produces a fatal damage event.
    shoot()
    wait(lambda: state(1)["health"] == 20 and at_position(state(1), HUB_SPAWN))
    if inventory != console("data get entity RigOpponent Inventory"):
        raise AssertionError("ordinary lobby inventory changed during defeat")
    if "ВЫКЛ" not in query(1):
        raise AssertionError("defeat must reset consent")
    if "FOUND_DROP" in console(
        "execute if entity @e[type=minecraft:item] run say FOUND_DROP"
    ):
        raise AssertionError("items dropped during ordinary hub defeat")
    note(
        "fatal-arrow-keeps-inventory-and-returns-to-spawn",
        {"spawn": list(HUB_SPAWN), "defender": state(1), "attacker": state(0)},
    )

    query(1, "/pvp on")
    bot("disconnect", 1)
    wait(lambda: "pos" not in state(1), 20)
    bot("connect 127.0.0.1:25565", 1)
    wait(
        lambda: state(1).get("onGround") is True and state(1).get("screen") is None, 90
    )
    if "ВЫКЛ" not in query(1):
        raise AssertionError("reconnect inherited old consent")
    note("second-client-reconnect-resets-consent")
    if list((server / "blockfield/stats").glob("Stats_*.json")):
        raise AssertionError("hub sparring created a battle statistics record")
    note("hub-combat-does-not-create-match-results")
    for i in (0, 1):
        bot("slot 8", i)
        bot("look 0 -60", i)
        bot("use", i)
        wait(lambda: state(i).get("screen") == "MenuScreen")
        model = bot("bfmenu", i)
        if not any(
            b.get("action") == "pvp:on"
            for s in model.get("sections", [])
            for b in s.get("buttons", [])
        ):
            raise AssertionError("missing real PvP menu actions")
        (output / f"pvp-menu-{i}.json").write_text(
            json.dumps(model, ensure_ascii=False, indent=2) + "\n"
        )
        filename = f"pvp-menu-{i}.png"
        bot("screenshot " + filename, i)
        shot = (
            server.parent
            / ("client" if i == 0 else "client2")
            / "screenshots"
            / filename
        )
        wait(lambda: shot.is_file() and shot.stat().st_size > 100, 15)
        shutil.copy2(shot, output / filename)
    note("both-clients-see-server-pvp-menu")
