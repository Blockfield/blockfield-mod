package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TabletCanvasTest {

    /** Внутренний прямоугольник планшета при типичных GUI-размерах (см. TabletFrame). */
    private static int[] inner(int guiW, int guiH) {
        TabletFrame f = TabletFrame.of(guiW, guiH);
        return new int[] {f.contentW(), f.contentH()};
    }

    @Test
    void canvasKeepsTheInnerAspectAtEveryGuiScale() {
        for (int[] gui : new int[][] {{480, 270}, {640, 360}, {427, 240}, {960, 540}}) {
            int[] in = inner(gui[0], gui[1]);
            TabletCanvas c = TabletCanvas.forInner(in[0], in[1]);
            assertEquals(
                    in[0] / (float) c.width(),
                    in[1] / (float) c.height(),
                    0.01f,
                    gui[0] + "x" + gui[1]);
            assertEquals(c.scale(), in[0] / (float) c.width(), 0.001f);
            assertTrue(c.width() >= TabletCanvas.MIN_WIDTH, "меню не должно сваливаться в колонку");
        }
    }

    @Test
    void growingByOverflowLowersTheScaleAndStopsAtTheLimit() {
        int[] in = inner(480, 270);
        TabletCanvas c = TabletCanvas.forInner(in[0], in[1]);
        TabletCanvas grown = c.grownBy(120, in[0], in[1]);
        assertTrue(grown.height() > c.height());
        assertTrue(grown.scale() < c.scale());
        assertEquals(in[0] / (float) grown.width(), in[1] / (float) grown.height(), 0.01f);
        TabletCanvas huge = c.grownBy(10_000, in[0], in[1]);
        assertEquals(TabletCanvas.MAX_HEIGHT, huge.height());
    }

    @Test
    void mouseMapsBackIntoCanvasCoordinates() {
        TabletCanvas c = TabletCanvas.forInner(400, 225);
        assertEquals(0, c.toCanvasX(30, 30), 0.001);
        assertEquals(c.width(), c.toCanvasX(30 + 400, 30), 0.5);
        assertEquals(c.height(), c.toCanvasY(50 + 225, 50), 0.5);
    }

    @Test
    void aSmallTabletScreenStartsUnscaled() {
        // GUI 640x360 (1080p at GUI scale 3): the tablet screen is lower than the design height.
        TabletCanvas c = TabletCanvas.forInner(596, 290);
        assertEquals(1f, c.scale(), 0.001f, "fits first at 1:1, shrinks only on overflow");
        assertTrue(
                TabletCanvas.forInner(900, 500).scale() > 1f,
                "a taller screen still scales the design up");
    }
}
