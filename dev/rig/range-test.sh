#!/bin/bash
# Полигон на двух настоящих клиентах: 16 сценариев MVP из docs/training/RANGE.md.
# Требует: сервер рига с веткой feat/firing-range, эталон maps/firing_range собран, blockfield/maps/firing_range.json,
# BLOCKFIELD_LOBBY_PVP_ENABLED=true, BLOCKFIELD_PVP_COOLDOWN_SECONDS=10, классы assault и sniper, оба клиента в хабе.
# RANGE_RESTART=1 — сценарий 16 перезапускает сервер рига (долго).
source "$(dirname "$0")/lib/bot.sh"
A=Player115
V=RigPeer
S=~/.cache/bf-test-server
D=blockfield_shared:firing_range
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
items() { RC "execute if items entity $1 container.* $2" | grep -oE '[0-9]+$' || echo 0; }
in_range() { [ "$(dim "$1")" = "$D" ]; }
wait_dim() {
    for i in $(seq 1 20); do
        [ "$(dim "$1")" = "$2" ] && return 0
        sleep 0.5
    done
    return 1
}
enter() {
    "$1" closescreen >/dev/null
    "$1" bfaction range:enter >/dev/null
    wait_dim "$1" "$D"
}
leave_range() {
    "$1" bfaction pvp:off >/dev/null
    "$1" bfaction range:exit >/dev/null
    wait_dim "$1" minecraft:overworld
}
target_hp() { RC "execute in $D run data get entity @e[tag=bf_range_target:$1,limit=1] Health" | grep -oE '[0-9.]+f$'; }
START=$(mktemp)
trap 'rm -f "$START"; RC "gamerule naturalRegeneration true" >/dev/null' EXIT
B closescreen >/dev/null
B2 closescreen >/dev/null
RC "tp $A -277.5 -40 604.5" >/dev/null
RC "tp $V -276.5 -40 604.5" >/dev/null
sleep 1

# 1. Все слоты комнат заняты — полигон всё равно доступен и комнату не занимает.
limit_msgs=""
for i in 1 2 3 4; do limit_msgs+=$(RC "bf start room"); done
before=$(RC "bf stop" | grep -oE 'Открыты: .*')
enter B && check "range reachable with all room slots busy ($before)" ok || check "range with full slots" no "$(dim B)"
after=$(RC "bf stop" | grep -oE 'Открыты: .*')
[ "$before" = "$after" ] && check "range does not take a room slot" ok || check "range takes no slot" no "$before / $after"
for id in $(echo "$after" | sed 's/Открыты: //; s/\..*//' | tr ',' ' '); do RC "bf stop room $id" >/dev/null; done
sleep 2
touch "$START" # scenario 14 checks the range only, not the rooms this scenario opened

# 2. Кит без дублирования: выбор, пополнение, повторный выбор.
B bfaction class:assault:0 >/dev/null
sleep 1
g1=$(items $A tacz:modern_kinetic_gun)
sleep 3.2
B bfaction range:refill >/dev/null
sleep 1
B bfaction range:refill >/dev/null
sleep 1
g2=$(items $A tacz:modern_kinetic_gun)
sleep 3.2
B bfaction class:assault:0 >/dev/null
sleep 1
g3=$(items $A tacz:modern_kinetic_gun)
t=$(items $A blockfield:command_tablet)
[ "$g1" -ge 1 ] && [ "$g1" = "$g2" ] && [ "$g2" = "$g3" ] && [ "$t" = 1 ] && check "kit/refill/reselect do not duplicate ($g1 guns, $t tablet)" ok || check "kit no duplication" no "$g1/$g2/$g3 tablet=$t"
B messages 5 | grep -q "дай снаряжению отдохнуть" && check "refill rate limit" ok || check "refill rate limit" no "$(B messages 5 | tr '\n' ' ')"

# 3. Выход с предметом на курсоре: курсор очищен, предмет не выпал.
RC "item replace entity $A player.cursor with tacz:ammo 64" >/dev/null
leave_range B && check "exit returns to hub" ok || check "exit" no "$(dim B)"
cur=$(RC "execute if items entity $A player.cursor *")
drops=$(RC "execute in $D if entity @e[type=item]")
g=$(items $A tacz:modern_kinetic_gun)
! echo "$cur" | grep -q passed && ! echo "$drops" | grep -q passed && [ "$g" = 0 ] && check "no cursor item, no drop, no range gear in hub" ok || check "exit with cursor" no "$cur / $drops / guns=$g"

# 4. Переподключение на полигоне: хаб без снаряжения.
enter B2
B2 bfaction class:assault:0 >/dev/null
sleep 1
B2 disconnect >/dev/null
sleep 5
B2 connect 127.0.0.1:25599 >/dev/null
for i in $(seq 1 40); do
    B2 state | grep -q '"connected":true' && [ -n "$(dim B2)" ] && break
    sleep 2
done
sleep 3
[ "$(dim B2)" = minecraft:overworld ] && [ "$(items $V tacz:modern_kinetic_gun)" = 0 ] && check "reconnect from range lands in hub without gear" ok || check "reconnect" no "$(dim B2) guns=$(items $V tacz:modern_kinetic_gun)"

# 5. Смерть на полигоне: остаётся на полигоне, HP полное, кит выдан заново.
enter B
B bfaction class:assault:0 >/dev/null
sleep 1
RC "execute in $D run tp $A 60.5 65 30.5" >/dev/null
RC "kill $A" >/dev/null
sleep 1.5
p=$(pos $A)
h=$(hp $A)
in_range B && [ "${h%f}" = 20.0 ] && [ "$(items $A tacz:modern_kinetic_gun)" -ge 1 ] && echo "$p" | grep -q '^\[2.5' && check "death in range is a soft reset to the firing line" ok || check "death in range" no "$(dim B) hp=$h pos=$p"

# 6. Блокировки классов: ADMIN действует на полигоне, игрок полигона и хаба не кикается lock-циклом.
enter B2 >/dev/null
RC "bf class lock assault true" >/dev/null
sleep 2
in_range B && [ "$(dim B2)" = "$D" ] && check "forced lock does not move range players (lock loop regression)" ok || check "lock loop" no "$(dim B) / $(dim B2)"
B bfaction class:assault:0 >/dev/null
sleep 1
B messages 4 | grep -q "класс сейчас закрыт" && check "ADMIN-locked class refused in range" ok || check "admin lock refused" no "$(B messages 4 | tr '\n' ' ')"
RC "bf class unlock assault" >/dev/null
sleep 1
B bfaction class:assault:0 >/dev/null
sleep 1
B messages 3 | grep -q "Полигон: " && check "unlock restores class in range" ok || check "unlock" no "$(B messages 3 | tr '\n' ' ')"
RC "bf stop" | grep -q "Открытых комнат нет" && check "unlock touched no room" ok || check "unlock touched no room" no "$(RC 'bf stop')"

# 7. Статичная мишень: TTK-строка и хедшот.
RC "execute in $D run tp $A -18.5 65 3.5 180 0" >/dev/null
sleep 1
B slot 0 >/dev/null
sleep 2
B lookat -18.5 66.75 10.5 >/dev/null
sleep 0.5
for i in $(seq 1 30); do
    B tacz shoot >/dev/null
    sleep 0.35
    B messages 3 | grep -q "A10a броня:assault: TTK" && break
done
line=$(B messages 8 | grep -oE "A10a броня:assault: TTK[^\"]*" | tail -1)
echo "   $line"
echo "$line" | grep -q "точность: тик сервера" && check "static target TTK/DPS line" ok || check "TTK line" no "$(B messages 6 | tr '\n' ' ')"
B messages 12 | grep -q "голова" && check "headshot reported" ok || check "headshot reported" no "no голова in overlay"

# 8. Броня мишени против игрока в той же броне: одинаковый выстрел, одинаковая потеря HP (±1.0).
# Scenario 7 can empty the magazine; 4 blocks keeps hip-fire spread on the body; regen off until exit.
B2 bfaction class:assault:0 >/dev/null
sleep 1
RC "item replace entity $V weapon.mainhand with air" >/dev/null
B bfaction range:refill >/dev/null
sleep 1
RC "gamerule naturalRegeneration false" >/dev/null
RC "execute in $D run tp $A -10.5 65 21.5 180 0" >/dev/null
sleep 3.5
B clearmessages >/dev/null
for i in 1 2 3; do
    B lookat -10.5 65.9 25.5 >/dev/null
    sleep 0.4
    B tacz shoot >/dev/null
    sleep 0.6
    tloss=$(B messages 6 | grep -oE "B25a броня:assault · −[0-9.]+[^\"]*" | grep -v голова | tail -1 | grep -oE '−[0-9.]+' | tr -d '−')
    [ -n "$tloss" ] && break
done
RC "execute in $D run tp $V -6.5 65 25.5 180 0" >/dev/null
B bfaction pvp:on >/dev/null
B2 bfaction pvp:on >/dev/null
sleep 3.5
# Shots right after a teleport/PvP toggle sometimes do not register on the player; settle first and retry.
RC "execute in $D run tp $A -6.5 65 21.5 180 0" >/dev/null
sleep 2.5
h0=$(hp $V)
for i in 1 2 3 4 5; do
    B lookat -6.5 65.9 25.5 >/dev/null
    sleep 0.4
    B tacz shoot >/dev/null
    sleep 0.8
    h1=$(hp $V)
    [ "$h1" != "$h0" ] && break
done
ploss=$(python3 -c "print(round(float('${h0%f}')-float('${h1%f}'),1))" 2>/dev/null)
echo "   target loss $tloss, player loss $ploss"
# One AK body hit on armored players measured 2.8–3.7 on the rig (hit location), so ±1.0, not ±0.5.
python3 -c "import sys; sys.exit(0 if abs(float('${tloss:-99}')-float('${ploss:-0}')) <= 1.0 else 1)" && check "armored target matches armored player" ok || check "armor equivalence" no "target $tloss player $ploss"

# 9. Движущаяся мишень меняет X в пределах дорожки.
# Selector tag= cannot parse ':', so match the id tag through NBT.
fx() { RC "execute in $D as @e[tag=bf_range_target] if data entity @s {Tags:[\"bf_range_target:F25\"]} run data get entity @s Pos[0]" | grep -oE '[0-9.-]+d$' | tr -d d; }
x1=$(fx)
sleep 0.6
x2=$(fx)
python3 -c "import sys; a,b=float('$x1'),float('$x2'); sys.exit(0 if a!=b and 18.4<=a<=28.6 and 18.4<=b<=28.6 else 1)" && check "strafing target moves ($x1 -> $x2)" ok || check "strafe" no "$x1 / $x2"

# 10. Матрица PvP-флагов: урон только при ВКЛ у обоих.
# 4 blocks: hip-fire spread misses at 8.
RC "execute in $D run tp $A 50.5 65 -1.5 0 0" >/dev/null
RC "execute in $D run tp $V 50.5 65 2.5 180 0" >/dev/null
sleep 11
for combo in "off off" "on off" "off on" "on on"; do
    set -- $combo
    B bfaction pvp:$1 >/dev/null
    B2 bfaction pvp:$2 >/dev/null
    RC "effect give $V minecraft:instant_health 1 5" >/dev/null
    sleep 1.5
    h0=$(hp $V)
    for i in 1 2 3; do
        B lookat 50.5 65.9 2.5 >/dev/null
        sleep 0.4
        B tacz shoot >/dev/null
        sleep 0.5
        h1=$(hp $V)
        [ "$h1" != "$h0" ] && break
    done
    hurt=$(python3 -c "print(float('${h1%f}') < float('${h0%f}'))")
    want=$([ "$combo" = "on on" ] && echo True || echo False)
    [ "$hurt" = "$want" ] && check "pvp $1/$2 hurt=$hurt" ok || check "pvp $1/$2" no "hp $h0 -> $h1"
    [ "$combo" != "on on" ] && sleep 11
done

# 11. Кулдаун: после боя выключение ждёт; выход и смена класса отклонены.
B2 bfaction pvp:off >/dev/null
sleep 0.5
B2 bfaction range:exit >/dev/null
B2 bfaction class:sniper:0 >/dev/null
sleep 1
in_range B2 && B2 messages 4 | grep -q "Сначала выключи PvP" && check "exit/class refused while DISABLING" ok || check "cooldown refusal" no "$(dim B2) $(B2 messages 4 | tr '\n' ' ')"

# 12. Старый снаряд после OFF: граната, брошенная при ВКЛ, не ранит после выключения и повторного ВКЛ цели.
sleep 11
B bfaction pvp:on >/dev/null
if [ "$(items $A lrtactical:throwable)" -gt 0 ]; then
    RC "execute in $D run tp $V 50.5 65 -1.5 180 0" >/dev/null
    sleep 1
    B lookat 50.5 65 -1.5 >/dev/null
    B slot 3 >/dev/null
    B use >/dev/null
    B bfaction pvp:off >/dev/null
    sleep 11
    B bfaction pvp:on >/dev/null
    B2 bfaction pvp:on >/dev/null
    h0=$(hp $V)
    sleep 4
    h1=$(hp $V)
    [ "${h0%f}" = "${h1%f}" ] && check "old grenade cannot hit after OFF/ON" ok || check "old grenade" no "hp $h0 -> $h1"
else
    echo "SKIP old projectile: no lrtactical:throwable in the assault kit (covered by LobbyPvpTest)"
fi

# 13. Блоки не меняются: взрыв и стрельба на полигоне.
RC "execute in $D run summon minecraft:tnt 46.5 65 20.5 {fuse:1}" >/dev/null
sleep 1.5
ok13=ok
for spec in "-23 64 10 minecraft:lime_concrete" "0 65 -1 minecraft:smooth_stone_slab" "33 65 0 minecraft:stone_bricks" "46 64 20 minecraft:polished_andesite"; do
    set -- $spec
    RC "execute in $D if block $1 $2 $3 $4" | grep -q passed || ok13="$spec"
done
[ "$ok13" = ok ] && check "range blocks unchanged after explosion and shots" ok || check "blocks unchanged" no "$ok13"

# 14. Никаких файлов статистики за время теста.
f=$(find $S/blockfield/stats -newer "$START" -type f 2>/dev/null | head -1)
[ -z "$f" ] && check "no stats files written" ok || check "no stats files" no "$f"

# 15. Вход в комнату с полигона запрещён.
RC "bf start room" >/dev/null
room=$(RC "bf stop" | grep -oE 'Открыты: [^,.]+' | sed 's/Открыты: //')
B bfaction join:$room >/dev/null
B bfaction play:room >/dev/null
sleep 1
in_range B && B messages 4 | grep -q "Сначала вернитесь с полигона" && check "join/play refused from range" ok || check "join refused" no "$(dim B)"
[ -n "$room" ] && RC "bf stop room $room" >/dev/null

# 16. Рестарт сохраняет мир и ровно 12 мишеней.
if [ "$RANGE_RESTART" = 1 ]; then
    sleep 11
    leave_range B
    leave_range B2
    (cd "$R" && ./server.sh stop && ./server.sh start) >/dev/null
    for i in $(seq 1 60); do
        B state | grep -q '"connected":true' && break
        B connect 127.0.0.1:25599 >/dev/null
        sleep 3
    done
    sleep 25
    enter B
    sleep 3
    n=$(RC "execute in $D if entity @e[tag=bf_range_target]" | grep -oE '[0-9]+$')
    RC "execute in $D if block -23 64 10 minecraft:lime_concrete" | grep -q passed && [ "$n" = 12 ] && check "restart keeps world and 12 targets" ok || check "restart" no "targets=$n"
else
    echo "SKIP restart: RANGE_RESTART=1 to run"
fi

sleep 11
leave_range B >/dev/null
leave_range B2 >/dev/null
echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
