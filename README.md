# Blockfield

Командные бои с захватом точек и выбором класса в Minecraft 1.21.1 / Fabric.

Игровой код разделён на `common/`, `client/` и `server/`.
[Справочник разработчика](docs/AGENTS.md) описывает архитектуру, проверки и выпуск.
Связи репозиториев и сетевые контракты — в [общей карте архитектуры](https://github.com/Blockfield/blockfield-workspace/blob/main/docs/ARCHITECTURE.md).
Для игры используйте [лаунчер](https://github.com/Blockfield/Blockfield-Launcher)
или [готовую клиентскую сборку](https://github.com/Blockfield/blockfield-releases).

Нужны Just 1.57.0, Python 3.12+, Node 22 и JDK 21. Инструменты проверок ставятся в общий для всех checkout кеш `~/.cache/blockfield-quality` (`XDG_CACHE_HOME`), в CI — в `.cache/quality`.

```sh
just setup
just check # Python, shell, workflow: lint и формат
just format # применить формат Python, shell, YAML и Java (AOSP)
just java-format # Java: AOSP
just java-check
just test # Python unit-тесты и чистые Java-проверки
```

Версии инструментов закреплены в `scripts/toolchain.py`. CI на PR/main выполняет эти проверки без публикации;
релизный workflow проверяет их перед выпуском по тегу.
Команды одинаковы на Windows и Linux; запускатель выбирает `gradlew.bat` на Windows.
Исторические проверки контейнерного `dev/rig/isolated` остаются в Linux CI;
активный игровой dev находится в workspace.

Ветка `dev` обновляет отдельный homeserver-стенд: workflow `Dev server` проверяет
и собирает только серверный JAR, заменяет его в закреплённом образе пака и
вызывает отдельный `DOKPLOY_DEV_WEBHOOK`. Клиент, полный пак и production не
выпускаются. Зависимости Gradle кешируются между сборками; базовый digest в
`dev/Dockerfile.dev` обновляется явно при смене серверных зависимостей.
Подключение локальных клиентов и HMR описаны в
[workspace](https://github.com/Blockfield/blockfield-workspace/tree/main/infra/dev-server).

## Игровой стенд

Стенд, прямой запуск Minecraft и testbot принадлежат [blockfield-workspace](https://github.com/Blockfield/blockfield-workspace/blob/main/docs/DEVELOPING.md).
Из корня workspace на Linux и Windows, без Docker и отдельного dev-лаунчера:

```sh
just status
just build-mods blockfield-mod
just setup
just server start
just play --mod
just bot state
just scenario
```

Перед запуском подтвердите владельца процессов и свободную память. Агент работает только
со своим изолированным стендом; управление его Minecraft требует согласия владельца.

Compile-only зависимости скачиваются по закреплённым URL и sha256 (`pinnedMod` в `build.gradle`), JAR в Git не хранятся. Тег `vX.Y.Z` (= `mod_version`) выпускает GitHub Release с jar клиента; server jar собирается и публикуется из `blockfield-server` (mod-server/).

## История

С 2026-09-27 это независимый репозиторий `Blockfield/blockfield-mod`. Прежний архивный форк хранит полную исходную историю, PR и релизы до v1.31.0, на которые ссылаются старые пины.

История при переносе отфильтрована: удалены скриншоты проверок в `.impeccable/review/` и `dev/maps/review-*/` и JAR в `client/libs/`. Авторство, даты, сообщения, ветки и теги сохранены, хеши коммитов изменились. Коммиты до перехода на `pinnedMod` собираются только из архивного форка.

При переносе 2026-09-27 использовались команды ниже. Это историческое свидетельство,
а не инструкция для рабочего клона: принудительное переключение может потерять
локальные изменения. Текущая установка исходников описана в руководстве workspace.

```sh
git remote rename origin fork-archive
git remote add origin https://github.com/Blockfield/blockfield-mod.git
git fetch origin --tags --force
git switch -C main origin/main
```

При том переносе незапушенные ветки переносили `git rebase --onto origin/<новая база> <старая база> <ветка>`. Соответствие старых и новых коммитов сохранялось в `.migration/backups/history-rewrite-2026-09-27/blockfield-mod-commit-map.txt` рабочей папки.

Условия использования: [LICENSE](LICENSE).
