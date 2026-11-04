package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpawnSelectionLayoutTest {
    @Test
    void shortListFitsAboveButtons() {
        // changeClass начинается на virtualHeight-80: 6 строк помещаются без перекрытия кнопок.
        int bottom = 400 - 80 - 8;
        int perPage = SpawnSelectionScreen.rowsPerPage(100, bottom);
        assertTrue(perPage == 6, "perPage=" + perPage);
    }

    @Test
    void longListNeedsPages() {
        int perPage = SpawnSelectionScreen.rowsPerPage(100, 400 - 80 - 8 - 20 - 6);
        int pages = (int) Math.ceil(13.0 / perPage);
        assertTrue(pages >= 2, "perPage=" + perPage);
    }

    @Test
    void statusHasItsOwnRowBetweenTheListOrPagerAndActions() throws Exception {
        int[][] sizes = {
            {320, 180},
            {480, 270},
            {640, 360},
            {960, 540},
            {1280, 720},
            {1920, 1080},
            {360, 640},
            {800, 600}
        };
        var layout =
                SpawnSelectionScreen.class.getDeclaredMethod("layout", double.class, double.class);
        layout.setAccessible(true);
        for (int[] size : sizes) {
            for (int count : List.of(13, 7, 6, 1)) {
                JsonObject data = new JsonObject();
                JsonArray points = new JsonArray();
                for (int i = 1; i < count; i++) {
                    JsonObject point = new JsonObject();
                    point.addProperty("n", "Point" + i);
                    points.add(point);
                }
                data.add("pts", points);
                var screen = new SpawnSelectionScreen(data.toString(), "layout-test");
                screen.width = size[0];
                screen.height = size[1];
                Object geometry = layout.invoke(screen, -1d, -1d);
                Object deploy = part(geometry, "deploy");
                Object changeClass = part(geometry, "changeClass");
                int statusY = (int) part(deploy, "y") - 44;
                int listBottom = statusY - 8;
                if ((int) part(geometry, "pages") > 1) {
                    Object pager = part(geometry, "prevPage");
                    assertTrue(
                            (int) part(pager, "y") + (int) part(pager, "h") <= listBottom,
                            "Pagination must end above the deployment status");
                    listBottom = (int) part(pager, "y") - 6;
                }
                for (Object row : (List<?>) part(geometry, "rows")) {
                    Object rect = part(row, "rect");
                    assertTrue(
                            (int) part(rect, "y") + (int) part(rect, "h") <= listBottom,
                            "Spawn rows must end above the pager or status");
                }
                assertTrue(statusY + 9 + 4 <= (int) part(changeClass, "y"));
                assertTrue(
                        (int) part(changeClass, "y") + (int) part(changeClass, "h")
                                < (int) part(deploy, "y"));
            }
        }
    }

    private static Object part(Object record, String name) throws Exception {
        var accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    @Test
    void tabCyclesAndSkipsNothingWhenLockedOut() {
        List<String> order = List.of("spawn:BASE", "spawn:A", "action:deploy", "action:class");
        assertEquals("spawn:BASE", SpawnSelectionScreen.cycleFocus(order, null, true));
        assertEquals("action:class", SpawnSelectionScreen.cycleFocus(order, null, false));
        assertEquals("spawn:A", SpawnSelectionScreen.cycleFocus(order, "spawn:BASE", true));
        assertEquals("spawn:BASE", SpawnSelectionScreen.cycleFocus(order, "spawn:A", false));
    }

    @Test
    void sameMapRespawnAndRefreshKeepTheSelectedPoint() {
        SpawnSelectionMemory memory = new SpawnSelectionMemory();
        assertEquals("BASE", memory.forMap("konstaxa"));
        memory.select("konstaxa", "Alpha");
        assertEquals("Alpha", memory.forMap("konstaxa"));
        assertEquals("Alpha", memory.forMap("konstaxa"));
        memory.select("konstaxa", "BASE");
        assertEquals("BASE", memory.forMap("konstaxa"));
    }

    @Test
    void visitingAnotherMapClearsTheChoiceEvenWithoutSelectingThere() {
        SpawnSelectionMemory memory = new SpawnSelectionMemory();
        memory.select("konstaxa", "Alpha");
        assertEquals("BASE", memory.forMap("berdichev"));
        assertEquals("BASE", memory.forMap("konstaxa"));
    }

    @Test
    void selectingOnAnotherMapDoesNotRestoreThePreviousMapsChoice() {
        SpawnSelectionMemory memory = new SpawnSelectionMemory();
        memory.select("konstaxa", "Alpha");
        memory.select("berdichev", "Beta");
        assertEquals("Beta", memory.forMap("berdichev"));
        assertEquals("BASE", memory.forMap("konstaxa"));
        assertEquals("BASE", memory.forMap("berdichev"));
    }

    @Test
    void nullOrEmptyBoundsDoNotCrashLayout() throws Exception {
        var layout =
                SpawnSelectionScreen.class.getDeclaredMethod("layout", double.class, double.class);
        layout.setAccessible(true);
        for (String json : List.of("{\"bounds\":null}", "{\"bounds\":{}}", "{}")) {
            var screen = new SpawnSelectionScreen(json, "layout-test");
            screen.width = 640;
            screen.height = 400;
            try {
                layout.invoke(screen, -1d, -1d);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new AssertionError("layout crashed on " + json, e.getCause());
            }
        }
    }
}
