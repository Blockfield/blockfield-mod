package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TabletAnimTest {

    @Test
    void openAndCloseEaseFullyBetweenZeroAndOne() {
        TabletAnim anim = new TabletAnim();
        assertFalse(anim.visible(0));
        assertEquals(0f, anim.progress(0));

        anim.open(0);
        assertEquals(0f, anim.progress(0));
        assertEquals(1f, anim.progress(TabletAnim.DURATION_NS));
        assertTrue(anim.visible(TabletAnim.DURATION_NS));

        anim.close(TabletAnim.DURATION_NS);
        assertEquals(1f, anim.progress(TabletAnim.DURATION_NS));
        assertTrue(anim.visible(TabletAnim.DURATION_NS));
        assertEquals(0f, anim.progress(2 * TabletAnim.DURATION_NS));
        assertFalse(anim.visible(2 * TabletAnim.DURATION_NS));
    }

    /**
     * Корень бага "низкий FPS": в старой анимации прогресс менялся только раз за тик (20 Гц),
     * поэтому между соседними кадрами на быстром мониторе значение часто не менялось вовсе. Здесь
     * прогресс -- функция реального времени, так что даже маленький шаг времени (кадр при 240 Гц ~=
     * 4 мс) обязан дать другое (не меньшее при открытии) значение, пока анимация не завершилась.
     */
    @Test
    void progressChangesSmoothlyEveryFrameNotJustEveryTick() {
        TabletAnim anim = new TabletAnim();
        anim.open(0);
        long frameNs = 4_000_000L; // ~4мс кадра на 240 Гц, гораздо чаще тика (50мс)
        float previous = -1f;
        for (long t = 0; t <= TabletAnim.DURATION_NS; t += frameNs) {
            float p = anim.progress(t);
            assertTrue(p >= previous, "прогресс не должен уменьшаться при открытии");
            if (t > 0 && t < TabletAnim.DURATION_NS)
                assertTrue(p > previous, "кадр между тиками обязан сдвинуть прогресс");
            previous = p;
        }
    }

    @Test
    void closingMidOpenContinuesFromCurrentProgressWithoutAJump() {
        TabletAnim anim = new TabletAnim();
        anim.open(0);
        long halfway = TabletAnim.DURATION_NS / 2;
        float beforeClose = anim.progress(halfway);
        anim.close(halfway);
        float rightAfterClose = anim.progress(halfway);
        assertEquals(
                beforeClose,
                rightAfterClose,
                0.001f,
                "закрытие не должно дёргать прогресс скачком");

        float previous = rightAfterClose;
        for (long t = halfway; t <= halfway + TabletAnim.DURATION_NS; t += 10_000_000L) {
            float p = anim.progress(t);
            assertTrue(p <= previous, "прогресс не должен расти при закрытии");
            previous = p;
        }
        assertEquals(0f, previous);
    }
}
