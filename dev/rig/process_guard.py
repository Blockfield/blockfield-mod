#!/usr/bin/env python3
"""Discover rig processes and stop only exact, registered processes from this session."""

import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys

RIG = Path(__file__).resolve().parent
HELPER = Path.home() / ".general/scripts/agent-process"
if not HELPER.exists():
    HELPER = RIG / "agent-process"


def owner():
    for key in (
        "AI_AGENT_SESSION_ID",
        "CODEX_THREAD_ID",
        "CODEX_SESSION_ID",
        "CLAUDE_SESSION_ID",
        "GEMINI_SESSION_ID",
        "OPENCODE_SESSION_ID",
        "BF_RIG_OWNER",
    ):
        if os.environ.get(key):
            return os.environ[key]
    raise RuntimeError("Нет ID текущей сессии; задайте BF_RIG_OWNER явно.")


def process(pid):
    try:
        root = Path("/proc") / str(pid)
        stat = (root / "stat").read_text().rsplit(")", 1)[1].split()
        return {
            "pid": int(pid),
            "ticks": stat[19],
            "cwd": str((root / "cwd").resolve(strict=True)),
            "command": (root / "cmdline")
            .read_bytes()
            .decode()
            .rstrip("\0")
            .split("\0"),
            "environment": (root / "environ").read_bytes().decode().split("\0"),
        }
    except (OSError, ValueError, UnicodeError):
        return None


def processes():
    return [
        p
        for path in Path("/proc").iterdir()
        if path.name.isdigit()
        if (p := process(path.name)) is not None
    ]


def listeners(ports):
    result = subprocess.run(
        ["ss", "-H", "-ltnp"], check=True, text=True, capture_output=True
    )
    rows = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 4 and fields[3].rsplit(":", 1)[-1] in {str(p) for p in ports}:
            rows.append((line, {int(pid) for pid in re.findall(r"pid=(\d+)", line)}))
    return rows


def matches(p, kind, cwd):
    cmd = p["command"]
    if p["cwd"] != str(cwd):
        return False
    if kind == "server":
        return "-jar" in cmd and "fabric-server-launch.jar" in cmd
    return (
        "run" in cmd
        and "--name" in cmd
        and cmd[cmd.index("--name") + 1 : cmd.index("--name") + 2]
        == [settings(kind)[1]]
    )


def client_number(kind):
    match = re.fullmatch(r"client([2-9]|1[0-9])?", kind)
    if not match:
        raise RuntimeError(f"Неизвестный процесс стенда: {kind}")
    return int(match.group(1) or 1)


def client_dir(kind):
    n = client_number(kind)
    return f"bf-test-client{n if n > 1 else ''}"


def settings(kind):
    if kind == "server":
        return Path.home() / ".cache/bf-test-server", "bf-server", (25599, 25575, 5005)
    n = client_number(kind)
    if n == 1:
        return RIG, "bf-client", (47777, 5006)
    # Extra players (RigPeer...): own container and bot port 47776+N, no JDWP (5006 stays with the first client).
    return RIG, f"bf-client{n}", (47776 + n,)


def discover(kind, target=None):
    cwd, name, ports = settings(kind)
    all_processes = processes()
    found = [p for p in all_processes if matches(p, kind, cwd)]
    rows = listeners(ports)
    for line, pids in rows:
        print(line)
        for pid in pids:
            p = process(pid)
            if p:
                print(f"PID {pid}: cwd={p['cwd']} command={p['command']}")
    if len(found) > 1:
        raise RuntimeError(f"Найдено несколько {name}; автоматический выбор запрещён.")
    if kind != "server":
        inspection = subprocess.run(["podman", "container", "exists", name])
        if inspection.returncode not in (0, 1):
            raise RuntimeError("Не удалось проверить контейнер клиента.")
        container_exists = inspection.returncode == 0
        if container_exists and not found:
            raise RuntimeError(
                f"Контейнер {name} существует без подходящего процесса; оставлен владельцу."
            )
    if found:
        existing = found[0]
        if kind != "server" and target is not None:
            cmd = existing["command"]
            if "--quickPlayMultiplayer" not in cmd or cmd[
                cmd.index("--quickPlayMultiplayer") + 1 :
            ] != [target]:
                raise RuntimeError(
                    "Клиент запущен с другой целью подключения; оставлен владельцу."
                )
        if kind == "server" and any(
            not pids or pids != {existing["pid"]} for _, pids in rows
        ):
            raise RuntimeError(
                "Порт стенда занят другим или неидентифицированным процессом."
            )
        if kind != "server":
            client_cwd = str(Path.home() / ".cache" / client_dir(kind))
            for _, pids in rows:
                if not pids or any(
                    not (p := process(pid)) or p["cwd"] != client_cwd for pid in pids
                ):
                    raise RuntimeError(
                        "Порт клиента занят процессом с другим рабочим каталогом."
                    )
        print(
            f"Используется существующий {name}: PID {existing['pid']}, cwd={existing['cwd']}"
        )
        try:
            with socket.create_connection(
                ("127.0.0.1", ports[0]), timeout=2
            ) as connection:
                if kind == "client":
                    connection.sendall(b"state\n")
                    response = connection.recv(65536)
                    json.loads(response)
            print(f"Сервис отвечает на 127.0.0.1:{ports[0]}")
        except (OSError, ValueError):
            print("Сервис ещё не готов; существующий процесс оставлен запущенным.")
        return True
    if rows:
        raise RuntimeError(
            "Порт занят, но подходящий процесс не найден; новый экземпляр не запущен."
        )
    return False


def registered(kind):
    cwd, name, _ = settings(kind)
    output = subprocess.run(
        [str(HELPER), "list"], check=True, text=True, capture_output=True
    ).stdout
    found = []
    for row in output.splitlines()[1:]:
        fields = row.split()
        if len(fields) < 5 or fields[1] != "yes" or fields[3] != name:
            continue
        metadata = json.loads(
            subprocess.run(
                [str(HELPER), "status", fields[0]],
                check=True,
                text=True,
                capture_output=True,
            ).stdout
        )
        if metadata["cwd"] == str(cwd):
            found.append(metadata)
    return found


def stop(kind):
    session = owner()
    found = registered(kind)
    if len(found) != 1:
        raise RuntimeError(
            "Нужна ровно одна живая запись процесса в registry; ничего не остановлено."
        )
    metadata = found[0]
    p = process(metadata["pid"])
    cwd, _, _ = settings(kind)
    if (
        metadata["owner"] != session
        or not p
        or p["ticks"] != str(metadata["start_ticks"])
        or not matches(p, kind, cwd)
        or p["command"][1:] != metadata["command"][1:]
        or f"AI_AGENT_PROCESS_ID={metadata['id']}" not in p["environment"]
    ):
        raise RuntimeError(
            "Владелец или точная идентичность процесса не совпадают; ничего не остановлено."
        )
    subprocess.run(
        [str(HELPER), "stop", "--owner", session, metadata["id"]], check=True
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("discover", "idle", "stop", "owner"))
    parser.add_argument("kind", nargs="?")
    parser.add_argument("target", nargs="?")
    args = parser.parse_args()
    if args.action == "owner":
        print(owner())
    elif args.action == "stop":
        stop(args.kind)
    else:
        exists = discover(args.kind, args.target)
        if args.action == "idle" and exists:
            raise RuntimeError("Стенд запущен; изменение файлов мира/пака запрещено.")
        return 10 if exists else 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Отказ: {error}", file=sys.stderr)
        sys.exit(1)
