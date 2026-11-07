#!/bin/bash
# Headless-стенд Blockfield: настоящий клиент с полным паком в podman (Xvfb + GPU через VirtualGL или llvmpipe),
# управление по TCP через мод bftestbot. Подробности и сценарии — README.md.
set -eu
source "$(dirname "${BASH_SOURCE[0]}")/paths.sh"
CACHE="$HOME/.cache"
CLIENT="$CACHE/bf-test-client"
RUN="$CACHE/bf-rig"
SERVER_DIR="$CACHE/bf-test-server"
PACK_URL="${BF_PACK_URL:-https://blockfield.pro/pack.toml}"
JAVA="$(mise where java@temurin-21.0.12+101.0.LTS)/bin/java"
PACKWIZ_BOOT="$CACHE/bf-rig/installers/packwiz-installer-bootstrap.jar"
PACKWIZ_MAIN="$CACHE/bf-rig/installers/packwiz-installer.jar"
AGENT_PROCESS="$HOME/.general/scripts/agent-process"
[ -x "$AGENT_PROCESS" ] || AGENT_PROCESS="$RIG/agent-process" # bundled copy for machines without the owner's helper
NAME=bf-client
BOT_PORT=47777
KIND=client
SCRIPT=run-client.sh
LOG="$RUN/client.out"
JDWP=1
DISPLAY_NUM=99
# BF_RIG_CLIENT=N (2..19) — ещё игрок (RigPeer, RigPeer3...): свой каталог, контейнер, порт бота 47776+N и экран
# Xvfb :100-N, без JDWP (5006 у первого).
N="${BF_RIG_CLIENT:-1}"
case "$N" in 1 | [2-9] | 1[0-9]) ;; *)
    echo "BF_RIG_CLIENT: 1..19"
    exit 1
    ;;
esac
if [ "$N" != 1 ]; then
    KIND=client$N
    NAME=bf-client$N
    BOT_PORT=$((47776 + N))
    CLIENT="$CACHE/bf-test-client$N"
    SCRIPT=run-client$N.sh
    LOG="$RUN/client$N.out"
    JDWP=0
    DISPLAY_NUM=$((100 - N))
fi

usage() {
    sed -n '2,3p' "$0"
    grep -E '^  [a-z|-]+\)' "$0" | sed 's/)//;s/^/  rig.sh/'
}

lowmem() {
    python3 - "$CLIENT/options.txt" <<'EOF'
import sys
path = sys.argv[1]
want = {'renderDistance': '2', 'simulationDistance': '5', 'maxFps': '20', 'enableVsync': 'false',
        'mipmapLevels': '0', 'graphicsMode': '0', 'particles': '2', 'biomeBlendRadius': '0',
        'entityDistanceScaling': '0.5', 'soundCategory_master': '0.0',
        # Xvfb never focuses the window: otherwise the pause menu reopens every tick and swallows bot key presses.
        'pauseOnLostFocus': 'false'}
lines = open(path).read().splitlines()
keys = {line.split(':', 1)[0] for line in lines}
lines = [f"{k}:{want[k]}" if (k := line.split(':', 1)[0]) in want else line for line in lines]
lines += [f"{k}:{v}" for k, v in want.items() if k not in keys]
open(path, 'w').write('\n'.join(lines) + '\n')
EOF
    grep -E '^(renderDistance|simulationDistance|maxFps|graphicsMode):' "$CLIENT/options.txt" | tr '\n' ' '
    echo
}

bot() {
    exec 3<>"/dev/tcp/127.0.0.1/$BOT_PORT" || {
        echo '{"error":"bot not listening"}'
        return 1
    }
    printf '%s\n' "$*" >&3
    IFS= read -r -t 60 line <&3 || true
    exec 3>&-
    printf '%s\n' "$line"
}

case "${1:-}" in
    install) # venv+portablemc, клиент в ~/.cache/bf-mc, пак, run-client.sh, образ bf-xvfb
        [ -x "$CACHE/pmc/bin/python" ] || { python3 -m venv "$CACHE/pmc" && "$CACHE/pmc/bin/pip" -q install portablemc; }
        mkdir -p "$CLIENT" "$RUN"
        "$RIG/with-mojang-dns.sh" "$CACHE/pmc/bin/python" "$RIG/install-client.py"
        "$0" pack
        podman image exists bf-xvfb || podman build -t bf-xvfb -f "$RIG/Containerfile" "$RIG"
        ;;
    pack) # обновить клиентский пак стенда с blockfield.pro (или $BF_PACK_URL)
        python3 "$RIG/process_guard.py" idle "$KIND"
        python3 "$RIG/install-packwiz.py" "$ROOT/blockfield-client/launcher.json" "$CACHE/bf-rig/installers"
        (cd "$CLIENT" && "$JAVA" -jar "$PACKWIZ_BOOT" --bootstrap-no-update --bootstrap-main-jar "$PACKWIZ_MAIN" -g -s client "$PACK_URL" | tail -1)
        python3 "$RIG/mod_jars.py" prune "$CLIENT/mods"
        ;;
    clone | clone2) # ещё игрок: reflink-копия ~/.cache/bf-test-client и run-clientN.sh (BF_RIG_CLIENT=N, по умолчанию 2)
        [ "$N" = 1 ] && exec env BF_RIG_CLIENT=2 "$0" "$@"
        python3 "$RIG/process_guard.py" idle "$KIND"
        first="$CACHE/bf-test-client"
        rm -rf "$CLIENT"
        cp -a --reflink=auto "$first" "$CLIENT"
        rm -rf "$CLIENT/logs" "$CLIENT/crash-reports" "$CLIENT/screenshots" "$CLIENT/saves"
        # N=2 keeps the RigPeer identity the harness and scenarios already know.
        user=RigPeer
        uuid=5d2f7e0c9a1b4c3d8e6f0a1b2c3d4e5f
        [ "$N" = 2 ] || {
            user=RigPeer$N
            uuid=$(printf '5d2f7e0c9a1b4c3d8e6f0a1b2c3d%04x' "$N")
        }
        sed -e "s|--username Player115|--username $user|" -e "s|--uuid [0-9a-f]*|--uuid $uuid|" \
            -e "s|$first|$CLIENT|g" "$RUN/run-client.sh" >"$RUN/$SCRIPT"
        chmod +x "$RUN/$SCRIPT"
        echo "игрок $user: $CLIENT, запуск: BF_RIG_CLIENT=$N rig.sh start"
        ;;
    lowmem) # минимальная графика в options.txt текущего клиента; rig.sh start делает это сам
        python3 "$RIG/process_guard.py" idle "$KIND"
        lowmem
        ;;
    mod) # rig.sh mod [jar] — подменить blockfield-client в стенде локальной сборкой
        python3 "$RIG/process_guard.py" idle "$KIND"
        jar="${2:-$(/bin/ls -t "$MODROOT"/client/build/libs/blockfield-client-*.jar | grep -v sources | head -1)}"
        installed=$(python3 "$RIG/mod_jars.py" install client "$CLIENT/mods" "$jar")
        sha256sum "$installed"
        ;;
    start) # rig.sh start [host:port] — контейнер с клиентом, автоподключение (по умолчанию прод по LAN)
        server="${2:-raknet;192.168.0.100:25566}"
        if python3 "$RIG/process_guard.py" discover "$KIND" "$server"; then
            :
        else
            result=$?
            [ "$result" = 10 ] && exit 0
            exit "$result"
        fi
        [ -x "$AGENT_PROCESS" ] || {
            echo "Нет agent-process; запуск запрещён"
            exit 1
        }
        process_owner=$(python3 "$RIG/process_guard.py" owner)
        # The pin is verified on every start (fetch is a no-op when the
        # marker matches); the jar is rebuilt when the sources changed.
        "$RIG/testbot/fetch-testbot.sh" || exit 1
        bot_jar=$(/bin/ls "$RIG"/testbot/upstream/build/libs/bftestbot-*.jar 2>/dev/null | grep -v sources | head -1)
        if [ -z "$bot_jar" ] || [ "$RIG/testbot/upstream/.upstream-sha" -nt "$bot_jar" ]; then
            echo "testbot jar missing or older than the pinned sources; rebuilding..."
            rm -f "$RIG"/testbot/upstream/build/libs/bftestbot-*.jar
            just --justfile "$MODROOT/justfile" gradle --no-daemon --max-workers=2 -p dev/rig/testbot/upstream build || exit 1
            bot_jar=$(/bin/ls "$RIG"/testbot/upstream/build/libs/bftestbot-*.jar 2>/dev/null | grep -v sources | head -1)
        fi
        [ -n "$bot_jar" ] || {
            echo "нет testbot jar: (cd \"$RIG/testbot/upstream\" && ./gradlew build)"
            exit 1
        }
        cp -f "$bot_jar" "$CLIENT/mods/bftestbot.jar"
        cp -f "$RIG/entry.sh" "$RUN/entry.sh"
        chmod +x "$RUN"/*.sh
        [ -x "$RUN/$SCRIPT" ] || {
            echo "нет $RUN/$SCRIPT: BF_RIG_CLIENT=$N rig.sh clone"
            exit 1
        }
        # Лёгкий режим по умолчанию: с дефолтами пака (дальность 20, 120 FPS, heap 1/4 ОЗУ) один клиент
        # занимал ~4 ГБ и 5 ядер. BF_RIG_GFX=full — options.txt как есть (видео, визуальные проверки).
        [ "${BF_RIG_GFX:-low}" = full ] || lowmem
        export BF_RES="${BF_RES:-640x360}" BF_CLIENT_XMX="${BF_CLIENT_XMX:-1100m}"
        export BF_W="${BF_W:-${BF_RES%x*}}" BF_H="${BF_H:-${BF_RES#*x}}"
        # GPU через VirtualGL (entry.sh); BF_RIG_GPU=0 — программный llvmpipe.
        gpu=()
        [ "${BF_RIG_GPU:-1}" = 1 ] && [ -e /dev/dri/renderD128 ] && gpu=(--device /dev/dri/renderD128)
        : >"$LOG"
        cmd=(podman run --rm --name "$NAME" --network=host "${gpu[@]}" -e BF_RES -e BF_W -e BF_H -e BF_CLIENT_XMX -e BF_AUDIO_WAV
            -e "BF_RIG_BOT_PORT=$BOT_PORT" -e "BF_RIG_JDWP=$JDWP" -e "BF_RIG_SCRIPT=$SCRIPT" -e "BF_RIG_DISPLAY=$DISPLAY_NUM"
            -v "$CACHE/bf-mc:$CACHE/bf-mc:z" -v "$CLIENT:$CLIENT:z" -v "$CACHE/bf-natives:$CACHE/bf-natives:z" -v "$RUN:/S:z"
            bf-xvfb /S/entry.sh --quickPlayMultiplayer "$server")
        "$AGENT_PROCESS" start --name "$NAME" --owner "$process_owner" --cwd "$RIG" --log "$LOG" -- "${cmd[@]}"
        echo "лог: $LOG; ждать: rig.sh wait"
        ;;
    stop)
        python3 "$RIG/process_guard.py" stop "$KIND"
        ;;
    wait) # ждать, пока бот ответит и игрок окажется в мире (до ~6 мин)
        for i in $(seq 1 120); do
            s=$(bot state 2>/dev/null || true)
            case "$s" in *'"pos"'*)
                echo "$s"
                exit 0
                ;;
            esac
            sleep 3
        done
        echo "клиент не вошёл в мир; хвост лога:"
        tail -20 "$LOG"
        exit 1
        ;;
    bot) # rig.sh bot <команда> — см. README (state, keys, use, lookat, chat, messages, screenshot ...)
        shift
        bot "$@"
        ;;
    shot) # rig.sh shot <имя> — снимок из клиента → /tmp/bf-shot-<имя>.jpg
        bot "screenshot $2.png" >/dev/null
        sleep 2
        magick "$CLIENT/screenshots/$2.png" -resize 1568x1568\> -quality 85 "/tmp/bf-shot-$2.jpg" && echo "/tmp/bf-shot-$2.jpg"
        ;;
    x) # rig.sh x <аргументы xdotool> — сырой ввод через X (настоящий путь GLFW)
        shift
        podman exec -e "DISPLAY=:$DISPLAY_NUM" "$NAME" xdotool "$@"
        ;;
    log) # rig.sh log [n]
        tail -"${2:-30}" "$LOG"
        ;;
    server) # rig.sh server sync|start|stop|rcon <cmd> — локальный сервер в ~/.cache/bf-test-server
        "$RIG/server.sh" "${@:2}"
        ;;
    *)
        usage
        exit 1
        ;;
esac
