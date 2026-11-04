package com.lunares.blockfield.client.music;

import java.util.Locale;

/**
 * Масштаб матча: насколько близок конец боя. CRITICAL -- своя команда проигрывает всерьёз,
 * FINAL_PUSH -- равная развязка в самом конце. Оба должны звучать редко: пороги в music.json,
 * секция {@code pressure}.
 */
public final class MatchPressure {
    public record Result(MusicState state, String reason) {
        public static final Result NONE = new Result(MusicState.AMBIENT, "нет");
    }

    private MatchPressure() {}

    /** Билеты (Захват точек, Кампания): у обеих команд убывают от {@code max}. */
    public static Result tickets(int mine, int enemy, int max, MusicConfig.Pressure t) {
        if (max <= 0 || mine <= 0 || enemy <= 0) return Result.NONE;
        double own = (double) mine / max;
        double their = (double) enemy / max;
        String score = String.format(Locale.ROOT, "билеты %d:%d из %d", mine, enemy, max);
        if (Math.min(own, their) <= t.finalShare && Math.abs(own - their) <= t.finalGap) {
            return new Result(MusicState.FINAL_PUSH, score + ", равная развязка");
        }
        if (own <= t.criticalShare && their - own >= t.criticalDeficit) {
            return new Result(MusicState.CRITICAL, score + ", проигрываем");
        }
        return Result.NONE;
    }

    /** Фраги (Командный бой): растут до {@code limit}; {@code timeLeft} < 0 -- время неизвестно. */
    public static Result frags(
            int mine, int enemy, int limit, int timeLeft, MusicConfig.Pressure t) {
        if (limit <= 0) return Result.NONE;
        String score =
                String.format(
                        Locale.ROOT,
                        "фраги %d:%d до %d, осталось %d с",
                        mine,
                        enemy,
                        limit,
                        timeLeft);
        boolean ending =
                timeLeft >= 0 && timeLeft <= t.finalSeconds
                        || Math.max(mine, enemy) >= limit - t.closeFrags;
        if (ending && Math.abs(mine - enemy) <= t.closeFrags)
            return new Result(MusicState.FINAL_PUSH, score + ", равная развязка");
        boolean late =
                enemy >= limit * (1 - t.criticalShare)
                        || timeLeft >= 0 && timeLeft <= t.finalSeconds * 2;
        if (late && enemy - mine >= t.criticalFrags)
            return new Result(MusicState.CRITICAL, score + ", проигрываем");
        return Result.NONE;
    }
}
