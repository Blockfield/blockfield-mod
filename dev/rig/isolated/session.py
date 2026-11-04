"""One foreground session inside an offline container. Own only children created here."""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import time
import uuid

ROOT = Path("/opt/bf")
WORK = Path("/work")
OUT = Path("/out")


def read_exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError("connection closed")
        data.extend(chunk)
    return bytes(data)


def rcon(command, password):
    # Hardcoded namespace-local endpoint; no target supplied by callers or environment.
    with socket.create_connection(("127.0.0.1", 25575), timeout=3) as sock:

        def exchange(number, kind, text):
            body = struct.pack("<ii", number, kind) + text.encode() + b"\0\0"
            sock.sendall(struct.pack("<i", len(body)) + body)
            length = struct.unpack("<i", read_exact(sock, 4))[0]
            if not 10 <= length <= 1024 * 1024:
                raise ValueError("invalid RCON length")
            payload = read_exact(sock, length)
            got, reply_kind = struct.unpack("<ii", payload[:8])
            if got != number or reply_kind not in (0, 2) or payload[-2:] != b"\0\0":
                raise ValueError("invalid RCON reply")
            return payload[8:-2].decode("utf-8")

        exchange(1, 3, password)
        return exchange(2, 2, command)


def bot(command, index=0):
    if type(index) is not int or index not in (0, 1):
        raise ValueError("only the two fixed local bot slots are allowed")
    with socket.create_connection(("127.0.0.1", 47777 + index), timeout=3) as sock:
        sock.settimeout(8)
        sock.sendall((command + "\n").encode())
        data = bytearray()
        while b"\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                raise EOFError("bot closed connection")
            data.extend(chunk)
            if len(data) > 1024 * 1024:
                raise ValueError("oversized bot reply")
        reply = json.loads(data.split(b"\n", 1)[0])
        if isinstance(reply, dict) and "error" in reply:
            raise RuntimeError(str(reply["error"]))
        return reply


def client_ready(state, name="RigPlayer"):
    """A LocalPlayer exists before server position/chunks/menu are usable."""
    if not isinstance(state, dict):
        return False
    pos = state.get("pos")
    return (
        state.get("connected") is True
        and state.get("screen") is None
        and state.get("server") == "127.0.0.1:25565"
        and state.get("name") == name
        and state.get("dimension") == "minecraft:overworld"
        and state.get("onGround") is True
        and isinstance(pos, list)
        and len(pos) == 3
        and all(type(v) in (int, float) and math.isfinite(v) for v in pos)
        and abs(pos[0] - 0.5) < 2
        and abs(pos[1] + 59) < 0.2
        and abs(pos[2] - 0.5) < 2
    )


def hub_menu_ready(model):
    """Validate the real server model instead of vanilla widgets, which this screen does not use."""
    if (
        not isinstance(model, dict)
        or model.get("id") != "hub"
        or model.get("title") != "BLOCKFIELD"
        or not isinstance(model.get("sections"), list)
    ):
        return False
    for section in model["sections"]:
        if not isinstance(section, dict) or not isinstance(
            section.get("buttons"), list
        ):
            continue
        for button in section["buttons"]:
            action = button.get("action") if isinstance(button, dict) else None
            if (
                action in ("casual", "find:room")
                and isinstance(button.get("label"), str)
                and button["label"].strip()
                and button.get("enabled", True) is True
                and button.get("disabled", False) is False
            ):
                return True
    return False


def client_command(argv, index=0):
    if type(index) is not int or index not in (0, 1):
        raise ValueError("invalid client slot")
    result = [
        argv[0],
        "-Djna.tmpdir=/tmp",
        *[a for a in argv[1:] if not a.startswith("-Djna.tmpdir=")],
    ]
    if index == 1:
        result = [a for a in result if not a.startswith("-Dbf.testbot.port=")]
        result.insert(1, "-Dbf.testbot.port=47778")
        result.insert(1, "-Duser.home=/tmp/rig-home-2")
        profile = uuid.UUID(
            bytes=hashlib.md5(b"OfflinePlayer:RigOpponent").digest(), version=3
        )
        for key, value in (
            ("--username", "RigOpponent"),
            ("--uuid", profile.hex),
            ("--gameDir", "/work/client2"),
        ):
            if result.count(key) != 1 or result.index(key) + 1 == len(result):
                raise ValueError("missing or ambiguous client argument: " + key)
            result[result.index(key) + 1] = value
    return result


def server_properties(password):
    return "\n".join(
        [
            "server-ip=127.0.0.1",
            "server-port=25565",
            "online-mode=false",
            "enable-rcon=true",
            "rcon.port=25575",
            "rcon.password=" + password,
            "enable-query=false",
            "enable-jmx-monitoring=false",
            "enforce-secure-profile=false",
            "level-name=world",
            "level-seed=1",
            "level-type=minecraft:flat",
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}',
            "generate-structures=false",
            "view-distance=3",
            "simulation-distance=3",
            "max-players=2",
            "spawn-protection=0",
            "gamemode=adventure",
            "max-tick-time=60000",
            "motd=Blockfield isolated smoke",
            "",
        ]
    )


def wait_for(check, children, timeout):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        for name, proc in children:
            if proc.poll() is not None:
                raise RuntimeError(
                    f"{name} exited before acceptance: {proc.returncode}"
                )
        try:
            result = check()
            if result:
                return result
        except (OSError, EOFError, ValueError, RuntimeError) as error:
            last = error
        time.sleep(1)
    raise TimeoutError("acceptance deadline expired: " + str(last))


def stop_children(children):
    errors = []
    for name, proc in reversed(children):
        if proc.poll() is not None:
            continue
        try:
            if name == "server":
                proc.stdin.write("stop\n")
                proc.stdin.flush()
            else:
                proc.terminate()
            proc.wait(timeout=45 if name == "server" else 15)
        except (OSError, subprocess.TimeoutExpired):
            errors.append(name + ": forced shutdown")
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=10)
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("server", "client", "pvp"), default="client")
    parser.add_argument("--accept-eula", action="store_true")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("explicit --accept-eula is required")
    report = {
        "schema": 1,
        "status": "failed",
        "mode": args.mode,
        "checks": [],
        "coverage": "minimal real Blockfield/Fabric smoke; not TaCZ/SBW or gameplay acceptance",
    }
    # Fixed test settings; never inherit LAN spawn or real export configuration.
    os.environ["BLOCKFIELD_LOBBY_SPAWN"] = "0.5 -59 0.5"
    os.environ["BLOCKFIELD_SESSION_PURPOSE"] = "playtest"
    os.environ["BLOCKFIELD_STATS_EXPORT_ENABLED"] = "false"
    os.environ["BLOCKFIELD_LOBBY_PVP_ENABLED"] = (
        "true" if args.mode == "pvp" else "false"
    )
    children, logs = [], []

    def note(name, value=True):
        report["checks"].append({"name": name, "passed": True, "evidence": value})

    def spawn(name, argv, cwd):
        log = (OUT / (name + ".log")).open("w")
        logs.append(log)
        proc = subprocess.Popen(
            argv,
            cwd=cwd,
            stdin=subprocess.PIPE,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        children.append((name, proc))
        return proc

    def interrupted(signum, frame):
        raise KeyboardInterrupt

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, interrupted)
    try:
        # This is also checked by launch.py's inspected container config, not just an env flag.
        interfaces = sorted(name for _, name in socket.if_nameindex())
        if interfaces != ["lo"]:
            raise RuntimeError("refusing runtime with external network interfaces")
        note("network-loopback-only", interfaces)
        manifest = json.loads((ROOT / "runtime-manifest.json").read_text())
        for rel, expected in manifest["files"].items():
            path = ROOT / rel
            if path.is_symlink() or not path.is_file():
                raise ValueError("missing or symlink runtime file")
            value = hashlib.sha256()
            with path.open("rb") as handle:
                for block in iter(lambda: handle.read(1024 * 1024), b""):
                    value.update(block)
            if value.hexdigest() != expected:
                raise ValueError("runtime digest mismatch: " + rel)
        report["runtime"] = manifest
        pack_marker = hashlib.sha256(
            (ROOT / "runtime-manifest.json").read_bytes()
        ).hexdigest()
        note("runtime-hashes")
        server = WORK / "server"
        shutil.copytree(ROOT / "server", server)
        password = secrets.token_hex(24)
        (server / "server.properties").write_text(server_properties(password))
        (server / "eula.txt").write_text("eula=true\n")
        env_file = server / "config"
        env_file.mkdir(exist_ok=True)
        (env_file / "blockfield-pack-version.txt").write_text(pack_marker + "\n")
        Path(os.environ["HOME"]).mkdir(parents=True, exist_ok=True)
        spawn(
            "server",
            [
                "java",
                "-Xms256M",
                "-Xmx1536M",
                "-XX:+UseG1GC",
                "-Djava.net.preferIPv4Stack=true",
                "-jar",
                "fabric-server-launch.jar",
                "nogui",
            ],
            server,
        )
        version = wait_for(lambda: rcon("bf version", password), children, 240)
        if manifest["source"][:10] not in version:
            raise RuntimeError(
                "server build does not match requested commit: " + version
            )
        note("server-build-identity", version)
        rcon("gamerule doDaylightCycle false", password)
        rcon("time set day", password)
        rcon("setworldspawn 0 -59 0", password)
        note("fresh-flat-world")
        if args.mode in ("client", "pvp"):
            client = WORK / "client"
            client.mkdir()
            shutil.copytree(ROOT / "client-mods", client / "mods")
            (client / "config").mkdir()
            (client / "config/blockfield-pack-version.txt").write_text(
                pack_marker + "\n"
            )
            (client / "options.txt").write_text(
                "renderDistance:3\nsimulationDistance:3\nmaxFps:20\npauseOnLostFocus:false\nguiScale:2\nskipMultiplayerWarning:true\nonboardAccessibility:false\n"
            )
            spawn(
                "display",
                ["Xvfb", ":99", "-screen", "0", "800x450x24", "-nolisten", "tcp"],
                WORK,
            )
            wait_for(lambda: Path("/tmp/.X11-unix/X99").exists(), children, 15)
            argv = json.loads((ROOT / "client-args.json").read_text())
            spawn(
                "client",
                [*client_command(argv), "--quickPlayMultiplayer", "127.0.0.1:25565"],
                client,
            )

            def ready():
                snapshot = bot("state")
                report["lastClientState"] = snapshot
                return snapshot if client_ready(snapshot) else None

            state = wait_for(ready, children, 300)
            note("real-client-in-world", state)
            bot("slot 8")
            wait_for(
                lambda: bot("state").get("held") == "blockfield:command_tablet",
                children,
                30,
            )
            bot("look 0 -60")
            bot("use")
            wait_for(lambda: bot("state").get("screen") == "MenuScreen", children, 30)

            def menu_ready():
                model = bot("bfmenu")
                return model if hub_menu_ready(model) else None

            menu = wait_for(menu_ready, children, 30)
            note("server-menu-visible", menu)
            bot("screenshot isolated-menu.png")
            shot = client / "screenshots/isolated-menu.png"
            wait_for(lambda: shot.is_file() and shot.stat().st_size > 100, children, 15)
            shutil.copy2(shot, OUT / "isolated-menu.png")
            bot("disconnect")
            wait_for(lambda: "pos" not in bot("state"), children, 20)
            bot("connect 127.0.0.1:25565")
            state = wait_for(ready, children, 60)
            note("real-client-reconnect", state)
            if args.mode == "pvp":
                second = WORK / "client2"
                second.mkdir()
                shutil.copytree(ROOT / "client-mods", second / "mods")
                (second / "config").mkdir()
                (second / "config/blockfield-pack-version.txt").write_text(
                    pack_marker + "\n"
                )
                shutil.copy2(client / "options.txt", second / "options.txt")
                Path("/tmp/rig-home-2").mkdir()
                spawn(
                    "client2",
                    [
                        *client_command(argv, 1),
                        "--quickPlayMultiplayer",
                        "127.0.0.1:25565",
                    ],
                    second,
                )
                wait_for(
                    lambda: client_ready(bot("state", 1), "RigOpponent"), children, 300
                )
                note("two-real-clients", [bot("state"), bot("state", 1)])
                import pvp_scenario

                pvp_scenario.run(
                    bot,
                    lambda command: rcon(command, password),
                    lambda check, seconds=30: wait_for(check, children, seconds),
                    note,
                    server,
                    OUT,
                )
                report["coverage"] = (
                    "two-client bow-shot consent/cooldown/defeat (melee is knife-only, no LR Tactical here); NOT full TaCZ/SBW acceptance"
                )

        for name, proc in children:
            if proc.poll() is not None:
                raise RuntimeError(name + " exited before final acceptance")
        report["status"] = "passed"
    except KeyboardInterrupt:
        report["status"] = "interrupted"
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
        if any(
            name.startswith("client") and proc.poll() is None for name, proc in children
        ):
            try:
                report["lastClientState"] = bot("state")
                report["lastMessages"] = bot("messages 8")
                if args.mode == "pvp":
                    report["secondClientState"] = bot("state", 1)
                    report["secondMessages"] = bot("messages 8", 1)
                bot("screenshot isolated-failure.png")
                failed_shot = WORK / "client/screenshots/isolated-failure.png"
                wait_for(lambda: failed_shot.is_file(), children, 5)
                shutil.copy2(failed_shot, OUT / "isolated-failure.png")
            except Exception as capture_error:
                report["diagnosticsError"] = type(capture_error).__name__
    finally:
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, signal.SIG_IGN)
        forced = stop_children(children)
        for log in logs:
            log.close()
        if forced:
            report["shutdownErrors"] = forced
            report["status"] = "failed"
        report["children"] = {name: proc.poll() for name, proc in children}
        if "server" in report["children"] and report["children"]["server"] != 0:
            report["status"] = "failed"
        (OUT / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        )
    print(json.dumps({"status": report["status"], "checks": len(report["checks"])}))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
