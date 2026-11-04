package com.lunares.blockfield.client.gui.tablet;

/**
 * Открытие/закрытие планшета: чистая функция от реального времени (наносекунды), а не от счётчика
 * клиентских тиков. Раньше прогресс анимации считался в {@code TabletState.tick(dtMs)}, который
 * вызывался ровно раз за клиентский тик (20 Гц) -- на мониторе с частотой кадров выше 20 Гц кадр
 * рендерился гораздо чаще, чем менялось значение прогресса, и анимация выглядела ступенчатой ("как
 * будто низкий FPS"), хотя сам рендер шёл быстро. Здесь прогресс -- функция {@code progress(now)}
 * от {@link System#nanoTime()}, переданного из render(): значение плавно меняется на каждом кадре
 * независимо от тикрейта.
 *
 * <p>Без классов Minecraft -- проверяется юнит-тестом без игры.
 */
public final class TabletAnim {

    /** Короткий ease-out: владелец просил ~120-150 мс. */
    public static final long DURATION_NS = 130_000_000L;

    private long phaseStartNanos;

    /**
     * Прогресс в момент последнего open()/close(): анимация продолжается от него, а не с нуля --
     * иначе прерывание на середине (урон закрыл планшет, пока он ещё доигрывал открытие) дало бы
     * видимый скачок вместо плавного разворота.
     */
    private float phaseStartProgress;

    private boolean opening;
    private boolean everOpened;

    public void open(long nowNanos) {
        phaseStartProgress = everOpened ? progress(nowNanos) : 0f;
        phaseStartNanos = nowNanos;
        opening = true;
        everOpened = true;
    }

    public void close(long nowNanos) {
        if (!everOpened) return;
        phaseStartProgress = progress(nowNanos);
        phaseStartNanos = nowNanos;
        opening = false;
    }

    /**
     * Планшет ещё должен рисоваться: открывается, полностью открыт, или ещё доигрывает закрытие.
     */
    public boolean visible(long nowNanos) {
        return everOpened && (opening || nowNanos - phaseStartNanos < DURATION_NS);
    }

    /**
     * 0 -- полностью закрыт/скрыт, 1 -- полностью открыт; ease-out по прошедшему реальному времени.
     */
    public float progress(long nowNanos) {
        if (!everOpened) return 0f;
        float t = clamp01((nowNanos - phaseStartNanos) / (float) DURATION_NS);
        float target = opening ? 1f : 0f;
        float eased = t * (2f - t); // квадратичный ease-out: f(0)=0, f(1)=1, монотонна
        return phaseStartProgress + (target - phaseStartProgress) * eased;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
