# Blockfield mod: правила агента

Прочитайте этот файл целиком. Подробности открывайте **по задаче**, не весь справочник:
[DEVELOPMENT-REFERENCE](DEVELOPMENT-REFERENCE.md).

## Код и контракты

- Minecraft 1.21.1/Fabric. `common/` копируется в два JAR с id `blockfield`;
  `client/` — UI/рендер/миксины, `server/` — авторитетная игровая логика.
  Два JAR никогда не устанавливаются вместе. Блоки карт принадлежат `blockfield-decor`;
  зависимость на decor защищает мир от потери блоков.
- Меняйте исходники в этом submodule; паки, форки и dev-инструменты — в их repo.
  Межрепозиторные связи: [карта workspace](https://github.com/Blockfield/blockfield-workspace/blob/main/docs/ARCHITECTURE.md).
  Изменили связь — обновите карту и контракт владельца.
- Проследите callers/данные, переиспользуйте helper/stdlib. Не чините симптомы во всех callers.
  Комментарий нужен для неочевидного ограничения; `ponytail:` — потолок и условие пересмотра.
- Не заменяйте интеграцию TaCZ Tweaks клиентскими хаками спринта/стрельбы.
  Не правьте версии `fabric.mod.json` вручную: их подставляет Gradle.
  Справочник: архитектура (§2), ловушки (§3); тема оружия — также [KNIFE_COMBAT](KNIFE_COMBAT.md),
  Ranked — [campaign-admission](review/campaign-admission.md),
  полигон — [RANGE](training/RANGE.md), статистика — [PRODUCER](statistics/PRODUCER.md),
  настройки — [admin-settings](admin-settings.md),
  control plane — [control-plane](control-plane.md).

## Стенд и безопасность

- Стенд принадлежит workspace. До запуска прочитайте его README, AGENTS.md и
  [DEVELOPING](https://github.com/Blockfield/blockfield-workspace/blob/main/docs/DEVELOPING.md).
  Перед игрой/Gradle — `just status`; Linux также `free -g`.
  Память: server 3 ГБ, client 4 ГБ, Gradle 2 ГБ; стенд один,
  Gradle — до двух одновременно с `--no-daemon --max-workers=2`
  (только пока после старта остаётся ≥ 3 ГБ MemAvailable);
  стенд и замеры времени — без параллельного Gradle.
  Configuration cache включён (`gradle.properties`): повторный прогон
  без изменений пропускает конфигурацию; `just lint`, `just format-check`,
  `just check` Gradle не запускают.
- Проверяйте порт, команду, cwd, PID/birth identity и владельца. Занятый порт не разрешает новый стенд на другом.
  Не подключайте бота, не меняйте мир/options/датапаки/JAR чужой сессии; ждите освобождения.
  Незарегистрированные процессы — владельца: без явного согласия не сигналить/перезапускать/заменять.
- Запуск/остановка: `just server start/stop`, `just play`/`just play --stop` из workspace.
  Стоп разрешён только своему владельцу с совпавшей native identity; не используйте kill по имени/порту,
  pkill/killall/fuser. Повторно используемые и чужие процессы оставляйте работающими.
- Computer use требует явного согласия на задачу и конкретный экземпляр; не обходите через shell/API/агентов.
  Minecraft агента — только `just play --headless`: свой Xvfb (Linux) или невидимый desktop (Windows), без звука.
  Подтвердите backend/владельца, не наследуйте `DISPLAY`/`WAYLAND_DISPLAY`; окно/fullscreen не являются headless.
  Нет backend — остановитесь без desktop fallback. Разрешение на этот клиент не разрешает приложения владельца.
  Ввод/снимки — testbot/MCP своего клиента. После проверки остановите клиент и backend штатно.
- Ожидания: точный PID/result/log/probe и жёсткий deadline; не `pgrep -f`/`ps | grep`.
  На timeout проверьте и сообщите. Перед завершением уберите свои waits/watchers.
- Не используйте mise/глобальные установки: кеш/venv проекта. POSIX shell; Bash явно через `bash -c`.
  Секреты и webhook URL не печатайте/коммитьте; credentials не переносите в CI.

## Проверки и выпуск

- Из этого repo локально: `just check` и точечные тесты; команды — `dev/quality.py` и CI.
  Версионированные хуки (`.githooks` через `core.hooksPath`): pre-commit форматирует
  staged-файлы теми же закреплёнными форматтерами и пере-stage'ит их, поэтому коммит
  всегда отформатирован (`just format-staged`, без Gradle — в отличие от `just format`,
  который правит Java через spotlessApply); pre-push запускает `just lint` и
  `just format-check` без Gradle; Java проверяет GJF 1.36.1
  (тот же форматтер, что Spotless) только в изменённых против `origin/main` файлах, `--all` — во всех.
  Хуки включаются автоматически при любом быстром `just`-вызове
  (lint/format/format-check/test/check): свежий клон получает хуки на первом `just`.
  Если git уже берёт хуки из другого места (`core.hooksPath` в любом конфиге или
  активные хуки в `.git/hooks`), `just` их не трогает и только предупреждает.
  Если записать `core.hooksPath` не удалось, `just setup` завершается ошибкой.
  Обход хука только через `--no-verify`.
  Полные `just java-check`, `just test` выполняет PR CI (зелёный до merge).
  Не ослабляйте lint/format/tests. Из workspace: build/setup/watch/scenario — справочник §4–5.
  Hotswap меняет тела методов; новые классы/поля/миксины требуют рестарта своего экземпляра.
- Игровое исправление: воспроизводимый сценарий до/после, правильные JAR/коммит/мир (`bf version`, `bot state`).
  `rawinput` проверяет callbacks/удержание, `tacz` — оператор оружия; `press` не заменяет их.
  Сохраняйте команды и доказательства в game-job. Компиляция/unit не являются игровой приёмкой.
- Загрузки: immutable full SHA/версия + checksum, без main/latest и сети при импорте.
  Не переписывайте историю, не удаляйте теги/релизы/backup и уникальные правки; архивные repo не рабочие.
  После merge в next — чистый next, удаление временных PR-веток/worktrees; без идентификаторов моделей в PR/артефактах.
  PR — в `next`, не в `main`; выпуск — один next→main merge commit, хотфиксы тоже через `next`.
- Workspace закрепляет SHA только проверенного набора. Общие моды/ресурсы меняют контракт и требуют парного выпуска,
  сервер первым. Каждый тег клиента/сервера и ручной Deploy требуют явного подтверждения в текущем разговоре:
  они запускают выпуск **и прод-деплой**. Подготовка кода/PR/release notes разрешение не заменяет.
- Выпуск/откат: справочник §6, [DEPLOY](https://github.com/Blockfield/blockfield-server/blob/main/docs/DEPLOY.md).
  Release notes и описания PR пишет CI (action `release-text`), агент прозу не пишет, файл `release-notes/` не готовит;
  [формат](https://github.com/Blockfield/blockfield-client/blob/main/docs/RELEASE-NOTES.md).
- Отчёт раздельно: **слито / опубликовано / развёрнуто / проверено в игре**; фактические ссылки/команды/SHA и ограничения.
