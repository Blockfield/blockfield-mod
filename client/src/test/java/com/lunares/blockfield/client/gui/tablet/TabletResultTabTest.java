package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TabletResultTabTest {

    /** ФРАГИ / МЕСТО / ВРЕМЯ БОЯ с реальными ширинами шрифта из скриншота стекла. */
    private static final int[] LABELS = {30, 30, 54};

    private static final int[] VALUES = {6, 28, 29};

    private static int rowWidth(float scale) {
        int total = 20 * (LABELS.length - 1);
        for (int i = 0; i < LABELS.length; i++)
            total += Math.max(LABELS[i], Math.round(scale * VALUES[i]));
        return total;
    }

    @Test
    void statsRowFitsTheColumnOfTheRealGlass() {
        // Кадр стекла 590x380 -> холст 574 px, колонка справа от карты ~333 px.
        float scale = TabletResultTab.scoreScale(LABELS, VALUES, 333);
        assertEquals(2f, scale, 0.001f);
        assertTrue(rowWidth(scale) <= 333);
    }

    @Test
    void aNarrowColumnShrinksTheDigitsInsteadOfOverflowing() {
        int width = rowWidth(1.5f);
        assertEquals(1.5f, TabletResultTab.scoreScale(LABELS, VALUES, width), 0.001f);
        assertEquals(1f, TabletResultTab.scoreScale(LABELS, VALUES, 10), 0.001f);
    }

    @Test
    void longStatLabelsChooseFewerColumnsAtFullTextSize() {
        int columns = TabletResultTab.gridColumns(333, 88, 8);
        assertEquals(3, columns);
        assertTrue((333 - (columns - 1) * 3) / columns - 14 >= 88);
        assertEquals(4, TabletResultTab.gridColumns(500, 88, 8));
    }
}
