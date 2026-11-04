#!/usr/bin/env python3
"""Стек вызовов на точке останова в живом сервере/клиенте стенда (JDWP, jdb).
   jdbtrace.py server|client 'pkg.Class.method[,pkg.Other.method]' [секунд=30]
Печатает трассу при каждом попадании, затем снимает точку. Поток JVM стоит только на время печати.
JDB_ON_HIT='locals;eval x.foo()' — дополнительные команды jdb при попадании (через ;).
JDB_HOLD=1.5 — сколько секунд держать JVM на точке (по умолчанию 0.5): пауза всей JVM, включая Netty."""

import subprocess
import sys
import threading
import time
import os
from pathlib import Path

side, target = sys.argv[1], sys.argv[2]
secs = int(sys.argv[3]) if len(sys.argv) > 3 else 30
port = {"server": 5005, "client": 5006}[side]
java = subprocess.check_output(
    ["mise", "where", "java@temurin-21.0.12+101.0.LTS"], text=True
).strip()
discovery = subprocess.run(
    [
        sys.executable,
        str(Path(__file__).with_name("process_guard.py")),
        "discover",
        side,
    ]
)
if discovery.returncode != 10:
    if discovery.returncode == 0:
        print(f"Нет работающего {side} для jdbtrace", file=sys.stderr)
    sys.exit(discovery.returncode or 1)
p = subprocess.Popen(
    [f"{java}/bin/jdb", "-attach", f"127.0.0.1:{port}"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1,
)
hit = threading.Event()


def reader():
    for line in p.stdout:
        line = line.rstrip()
        if "Breakpoint hit" in line:
            hit.set()
            print("--- hit", line.split('"')[1] if '"' in line else "")
        elif (
            line.startswith("  [")
            or "Unable" in line
            or "Deferring" in line
            or "Set breakpoint" in line
            or "not found" in line
            or " = " in line
            or "Exception" in line
            or "Local" in line
        ):
            print(line, flush=True)


threading.Thread(target=reader, daemon=True).start()


def send(s):
    p.stdin.write(s + "\n")
    p.stdin.flush()


for t in target.split(","):
    send(f"stop in {t}")
deadline = time.time() + secs
while time.time() < deadline:
    if hit.wait(0.2):
        hit.clear()
        send("where")
        for cmd in filter(None, os.environ.get("JDB_ON_HIT", "").split(";")):
            send(cmd)
            time.sleep(0.4)
        time.sleep(float(os.environ.get("JDB_HOLD", 0.5)))
        send("cont")
for t in target.split(","):
    send(f"clear {t}")
time.sleep(0.3)
send("quit")
try:
    p.wait(3)
except subprocess.TimeoutExpired:
    p.kill()
