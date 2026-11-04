package com.lunares.blockfield.client.music;

import java.util.HashMap;
import java.util.Map;

/**
 * Скользящая оценка «идёт ли рядом бой»: события добавляют вес, вес экспоненциально затухает.
 * Порог, а не событие: одиночный выстрел не должен включать боевую музыку.
 */
public final class CombatMeter {
    private double halfLifeMs;
    private double score;
    private long at;
    private final Map<Integer, double[]> shooters = new HashMap<>();

    public CombatMeter(double halfLifeSeconds) {
        halfLife(halfLifeSeconds);
    }

    public void halfLife(double seconds) {
        this.halfLifeMs = Math.max(0.1, seconds) * 1000.0;
    }

    public void add(double weight, long now) {
        decay(now);
        score += weight;
    }

    /**
     * Выстрел рядом. Вклад одного стрелка за секунду ограничен {@code capPerSecond}: очередь одного
     * автомата -- ещё не перестрелка, бой набирается только от нескольких участников или долгой
     * стрельбы.
     */
    public void shot(int shooter, double weight, double capPerSecond, long now) {
        double[] window = shooters.computeIfAbsent(shooter, id -> new double[] {now, 0});
        if (now - window[0] >= 1000) {
            window[0] = now;
            window[1] = 0;
        }
        double accepted = Math.min(weight, capPerSecond - window[1]);
        if (accepted <= 0) return;
        window[1] += accepted;
        add(accepted, now);
        if (shooters.size() > 64) shooters.values().removeIf(w -> now - w[0] > 2000);
    }

    public double value(long now) {
        decay(now);
        return score;
    }

    public void reset() {
        score = 0;
        shooters.clear();
    }

    private void decay(long now) {
        if (now <= at) return;
        score *= Math.pow(0.5, (now - at) / halfLifeMs);
        at = now;
    }
}
