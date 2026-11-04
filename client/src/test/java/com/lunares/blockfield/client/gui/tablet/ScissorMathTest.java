package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScissorMathTest {

    /**
     * Баг из отчёта владельца: карточка «Случайно» без правой рамки и обрезанная подсказка --
     * содержимое шире, чем scissor. Причина: если правую грань считать как left + round(w*scale)
     * (округлить ширину отдельно от позиции), на нецелом масштабе результат иногда на 1px уже, чем
     * настоящая правая граница контента round((x+w)*scale). Здесь показываем оба числа на реальных
     * значениях планшета и проверяем, что новый способ (через абсолютные грани) их не путает.
     */
    @Test
    void rightEdgeNeverEndsUpNarrowerThanScalingTheFarCorner() {
        // Реальное сочетание с GUI-масштабом 4 при 480x270: contentX=16, contentWidth=600, масштаб
        // холста планшета ~0.5253 (почти никогда не целое число).
        int x = 16, w = 600;
        float scale = 0.5253f;
        int[] rect = ScissorMath.scale(x, 0, w, 1, scale, 0, 0);
        int right = rect[0] + rect[2];
        int naiveRight = Math.round(x * scale) + Math.round(w * scale);
        assertEquals(
                Math.round((x + w) * scale),
                right,
                "правая грань обязана считаться от абсолютной координаты (x+w)*scale");
        // На этих конкретных числах наивный способ (округлить x и w по отдельности) даёт на 1px
        // меньше --
        // именно так пропадала последняя колонка пикселей (рамка карточки, конец подсказки).
        assertNotEquals(
                naiveRight, right, "тест подобран так, чтобы независимое округление отличалось");
    }

    @Test
    void positionAndSizeShiftAndScaleTogether() {
        int[] rect = ScissorMath.scale(10, 20, 100, 50, 2f, 5, 7);
        assertArrayEquals(new int[] {25, 47, 200, 100}, rect);
    }

    @Test
    void zeroSizeStaysZeroSize() {
        int[] rect = ScissorMath.scale(0, 0, 0, 0, 0.5f, 3, 4);
        assertEquals(3, rect[0]);
        assertEquals(4, rect[1]);
        assertEquals(0, rect[2]);
        assertEquals(0, rect[3]);
    }
}
