# Изолированные рабочие копии мастерской

Реализовано в рабочем коде, ещё не выпущено и не проверено в игре.

В профиле `workshop` задайте `BLOCKFIELD_WORKSHOP_DRAFTS_DIR` — каталог SSD
с рабочими копиями внутри контейнера. При отсутствии этой переменной используется
`BF_MAP_ACTIVE_DIR/drafts`, если задан `BF_MAP_ACTIVE_DIR`. Без обеих переменных
сохраняется прежний путь `world/dimensions/blockfield_workshop/<mapId>`.

Контроллер до запуска мастерской создаёт копии обычным `checkout`, без hardlink:

```text
<drafts-root>/<session-id>/<mapId>/
  map.json
  manifest.json
  world/{region,entities,poi}/
  world/blockfield-base.json
```

`session-id` из `session.json` одновременно является `workingCopyId`. Новая
сессия получает новый UUID и каталог; resume использует прежний UUID и сохраняет
правки. Мод не создаёт отсутствующую копию из образа и не заменяет существующую.
Все виды карт (`arena`, `lobby`, `limbo`) используют Anvil, включая внешние
чанки `c.X.Z.mcc`. Immutable manifest описывает исходную версию копии; после
редактирования он не является контрольной суммой её текущего содержимого.

Перед первым открытием мод требует согласованные файлы контроллера:

- `session.json`: числовой `schema:1`, строковый канонический UUID `id`, `maps`
  из допустимых ID либо `null` для всех доступных копий.
- `profile.json` (`BLOCKFIELD_BACKEND_PROFILE_FILE`, по умолчанию
  `/backend-lock/profile.json`): числовой `schema:1`, `profile:"workshop"`,
  тот же строковый `session` и канонический UUID `generation`.

UUID закрепляется на срок жизни JVM. Изменившийся `session.json` закрывает
доступ к картам до смены backend, а последние сохранения старого мира продолжают
идти в прежнюю копию. Не меняйте UUID работающей сессии для переключения карт.

`WorkshopDimensionPathMixin` перенаправляет только
`blockfield_workshop:<mapId>`; платформа `blockfield_workshop:hub`, game overworld
и измерения матчей не затрагиваются. Путь не зависит от symlink/junction ОС.
Session/map/world/config и Anvil-каталоги не могут быть symlink; traversal,
невалидные UUID/ID и зарезервированный `hub` отвергаются.

`MapConfigManager` в этом профиле читает `map.json` выбранных копий.
`WorkshopMarkup` пишет и отменяет правки в той же копии, не в active bundle
и не в общей папке опубликованных карт. Короткая история undo хранится рядом
с её JSON; постоянная история world+JSON принадлежит CAS контроллера.
`WorkshopAccess.submit` передаёт сайту `mapId` и точный `workingCopyId`.
Сайт проверяет ID текущей сессии и права, затем передаёт оба поля в inbox
`candidate`; контроллер сохраняет именно эту копию после flush/stop.

Mounts: game получает active heads/bundles read-only; workshop получает drafts
read-write и admin/session/profile read-only. Archive blobs никогда не
монтируются writable в Minecraft. Конкретный host путь задаётся независимо
от container пути, но оба указывают на одни физические drafts.

Начальные источники требуют отдельного импорта: lobby — region/entities/poi остановленного
игрового overworld; `workshopctl import-map` записывает CAS, а `activate-map` отдельно
проверяет exact `expectedHead`. Метаданные lobby содержат реальные bounds и
`mode_data.lobby.spawn`, сверяемый с Hub (`BLOCKFIELD_LOBBY_SPAWN`).

Для существующего `proxy/lobby/lobby.schem` добавлена доверенная bootstrap-команда
`bf workshop import-schematic <новый-UUID> lobby.schem`. Только console/RCON с permission 4,
без player entity, в профиле workshop с WorldEdit; input-каталог явно задаётся
`BLOCKFIELD_WORKSHOP_IMPORT_DIR`. Она проверяет ограниченный снимок исходного Sponge v2
(Minecraft 1.21.1, нулевое смещение, блоки и block entities), вставляет через WorldEdit
в новый void-мир `blockfield_workshop_import:<UUID>`, flush/save и сохраняет JSON результата
с исходным SHA256. Повторный UUID, path escape и неподдерживаемые данные отвергаются.
Право записи действует только синхронно в точном мире и объёме импорта; доступ игроков
и обычные WorldEdit ACL не расширяются. Готовые и незавершённые job-каталоги не удаляются.
После остановки изолированного сервера его Anvil-output передаётся `import-map` с kind=limbo.

В game-профиле с `BF_MAP_ACTIVE_DIR` хаб захватывает `lobby/head.json` один раз при старте
сервера. `Hub.start` до readiness открывает private persistent Fantasy-мир
`blockfield_lobby:<UUID>` из обычных копий `region/entities/poi/.mcc` той же `MapVersion`,
с `noSave=true`. Точка `mode_data.lobby.spawn {x,y,z,yaw?,pitch?}` содержит точные мировые
координаты; проверяются числовые типы, конечность, bounds и высота мира. Геометрия и spawn
не меняются вслед за новым head; новая версия lobby применяется при следующем старте game.
Без `BF_MAP_ACTIVE_DIR` остаются прежние overworld и `BLOCKFIELD_LOBBY_SPAWN`.

Телепорт, очередь комнат, регенерация, PvP и вход на полигон используют точный мир хаба.
Защита от пуль, взрывов, разбивания стекла и нотных блоков действует в canonical lobby
и в исходном overworld: ванильный вход может временно использовать его до переноса игрока.
Исходные `level.dat`, playerdata и прочие данные overworld не заменяются. После полного
закрытия миров удаляется только созданная этим запуском копия с точным UUID/marker;
старые либо изменённые копии оставляются для проверки при остановленном backend.

WorldEdit ACL сравнивает реальные storage paths и мир игрока. Его Fabric `World.id()`
совпадает у разных Fantasy-миров с типом OVERWORLD и не является их идентификатором.

Доверенная команда `bf workshop export-limbo <UUID>` читает только подготовленную
контроллером задачу `BLOCKFIELD_WORKSHOP_EXPORT_DIR/<UUID>/request.json`.
Требуются console/RCON permission 4 без player entity, профиль workshop, WorldEdit
и read-only `BF_MAP_ACTIVE_DIR`. Запрос закрепляет `versionDigest`, `configSha256`,
размеры и spawn; источник — проверенный immutable bundle `limbo/versions/<digest>`.
`mode_data.limbo` хранит целые `width,height,length` (1–256, объём ≤2 млн),
`spawn:{x,y,z,yaw?,pitch?}` внутри объёма и bounds от нуля до width/length.

`WorkshopLimboExport` делает обычную private SSD-копию Anvil в свежем
`blockfield_workshop_export:<UUID>` с `noSave=true`, используя native Minecraft
`RegionFile`, включая внешние `.mcc`. Сущности, биомы кроме void и блоки вне
объявленного объёма отклоняются: текущий Limbo не умеет их переносить.
WorldEdit пишет Sponge v2 с нулевым origin; затем проверяются форма, palette/block
data, каждый block state после readback и полный NBT всех block entities.
`lobby.schem` и атомарный `result.json` остаются в отдельном HDD job-каталоге.
Мод не пишет в archive blobs/active heads и не управляет прокси. После полного
server stop удаляется только созданная этим запуском private copy с точным marker;
неизвестные копии сохраняются. Готовые и частичные HDD jobs также сохраняются.

Controller проверяет результат и создаёт immutable производный artifact, связанный
с exact version/config SHA256. Только при доказанно остановленном configured proxy
он пишет selection и head с explicit expectedHead. Proxy читает ту же версию при
следующем запуске; эта операция не перезапускает его. Развёртывание reader/producer
и игровая приёмка остаются отдельными шагами. Наличие kind в каталоге не означает
уже выполненную runtime-проверку.

Проверки: `WorkshopSessionTest` проверяет согласование profile/session,
изоляцию путей, запрет alias к архивам и область перенаправления storage.
Игровая приёмка отдельно: правки двух сессий не смешиваются, resume сохраняет
геометрию и JSON, snapshot совпадает с сохранённой копией, а game/архив остаются
неизменны. Требуются также запуск и сохранение Anvil с внешним `.mcc`.
`LobbyWorldTest` проверяет точный spawn, разные inode копий, сохранность исходного
overworld, изоляцию версий и ограничение cleanup текущим запуском; игровой сценарий
отдельно проверяет вход, выход из матча/полигона, ожидание очереди, PvP/защиту блоков
и применение нового lobby head только после перезапуска.
