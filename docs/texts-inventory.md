# Player-facing Russian text inventory (chore/player-texts)

Scope: every player-facing Russian chat/actionbar/title/menu/kick string in `server` and `client`,
found by grepping quoted Cyrillic literals and filtering out `LOGGER.*`, thrown exceptions (config
validation, admin/log-facing) and `/bf ...` admin-command output (`sendSuccess`/`sendFailure`), which
stay technical and unchanged per the task brief.

Goal: short, punchy, military-arcade voice. No mechanics jargon, no numbers the player doesn't need,
no parenthetical technical reasons, no "сервер/переполнение/замер недействителен/генерация". Colour
codes kept; no `§l` (bold breaks ш/щ/ж glyphs) reintroduced.

Most of the existing text (menu titles, HUD labels, deathmatch/campaign broadcasts like "БОЙ НАЧАЛСЯ —
КАЖДЫЙ САМ ЗА СЕБЯ!", "КАМПАНИЯ ЗАВЕРШЕНА! 🏆") was already on-voice and is left untouched. The table
below lists only strings that were rewritten, plus the reviewed-but-unchanged notes for the categories
the owner named explicitly.

## Rewritten

| File:line | Old | New | Shown where |
|---|---|---|---|
| server/core/BattleBoundary.java:42 | «§cВернитесь в зону боя! Урон удваивается каждую секунду!» | «§cТы покинул зону боя — возвращайся, пока жив!» | Actionbar, out-of-bounds damage tick |
| server/training/RangeTargets.java:285 | «… — замер недействителен (посторонний урон/переполнение)» | «… — кто-то вмешался в стрельбу, попробуй ещё раз» | Chat, firing range TTK/DPS trial report |
| server/training/RangeRules.java:75 | «Полигон недоступен из матча. Сначала покиньте комнату.» | «Сначала выйди из боя — потом на стрельбище.» | Chat refusal, `/bf range enter` from a room |
| server/training/RangeRules.java:76 | «На полигон можно перейти только из хаба.» | «На стрельбище идут только из хаба.» | Chat refusal |
| server/training/RangeRules.java:77 | «Полигон недоступен сразу после поражения.» | «Дай себе секунду после поражения, потом на стрельбище.» | Chat refusal |
| server/training/RangeRules.java:78 | «Сначала выключите PvP в хабе.» | «Сначала выключи PvP в хабе.» | Chat refusal |
| server/training/FiringRange.java:204-205 | «Полигон закрыт администратором.» / «Полигон на этом сервере недоступен.» | «Полигон сейчас закрыт.» / «Полигон пока недоступен.» | Chat refusal, `range:enter` |
| server/training/FiringRange.java:214 | «Полигон сейчас недоступен: мир не открылся. Сообщите администратору.» | «Полигон не открылся. Попробуй ещё раз чуть позже.» | Chat refusal |
| server/training/FiringRange.java:236 | «Вы не на полигоне.» | «Ты не на полигоне.» | Chat refusal, `range:exit` |
| server/training/FiringRange.java:238 | «Выход после выключения PvP. …» | «Сначала выключи PvP, потом выходи. …» | Chat refusal |
| server/training/FiringRange.java:260 | «Смена класса после выключения PvP. …» | «Сначала выключи PvP, потом меняй класс. …» | Chat refusal |
| server/training/FiringRange.java:261 | «Указанный класс не существует!» | «Такого класса нет!» | Chat refusal |
| server/training/FiringRange.java:262 | «Этот класс заблокирован администратором.» | «Этот класс сейчас закрыт.» | Chat refusal |
| server/training/FiringRange.java:264 | «Не удалось выдать снаряжение класса.» | «Не получилось выдать снаряжение.» | Chat refusal |
| server/training/FiringRange.java:280 | «Пополнение после выключения PvP. …» | «Сначала выключи PvP, потом пополняйся. …» | Chat refusal |
| server/training/FiringRange.java:282 | «Пополнение не чаще раза в 3 с.» | «Не так быстро — дай снаряжению отдохнуть.» | Chat refusal |
| server/training/FiringRange.java:284 | «Снаряжение пополнено» | «Снаряжение пополнено!» | Actionbar |
| server/training/FiringRange.java:350 | «Полигон закрыт администратором.» | «Полигон закрыли — жди новых учений.» | Chat, `/bf range close` broadcast to range players |
| server/pvp/LobbyPvp.java:178 | «PvP сейчас недоступно в этом контексте.» | «PvP сейчас не переключить.» | Actionbar, `/pvp` toggle refusal |
| server/core/PlayerTeamManager.java:138 | «§cОшибка: Команда X не зарегистрирована в текущем режиме!» | «§cКоманды X в этом бою нет!» | Chat, team join |
| server/core/PlayerTeamManager.java:143 | «§cВ рейтинговом матче команда закреплена за участником состава.» | «§cВ рейтинговом матче свою команду не выбирают.» | Chat, team join |
| server/core/PlayerTeamManager.java:155 | «§cОшибка: Команда X переполнена! Лимит: N» | «§cКоманда X уже набрана — свободных мест нет.» | Chat, team join |
| server/core/PlayerTeamManager.java:393 | «§eРезерв места истёк или кампания завершена — вы в лобби.» | «§eТебя долго не было — место не сохранилось. Ты в лобби.» | Chat, ranked reconnect |
| server/core/Hub.java:399-400 | «Техработы: новые матчи закрыты.» / «Сейчас не удаётся начать матч. Попробуйте чуть позже.» | «Штаб не принимает новые бои — заходи позже.» / «Бой не начался. Попробуй ещё раз.» | Chat refusal, hub casual-mode start |
| server/core/Hub.java:424-425 | «§cТехработы: новые матчи закрыты.» / «§cНе удалось открыть комнату: их уже N из M.» | «§cШтаб не принимает новые бои — заходи позже.» / «§cСейчас слишком много боёв — попробуй через минуту.» | Chat refusal, `/bf play` |
| server/core/ClassRestrictionManager.java:19-21 | `PLAYERS("Недостаточно игроков")`, `ADMIN("Заблокировано администратором")`, `SPECIAL_MODE("Особый режим")` | `PLAYERS("мало бойцов в отряде")`, `ADMIN("закрыт командованием")`, `SPECIAL_MODE("не подходит для этого режима")` | Chat, class-block reason suffix |
| server/core/ClassRestrictionManager.java:93 | «§c[Блокировка] Класс X заблокирован. Причина: …» | «§c⚠ Класс X закрыт: …» | Chat broadcast |
| server/core/ClassRestrictionManager.java:108 | «§e⚠ Ваш класс заблокирован! У вас есть 3 минуты (180 сек) доигрывания до принудительной смены.» | «§e⚠ Твой класс закрыли! Доиграй ещё 3 минуты — потом сменим сами.» | Chat |
| server/core/ClassRestrictionManager.java:128 | «§a[Разблокировка] Класс X снова доступен для выбора.» | «§a✔ Класс X снова открыт!» | Chat broadcast |
| server/core/ClassRestrictionManager.java:164 | «§cВы были переведены в лобби команды для смены заблокированного класса!» | «§cТвой класс закрыли — выбери другой в лобби команды!» | Chat |
| server/core/ClassRestrictionManager.java:263 | «§c⏳ Класс заблокирован. Принудительный кик в лобби через: N сек.» | «§c⏳ Класс закрыт — вышвырнем в лобби через: N сек.» | Actionbar countdown |
| server/BlockFieldServer.java:1223 | «§c[ТЕХРАБОТЫ] Матч прерван администратором: reason» | «§c[СТОП] Бой прерван командованием: reason» | Chat broadcast, `/bf maintenance interrupt confirm` |
| server/BlockFieldServer.java:1344 (via `getBlockedReason`) | «§cЭтот класс заблокирован! Причина: …» (uses updated reason text) | unchanged wrapper, updated reason text | Chat, `/class` select refusal |
| server/BlockFieldServer.java:1407 | «§cВаш класс заблокирован! Пожалуйста, выберите другой доступный класс перед возрождением.» | «§cТвой класс закрыли! Выбери другой перед высадкой.» | Chat, `/spawn` refusal |
| server/BlockFieldServer.java:1461 | «§c[BlockField] На точке идет бой (прогресс защиты N% < M%). Спавн заблокирован!» | «§c[BlockField] На этой точке ещё идёт бой — высадка здесь закрыта!» | Chat, spawn-point refusal |
| server/PackVersionGate.java:15 | «На сервере не указана версия пака. Сообщите администратору.» | «Версия пака не настроена — сообщи администратору.» | Kick/disconnect screen, login |
| server/PackVersionGate.java:32-33 | «Требуется обновление пака.\nЗакройте игру и обновите пак через лаунчер.\nСкачать: …» | «Твой пак устарел.\nЗакрой игру и обнови пак через лаунчер.\nСкачать: …» | Kick/disconnect screen, login |

## Reviewed, left unchanged (already on-voice or out of scope)

- Menu/HUD labels and screen titles (`ВЫБОР КЛАССА`, `ТОЧКИ ВЫСАДКИ`, `ПОКИНУТЬ МАТЧ`, `ТАБЛИЦА БОЯ`,
  class names, tablet tabs, `ГОТОВНОСТЬ`, `ГОЛОСОВАНИЕ`, `ТАБЛИЦА ЛИДЕРОВ`, etc.) — already short,
  punchy, non-technical.
- `server/modes/deathmatch/DeathmatchMode.java`, `server/modes/campaign/CampaignMode.java` broadcasts —
  already military-arcade ("БОЙ НАЧАЛСЯ — КАЖДЫЙ САМ ЗА СЕБЯ!", "🏆 КАМПАНИЯ ЗАВЕРШЕНА! …", "⚔ ФРОНТ
  СДВИНУЛСЯ! ⚔", MVP/killfeed lines) — no jargon, no changes needed.
- `server/core/PlayerTeamManager.java` other chat lines (switchTeam hint, joinTeam success, leaveTeam,
  swapPlayers, "С возвращением в бой!") — already casual and clear.
- `server/core/RoomPresence.java` ("Лобби сервера") and `client/LauncherBridge.java` ("На сервере" /
  "В главном меню") — Discord rich-presence / website server-list metadata, not shown inside the game
  UI; out of the requested chat/actionbar/title/menu/kick scope.
- `server/modes/campaign/CampaignMode.java:1777` `"Регенерация"` — capture-point HUD status tag
  (point health regrowing on its own), not "world generation" jargon; kept as a normal status label
  alongside "Оборона"/"Захват"/"Оспаривается".
- Admin/ops `/bf …` command output (`sendSuccess`/`sendFailure` in `BlockFieldServer.java`,
  `FiringRange.reset`, `Room.stop` reasons persisted to match-stats logs) — kept technical and
  unchanged per the task brief.
