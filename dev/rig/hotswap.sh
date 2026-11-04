#!/bin/bash
# Горячая замена кода в работающем сервере/клиенте стенда без рестарта (JDWP + jdb redefine).
#   hotswap.sh server|client          — собрать remapJar, найти изменившиеся классы, подменить в JVM
#   hotswap.sh server|client baseline — запомнить классы jar из mods/ как «то, что сейчас в JVM» (после старта)
# Ограничение стандартной JVM: только тела методов. Новый класс/поле/метод/сигнатура/лямбда —
# jdb печатает "schema change"/"add method not implemented" → нужен restart (stop, mod, start).
set -eu
source "$(dirname "${BASH_SOURCE[0]}")/paths.sh"
MOD="$ROOT/BlockField-Private-Project"
side="${1:?server|client}"
action="${2:-swap}"
case "$side" in
    server)
        port=5005
        mods_dir="$HOME/.cache/bf-test-server/mods"
        ;;
    client)
        port=5006
        mods_dir="$HOME/.cache/bf-test-client/mods"
        ;;
    *)
        echo "server|client"
        exit 1
        ;;
esac
deployed=$(python3 "$RIG/mod_jars.py" deployed "$side" "$mods_dir")
STATE="$HOME/.cache/bf-rig/hotswap/$side"
OLD="$STATE/old"
NEW="$STATE/new"
JDB="$(mise where java@temurin-21.0.12+101.0.LTS)/bin/jdb"

extract() {
    rm -rf "$2"
    mkdir -p "$2"
    unzip -q -o "$1" 'com/lunares/*' -d "$2"
}

if [ "$action" = baseline ]; then
    extract "$deployed" "$OLD"
    echo "baseline: $(find "$OLD" -name '*.class' | wc -l) классов из $deployed"
    exit 0
fi
require_target() {
    if python3 "$RIG/process_guard.py" discover "$side"; then
        echo "Нет работающего $side для hotswap"
        return 1
    else
        result=$?
        [ "$result" = 10 ] && return 0
        return "$result"
    fi
}
require_target
[ -d "$OLD" ] || {
    echo "нет baseline: hotswap.sh $side baseline (после server.sh start / rig.sh start)"
    exit 1
}
(cd "$MOD" && env -u ARGV0 mise exec -- ./gradlew -q --offline ":$side:remapJar")
jar=$(/bin/ls -t "$MOD/$side/build/libs/blockfield-$side-"*.jar | grep -v sources | head -1)
extract "$jar" "$NEW"
script=$(mktemp)
n=0
while IFS= read -r f; do
    rel="${f#"$NEW"/}"
    cmp -s "$f" "$OLD/$rel" && continue
    cls="${rel%.class}"
    echo "redefine ${cls//\//.} $f" >>"$script"
    n=$((n + 1))
done < <(find "$NEW" -name '*.class' | sort)
if [ "$n" = 0 ]; then
    echo "изменений нет"
    rm -f "$script"
    exit 0
fi
echo "quit" >>"$script"
echo "hotswap $side: $n классов → 127.0.0.1:$port"
require_target
# jdb печатает 'redefine' без ошибок при успехе; любая строка с Error/exception/not — провал
out=$("$JDB" -attach "127.0.0.1:$port" <"$script" 2>&1 | grep -vE '^(Initializing jdb|>|\s*$)' || true)
rm -f "$script"
echo "$out" | sed 's/^/  jdb: /'
if echo "$out" | grep -qiE 'error|exception|not implemented|schema|no class|unable'; then
    echo "hotswap НЕ применён (см. выше) — нужен рестарт: stop, mod, start для $side"
    exit 2
fi
rm -rf "$OLD"
mv "$NEW" "$OLD"
echo "hotswap применён: $(grep -c . <<<"$(find "$OLD" -name '*.class')") классов в baseline"
