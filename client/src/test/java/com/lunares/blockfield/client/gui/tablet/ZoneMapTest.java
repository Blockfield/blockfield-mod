package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ZoneMapTest {

    /** Боевая зона карты fields из blockfield/maps/fields.json. */
    private static ZoneMap fields(int frameW, int frameH) {
        return ZoneMap.fit(3520, -960, 4223, -257, 10, 20, frameW, frameH);
    }

    @Test
    void squareZoneKeepsAspectAndCentresInFrame() {
        ZoneMap zm = fields(400, 200);
        assertEquals(200, zm.h());
        assertEquals(200, zm.w(), "зона почти квадратная: ширина ограничена высотой рамки");
        assertEquals(10 + (400 - 200) / 2, zm.x());
        assertEquals(20, zm.y());
    }

    @Test
    void cornersMapToTheEdgesOfTheDrawnRectangle() {
        ZoneMap zm = fields(300, 300);
        assertEquals(zm.x(), Math.round(zm.screenX(3520)));
        assertEquals(zm.x() + zm.w(), Math.round(zm.screenX(4223)));
        assertEquals(zm.y(), Math.round(zm.screenZ(-960)));
        assertEquals(zm.y() + zm.h(), Math.round(zm.screenZ(-257)));
    }

    @Test
    void centreOfZoneLandsInCentreOfRectangle() {
        ZoneMap zm = fields(300, 300);
        assertEquals(zm.x() + zm.w() / 2f, zm.screenX((3520 + 4223) / 2.0), 0.5f);
        assertEquals(zm.y() + zm.h() / 2f, zm.screenZ((-960 - 257) / 2.0), 0.5f);
    }

    @Test
    void outsideTheZoneIsNotDrawnButOwnMarkerIsClamped() {
        ZoneMap zm = fields(300, 300);
        assertFalse(zm.contains(3000, -500));
        assertTrue(zm.contains(3800, -600));
        assertEquals(zm.x(), Math.round(zm.clampedScreenX(1000)));
        assertEquals(zm.x() + zm.w(), Math.round(zm.clampedScreenX(9999)));
        assertEquals(zm.y() + zm.h(), Math.round(zm.clampedScreenZ(0)));
    }

    @Test
    void wideZoneIsLimitedByFrameWidth() {
        ZoneMap zm = ZoneMap.fit(0, 0, 1000, 100, 0, 0, 200, 200);
        assertEquals(200, zm.w());
        assertEquals(20, zm.h());
        assertEquals(90, zm.y(), "узкая полоса по центру рамки");
    }

    @Test
    void frameNeverOverflows() {
        for (int w = 40; w < 500; w += 37) {
            for (int h = 40; h < 500; h += 41) {
                ZoneMap zm = fields(w, h);
                assertTrue(zm.w() <= w && zm.h() <= h, w + "x" + h);
                assertTrue(zm.x() >= 10 && zm.y() >= 20, w + "x" + h);
            }
        }
    }

    @Test
    void screenPointMapsBackToTheWorldPointItCameFrom() {
        ZoneMap zm = fields(300, 300);
        for (double[] world :
                new double[][] {{3520, -960}, {4223, -257}, {3800, -600}, {3999.5, -401.25}}) {
            double sx = zm.screenX(world[0]);
            double sy = zm.screenZ(world[1]);
            assertTrue(zm.onMap(sx, sy), world[0] + "/" + world[1]);
            assertEquals(world[0], zm.worldX(sx), 1.5);
            assertEquals(world[1], zm.worldZ(sy), 1.5);
        }
        assertFalse(zm.onMap(zm.x() - 1, zm.y()));
        assertFalse(zm.onMap(zm.x(), zm.y() + zm.h() + 1));
    }
}
