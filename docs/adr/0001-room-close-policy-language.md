# ADR 0001: Java для политики закрытия комнат

Дата: 2026-09-30. Решение: **NO-GO для Kotlin в этой политике**; сохранить Java-выделение и проверки.
Задача: [blockfield-server #43](https://github.com/Blockfield/blockfield-server/issues/43), реализация в `blockfield-mod`.

## Контекст и граница

Исходный `Room.java`, blob `3834016b0e3e7de1b15a47fa87d2c2dae0770cd7`, различает комнату без истории
участников, добровольно опустевший состав и отключившихся участников. Запросы к `CampaignAdmission`
не являются простыми getter: `hasLiveReservations(now)` и `canAbandon(now)` вызывают `advance(now)`,
удаляют истёкшие резервы и могут отменить подготовленный запуск. Предварительно собирать все ответы
в snapshot нельзя.

Выделен только выбор `Action` и следующего `idleSeconds`. `Room` остаётся владельцем счётчика,
members/everPopulated, состояния, generation, мира, режимов и всех побочных эффектов. Policy не
хранит состояния, не создаёт потоков, callback или таймеров. Minecraft-объекты в контракт не входят.
`Room.tickAll` по-прежнему вызывается из существующего цикла раз в 20 тиков; резерв проверяется по
одному текущему server tick, а не по номеру секундного вызова.

| Условие | Решение | Следующий счётчик |
|---|---|---|
| Состав пуст и никогда не был заполнен | WAIT до границы; CLOSE_UNUSED на/после границы | +1 |
| Последний участник добровольно вышел | CLOSE_EMPTY сразу при следующей проверке | Без изменения |
| Есть online участник | PROCEED, существующий тик режима | 0 |
| Все offline, активная admission и действующий резерв | WAIT, без canAbandon | 0 |
| Все offline, резерва нет и canAbandon=true | STOP_RESERVATIONS → Abandoned | Без изменения |
| Все offline, остальные случаи до таймаута | WAIT | +1 |
| Offline timeout в GAME/PREPARING | STOP_ABANDONED | +1 |
| Offline timeout в IDLE/VOTING/MATCH_END | STOP_CLOSED | +1 |

Lazy порядок: пустой состав → online → активная admission (не FINISHED) → server tick →
hasLiveReservations → только при false canAbandon → только при false offline timeout. Пустой состав
вообще не запрашивает online/admission; online не продвигает admission; FINISHED не запрашивает
резервы или tick. Настройки таймаутов также читаются только в соответствующей ветке.

`CLOSE_*` вызывает прежний `close()`, а `STOP_*` прежний `stop(status, "NONE", reason)` с прежними
причинами. Это сохраняет различие записи статистики: `close()` при незавершённом сеансе сохраняет
Stopped, `stop()` сначала сохраняет указанный статус. Эти методы, `add/remove`, `tickMode`, запуск,
generation и защита от старых callback не менялись. Второго источника состояния нет.

## Выполненный Kotlin-пилот

Сначала 7 characterization tests выполнились на **неизменённом** `Room.tick` (actual Loom
`:server:test`, BUILD SUCCESSFUL in 27s). Они проверяют idle/offline пороги до/на/после границы,
все пять GameState, вызов close против stop, reset при online/резерве, FINISHED и порядок запросов.
Затем выполнена Java-экстракция и Kotlin-реализация того же контракта.

Обе реализации используют те же enum Action, typed Decision и lazy Queries. В Kotlin нет `!!`,
корутин или собственной модели состояния. `@JvmStatic` и `@JvmRecord` сохраняют существующие
Java-вызовы `RoomClosePolicy.evaluate(...)`, `decision.action()` и `decision.idleSeconds()`.
Это проверено настоящими `javac`, `kotlinc`, `javap` и общим Java harness, без reflection bridge.
Kotlin 2.1.21 (исторический замер), JVM target 21: **80/80 checks**; эквивалентная Java: **80/80**. Harness
использует настоящий неизменённый CampaignAdmission, включая резерв до/на/после tick 150,
возврат online, FINISHED и fallback, когда admission ещё содержит online место, но Room не видит
игрока online.

Для mixed Gradle выбран KGP **2.2.20**: Gradle мода **8.14.2**, Java toolchain/target **21** остаются
прежними. [Матрица Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) включает Gradle
8.14 для этой линии; установленный KGP 2.1.20 имеет более узкую полностью проверенную матрицу.
Плагин добавлен только в отдельный эксперимент, не в production-сборку мода.

## Фактические измерения

Два отдельных scoped проекта с одинаковыми GameState, CampaignAdmission и Java harness. Java
проект использует только Java plugin; Kotlin проект дополнительно KGP 2.2.20. Таким образом Java
baseline не оплачивает конфигурацию неиспользуемого Kotlin plugin.

Все команды запускал координатор по одному: Gradle 8.14.2, `--no-daemon --max-workers=2
--no-build-cache`, heap 512m, metaspace 256m, Kotlin compiler in-process. Clean удаляет outputs
этого scoped проекта, но не общий dependency cache. Incremental меняет только тело политики
`idleSeconds + 1` → эквивалентное `1 + idleSeconds`; это **не** пустой up-to-date прогон. Каждый build
заново исполняет общий checkPolicy. По три замера каждого варианта, все **12/12 PASS**.

| Вариант | Clean, секунды (3 запуска) | Медиана clean | Incremental, секунды | Медиана incremental |
|---|---|---|---|---|
| Java | 7.983; 7.532; 8.037 | **7.983** | 7.233; 7.433; 7.334 | **7.334** |
| Kotlin | 31.646; 16.157; 14.504 | **16.157** | 15.355; 13.600; 11.342 | **13.600** |

Первый Kotlin clean включает первоначальную подготовку plugin/compiler; остальные запуски имеют
прогретые зависимости. Порядок Java → Kotlin, одинаковый no-daemon режим. Это малый scoped
эксперимент, а не измерение ускорения/замедления полного Minecraft/Loom build или server tick.
Чистую сборку всей рабочей копии ради замера не очищали. Производительность игры не измерялась.

До общего автоматического форматирования исходники policy: Java **43 строки / 1882 байта**,
Kotlin **44 строки / 1756 байт**. Сжатие строк не является основанием решения. Policy bytecode:
Java **4 класса / 6396 байт**, Kotlin **5 классов / 10623 байта**; оба major version **65** (Java 21).
Kotlin Decision добавляет data-class copy/component API, а enum/when — Kotlin runtime обращения;
смысл результатов и Java-адаптер остаются теми же.

## Runtime и упаковка

Реальный запуск Kotlin harness без stdlib завершился `NoClassDefFoundError:
kotlin/jvm/internal/Intrinsics`; с explicit stdlib — 80/80. Gradle добавляет stdlib к classpath,
но обычный собранный policy jar её **не включает**. Поэтому успешной компиляции недостаточно для
загрузки Fabric-мода. [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin)
поставляет runtime; при GO требовались бы явная server-only зависимость/manifest и проверка
упаковки/загрузки dedicated server. Случайное присутствие FLK через SBW не считается контрактом
нашего мода. В просмотренных паках FLK 1.13.13+kotlin.2.4.10 уже есть, но это не устраняет обязанность
объявить собственную зависимость при Kotlin-интеграции.

NO-GO: production содержит единственную **Java** policy; Kotlin prototype не включён в sourceSets,
client/server jars или fabric.mod.json. Build scripts, manifests и dependency pins не получают
Kotlin/FLK из этого пилота. Dedicated-server загрузка Kotlin-варианта не выполнялась, поскольку
он не интегрируется. Игровая производительность или исправление игрового бага не заявляются.

## Решение и проверки

Сохранить Java policy, 7 characterization tests, общий 80-check harness, JUnit adapter и
`dev/test-room-close-policy.py`. Полезное изменение — проверяемый контракт и сохранённый порядок
эффектов, оно не требует другого языка. Kotlin добавляет nullability checks и удобный data class,
но здесь входные значения уже примитивы/enum и отсутствует nullable дерево данных. Для этой
малой политики не показана польза, которая покрывает ещё один compiler/plugin/runtime и новую
матрицу упаковки. Более короткий файл или знакомство с Kotlin сами по себе не являются причиной GO.

Повторить пилот, если появится конкретный компонент с существенной nullable моделью или сложными
суммами состояний, для которого Kotlin продемонстрирует преимущество на одном и том же contract
harness и фактической production-сборке. Это не решение против Kotlin во всей организации.

Команды:

```sh
just test
just gradle --offline --no-daemon --max-workers=2 :server:test --tests '*RoomClosePolicy*' --tests '*CampaignAdmissionTest' :server:remapJar
just java-check
```

Targeted actual Loom test/remapJar — **PASS** после Java-адаптера. Последний полный quality build —
**PASS, 1m 4s**, 25 задач (21 выполнена, 4 up-to-date): spotlessCheck, lintJava, сборки client/common/server
и **380 server JUnit tests**, без failures/errors/skips. Итоговый server jar содержит четыре класса
Java policy, без `.kotlin_module` или bundled Kotlin runtime. `quality-build.log` сохранён рядом
с измерениями. Проверку на собственном игровом стенде координирует основной исполнитель; этот
build и prototype benchmark не подтверждают игровые сценарии. Координатор затем загрузил итоговые
client/server jars на собственном стенде и подключил клиент: свидетельство загрузки сохраняется
в `issues/yuushya/game/result.json`, server/client logs и `yuushya-bf2-loaded.png` рядом с этим audit.
Это проверка загрузки Java-варианта мода, а не воспроизведение порогов закрытия комнат в игре;
сценарии close/stop/reserve подтверждены characterization tests и общим harness.

## Отдельный общий baseline качества Java

По отдельному требованию владельца все Java-исходники common/client/server и dev testbot приведены
к Google Java Format **1.36.1**, AOSP (4 пробела), через Spotless **7.2.1**. Массовый форматирующий
diff относится к этому baseline, а не к Kotlin-пилоту; приведённые выше размеры двух policy сняты
до форматирования. Семантические изменения пилота ограничены policy, адаптером `Room` и проверками.

`lintJava` компилирует main и test с их объединённым compile classpath и `-proc:none`, завершая
сборку ошибкой при `divzero`, `empty`, `fallthrough` или `finally` warning (`-Werror`). Обычная
компиляция по-прежнему запускает Mixin annotation processor и показывает существующие API
deprecation/removal diagnostics; ради линтера mixin-код не менялся. Намеренный `1 / 0` в отдельной
временной probe действительно был отклонён этим gate. `check`, CI на PR/main и релизный workflow
проверяют форматирование и lint; форматирование применяется командой `just format`.
Ни Kotlin plugin, ни runtime dependency к production из этой работы не добавлены.

Исходники эксперимента, воспроизводящий `benchmark.py`, все 12 logs, `measurements.json`,
`artifact-inspection.json`, `cli-kotlin-results.json`, `Room-before.java`, characterization-before/after
logs хранятся в evidence координатора. Эксперимент остаётся
в evidence и не устанавливает ничего глобально. Статусы на момент передачи: локально реализовано
и проверено; PR/слияние, опубликовано, развёрнуто и проверено в игре — отдельные действия координатора.
