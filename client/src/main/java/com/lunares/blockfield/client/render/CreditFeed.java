package com.lunares.blockfield.client.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Лента начислений БК под счётчиком. Одна строка на источник: пока строка на экране, новое
 * начисление из того же источника прибавляется к её сумме и меняет подпись («Убийство» -> «Двойное
 * убийство»), поэтому посекундное удержание точки не превращается в столбик одинаковых строк.
 */
final class CreditFeed {
    static final long IN_MS = 220;
    static final long POP_MS = 320;
    static final long HOLD_MS = 5500;
    static final long FADE_MS = 500;

    /**
     * Начисление от этой суммы даёт всплеск в полную силу; посекундная мелочь за точку почти не
     * дёргает HUD.
     */
    private static final double FULL_POP_AMOUNT = 20;

    /** За это время счётчик баланса докручивается до ~95% нового значения. */
    private static final double ROLL_MS = 450;

    static final class Line {
        final String source;
        final long bornMs;
        String label;
        double amount;
        long bumpedMs;
        float popStrength;

        Line(String source, String label, double amount, long now) {
            this.source = source;
            this.label = label;
            this.amount = amount;
            this.bornMs = now;
            this.bumpedMs = now;
            this.popStrength = strength(amount);
        }

        /** 0..1: въезд новой строки. */
        float enter(long now) {
            return easeOut(Math.min(1f, (now - bornMs) / (float) IN_MS));
        }

        /** 1..0: всплеск суммы после начисления. */
        float pop(long now) {
            return popStrength * (1f - easeOut(Math.min(1f, (now - bumpedMs) / (float) POP_MS)));
        }

        /** 1..0: строка гаснет, когда начисления из источника прекратились. */
        float alpha(long now) {
            return Math.max(0f, Math.min(1f, 1f - (now - bumpedMs - HOLD_MS) / (float) FADE_MS));
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private double shownBalance = Double.NaN;
    private long rolledMs;
    private long balanceBumpedMs = Long.MIN_VALUE / 2;
    private float balancePopStrength;

    void add(String source, String label, double amount, long now) {
        if (source == null || !Double.isFinite(amount) || amount == 0) return;
        if (amount > 0) {
            balanceBumpedMs = now;
            balancePopStrength = strength(amount);
        }
        for (Line line : lines(now)) {
            if (line.source.equals(source)) {
                line.amount += amount;
                line.label = label;
                line.bumpedMs = now;
                line.popStrength = strength(amount);
                return;
            }
        }
        lines.add(new Line(source, label, amount, now));
    }

    List<Line> lines(long now) {
        lines.removeIf(line -> line.alpha(now) <= 0f);
        return lines;
    }

    /** Баланс для показа: догоняет настоящий плавно, а после перерыва (вход в бой) встаёт сразу. */
    double rollBalance(double target, long now) {
        if (Double.isNaN(shownBalance)
                || now - rolledMs > 1000
                || Math.abs(target - shownBalance) < 0.5) {
            shownBalance = target;
        } else {
            shownBalance +=
                    (target - shownBalance) * (1 - Math.exp(-3.0 * (now - rolledMs) / ROLL_MS));
        }
        rolledMs = now;
        return shownBalance;
    }

    /** 1..0: подсветка счётчика после начисления. */
    float balancePop(long now) {
        return balancePopStrength
                * (1f
                        - easeOut(
                                Math.max(
                                        0f,
                                        Math.min(
                                                1f,
                                                (now - balanceBumpedMs) / (float) (POP_MS * 2)))));
    }

    void clear() {
        lines.clear();
        shownBalance = Double.NaN;
    }

    private static float strength(double amount) {
        return (float) Math.min(1, Math.abs(amount) / FULL_POP_AMOUNT);
    }

    private static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }
}
