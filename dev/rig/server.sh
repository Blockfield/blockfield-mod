#!/bin/bash
# Локальный сервер полного пака в ~/.cache/bf-test-server (порт 25599, RCON 25575/leaks).
#   server.sh pack   — пины и конфиги из blockfield-server, проверка совместимости с опубликованным клиентом;
#                      без FabricProxy-Lite и CrossStitch; миры обновляются отдельно через sync
#   server.sh sync   — мир/blockfield/blockfield-published/config/maps с прода
#                      (только когда сервер стоит; источник задаётся переменными
#                      окружения, в репозитории прод-пути не хранятся):
#                      BF_PROD_LOBBY=user@host:/srv/.../lobby BF_PROD_MAPS=user@host:/srv/.../templates/maps
#   server.sh mod [jar] — подменить blockfield-server локальной сборкой
#   server.sh start | stop | rcon <cmd> | log [n]   (JDWP 127.0.0.1:5005 -> hotswap.sh server)
set -eu
source "$(dirname "${BASH_SOURCE[0]}")/paths.sh"
S="$HOME/.cache/bf-test-server"
JAVA="$(mise where java@temurin-21.0.12+101.0.LTS)/bin/java"
AGENT_PROCESS="$HOME/.general/scripts/agent-process"
[ -x "$AGENT_PROCESS" ] || AGENT_PROCESS="$RIG/agent-process" # bundled copy for machines without the owner's helper
case "${1:-}" in
    pack)
        python3 "$RIG/process_guard.py" idle server
        mkdir -p "$S"
        cd "$S"
        python3 "$ROOT/blockfield-server/scripts/prepare-rig.py" --out "$S" --client-manifest "${BF_CLIENT_MANIFEST:-https://blockfield.pro/build.json}"
        echo "eula=true" >eula.txt
        grep -q '^server-port=25599' server.properties 2>/dev/null || cat >>server.properties <<'P'
server-port=25599
online-mode=false
enable-rcon=true
rcon.port=25575
rcon.password=leaks
gamemode=adventure
spawn-protection=0
sync-chunk-writes=false
view-distance=8
P
        ;;
    sync)
        python3 "$RIG/process_guard.py" idle server
        : "${BF_PROD_LOBBY:?set BF_PROD_LOBBY=user@host:/.../lobby and BF_PROD_MAPS=user@host:/.../templates/maps}"
        : "${BF_PROD_MAPS:?set BF_PROD_LOBBY=user@host:/.../lobby and BF_PROD_MAPS=user@host:/.../templates/maps}"
        mkdir -p "$S/maps"
        rsync -a --delete --exclude session.lock "$BF_PROD_LOBBY/world/" "$S/world/"
        rsync -a --delete "$BF_PROD_LOBBY/blockfield/" "$S/blockfield/"
        # Published map JSON matching the published templates; the server reads $S/blockfield-published/maps by default.
        rsync -a --delete "$BF_PROD_LOBBY/blockfield-published/" "$S/blockfield-published/"
        rsync -a "$BF_PROD_LOBBY/config/" "$S/config/"
        rsync -a --delete "$BF_PROD_MAPS/" "$S/maps/"
        echo synced
        ;;
    mod)
        python3 "$RIG/process_guard.py" idle server
        libs="$ROOT/blockfield-server/mod-server/server/build/libs"
        pinned="$(python3 -c 'import json,sys;print("\n".join(json.load(open(sys.argv[1])).get("files", {})))' "$ROOT/blockfield-server/distribution/gameplay.lock.json" 2>/dev/null | grep '^blockfield-server-.*\.jar$' | head -1)"
        if [ -n "${2:-}" ]; then
            jar="$2"
        elif [ -n "$pinned" ] && [ -f "$libs/$pinned" ]; then
            jar="$libs/$pinned"
        else
            if [ -n "$pinned" ]; then
                echo "lock pins $pinned, no such build; using newest local jar" >&2
            fi
            jar="$(/bin/ls -t "$libs"/blockfield-server-*.jar 2>/dev/null | grep -v sources | head -1)"
        fi
        [ -n "$jar" ] || {
            echo "no server jar: pass the jar path (built in blockfield-server/mod-server)"
            exit 1
        }
        installed=$(python3 "$RIG/mod_jars.py" install server "$S/mods" "$jar")
        sha256sum "$installed"
        ;;
    start)
        if python3 "$RIG/process_guard.py" discover server; then
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
        # Accepted connections of a just-stopped server keep :25599 in TIME-WAIT for up to a minute;
        # java then fails with "bind: Address already in use", so wait for the port first.
        for i in $(seq 1 60); do
            [ -z "$(ss -Htan state time-wait '( sport = :25599 )' 2>/dev/null)" ] && break
            [ "$i" = 1 ] && echo "порт 25599 в TIME-WAIT, жду освобождения"
            sleep 2
        done
        : >"$S/server.out"
        cmd=("$JAVA" "-Xms${BF_SERVER_XMS:-512m}" "-Xmx${BF_SERVER_XMX:-1600m}" -XX:+UseG1GC -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005 -DBLOCKFIELD_MAPS_DIR=maps -jar fabric-server-launch.jar nogui)
        "$RIG/hotswap.sh" server baseline
        (cd "$S" && BLOCKFIELD_MAPS_DIR=maps "$AGENT_PROCESS" start --name bf-server --owner "$process_owner" --cwd "$S" --log "$S/server.out" -- "${cmd[@]}")
        for i in $(seq 1 60); do
            grep -q 'Done (' "$S/server.out" 2>/dev/null && {
                echo "сервер готов: 127.0.0.1:25599"
                exit 0
            }
            sleep 3
        done
        echo "сервер не поднялся за 3 мин"
        tail -20 "$S/server.out"
        exit 1
        ;;
    stop)
        python3 "$RIG/process_guard.py" stop server
        ;;
    rcon)
        shift
        python3 "$RIG/rcon.py" "$@"
        ;;
    log) tail -"${2:-30}" "$S/server.out" ;;
    *)
        sed -n '2,7p' "$0"
        exit 1
        ;;
esac
