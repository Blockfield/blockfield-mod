package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MinimapViewTest {

    /** Зона 400x400 блоков, окно 100x100 px на 50 блоков: 2 px на блок. */
    private static MinimapView view(double playerX, double playerZ, float yaw, boolean rotate) {
        return MinimapView.of(0, 0, 400, 400, playerX, playerZ, yaw, rotate, 50, 10, 20, 100, 100);
    }

    @Test
    void playerSitsInTheCentreAndScaleIsPixelsPerBlock() {
        MinimapView v = view(200, 200, 0f, false);
        assertArrayEquals(new float[] {60f, 70f}, v.project(200, 200), 0.6f);
        // +10 блоков на восток = +20 px вправо, +10 на юг = +20 px вниз (север сверху).
        assertArrayEquals(new float[] {80f, 90f}, v.project(210, 210), 0.6f);
    }

    @Test
    void movementBetweenTicksKeepsSubpixelOffsetsAndPlayerCentred() {
        for (boolean rotate : new boolean[] {false, true}) {
            MinimapView previous = view(200, 200, 37f, rotate);
            for (int frame = 1; frame <= 60; frame++) {
                double x = 200 + frame / 60.0;
                double z = 200 + frame / 120.0;
                MinimapView current = view(x, z, 37f, rotate);
                assertEquals(-2f / 60, current.offsetX() - previous.offsetX(), 0.0001f);
                assertEquals(-1f / 60, current.offsetY() - previous.offsetY(), 0.0001f);
                assertArrayEquals(new float[] {60, 70}, current.project(x, z), 0.0001f);
                previous = current;
            }
        }
    }

    @Test
    void zoneEdgeLeavesTheWindowInsteadOfStretching() {
        MinimapView v = view(10, 10, 0f, false);
        // Игрок в 10 блоках от угла зоны: угол на 20 px выше и левее центра окна, остальное —
        // пусто.
        assertArrayEquals(new float[] {40f, 50f}, v.project(0, 0), 0.6f);
        assertTrue(v.inFrame(40f, 50f, 0f));
        // Дальний угол зоны в окно не попадает: обрезается ножницами, а не сжимается.
        float[] far = v.project(400, 400);
        assertFalse(v.inFrame(far[0], far[1], 0f));
    }

    @Test
    void rotationPutsTheViewDirectionUp() {
        for (float yaw : new float[] {0, 90, -90, 180, 37, 359, -359}) {
            MinimapView v = view(200, 200, yaw, true);
            double angle = Math.toRadians(yaw);
            float[] ahead = v.project(200 - Math.sin(angle) * 10, 200 + Math.cos(angle) * 10);
            assertEquals(v.centerX(), ahead[0], 0.01f);
            assertEquals(v.centerY() - 20, ahead[1], 0.01f);
        }
    }

    @Test
    void northUpDoesNotRotate() {
        assertEquals(0f, view(200, 200, 123f, false).rotation());
    }

    @Test
    void markerOutsideTheWindowIsPinnedToTheFrame() {
        MinimapView v = view(200, 200, 0f, false);
        float[] far = v.project(400, 200);
        assertFalse(v.inFrame(far[0], far[1], 9f));
        float[] pinned = v.clamp(far[0], far[1], 9f);
        assertEquals(10 + 100 - 9, pinned[0], 0.01f);
        assertEquals(70f, pinned[1], 0.6f);
        assertTrue(v.inFrame(pinned[0], pinned[1], 9f));
    }

    @Test
    void withoutASnapshotThereIsNoWidget() {
        assertTrue(MinimapView.shouldRender(true, true, true, true, true));
        assertFalse(MinimapView.shouldRender(true, true, true, true, false));
        assertFalse(MinimapView.shouldRender(true, true, true, false, true));
        assertFalse(MinimapView.shouldRender(false, true, true, true, true));
        assertFalse(MinimapView.shouldRender(true, false, true, true, true));
        assertFalse(MinimapView.shouldRender(true, true, false, true, true));
    }
}
