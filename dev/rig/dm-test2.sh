#!/bin/bash
# Deathmatch, вторая часть приёмки (#9): минимальный состав, защита, таймер респавна, окружение, reconnect
# (счёт/комплект/таймер), таймлимит с ничьей и победой, офлайн-участник при закрытии, новая комната без
# старого счёта, соседняя комната Кампании, экспорт итога (outbox).
# Требует: сервер рига с dm_arena и outbox статистики (BLOCKFIELD_STATS_EXPORT_ENABLED=true, ingest недоступен),
# оба клиента в хабе. Правила комнат пишутся в blockfield/modes/deathmatch.json и восстанавливаются на выходе.
source "$(dirname "$0")/lib/bot.sh"
A=Player115
V=RigPeer
S=~/.cache/bf-test-server
RULES=$S/blockfield/modes/deathmatch.json
SHOTS=${SHOTS:-/tmp}
pass=0
fail=0
check() { if [ "$2" = ok ]; then
    pass=$((pass + 1))
    echo "PASS $1"
else
    fail=$((fail + 1))
    echo "FAIL $1 :: $3"
fi; }
dim() { "$1" state | grep -oE '"dimension":"[^"]*"' | cut -d'"' -f4; }
in_arena() { [[ "$(dim "$1")" == blockfield:dm_arena* ]]; }
wait_arena() {
    for i in $(seq 1 "${2:-20}"); do
        in_arena "$1" && return 0
        sleep 1
    done
    return 1
}
wait_dim() {
    for i in $(seq 1 "${3:-20}"); do
        [ "$(dim "$1")" = "$2" ] && return 0
        sleep 1
    done
    return 1
}
log_since() { tail -n +$((L0 + 1)) $S/server.out; }
frags() { "$1" messages 8 | grep -oE 'Фраги: §c[0-9]+' | tail -1 | grep -oE '[0-9]+$'; }
slots() { RC "data get entity $1 Inventory" | grep -oE 'Slot: [0-9-]+b' | wc -l; }
kill_by() { RC "execute in $ARENA run damage $2 100 blockfield:knife by $1" >/dev/null; }
START=$(mktemp)
cp "$RULES" "$RULES.dm-test2.bak"
trap 'rm -f "$START"; mv -f "$RULES.dm-test2.bak" "$RULES"' EXIT
rules() { echo "$1" >"$RULES"; }
to_hub() {
    B bfaction leave >/dev/null 2>&1
    B2 bfaction leave >/dev/null 2>&1
    sleep 3
    B closescreen >/dev/null
    B2 closescreen >/dev/null
    RC "tp $A -277.5 -40 604.5" >/dev/null
    RC "tp $V -276.5 -40 604.5" >/dev/null
    sleep 1
}
# Открыть комнату кнопкой хаба у A, V входит по join, оба выбирают класс; печатает номер комнаты.
open_room() {
    L0=$(wc -l <$S/server.out)
    B bfaction play:deathmatch:dm_arena >/dev/null
    sleep 3
    room=$(log_since | grep -oE 'Комната [0-9]+ открыта \(Deathmatch' | tail -1 | grep -oE '[0-9]+')
    [ "$1" = solo ] || B2 bfaction join:$room >/dev/null
    sleep 2
    B bfaction class:assault:0 >/dev/null
    [ "$1" = solo ] || B2 bfaction class:assault:0 >/dev/null
}
reconnect() {
    "$1" disconnect >/dev/null
    sleep 4
    "$1" connect 127.0.0.1:25599 >/dev/null
    for i in $(seq 1 40); do
        "$1" state | grep -q '"connected":true' && [ -n "$(dim "$1")" ] && break
        sleep 2
    done
    sleep 3
}

to_hub
CAMPAIGN_ROOM=""
# 0. Соседняя комната Кампании: остаётся открытой и не закрывается вместе с Deathmatch.
L0=$(wc -l <$S/server.out)
RC "bf start campaign Center" >/dev/null
sleep 5
CAMPAIGN_ROOM=$(log_since | grep -oE 'Комната [0-9]+ открыта \(Кампания|Комната [0-9]+ открыта \([^D]' | tail -1 | grep -oE '[0-9]+' | head -1)
echo "   campaign room: ${CAMPAIGN_ROOM:-none}"

# 1. Минимальный состав: один игрок не запускает бой.
rules '{"kill_limit":30,"time_limit_seconds":600,"preparation_seconds":5,"minimum_players":2,"respawn_seconds":30,"protection_seconds":6}'
open_room solo
R1=$room
sleep 9
B messages 20 | grep -q "Нужно минимум 2" && [ "$(dim B)" = "blockfield_shared:respawn" ] &&
    check "one player does not start the match" ok || check "minimum roster" no "$(dim B) $(log_since | grep -aE 'Нужно|БОЙ' | tail -2)"
B screenshot dm-menu.png >/dev/null
B2 bfaction join:$R1 >/dev/null
sleep 2
B2 bfaction class:assault:0 >/dev/null
wait_arena B 20 && wait_arena B2 10 && check "match starts once the second player joins" ok || check "start with roster" no "$(dim B) / $(dim B2)"
ARENA=$(dim B)

# 2. Защита после высадки: по защищённому урон не проходит; атака снимает свою защиту.
# Решает ответ /damage («Applied» / «invulnerable»): 4 урона целиком гасит броня комплекта, HP не показателен.
hit() { RC "execute in $ARENA run damage $2 4 blockfield:knife by $1"; }
hit $A $V | grep -q "invulnerable" && check "protected player takes no damage" ok || check "protection blocks damage" no "A->V not blocked"
hit $V $A >/dev/null
sleep 0.3
hit $A $V | grep -q "Applied" && check "attacking removes own protection" ok || check "attack removes protection" no "A->V still blocked after V attacked"

# 3. Таймер респавна: высадка сразу после смерти отклоняется, после 30 с — проходит.
sleep 7 # защита A истекла
kill_by $A $V
sleep 1.5
B2 bfaction spawn:BASE >/dev/null
sleep 2
[ "$(dim B2)" = "blockfield_shared:respawn" ] && check "respawn delay is enforced by the server" ok || check "respawn delay" no "$(dim B2)"
[ "$(frags B)" = 1 ] && check "gun-free kill by A counted once" ok || check "kill counted" no "A frags $(frags B)"

# 4. Reconnect не обходит таймер респавна (V мёртв, таймер ещё идёт).
reconnect B2
B2 bfaction class:assault:0 >/dev/null
sleep 1
B2 bfaction spawn:BASE >/dev/null
sleep 2
[ "$(dim B2)" = "blockfield_shared:respawn" ] && check "reconnect does not skip respawn timer" ok || check "reconnect respawn timer" no "$(dim B2)"
for i in $(seq 1 35); do
    B2 bfaction spawn:BASE >/dev/null
    sleep 1
    in_arena B2 && break
done

# 5. Окружение: смерть без фрага.
sleep 7
f0=$(frags B)
RC "execute in $ARENA run damage $V 100 minecraft:lava" >/dev/null
sleep 2
[ "$(frags B)" = "$f0" ] && [ "$(dim B2)" = "blockfield_shared:respawn" ] && check "environment death gives no frag" ok || check "environment death" no "A frags $(frags B) (was $f0), V $(dim B2)"

# 6. Reconnect A: счёт сохраняется, комплект не дублируется.
s0=$(slots $A)
reconnect B
B bfaction class:assault:0 >/dev/null
sleep 1
for i in $(seq 1 35); do
    B bfaction spawn:BASE >/dev/null
    sleep 1
    in_arena B && break
done
sleep 8 # пока идёт защита после высадки, в action bar вместо счёта строка щита
[ "$(frags B)" = 1 ] && check "reconnect keeps the score" ok || check "reconnect score" no "A frags $(frags B)"
[ "$(slots $A)" = "$s0" ] && check "reconnect does not duplicate the kit" ok || check "kit duplication" no "slots $s0 -> $(slots $A)"

# 7. Выход обоих: комната закрывается, измерение освобождается, Кампания не задета.
to_hub
sleep 3
log_since | grep -q "Комната $R1 закрыта\|Комната $R1: все игроки вышли" && check "room closes when everyone leaves" ok || check "room close" no "$(log_since | grep -a "Комната $R1" | tail -2)"
ls $S/world/dimensions/blockfield/ 2>/dev/null | grep -q dm_arena && check "arena dimension removed after close" no "$(ls $S/world/dimensions/blockfield/)" || check "arena dimension removed after close" ok
if [ -n "$CAMPAIGN_ROOM" ]; then
    log_since | grep -q "Комната $CAMPAIGN_ROOM закрыта" && check "campaign room untouched" no "closed" || check "campaign room untouched by deathmatch" ok
fi

# 8. Таймлимит без фрагов: ничья; новая комната стартует с нулевым счётом.
rules '{"kill_limit":30,"time_limit_seconds":20,"preparation_seconds":5,"minimum_players":2,"respawn_seconds":3,"protection_seconds":2}'
open_room
R2=$room
wait_arena B 20 && wait_arena B2 10
sleep 2
[ "$(frags B)" = 0 ] && check "new room does not inherit score" ok || check "new room score" no "A frags $(frags B)"
wait_dim B minecraft:overworld 40
B messages 20 | grep -q "НИЧЬЯ" && check "time limit with equal scores is a draw" ok || check "draw" no "$(B messages 12 | grep -v ACTIONBAR | tr '\n' ' ' | cut -c1-300)"
B screenshot dm-hub-after.png >/dev/null
f=$(find $S/blockfield/stats -maxdepth 1 -name 'Stats_Deathmatch_*' -newer "$START" | sort | tail -1)
python3 - "$f" <<'EOF' && check "draw saved without winner" ok || check "draw stats" no "$f"
import json, sys
d = json.load(open(sys.argv[1]))
print('   stats:', {k: d.get(k) for k in ('GameMode', 'MapName', 'Map', 'MatchEndStatus', 'Winner', 'WinReason')})
sys.exit(0 if d.get('GameMode') == 'Deathmatch' and str(d.get('Winner')).upper() in ('DRAW', 'NONE') else 1)
EOF
touch "$START"

# 9. Таймлимит с одним фрагом: победа по времени; офлайн-участник освобождается при закрытии.
rules '{"kill_limit":30,"time_limit_seconds":25,"preparation_seconds":5,"minimum_players":2,"respawn_seconds":3,"protection_seconds":2}'
open_room
R3=$room
wait_arena B 20 && wait_arena B2 10
ARENA=$(dim B)
sleep 3
kill_by $A $V
sleep 1
B2 disconnect >/dev/null
wait_dim B minecraft:overworld 45
B messages 20 | grep -q "ПОБЕДА: $A" && check "time limit winner by score" ok || check "time limit winner" no "$(B messages 12 | grep -v ACTIONBAR | tr '\n' ' ' | cut -c1-300)"
sleep 2
B2 connect 127.0.0.1:25599 >/dev/null
for i in $(seq 1 40); do
    B2 state | grep -q '"connected":true' && [ -n "$(dim B2)" ] && break
    sleep 2
done
sleep 4
[ "$(dim B2)" = "minecraft:overworld" ] && check "offline participant returns to hub after match closed" ok || check "offline participant released" no "$(dim B2)"
out=$(find $S/blockfield/stats/outbox-v1 -type f -newer "$START" 2>/dev/null | sort)
python3 - "$A" $out <<'EOF' && check "export has deathmatch match with personal winner" ok || check "export winnerUuid" no "$(echo $out | wc -w) outbox files"
import json, sys
name, files = sys.argv[1], sys.argv[2:]
for f in files:
    try:
        d = json.load(open(f))
    except Exception:
        continue
    d = d.get('result', d)
    if d.get('modeId') != 'deathmatch' or d.get('kind') != 'match':
        continue
    winner = d.get('winnerUuid')
    names = {p.get('uuid'): p.get('name') for p in d.get('participants', [])}
    print('   export:', d.get('modeId'), d.get('kind'), d.get('mapId'), d.get('endReason'), d.get('winner'), 'winnerUuid ->', names.get(winner))
    if winner and names.get(winner) == name and d.get('mapId') == 'dm_arena':
        sys.exit(0)
sys.exit(1)
EOF
if [ -n "$CAMPAIGN_ROOM" ]; then RC "bf stop room $CAMPAIGN_ROOM Проверка завершена" >/dev/null; fi
echo "RESULT pass=$pass fail=$fail"
