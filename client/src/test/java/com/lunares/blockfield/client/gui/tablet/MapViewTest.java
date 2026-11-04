package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapViewTest {

    /** Вытянутая по Z зона 381x797 из отчёта владельца, рамка планшета шире своей высоты. */
    private static MapView tall(float zoom, float anchorX, float anchorY, float u, float v) {
        return MapView.of(
                -1000, 1800, -619, 2597, 1900, 0, 0, 800, 400, zoom, anchorX, anchorY, u, v);
    }

    @Test
    void northernBaseFlipsTheMapSoTheBaseIsAtTheBottom() {
        MapView view = tall(1f, 0, 0, 0.5f, 0.5f);
        assertTrue(view.flipped());
        assertEquals(400f, view.h(), 0.01f, "набок не кладём: высота зоны заняла высоту рамки");
        assertEquals(400f * 381 / 797, view.w(), 0.01f);
        float[] northWest = view.screen(-1000, 1800);
        assertEquals(
                view.x() + view.w(), northWest[0], 0.01f, "разворот, а не зеркало: запад справа");
        assertEquals(view.y() + view.h(), northWest[1], 0.01f);
    }

    @Test
    void southernOrMissingBaseStaysNorthUp() {
        assertFalse(
                MapView.of(-1000, 1800, -619, 2597, 2500, 0, 0, 800, 400, 1f, 0, 0, 0.5f, 0.5f)
                        .flipped());
        assertFalse(
                MapView.of(
                                -1000,
                                1800,
                                -619,
                                2597,
                                Double.NaN,
                                0,
                                0,
                                800,
                                400,
                                1f,
                                0,
                                0,
                                0.5f,
                                0.5f)
                        .flipped());
    }

    @Test
    void pingRoundTripsThroughFlipAndZoom() {
        MapView view = tall(3f, 250, 120, 0.3f, 0.7f);
        float[] at = view.screen(-800, 2100);
        double[] world = view.world(at[0], at[1]);
        assertEquals(-800, world[0], 0.01);
        assertEquals(2100, world[1], 0.01);
    }

    @Test
    void zoomKeepsThePointUnderTheCursorAndNeverLeavesTheFrame() {
        MapView view = tall(8f, 250, 120, 0.3f, 0.7f);
        assertEquals(250f, view.x() + 0.3f * view.w(), 0.01f);
        assertEquals(120f, view.y() + 0.7f * view.h(), 0.01f);
        MapView corner = tall(8f, 10, 10, 1f, 1f);
        assertEquals(800f, corner.x() + corner.w(), 0.01f, "карта не отрывается от края рамки");
        assertEquals(400f, corner.y() + corner.h(), 0.01f);
    }

    @Test
    void draggingMovesZoomedMapAndKeepsPingsAtTheVisibleWorldPosition() {
        for (boolean tall : new boolean[] {false, true}) {
            MapView view =
                    MapView.of(
                            0,
                            0,
                            400,
                            tall ? 800 : 400,
                            tall ? 100 : Double.NaN,
                            0,
                            0,
                            800,
                            400,
                            8,
                            400,
                            200,
                            0.5f,
                            0.5f);
            MapView moved = view.pan(30, -20, 0, 0, 800, 400);
            assertEquals(view.x() + 30, moved.x(), 0.01f);
            assertEquals(view.y() - 20, moved.y(), 0.01f);
            double[] before = view.world(400, 200);
            assertArrayEquals(before, moved.world(430, 180), 0.01);
            MapView edge = moved.pan(100000, 100000, 0, 0, 800, 400);
            assertEquals(0, edge.x());
            assertEquals(0, edge.y());
            MapView back = edge.pan(-10, -10, 0, 0, 800, 400);
            assertEquals(-10, back.x());
            assertEquals(-10, back.y());
            MapView otherEdge = back.pan(-100000, -100000, 0, 0, 800, 400);
            assertEquals(800, otherEdge.x() + otherEdge.w(), 0.01f);
            assertEquals(400, otherEdge.y() + otherEdge.h(), 0.01f);
        }
        MapView fitted = tall(1, 0, 0, 0.5f, 0.5f);
        assertEquals(fitted, fitted.pan(100, 100, 0, 0, 800, 400));
    }

    @Test
    void minimapPointersOnOneBearingDoNotStack() {
        List<float[]> placed = List.of(new float[] {50, 90});
        assertTrue(MinimapView.crowded(new float[] {52, 90}, placed, 20));
        assertFalse(MinimapView.crowded(new float[] {10, 90}, placed, 20));
    }
}
