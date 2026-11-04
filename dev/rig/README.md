# Blockfield rig — headless-клиент для агентов

Настоящий Fabric-клиент 1.21.1 с полным паком (81 мод, как у игроков) в podman-контейнере
`bf-xvfb` (Xvfb; GL на GPU через VirtualGL, без render-ноды — llvmpipe). Внутри клиента мод `bftestbot` слушает `127.0.0.1:47777`:
строка команды → строка JSON. Клавиши уходят тем же путём, что и GLFW (`KeyMapping.click/set`),
поэтому миксины на управление проверяются честно. Для совсем сырого ввода есть xdotool.

Кэши (переживают перезагрузку, `/tmp` — нет): `~/.cache/bf-mc` (версии/библиотеки), `~/.cache/bf-test-client`
(пак клиента), `~/.cache/bf-natives`, `~/.cache/bf-rig` (run-client.sh, client.out),
`~/.cache/bf-test-server` (локальный сервер), `~/.cache/pmc` (venv portablemc).

Пак клиента берётся с `blockfield.pro` (или `BF_PACK_URL`); установщики скачиваются в кэш по хешам из соседнего `blockfield-client/launcher.json`. Серверный `pack` читает пины и конфиги из соседнего `blockfield-server`, проверяет контракт опубликованного клиента (`BF_CLIENT_MANIFEST`) и скачивает приватные зависимости через авторизованный `gh`. Архивный монолит не нужен. Миры сохраняются; команда `sync` обновляет их отдельно.

## Быстрый старт (Linux)

```sh
cd BlockField-Private-Project/dev/rig  # исторический blockfield-rig тоже поддерживается
./rig.sh install                  # один раз (и после потери кэшей); образ bf-xvfb собирается из Containerfile
just --justfile ../../justfile gradle --no-daemon --max-workers=2 -p dev/rig/testbot build # мод-бот, native JDK 21
export BF_RIG_OWNER=<имя-сессии>  # только если среда не передала ID сессии автоматически
./rig.sh start                    # клиент → прод по LAN 192.168.0.100:25565
./rig.sh start 127.0.0.1:25599    # или локальный сервер (см. ниже)
./rig.sh wait                     # ждёт входа в мир, печатает state (2–4 мин на этой машине)
./rig.sh bot state
./rig.sh stop
```

Один тяжёлый процесс за раз: клиент 4 ГБ heap, сервер 3 ГБ, gradle ещё 2 — все три вместе
роняли ноутбук (см. память). Клиент не запускать во время сборки gradle.

## Лёгкий режим, GPU и несколько клиентов

`rig.sh start` сам делает клиент лёгким: `lowmem` в options.txt (render 2, simulation 5, 20 FPS, без mipmap),
окно 640×360, heap 1100m, `MALLOC_ARENA_MAX=2`, два GC-потока. `BF_RIG_GFX=full` оставляет options.txt как есть
(видео, визуальные проверки), `BF_RES`/`BF_CLIENT_XMX` переопределяют размеры. `server.sh start` — heap 1600m.

GL клиента идёт на GPU: `rig.sh` пробрасывает `/dev/dri/renderD128`, `entry.sh` запускает клиент под
`vglrun -d egl` (VirtualGL рисует на карте и копирует кадр в Xvfb; xdotool, `import` и скриншоты работают как раньше).
В логе клиента первая строка — `rig: GPU через VirtualGL` или `rig: программный рендер llvmpipe`.
`BF_RIG_GPU=0` — принудительно llvmpipe (ограничен двумя потоками). После изменения `Containerfile`
пересоберите образ: `podman build -t bf-xvfb -f Containerfile .` (старый образ без VirtualGL тихо даёт llvmpipe).

```sh
BF_RIG_CLIENT=2 ./rig.sh clone                   # RigPeer в ~/.cache/bf-test-client2; N=3.. — RigPeerN
./server.sh start
./rig.sh start 127.0.0.1:25599
BF_RIG_CLIENT=2 ./rig.sh start 127.0.0.1:25599  # бот клиента N на 47776+N, экран Xvfb :100-N
BF_RIG_CLIENT=2 ./rig.sh bot state
```

Клиент N — отдельный контейнер `bf-clientN` с отдельной записью владельца; JDWP (hotswap клиента) есть только у
первого. `clone` пересоздаёт каталог клиента из первого, поэтому после `rig.sh pack`/`mod` первого повторите его
(устаревший пак сервер отклоняет: «Твой пак устарел»). `rig.sh pack` и `server.sh pack` удаляют jar, которых нет
в манифесте packwiz: старая сборка форка рядом с переименованной загружалась вместо неё (ParCool bf1 вместо bf5). Локальная подмена `rig.sh mod`/`server.sh mod` тоже уходит — после `pack` повторите `mod`.

Замер 2026-09-23 (Ryzen 5 7535U, Radeon 680M, 13.6 ГБ, zram): клиент на GPU ~0.2 ядра в простое и ~0.7 в движении
против ~5 ядер у клиента с дефолтами пака на llvmpipe; память клиента ~2.1–2.3 ГБ (RSS+swap), сервера ~1.5 ГБ.
Сервер и 5 клиентов работали при открытом браузере: 20 TPS (MSPT 4–6), у всех 20 FPS, давление памяти PSI full
7–16 %, свободно ~1 ГБ — это потолок машины; шестой не запускать. Больше игроков ограничивает и `max-players=5`
в server.properties стенда. Gradle при нескольких клиентах не запускать.

## Стресс-тест сервера (`./stress.py`)

Фейковые игроки Carpet на копии риг-сервера в `/var/tmp/bf-stress-server` (порт 25610, RCON 25576): рабочий
стенд и его мир не трогаются. У ботов нет клиента, поэтому меню и стрельбу за них делает сервер: `/bf action
<игроки> <действие>` (join/class/spawn из экранов) и `/bf bot fire|stop|stats` (выстрелы TACZ тем же серверным путём).

```sh
./stress.py server init                               # один раз: копия стенда + Carpet + длинный баланс кампании
./stress.py server start ../../server/build/libs/blockfield-server-*.jar
./stress.py hub 10,25,50                              # боты бродят по хабу, строка метрик на ступень
./stress.py combat --guns --jfr /var/tmp/combat.jfr   # 32 в кампании (отряды бегут на точки) + 18 в deathmatch
./stress.py jfr /var/tmp/combat.jfr                   # куда уходит тик сервера, имена Mojang
./stress.py server stop
```

Строка метрик: средний/P50/P95/P99 тика по `/tick query`, ядра сервера, убийства и захваты из лога, heap, память
хоста. Carpet убирает погибшего бота с сервера, драйвер заводит его заново — 100 перезаходов за 2 минуты боя
дороже, чем у живых игроков, учитывайте в `processUnloads`. Строка профиля `carpet setupTracking` — сам Carpet.

Замер 2026-09-23 (50 ботов, 2 ГБ heap): хаб 61.8 → 7.0 мс после фикса рамок с картами (#119); бой со
стрельбой (~210 выстрелов/с, ~50 убийств в минуту) — 18.5 мс в среднем, P99 110 мс.

### Регистрация попаданий и пинг (`./hitreg.py`)

Клиент стенда подключается к стресс-серверу через `delay-proxy.py <мс в одну сторону> 25610` (порт 25600) и стреляет
по Carpet-боту, который спринтует поперёк на 20 блоках по барьерной платформе. `hitreg.py setup <измерение комнаты>`
ставит платформу и обоих игроков, `hitreg.py run [--mode track|flick]` — 40 выстрелов: `track` ведёт прицел за
видимой позицией цели каждый тик (testbot `track <имя>`) и стреляет `tacz shoot`, `flick` — `aimshoot`, поворот и
выстрел в одном кадре. Прицел ADS: сначала `rig.sh x mousedown 3`. Пинг, который видит сервер, — поле `ping` в
`bot state`. Бот (`lookat`, `track`, `aimshoot`) ставит поворот игрока, а выстрел добавляет к нему наклон Camera Overhaul
(в движении до ~1.7°): для чистых замеров меткости стой на месте или выключи мод в `config/cameraoverhaul.toml` клиента стенда.
Замер 2026-09-23: `ServerHitboxOffset` 3.0 → 2.0 поднял попадания с 15/40/47 % до 97/95/90 %
при RTT 0/100/200 мс (modpack#51).

## Владение процессами

`rig.sh start` и `server.sh start` сначала проверяют `ss -ltnp`, команду и `/proc/<pid>/cwd`,
затем пробуют сервис. Подходящий экземпляр переиспользуется, включая процесс другой сессии;
занятый порт, неидентифицированный контейнер или другая цель подключения останавливают запуск.
Старт выполняется только через `~/.general/scripts/agent-process`, без fallback на `nohup`; если его на машине нет,
берётся копия `dev/rig/agent-process` (реестр у обеих общий — `$XDG_RUNTIME_DIR/ai-agent-processes`).
ID владельца берётся из `AI_AGENT_SESSION_ID`, `CODEX_THREAD_ID` и остальных стандартных переменных
сессии. `BF_RIG_OWNER` используется только при их отсутствии; выдуманного общего ID по дате нет.

`stop` сначала проверяет ровно одну живую запись registry: владелец текущей сессии, PID, время рождения,
команда, рабочий каталог и метка `AI_AGENT_PROCESS_ID` должны совпасть. Только затем вызывается
`agent-process stop <точный-id>`; прямых `podman stop`, RCON `stop`, kill по порту или имени нет.
Незарегистрированный процесс и процесс другой сессии считаются чужими; их остановка требует явного
разрешения пользователя и отдельного вызова helper с этим разрешением. Скрипты не принимают `--force`.
`pack`, `mod` и серверный `sync` запрещены при работающем экземпляре. Сначала остановите свою сторону
стенда, затем замените JAR через `mod` и запустите её снова. Реестр можно посмотреть через
`~/.general/scripts/agent-process list`.

Проверки защит без запуска Minecraft из корня мода: `just test`.
`paths.sh` поддерживает `dev/rig` внутри репозитория мода и прежний соседний `blockfield-rig`;
`jdbtrace.py` и `install-client.py` используют кэши домашнего каталога и от относительного расположения не зависят.

## Команды бота (`./rig.sh bot ...`)

| команда | что делает |
|---|---|
| `bfmenu` / `bfui` | модель серверного меню внутри планшета / геометрия меню, класса или высадки для UI-проверок |
| `uiclick <x> <y>` / `uikey <GLFW key> [modifiers]` | события открытого Screen в GUI-координатах |
| `uiscale <1..4>` | сменить GUI scale в памяти клиента |
| `uipreview <base64 JSON>` | UI-фикстура `{kind: menu|class|spawn, data: object, ui?: object}`; только на локальном сервере `127.0.0.1:25599`, не проверяет серверную логику |
| `state` | экран, сервер, позиция, yaw/pitch, camYaw/camPitch (куда смотрит камера после модов камеры), coYaw/coPitch (наклон Camera Overhaul — его выстрел прибавляет к повороту), hp, режим, предмет в руках, цель под прицелом (блок + blockstate) |
| `frames start\|get` | построчно на каждый кадр: дроны SBW и строка `cam <камера−игрок по pitch> <по yaw> <pitch игрока> <наклон Camera Overhaul по pitch> <по yaw>` |
| `keys` | все привязки: клавиша, isDown, clickCount |
| `press <key> [ticks]` | нажатие как от GLFW: `key.mouse.right`, `key.keyboard.e` … (по умолчанию держит 2 тика) |
| `use` / `attack` | `press key.mouse.right` / `key.mouse.left` |
| `hold <mapping> <ticks>` | держать привязку по имени (`key.forward`, `key.jump`, `key.sprint`) |
| `look <yaw> <pitch>`, `lookat <x> <y> <z>` | повернуть камеру |
| `block <x> <y> <z>` | blockstate на клиенте |
| `slot <n>` | слот хотбара |
| `chat <текст>` | сообщение или команда (`chat /bf menu`) — без граблей с клавишей `t` (она у TACZ) |
| `messages [n]`, `clearmessages` | последние сообщения чата/action bar |
| `screenshot <имя>` | PNG в `~/.cache/bf-test-client/screenshots`; `./rig.sh shot <имя>` сразу даёт jpg |
| `closescreen`, `respawn`, `disconnect`, `connect <host:port>` | экраны и переподключение без рестарта клиента |
| `bfaction <действие>` | действие меню Blockfield как от кнопки экрана: `play:room`, `vote:<map>`, `class:assault:0`, `spawn:BASE`, `leave`, `kill` |
| `tacz shoot\|reload\|aim <bool>` | оператор оружия TACZ напрямую; `shoot` возвращает ShootResult (`SUCCESS`, `IS_SPRINTING`, `NOT_GUN`…) — видно, почему не стреляет |
| `entities [radius]` | видимые сущности: `id`, `uuid`, тип, имя, tickPos/renderPos/eyeY — цели для `forge` |
| `uuid <имя>` | серверный UUID и entity id сущности по имени (другой клиент рига, техника) |
| `forge <kind> …` | тест-онли: собрать C2S-пакет мода рефлексией и отправить через `ClientPlayNetworking.send` — проверка серверных сетевых фиксов. Только на стенде, в прод не идёт (см. ниже) |
| `attempt grab\|gap\|crawl <yaw>`, `attempt get` | ход ParCool по тикам настоящими привязками (`key.parcool.ClingToCliff`, `key.parcool.Crawl`): grab — встать у края, прыжок, зацеп, подъём; gap — отход до 3 блоков, разбег, прыжок с края, зацеп; crawl — ползком вперёд. `get`: зацеп/подъём (тик), тики ползком, maxY, все блоки, на которых стоял |
| `soundlog start\|get` | журнал звуков, которые клиент начал играть (тик, id, категория, relative = 2D), с момента `start`; ловит и короткие звуки, которые пропускает снимок `sounds`. Категория с громкостью 0 не попадает в журнал: для тихой записи `opt sound master 0.01` |

Что бот НЕ умеет: TACZ читает стрельбу/прицел/клавишу взаимодействия с сырого ввода GLFW, а не через
KeyMapping, поэтому `use`/`attack`/`press key.keyboard.e` для оружия не работают. Для них — `tacz …`
или сырой ввод: `./rig.sh x key e`, `./rig.sh x click 1` (клавиши через xdotool доходят, мышь — не всегда).
`state.target` — блок под прицелом (нет поля = ничего в радиусе); `lookat` целится точно, но помните,
что x=-787.5 лежит в блоке -788.

Сырой ввод: `./rig.sh x key slash`, `./rig.sh x click 3`, `./rig.sh x mousemove_relative -- 20 0`.
Окно фокусить: `./rig.sh x search --name Minecraft` → `windowfocus <id>`.

## Поддельные C2S-пакеты (`forge`, только стенд)

`forge` собирает пакет самого мода рефлексией (`Class.forName` по классам форка, конструктор по типам
аргументов: `UUID`, `int`, `String`, `List`, `Vec3`/`double`; `null` подходит любому объектному полю, бокс —
примитиву) и шлёт его как обычный клиент через `ClientPlayNetworking.send`. Кодек берётся из регистрации
самого мода, поэтому отдельно ничего регистрировать не нужно. Нужен, чтобы проверить, что сервер игнорирует
подставленную клиентом личность (parcool #2, superbwarfare #11/#13). При провале разрешения конструктора
ошибка называет класс и все его конструкторы. В прод-jar мод бота не входит — он живёт только в клиенте рига.

| команда | что шлёт |
|---|---|
| `forge use_block <x> <y> <z> [face=up]` | ванильный `ServerboundUseItemOnPacket` прямо в соединение, мимо клиентского `UseBlockCallback` — как изменённый клиент (проверка серверного запрета рычагов и люков) |
| `forge parcool_stamina <uuid> <value> [max=2000] [exhausted=false]` | `StaminaPayload(uuid, ReadonlyStamina(exhausted,value,max))` с чужим playerID |
| `forge parcool_action <uuid>` | `ActionStatePayload(uuid, [])` — пустой список действий, чужой playerID |
| `forge sbw_melee <uuid>[,<uuid>…]` | `MeleeAttackMessage(List<UUID>)` |
| `forge sbw_vehicle_fire <shooterVehicleId\|-> <weaponName\|-> [x y z]` | `VehicleFireMessage(null, targetPos, weaponName, shooterVehicleId)`; `-` = null, targetPos только с x y z (имя оружия сервер использует лишь при ударе по точке) |
| `forge sbw_lunge 0 <uuid>` / `forge sbw_lunge 1 <x> <y> <z>` | `LungeMineAttackMessage(type, uuid, Vec3)` (для типа 0 pos=ZERO, для типа 1 uuid=0) |
| `forge sbw_skin <entityId> <skinId>` | `SetVehicleSkinMessage(entityId, skinId)` |
| `forge sbw_laser <uuid> <damage> [headshot=false]` | bf19: `LaserShootMessage(damage, uuid, headshot)`; bf20: класса нет, шлётся сырой пакет на канал `superbwarfare:laser_shoot` (double+uuid+bool). Сервер bf20 канал не знает — Fabric отбрасывает пакет молча, дисконнекта нет |

Цели берутся из `entities` или `uuid <имя>`. Старые клиенты 2.58.0 (bf6/bf19): сигнатуры пакетов те же, так что все команды собираются и против
сервера bf6/bf19 (без исправлений) — это негативный контроль: `parcool_*` и `sbw_melee` там проходят; на bf19
`sbw_laser` строит настоящий `LaserShootMessage`, а команды #13 (`sbw_vehicle_fire`/`sbw_lunge`/`sbw_skin`)
против bf19-сервера бессмысленны — там ещё нет проверок.

## Зацеп и ползание ParCool на карте (`./parkour-test.py`)

Проверяет закрытие карт барьерами вживую. Попытки берутся из модели: `parcool-seal.py --attempts` в паке
(`tools/codre-maps/rebuild/run.sh` пишет `sources/codre/seal/<id>-attempts.json`) — передний край карты как есть,
ход `grab`/`gap`/`crawl` со стартовой клетки к цели. Скрипт открывает комнату deathmatch с картой (шаблон из
`~/.cache/bf-test-server/maps/<id>`, сервер не перезапускать — комната берёт шаблон при открытии), ставит игрока на
старт через RCON и отдаёт боту `attempt`. Итог хода: `reached` (стоял на цели), `near` (рядом на её высоте), `no`.
Главное — `roof`: игрок постоял в колонке, которая на карте без барьеров была крышей (`roof` в attempts.json), на её
высоте или выше. С `--reach <attempts.json закрытой карты>` старты, отрезанные барьерами от спавнов, не считаются
(игрока туда ставит только RCON). `--judge <результаты>` пересчитывает итог без игры.

```sh
./server.sh start && ./rig.sh start 127.0.0.1:25599 && ./rig.sh wait
./parkour-test.py …/sources/codre/seal/train-attempts.json --kinds grab,crawl --out /tmp/train.json
./parkour-test.py …/train-attempts.json --kinds gap --limit 60           # выборка, ~4.5 с на попытку
./parkour-test.py …/train-attempts.json --only reached --from /tmp/train.json   # повторить дошедшие
./parkour-test.py …/train-attempts.json --reach …/train-sealed.json --judge /tmp/train.json
```

Карта без барьеров (`sources/codre-out/unsealed/<id>`) — положительный контроль: на Train 2026-09-27 дошли
5–6 из 10 grab и 9 из 16 gap; остальное — запас модели (тонкий люк у дальнего края клетки, стена 3.06 при пределе
3.14). Закрытая карта: `reached`/`near` = живая лазейка, смотреть глазами.

## Задержка сети и клавиши, разделяемые модами

`bot hold key.sprint` опускает только эту привязку: ParCool `key.parcool.FastRun` на том же Shift не сработает,
поэтому всё, что зависит от чужих биндов на общей клавише, проверяй сырым вводом (`./rig.sh x keydown shift`).
Баги, зависящие от пинга, воспроизводи через `delay-proxy.py <мс в одну сторону>` (слушает 25600 → 25599,
запускать через `agent-process start --owner $CLAUDE_CODE_SESSION_ID --name bf-delay-proxy`) и `bot connect 127.0.0.1:25600`.

## Горячая замена кода (без рестарта)

Сервер (`server.sh start`) и клиент (`entry.sh`) стартуют с JDWP: 127.0.0.1:5005 и :5006.
`./hotswap.sh server` / `./hotswap.sh client` — собрать remapJar, найти изменившиеся классы
относительно того, что сейчас в JVM, и подменить их через jdb (5–10 с от правки до живого кода).
Ограничение обычной JVM: только тела методов. Новый класс/поле/метод/лямбда → jdb печатает
schema change / not implemented → `server.sh stop && server.sh mod && server.sh start` (~40 с)
или для клиента `rig.sh stop && rig.sh mod && rig.sh start` (~3 мин). Проверка, что код живой: `bf version`
показывает версию и коммит (`-dirty` = незакоммиченные правки), а hotswap печатает список классов.

Отладка без правок: `./jdbtrace.py server 'pkg.Class.method[,…]' [сек]` — стек при каждом
попадании; `JDB_ON_HIT='eval expr;locals'` — вычислить выражения в точке (имена локалов и
`method_XXXX` intermediary для ваниллы; маппинг в ~/.gradle/caches/fabric-loom/1.21.1/*/mappings.tiny).
Так найден дубль жилета (Accessories выбрасывал его при входе) — см. docs/AGENTS.md мода.

Карты: `tools/nbt_find.py <worlddir> '<regex id>'` — координаты блоков по id, `palette` — сводка,
`BE=<regex> … be` — блок-энтити. Эталоны карт: `server.sh sync` (rsync с прода, в т.ч. zaliznichne/berdichev).

## Подмена модов

- `./rig.sh mod [jar]` — положить локально собранный `blockfield-client` (по умолчанию из
  `BlockField-Private-Project/client/build/libs`). `./rig.sh pack` возвращает опубликованную версию.
- `./server.sh mod [jar]` — то же для сервера.

`mod` разрешён только при остановленной стороне стенда: замена открытого JAR ломает загрузку классов JVM.
Имя устанавливаемого JAR сохраняется. После успешного копирования удаляются остальные runtime JAR
`blockfield-<сторона>-*.jar`, включая старое ручное имя `1.0.0`; моды другой стороны не затрагиваются.
Hotswap выбирает единственный установленный JAR своей стороны, игнорирует sources/dev и отказывается
работать при дубликатах. Перед первым обновлением пака на старом стенде выполните `stop`, затем
`mod <актуальный-jar>`, чтобы убрать старое ручное имя; `pack` не удаляет посторонние файлы самовольно.
Hotswap проверяет ожидаемый процесс и его порты перед сборкой и перед подключением отладчика.

## Локальный сервер (`./server.sh`)

`pack` (мод-сет side=server из локального `blockfield-modpack` без FabricProxy-Lite и CrossStitch — с ними
прямое подключение отбивается; маркер версии пака — опубликованный, как у `rig.sh pack`), `sync` (мир лобби, blockfield/, blockfield-published/ — опубликованный JSON карт под те же эталоны, config/, эталоны карт с homeserver), `start`,
`stop`, `rcon <cmd>` (25575, пароль `leaks`), `log`. Порт 25599. Команды от лица игрока:
`rcon "execute as Player115 run bf play room"`.

## Типовой сценарий: проверить двойной клик

```sh
./rig.sh bot clearmessages; ./rig.sh bot use; sleep 1; ./rig.sh bot messages 5
```
Компас в руках открывает меню BLOCKFIELD: две строки за один `use` = баг.
Дверь: `lookat` на дверь, `use`, `block x y z` → `open=true/false`.

## Автоматические проверки

Регрессия первого выстрела TACZ: при спавне несколько ответов синхронизации могут прийти на Netty
до обработки первого на сервере. `SyncBaseTimestampReceiveTime` хранит FIFO на соединение;
одна отметка на соединение теряет время второго ответа. Трасса `record` / `take` подтвердила
один и тот же listener и порядок `record, record, take, take`.
Для воспроизведения задержки ставьте `stop thread in com.tacz.guns.network.message.ClientMessageSyncBaseTimestamp.handle`,
выдерживайте 1.5 с на каждом попадании и продолжайте через `resume`. Обычный `stop in` останавливает
всю JVM, включая Netty, и для этой проверки не подходит.
Проверено: Benelli при первом спавне и после kill → class → spawn списывает 8 → 5 за три выстрела;
AK-47 после спавна списывает первый патрон 30 → 29. `shot rejected` отсутствует.

Из корня мода: `just test`. Эти тесты также идут в CI.
Живой стенд из корня мода: `just rig-check shot|armor|lobby` (один сценарий за вызов).
`shot`: заряженный ствол в руках, проверяет реальное списание серверного патрона.
`armor`: надетая броня с >=5 пунктами плиты и >5 HP, проверяет взрыв без атакующего.
`lobby`: выходит из текущего матча, проверяет синхронный слот планшета, чистый инвентарь и один пустой жилет.
Скрипт отказывается менять игру, если клиент подключён не к `127.0.0.1:25599`.

Дополнительный сценарий очистки курсора: в матче открыть жилет, взять патроны на курсор
и завершить раунд через RCON. После возврата в лобби открыть/закрыть жилет и инвентарь:
патроны не должны возвращаться, жилет пуст, в инвентаре только планшет. Сервер должен
закрыть контейнер до очистки снаряжения, включая предмет на курсоре.
