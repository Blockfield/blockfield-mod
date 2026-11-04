# Настройки сайта (`blockfield-admin/settings.json`)

Сайт пишет файл, сервер подхватывает его без рестарта. Код: `policy/AdminSettings` (разбор, проверки,
приоритет, план блокировок — unit `AdminSettingsTest`), `core/AdminSettingsSync` (чтение и применение).

**Путь.** `<каталог сервера>/blockfield-admin/settings.json` (прод: `/data/blockfield-admin/settings.json`),
env `BLOCKFIELD_ADMIN_SETTINGS_FILE` переопределяет. Каталог вне копии конфигов образа — переживает рестарт.

**Цикл.** Чтение на `SERVER_STARTED` (после загрузки классов), дальше за каталогом следит
фоновый WatchService (перечитывает только серверный поток), для ненадёжных файловых систем —
медленная перепроверка раз в 60 с; сверяются mtime+size, разбор только при изменении. Сайт
должен писать файл атомарно (tmp + rename).
После каждой попытки рядом атомарно пишется `applied.json`:

```json
{
  "revision": 7,
  "appliedAt": 1760000000000,
  "errors": ["rooms.maxRooms: 40 clamped to 16"]
}
```

Битый JSON или `schema != 1` — файл отвергнут, действуют прежние настройки, `revision` в `applied.json` —
последняя применённая, причина в `errors`. Неверный тип или секция не объектом — поле сохраняет последнее
рабочее значение (запись в `errors`), остальное применяется; отсутствующий ключ — прежнее поведение (env/JSON).
Неизвестный ключ/класс пропускается. Значение вне диапазона обрезается (тоже в `errors`).
Ошибка чтения файла повторяется каждые 5 с, но логируется и пишется в `applied.json` один раз на новую ошибку.
Файл удалён — возврат к env/JSON-конфигам, `applied.json` = `{"revision": null, ..., "errors": ["settings.json not found; ..."]}`.
В лог — одна строка INFO на ревизию (`Admin settings revision N applied; changed: lobby, rooms; errors: 0`) и WARN на каждую ошибку.
Профиль `workshop` файл не читает.

## Формат

Все секции и ключи необязательны; отсутствие = прежнее поведение (env или JSON-конфиг).

```json
{
  "schema": 1,
  "revision": 7,
  "updatedAt": 1760000000000,
  "updatedBy": "admin",
  "lobby": { "pvpEnabled": true, "pvpCooldownSeconds": 10 },
  "rooms": {
    "maxRooms": 2,
    "idleCloseSeconds": 600,
    "abandonCloseSeconds": 30
  },
  "deathmatch": { "minPlayers": 2 },
  "tdm": { "killLimit": 50, "timeLimitSeconds": 600 },
  "snipers": { "killLimit": 40, "timeLimitSeconds": 600 },
  "classLocks": ["sniper"],
  "campaignBalance": {
    "*": { "max_tickets": 800 },
    "South": { "min_players_per_team": 2, "enable_spawn_shield": false },
    "room": { "preparation_time_seconds": 45 }
  },
  "parcool": { "allowedActions": ["FastRun", "ClingToCliff", "Crawl"] }
}
```

| Ключ                        | Диапазон                                         | Вместо                                                                                                       | Когда действует                                                                       |
| --------------------------- | ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------- |
| `lobby.pvpEnabled`          | bool                                             | env `BLOCKFIELD_LOBBY_PVP_ENABLED`                                                                           | сразу; переключение сбрасывает согласие всех в хабе и на полигоне в ВЫКЛ              |
| `lobby.pvpCooldownSeconds`  | 0..600 (0 = 1 тик)                               | env `BLOCKFIELD_PVP_COOLDOWN_SECONDS` (1..60)                                                                | для новых запросов выключения; уже идущий отсчёт сохраняет прежнюю задержку           |
| `rooms.maxRooms`            | 1..16                                            | 16 (защита памяти; `max_rooms` из `modes/conquest.json` больше не читается)                                  | при следующем открытии комнаты; открытые не закрываются. Игроку лимит не показывается |
| `rooms.idleCloseSeconds`    | 30..3600                                         | env `BLOCKFIELD_IDLE_CLOSE_SECONDS`                                                                          | сразу (проверка каждую секунду)                                                       |
| `rooms.abandonCloseSeconds` | 5..600                                           | env `BLOCKFIELD_ABANDON_CLOSE_SECONDS`                                                                       | сразу                                                                                 |
| `deathmatch.minPlayers`     | 1..24 (1 — можно начать бой одному)              | `minimum_players` в `blockfield/modes/deathmatch.json` (без сайта и файла — `DeathmatchRules.defaults()`, 2) | при следующем старте арены Deathmatch; идущий бой не меняется                         |
| `tdm.killLimit`             | 5..500                                           | `TeamDeathmatchScore.Rules.DEFAULTS` (50)                                                                    | со следующего раунда командного боя; идущий раунд не меняется                         |
| `tdm.timeLimitSeconds`      | 60..3600                                         | `TeamDeathmatchScore.Rules.DEFAULTS` (600)                                                                   | со следующего раунда командного боя                                                   |
| `snipers.killLimit`         | 5..500                                           | `TeamDeathmatchScore.Rules.DEFAULTS` (50)                                                                    | со следующего раунда «Перелётных снайперов»; идущий раунд не меняется                 |
| `snipers.timeLimitSeconds`  | 60..3600                                         | `TeamDeathmatchScore.Rules.DEFAULTS` (600)                                                                   | со следующего раунда «Перелётных снайперов»                                           |
| `classLocks`                | id `[a-z0-9_]{1,32}`, существующий класс         | —                                                                                                            | сразу, как `/bf class lock <id>` (ADMIN, 3 мин доигрывания)                           |
| `campaignBalance`           | ключи `balance` из `CampaignConfig.BALANCE_KEYS` | `balance` файла кампании                                                                                     | при загрузке конфига                                                                  |
| `parcool.allowedActions`    | имена действий ParCool (ниже)                    | `world/serverconfig/parcool-server.toml` образа                                                              | сразу, всем игрокам онлайн и при входе                                                |

`updatedAt`/`updatedBy` сервер не читает. `revision` — целое, без него 0.

**classLocks.** Сервер помнит, какие блокировки поставил сайт и какие — команда `/bf class lock` (до `/bf class unlock`).
Класс, убранный из списка, разблокируется только если его поставил сайт, причина всё ещё ADMIN и команда его
не блокировала; блокировки командой, автоматические PLAYERS и SPECIAL_MODE сайт не снимает. Класс, уже заблокированный командой, сайт своим не считает. Снятие сайтом не
оставляет постоянного исключения для авто-блокировки по числу игроков (в отличие от `/bf class unlock`).
После рестарта список применяется заново.

**kits / fixedModeKits.** `kits` — объект полных определений по ID: `id`, `name`,
`allowedModeIds`, `deploymentCost` (0..10000), `playerLimit` (1..999), `knifeSlot` (0..7),
`variants` с устойчивыми ID вариантов и списками предметов. Дополнительно поддерживаются
командная броня, аксессуары/Curios и запасы дронов. Лимиты: 64 комплекта, 1..16 вариантов,
36 предметов и 16 аксессуаров в списке. Релизные определения читаются из
`BLOCKFIELD_KITS_DIR`; удаление оверлея возвращает штатный комплект или удаляет пользовательский.
Сайт использует `kits.json` рядом с настройками: эффективные определения и реальные registries
предметов, оружия, обвесов, боеприпасов, гранат и аксессуаров.

Метаданные предметов типизированы; произвольный NBT не принимается. Для оружия SBW
`data.gunAmmo` (0..1000) задаёт заряженные боеприпасы; остальные оружейные поля предназначены
для TaCZ. Неверное определение или привязка отклоняет применение всей ревизии, сохраняя
последний рабочий каталог. Выбор варианта привязан к токену определения: устаревшая выдача
требует повторного выбора, в том числе на полигоне.

`fixedModeKits` связывает боевой режим с ID комплекта; `null` отключает стандартную привязку.
Полигон поддерживает доступность комплектов, но не фиксированную привязку. По умолчанию
`awp` использует существующий `sniper`, `snipers` — `snipers`. В фиксированном режиме выдача
бесплатна и без командной квоты; обычная цена и лимит того же комплекта сохраняются.
Workshop не применяет настройки: список брони мишеней читает из проверенного Game `kits.json`
через общий каталог только для чтения рядом с `BLOCKFIELD_ADMIN_SETTINGS_FILE`.

**disabledMapIds.** Массив до 4096 ID карт исключает их из новых матчей, голосований и входов
на полигон после применения настроек. Уже начатый матч и последующие этапы его Ranked-кампании
продолжаются. История, черновики и закрытые тесты сохраняются; повторное включение не требует
рестарта. `applied.json` содержит фактически применённый список для подтверждения сайта.

**campaignBalance.** Приоритет: файл кампании < `"*"` < `"<id>"`. id — поле `id` файла `blockfield/campaigns/<id>.json`,
а без него имя файла без `.json`; Casual-комнаты (баланс из `modes/conquest.json`) имеют id `room`.
Идущие матчи не меняются: Ranked-кампания читает конфиг при старте, Casual-комната — при старте каждого раунда
после голосования. Значение сайта вне диапазона обрезается (запись в `errors`); файлы кампаний не проверяются.
`int` — целое число (`2.0` принимается как 2, `1.5` — ошибка, ключ игнорируется), `double` — число, `bool` — true/false.

| Ключ                               | Тип    | Диапазон  |
| ---------------------------------- | ------ | --------- |
| `max_tickets`                      | int    | 1..100000 |
| `ticket_bleed_multiplier`          | int    | 0..100    |
| `ticket_bleed_timer_seconds`       | int    | 1..600    |
| `death_ticket_cost`                | int    | 0..1000   |
| `capture_speed_percent_per_tick`   | int    | 1..100    |
| `uncapture_speed_percent_per_tick` | int    | 1..100    |
| `score_per_kill`                   | int    | 0..10000  |
| `score_per_obj_sec`                | double | 0..1000   |
| `score_per_capture`                | int    | 0..10000  |
| `score_per_uncapture`              | int    | 0..10000  |
| `preparation_time_seconds`         | int    | 0..600    |
| `transition_time_seconds`          | int    | 0..600    |
| `enable_spawn_shield`              | bool   | —         |
| `spawn_shield_duration_seconds`    | int    | 0..60     |
| `safe_spawn_threshold`             | int    | 0..100    |
| `base_warn_radius`                 | double | 0..1000   |
| `base_kill_radius`                 | double | 0..1000   |
| `max_players_per_team`             | int    | 1..64     |
| `min_players_per_team`             | int    | 1..64     |
| `base_spawn_time_seconds`          | int    | 0..300    |
| `spawn_time_per_point_seconds`     | int    | 0..300    |
| `shuffle_teams_between_rounds`     | bool   | —         |
| `ready_countdown_seconds`          | int    | 0..300    |
| `reserve_seconds`                  | int    | 0..3600   |
| `ready_timeout_seconds`            | int    | 10..3600  |

Пары после слияния (файл < `"*"` < id), если сайт задал хотя бы один ключ: `min_players_per_team` > `max_players_per_team`
→ min = max; `base_kill_radius` > `base_warn_radius` → kill = warn. При разборе ошибка пишется для слияния `"*"` + кампания,
при загрузке конфига (где известен и файл) — WARN в лог.

**parcool.** Список разрешённых действий ParCool; остальные запрещены, `[]` — запрещено всё. Сервер (`core/ParCoolLimits`)
перечитывает базовый файл в глобальное ограничение форка (`Limitations.getGlobalLimitation()`), ставит разрешения по
списку, включает ограничение и рассылает его игрокам онлайн (`Limitations.updateOnlyLimitation`); вошедшие позже
получают его от ParCool при входе. Секции или ключа `allowedActions` нет — глобальное ограничение снова читается из
`world/serverconfig/parcool-server.toml`, который образ сервера кладёт при каждом старте (blockfield-server
`server/lobby/serverconfig`, там сейчас FastRun, ClingToCliff, Crawl). Остальные поля базового файла (стамина,
модификаторы) сайт не меняет. Неизвестное имя (регистр важен) — запись в `errors`, пропускается; не массив или
секция не объектом — остаётся прежний список. Действие, начатое до запрета, обрывается там, где ParCool проверяет
разрешение в `canContinue` (например, ClingToCliff). Проверяет ограничение клиент (`ActionInfo.can`): сервер его
только рассылает, поэтому изменённый клиент обходит запрет, как любой чит движения.

Имена (порядок `Actions.LIST` форка): BreakfallReady, CatLeap, ChargeJump, ClimbPoles, ClimbUp, ClingToCliff, HideInBlock,
Crawl, Dive, Dodge, FastRun, FastSwim, Flipping, HangDown, HorizontalWallRun, JumpFromBar, QuickTurn, RideZipline, Roll,
SkyDive, Slide, Tap, Vault, VerticalWallRun, WallJump, WallSlide, StartSwimByCrawl. ClimbUp — подтягивание из зацепа
на уступ: без него ClingToCliff только держит на краю.

## GunBalance: горячие переопределения TaCZ

**Статус: реализация в рабочем коде, ещё не выпущена.** Прямая компиляция, unit-проверки
типов/границ и проверка 91 файла текущих TaCZ bf7 / Elite X bf9 выполнены. Gradle/CI и
приёмка на изолированном игровом стенде должны завершиться перед выпуском.

```json
{
  "schema": 1,
  "revision": 8,
  "gunBalance": {
    "elitex:ak47": { "draw_time": 0.82 },
    "tacz:glock_17": { "rpm": 300, "bullet": { "damage": 9 } }
  }
}
```

Ключ — **ID индекса оружия** (`elitex:ak47`), а не имя файла данных (`ak47x_data`).
Значения — изменённые поля `GunData` и server-only `body_part_multipliers`. Объекты объединяются рекурсивно;
массив заменяется целиком. Удаление поля, оружия или всей секции возвращает исходное
значение текущего ганпака/датапаков. `null` отключает только nullable-поле, например
`heat` или `charging`; он не означает возврат к дефолту. При общем data ID два
индекса с native overrides должны задавать одинаковый итоговый JSON, иначе ревизия отвергается.
Body-only запись не претендует на backing data; её коэффициенты независимы даже у aliases.

`body_part_multipliers` содержит `head`, `torso`, `left_arm`, `right_arm`, `left_leg`, `right_leg`.
Каждое значение — конечное число 0..10; отсутствующее поле и reset дают 1.
Это относительные множители поверх прежнего урона/нативного headshot/legshot, без здоровья конечностей.
Метаданные строго удаляются перед native merge/validation, сравнением aliases и S2C:
они не являются полями `GunData`. Изменённые native данные и серверная карта коэффициентов
применяются одним подготовленным Plan; невалидная запись не изменяет ни одну карту.

SBW имеет ключи реальных registry items, например `item:superbwarfare/ak_47`.
LR использует точный data ID: `melee:lrtactical/dagger` для ножа и, например,
`throwable:lrtactical/smoke_grenade` для метательного оружия. Общие wrapper items
`item:lrtactical/melee` и `item:lrtactical/throwable` не принимаются: разные data variants
не делят один коэффициент. Существующая knife-only политика разрешает только dagger;
karambit и baseball_bat остаются запрещены и не представлены как рабочие настройки.
Эти три typed kinds допускают только `body_part_multipliers`, без native TaCZ полей.
Каталог добавляет `weaponKind` (`tacz`/`item`/`throwable`/`melee`), `sourceId`
(исходный index/item ID), `bodyPartsEnabled`. Body-only rows имеют `type=weaponKind`,
`data=id` и только поля частей тела. Список строится из поддержанных registry оружий
и серверных LR indices; произвольный/неоружейный/неподдержанный ID отвергается.
Настройка `config/blockfield/body-parts.json` содержит только `enabled` и включает лишь
шесть относительных множителей урона по частям тела; геометрия попаданий — всегда шесть
одинаковых vanilla inner-кубов, независимо от этого флага.
Источник снаряда захватывается при запуске и сохраняется через NBT; неизвестный источник даёт 1.
LR физический снаряд сохраняет ThrowableIndex исходного entity stack, без текущей руки стрелка.
Точная часть применяется только к native прямому попаданию; взрыв, огонь и splash остаются native.
Нож сохраняет серверную точную часть click-time цели до задержанного удара с прежними reach/LOS
проверками; при включённом gate unclassified player fallback не наносит обычный урон.

`GunBalanceSchema` описывает все 33 сериализованных поля закреплённого TaCZ bf7,
включая вложенные `bullet`, `recoil`, `reload`, `heat`, `charging`,
`fire_mode_adjust`, `exclusive_attachments` и `script_param`. Типы, enum и ID
проверяются строго; неизвестное поле, дробное целое, бесконечное число, нарушение
границ или обязательных связей отвергает **всю новую ревизию** до изменения данных.
В `applied.json` остаётся прежняя `revision`, причина находится в `errors`;
`gunBalance` содержит отдельно применённую ревизию, число изменённых data ID,
число обновлённых игроков и возможную `reloadError`.

Пределы каждого поля находятся в машинном `fields` каталога. Дополнительно:
не более 256 оружий/элементов массива/записей карты, глубина `script_param` до 8,
одна атомарная delta до 240000 символов JSON. Тайминги оружия обычно заданы в
секундах; `heat.cooling_delay` / `over_heat_time` — миллисекунды, время эффекта
ближнего боя — тики. Границы не обрезают значение: ошибку нужно исправить.
Исторические ошибки исходных reload/кривых урона сохраняются при изменении других
полей и при reset; новое отклонение от этих исходных полей проверяется строго.
Наличие custom script меняет смысл reload feed/cooldown, поэтому к нему не
применяется правило стандартной магазинной перезарядки.

| Производитель               | Потребитель                           | Контракт                                                                                                                                            |
| --------------------------- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Админка сайта               | `AdminSettings` → `AdminSettingsSync` | Атомарная запись `settings.json`, `schema: 1`, возрастающая `revision`, опрос 5 с                                                                   |
| `server/balance/GunBalance` | Админка сайта                         | Соседний `gun-balance.json`: `schema`, `revision`, рекурсивный `fields`, `guns` с `id`, `data`, `name`, `type`, `defaults`, `effective`, `warnings` |
| `AdminSettingsSync`         | Админка сайта                         | Соседний `applied.json`: фактическая ревизия, ошибки и статус `gunBalance`                                                                          |
| `GunBalance`                | `client/balance/GunBalanceClient`     | S2C ID `blockfield:gun_balance`; JSON полных данных только изменённых **data ID**                                                                   |
| TaCZ assets manager         | Новый подключившийся клиент           | Штатный полный `tacz:server_sync_gun_pack` уже содержит эффективные GunData                                                                         |

Каталог заполняет отсутствующие POJO-дефолты и сохраняет исходные допустимые записи
вроде `distance: "infinite"`. Сайт использует его как источник списка, полей и
дефолтов; копию ганпака в сайте хранить не нужно. Файлы находятся рядом с
`BLOCKFIELD_ADMIN_SETTINGS_FILE`; при отдельном контейнере сайта каталог/настройки
должны быть доступны через согласованный volume. Сайт сохраняет обычные секции
настроек при редактировании баланса.

Обновление строит и проверяет новые GunData, CommonGunIndex, сетевой JSON и кеш
оружия в руках до установки на серверном потоке. Затем отдельный S2C обновляет
клиентские gunData/gunIndex и attachment cache. ClientGunIndex, дисплеи, раннеры
анимаций и текущие draw/reload/bolt timestamps сохраняются; новое время действия
считается от прежнего старта. Магазин не пополняется и не обнуляется. Если удалён
выбранный fire mode, используется первый разрешённый; оружие из инвентаря также
нормализуется при штатном обновлении свойств.

Горячая ревизия отвергается, если онлайн есть клиент без получателя нового S2C:
нужно обновить его клиент. При штатном reload TaCZ создаёт другой assets manager;
сервер снимает новые **исходные** данные и повторяет overrides перед полным sync.
Обычный join не переснимает baseline. Сам штатный TaCZ reload может сбросить draw;
горячее применение не вызывает `reload`, `ClientIndexManager.reload`, `draw(EMPTY)`
или сброс модели. Lua/display/анимации/звуки не пересылаются этим пакетом.
В текущих bf7/bf9 Lua нет `modify_cached_property` hooks; добавление таких скриптов
требует отдельно проверить пересчёт кеша и сохранение состояния.

При изменении сайта, мода клиента/сервера или TaCZ совместно проверять: чтение
каталога и атомарное сохранение формы; валидный update онлайн; отклонение целой
ревизии с одной ошибкой; reset; повторный join; обычный reload с сохранением
настоящих defaults; изменение в ходе draw/reload/bolt без повторного старта.
Компиляция и `GunBalanceSchemaTest` не заменяют эту игровую приёмку.
