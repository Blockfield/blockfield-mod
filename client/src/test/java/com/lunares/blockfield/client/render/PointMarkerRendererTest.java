package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.lunares.blockfield.client.gui.BfTheme;
import org.junit.jupiter.api.Test;

class PointMarkerRendererTest {
    @Test
    void reservesWholeHudPanelsAndLocalizesPointLabels() {
        int[] panel = {100, 6, 50, 20};
        assertTrue(PointMarkerRenderer.overlaps(95, 15, 6, panel));
        assertTrue(PointMarkerRenderer.overlaps(150, 26, 6, panel));
        assertFalse(PointMarkerRenderer.overlaps(93, 15, 6, panel));
        assertFalse(PointMarkerRenderer.overlaps(110, 40, 6, panel));
        assertEquals("Эпсилон", PointMarkerRenderer.displayName("Epsilon"));
        assertEquals("Йота", PointMarkerRenderer.displayName("Iota"));
        assertEquals("Склад", PointMarkerRenderer.displayName("Склад"));
    }

    @Test
    void parsesOwnerCapturerAndSkipsBrokenPoints() {
        var pts =
                JsonParser.parseString(
                                """
                [{"id":"a","n":"alpha","x":10,"y":64,"z":-5,"o":"NEUTRAL","c":"RED","p":40,"b":[5,-10,15,0]},
                 {"id":"b","n":"bravo","x":0,"y":64,"z":0,"o":"BLUE","c":"RED","p":250},
                 {"id":"c","n":"","x":"oops","y":1,"z":1},
                 {"id":"d","y":1,"z":1}]""")
                        .getAsJsonArray();
        var list = PointMarkerRenderer.parse(pts);
        assertEquals(2, list.size());
        var a = list.get(0);
        assertEquals("A", a.letter());
        assertEquals(10.5, a.x());
        assertEquals(BfTheme.NEUTRAL, a.ownerColor());
        assertEquals(BfTheme.RED_TEAM, a.barColor());
        assertEquals(40.0, a.progress());
        assertArrayEquals(new int[] {5, -10, 15, 0}, a.bounds());
        assertTrue(a.contested());
        assertTrue(PointMarkerRenderer.inside(a, 5.0, -10.0));
        assertTrue(PointMarkerRenderer.inside(a, 15.9, 0.9));
        assertFalse(PointMarkerRenderer.inside(a, 16.0, 0.0));
        var b = list.get(1);
        assertEquals(BfTheme.BLUE_TEAM, b.ownerColor());
        assertEquals(BfTheme.BLUE_TEAM, b.barColor());
        assertEquals(100.0, b.progress());
        assertNull(b.bounds());
        assertFalse(b.contested());
    }

    @Test
    void neutralProgressKeepsClaimingTeamColorDuringReversalAndDecay() {
        var pts =
                JsonParser.parseString(
                                """
                [{"n":"alpha","x":0,"y":64,"z":0,"o":"NEUTRAL","c":"BLUE","claiming":"RED","p":40},
                 {"n":"bravo","x":10,"y":64,"z":0,"o":"NEUTRAL","c":"NONE","claiming":"BLUE","p":25},
                 {"n":"charlie","x":20,"y":64,"z":0,"o":"BLUE","c":"RED","claiming":"NONE","p":60}]
                """)
                        .getAsJsonArray();
        var list = PointMarkerRenderer.parse(pts);
        assertEquals(BfTheme.RED_TEAM, list.get(0).barColor());
        assertEquals(40.0, list.get(0).progress());
        assertEquals(BfTheme.BLUE_TEAM, list.get(1).barColor());
        assertEquals(25.0, list.get(1).progress());
        assertEquals(BfTheme.BLUE_TEAM, list.get(2).barColor());
        assertEquals(60.0, list.get(2).progress());
    }

    @Test
    void zoneOfSeveralBoxesFromBs() {
        var pts =
                JsonParser.parseString(
                                """
                [{"id":"a","n":"alpha","x":1,"y":64,"z":1,"b":[0,0,9,9],"bs":[[0,0,9,2],[0,3,2,9]]},
                 {"id":"b","n":"bravo","x":1,"y":64,"z":1,"b":[0,0,9,9],"bs":[[0,0,9]]}]""")
                        .getAsJsonArray();
        var list = PointMarkerRenderer.parse(pts);
        var l = list.get(0);
        assertEquals(2, l.boxes().length);
        assertTrue(PointMarkerRenderer.inside(l, 9.5, 2.5));
        assertTrue(PointMarkerRenderer.inside(l, 1.0, 9.9));
        assertFalse(PointMarkerRenderer.inside(l, 5.0, 5.0), "inner corner of the L");
        var broken = list.get(1);
        assertEquals(1, broken.boxes().length, "a broken bs falls back to the bounding box");
        assertTrue(PointMarkerRenderer.inside(broken, 5.0, 5.0));
    }
}
