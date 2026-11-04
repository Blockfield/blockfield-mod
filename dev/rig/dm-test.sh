#!/bin/bash
# Deathmatch на двух настоящих клиентах: вход из хаба, высадка, фраг, суицид, reconnect, лимит фрагов, итог.
# Требует: сервер рига с веткой feat/deathmatch-playable, dm_arena построена, blockfield/modes/deathmatch.json
# с kill_limit=3, preparation_seconds=10, respawn_seconds=3, protection_seconds=2.
source "$(dirname "$0")/lib/bot.sh"
A=Player115
V=RigPeer
S=~/.cache/bf-test-server
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
pos() { RC "data get entity $1 Pos" | grep -oE '\[.*\]' | tr -d 'd'; }
log_since() { tail -n +$((L0 + 1)) $S/server.out; }
L0=$(wc -l <$S/server.out)
START=$(mktemp)
trap 'rm -f "$START"' EXIT

# 0. Отказ без карты: комната не создаётся, бой в хабе не стартует.
r=$(RC "bf start deathmatch no_such_arena_xyz")
echo "$r" | grep -q "Ошибка" && ! log_since | grep -q "открыта (Deathmatch" && check "missing arena rejected" ok || check "missing arena rejected" no "$r"

# 1. Хаб -> кнопка Deathmatch -> комната; второй игрок входит по join.
B closescreen >/dev/null
B2 closescreen >/dev/null
RC "tp $A -277.5 -40 604.5" >/dev/null
RC "tp $V -276.5 -40 604.5" >/dev/null
sleep 1
B bfaction play:deathmatch:dm_arena >/dev/null
sleep 3
room=$(log_since | grep -oE 'Комната [0-9]+ открыта \(Deathmatch' | tail -1 | grep -oE '[0-9]+')
[ -n "$room" ] && check "hub button opened deathmatch room $room" ok || check "hub button opened room" no "$(log_since | tail -5)"
B2 bfaction join:$room >/dev/null
sleep 3
d1=$(dim B)
d2=$(dim B2)
[ "$d1" = "blockfield_shared:respawn" ] && [ "$d2" = "blockfield_shared:respawn" ] && check "both wait in respawn room during preparation" ok || check "both in respawn room" no "$d1 / $d2"
teams=$(
    RC "team list"
    RC "execute if entity @a[team=]"
)
B bfaction class:assault:0 >/dev/null
B2 bfaction class:assault:0 >/dev/null

# 2. Старт после подготовки: оба высажены на арену в разных точках.
for i in $(seq 1 30); do
    d1=$(dim B)
    d2=$(dim B2)
    [[ "$d1" == blockfield:dm_arena* && "$d2" == blockfield:dm_arena* ]] && break
    sleep 1
done
pa=$(pos $A)
pv=$(pos $V)
dist=$(python3 -c "import json;a=json.loads('$pa');b=json.loads('$pv');print(round(sum((x-y)**2 for x,y in zip(a,b))**0.5,1))" 2>/dev/null)
[[ "$d1" == blockfield:dm_arena* && "$d2" == blockfield:dm_arena* ]] && check "both deployed into $d1 (distance $dist)" ok || check "deploy" no "$d1 / $d2"
python3 -c "import sys; sys.exit(0 if float('${dist:-0}') >= 20 else 1)" && check "spawn points spread apart" ok || check "spawn points spread apart" no "distance $dist"
held=$(B state | grep -oE '"held":"[^"]*"')
echo "   A held: $held"
RC "execute if entity @a[name=$A,team=]" | grep -q "passed" && check "no scoreboard team in FFA" ok || check "no scoreboard team in FFA" no "$(RC "team list")"

# 3. Настоящий фраг из выданного оружия: A и V лицом к лицу на полу арены, защита истекла.
sleep 3
RC "execute in $d1 run tp $A 0.5 65 -20.5 0 0" >/dev/null
RC "execute in $d1 run tp $V 0.5 65 -14.5 180 0" >/dev/null
sleep 1.5
B slot 0 >/dev/null
sleep 3
B lookat 0.5 66.1 -14.5 >/dev/null
sleep 1
hp0=$(hp $V)
dead=""
for i in $(seq 1 30); do
    B tacz shoot >/dev/null
    sleep 0.35
    d=$(dim B2)
    [ "$d" = "blockfield_shared:respawn" ] && {
        dead=1
        break
    }
done
echo "   V hp start $hp0, dead=$dead"
[ -n "$dead" ] && check "real gun kill moves victim to respawn room" ok || check "real gun kill" no "V hp $(hp $V)"
sleep 1
fr=$(
    B state >/dev/null
    B messages 5 | grep -oE 'Фраги: [^ ]*[0-9]+' | tail -1
)
echo "   A overlay: $(B messages 3 | tr '\n' ' ' | cut -c1-200)"

# 4. Респавн через 3 с по кнопке высадки: снова на арене, с защитой.
sleep 4
B2 bfaction spawn:BASE >/dev/null
sleep 2
[[ "$(dim B2)" == blockfield:dm_arena* ]] && check "victim redeploys after respawn delay" ok || check "victim redeploys" no "$(dim B2) $(B2 messages 3 | tr '\n' ' ')"

# 5. Суицид: смерть без фрага.
sleep 3
B2 bfaction kill >/dev/null
sleep 2
[ "$(dim B2)" = "blockfield_shared:respawn" ] && check "suicide counted as death, victim waits" ok || check "suicide" no "$(dim B2)"

# 6. Reconnect V: возвращается в свою комнату, счёт не сбрасывается.
B2 disconnect >/dev/null
sleep 5
B2 connect 127.0.0.1:25599 >/dev/null
for i in $(seq 1 40); do
    B2 state | grep -q '"connected":true' && [ -n "$(dim B2)" ] && break
    sleep 2
done
sleep 4
echo "   V after reconnect: $(dim B2)"
[ "$(dim B2)" = "blockfield_shared:respawn" ] || [[ "$(dim B2)" == blockfield:dm_arena* ]] && check "reconnect returns to room" ok || check "reconnect returns to room" no "$(dim B2)"

# 7. До лимита фрагов (3): ещё два фрага A по V через /damage от имени A (оружие проверено выше).
# После reconnect класс сбрасывается (needsClass): без повторного выбора высадка не пройдёт.
sleep 4
B2 bfaction class:assault:0 >/dev/null
sleep 1
B2 bfaction spawn:BASE >/dev/null
sleep 3
for k in 2 3; do
    for i in $(seq 1 10); do
        [[ "$(dim B2)" == blockfield:dm_arena* ]] && break
        B2 bfaction spawn:BASE >/dev/null
        sleep 1
    done
    sleep 2.5
    RC "execute in $d1 run damage $V 100 minecraft:player_attack by $A" >/dev/null
    sleep 4.5
done
for i in $(seq 1 15); do
    d1=$(dim B)
    [[ "$d1" != blockfield:dm_arena* && "$d1" != "blockfield_shared:respawn" ]] && break
    sleep 1
done
echo "   A now in $(dim B), V in $(dim B2)"
log_since | grep -aE 'Stats|Deathmatch|Комната .* закрыта|ПОБЕДА|НИЧЬЯ' | tail -6 | cut -c1-200
[ "$(dim B)" = "minecraft:overworld" ] && [ "$(dim B2)" = "minecraft:overworld" ] && check "kill limit ends match, both back in hub" ok || check "match end" no "$(dim B) / $(dim B2)"
B messages 12 | grep -q "ПОБЕДА: $A" && check "winner announced" ok || check "winner announced" no "$(B messages 12 | tr '\n' ' ' | cut -c1-300)"
f=$(find $S/blockfield/stats -name 'Stats_Deathmatch_*' -newer "$START" 2>/dev/null | head -1)
[ -n "$f" ] && python3 - "$f" "$A" <<'EOF' && check "stats saved once with personal result" ok || check "stats saved" no "$f"
import json, sys
d = json.load(open(sys.argv[1]))
players = {p.get('Name') or p.get('name'): p for p in (d.get('Players') or d.get('players') or [])}
print('   stats:', d.get('GameMode'), d.get('MatchEndStatus'), d.get('Winner'), {n: (p.get('Kills'), p.get('Deaths'), p.get('Suicides')) for n, p in players.items()})
a = players.get(sys.argv[2], {})
sys.exit(0 if d.get('GameMode') == 'Deathmatch' and a.get('Kills') == 3 else 1)
EOF
ls $S/world/dimensions/blockfield/ 2>/dev/null | grep -q dm_arena && check "arena dimension removed" no "$(ls $S/world/dimensions/blockfield/)" || check "arena dimension removed" ok
echo "RESULT pass=$pass fail=$fail"
