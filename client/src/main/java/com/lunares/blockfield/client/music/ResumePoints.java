package com.lunares.blockfield.client.music;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Где трек прервала смена состояния. Бой короче трека, и без этого игрок слышал бы в основном
 * вступления: вернулся в состояние быстро -- прерванный трек доигрывается с того же места.
 */
final class ResumePoints {
    record Point(String track, double position, long at) {}

    private final Map<String, Point> points = new HashMap<>();

    void interrupted(String track, double position, long now) {
        points.put(track, new Point(track, position, now));
    }

    /**
     * Самый свежий прерванный трек из {@code pool}, если прошло не больше {@code window} с и до
     * конца осталось не меньше {@code minLeft} с; взятая точка забывается.
     */
    Point take(
            List<String> pool,
            long now,
            double window,
            double minLeft,
            ToDoubleFunction<String> length) {
        points.values().removeIf(p -> now - p.at() > (long) (window * 1000));
        Point best = null;
        for (String track : pool) {
            Point p = points.get(track);
            if (p == null) continue;
            double total = length.applyAsDouble(track);
            if (total > 0 && total - p.position() < minLeft) continue;
            if (best == null || p.at() > best.at()) best = p;
        }
        if (best != null) points.remove(best.track());
        return best;
    }

    void clear() {
        points.clear();
    }
}
