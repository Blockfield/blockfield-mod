package com.lunares.blockfield.client;

/**
 * Ваниль снимает «Загрузку территории», как только собрана одна секция под игроком: после высадки
 * мир достраивается на глазах. Держим экран, пока не придут чанки вокруг точки, плюс пара тиков на
 * их сборку.
 */
public final class SpawnPreload {
    public static final int RADIUS = 3;
    static final int SETTLE_TICKS = 5;
    // ponytail: жёсткий потолок вместо опроса очереди сборки Sodium (стабильного API нет); если на
    // слабых
    // машинах мир всё ещё достраивается после экрана -- поднимать потолок или ждать секции в поле
    // зрения.
    static final int MAX_TICKS = 100;

    private int waited;
    private int settled;

    /** Раз в тик, пока ваниль ждёт чанк игрока. */
    public boolean ready(boolean playerSectionCompiled, boolean chunksAroundReceived) {
        if (++waited >= MAX_TICKS) return playerSectionCompiled;
        if (!playerSectionCompiled || !chunksAroundReceived) {
            settled = 0;
            return false;
        }
        return ++settled > SETTLE_TICKS;
    }
}
