package com.lunares.blockfield.api.mode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

public interface IGameMode {

    /**
     * Возвращает уникальный строковый ID режима для вызова из консоли (пример: "campaign",
     * "deathmatch").
     */
    String getId();

    /** Возвращает понятное название режима для отображения в интерфейсах и логах. */
    String getName();

    /**
     * Первичная инициализация. Вызывается сразу в момент обработки команды /bf start. Режим обязан
     * считать свои конфигурационные файлы баланса, карт и подготовить мир.
     *
     * @param server Текущий экземпляр MinecraftServer.
     * @param args Дополнительные аргументы из консоли (например, ID кастомной кампании или
     *     параметры лимитов).
     */
    void onInitialize(MinecraftServer server, String[] args);

    /** Начало боя после PREPARING: выдача кита и перенос ожидающих игроков на боевые спавны. */
    void onStart(MinecraftServer server);

    /**
     * Серверный тик режима. Вызывается строго один раз в секунду (каждые 20 тиков Minecraft).
     * Отвечает за логику изменения очков от удержания флагов (Ticket Bleed), проверку тайм-аутов
     * матча.
     */
    void onTick(MinecraftServer server, int currentTick);

    /**
     * Перехватчик гибели сущностей игроков на сервере. Используется для списания тикетов за смерть
     * в кампании или инкремента фраг-листа в Deathmatch.
     *
     * @param victim Погибший игрок.
     * @param source Причина нанесения смертельного урона.
     * @param killer Игрок, совершивший убийство (может быть null при суициде или ПВЕ уроне).
     */
    void onPlayerDeath(ServerPlayer victim, DamageSource source, ServerPlayer killer);

    /**
     * Обработчик подключения нового игрока (Late Join / Reconnect). Режим должен отправить игроку
     * актуальные сетевые пакеты состояния мира и назначить команду.
     */
    void onPlayerJoin(ServerPlayer player);

    /**
     * Обработчик выхода игрока с сервера во время активного матча. Используется для остановки
     * персональных таймеров классов и предотвращения абуза статистики.
     */
    void onPlayerLeave(ServerPlayer player);

    /**
     * Финальная деинициализация режима. Очистка игровых фракций Scoreboard, сброс кэша точек
     * захвата, выгрузка текущей карты и запись лога матча на диск.
     */
    void onStop(MinecraftServer server);
}
