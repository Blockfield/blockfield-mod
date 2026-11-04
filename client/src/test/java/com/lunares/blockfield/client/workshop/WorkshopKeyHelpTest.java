package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkshopKeyHelpTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void groupsCoverEveryWorkshopBindingExactlyOnce() {
        var seen = new HashSet<String>();
        assertEquals(5, WorkshopKeyHelp.GROUPS.size());
        for (String group : WorkshopKeyHelp.GROUPS) {
            assertFalse(WorkshopKeyHelp.actionsIn(group).isEmpty(), group);
            for (String action : WorkshopKeyHelp.actionsIn(group)) {
                assertTrue(seen.add(action), action);
                assertEquals(group, WorkshopKeyHelp.groupOf(action));
            }
        }
        var bound = new HashSet<String>();
        for (var binding : WorkshopControls.BINDINGS) bound.add(binding.action());
        assertEquals(bound, seen);
    }

    private static Map<String, String> defaultTable() {
        var table = new HashMap<String, String>();
        table.put("radial", "Z");
        table.put("ai", "V");
        table.put("selection", "G");
        table.put("copy", "`");
        table.put("paste", "Y");
        table.put("undo", "B");
        table.put("redo", "H");
        table.put("rotate", "R");
        table.put("extend", "N");
        table.put("chain", "K");
        return table;
    }

    private static List<WorkshopKeyHelp.Row> rows(Map<String, String> table) {
        return WorkshopKeyHelp.rows(
                action -> "L-" + action, table::get, "UNBOUND · Controls → Workshop");
    }

    @Test
    void cardContentEqualsTheBindingTable() {
        var byAction = new HashMap<String, WorkshopKeyHelp.Row>();
        for (var row : rows(defaultTable())) byAction.put(row.action(), row);
        assertEquals(15, byAction.size());
        assertEquals("L-radial · Z", byAction.get("radial").text());
        assertEquals("L-rotate · R", byAction.get("rotate").text());
        assertEquals("L-copy · `", byAction.get("copy").text());
        assertEquals("L-chain · K", byAction.get("chain").text());
        for (String unbound : new String[] {"library", "ghost", "maps", "submit", "keys"})
            assertEquals(
                    "L-" + unbound + " · UNBOUND · Controls → Workshop",
                    byAction.get(unbound).text());
        // Grouping follows the card sections.
        assertEquals("menu", byAction.get("radial").group());
        assertEquals("menu", byAction.get("keys").group());
        assertEquals("selection", byAction.get("selection").group());
        assertEquals("buffer", byAction.get("undo").group());
        assertEquals("sketch", byAction.get("extend").group());
        assertEquals("sketch", byAction.get("chain").group());
        assertEquals("world", byAction.get("submit").group());
    }

    @Test
    void reboundAndUnboundKeysShowCorrectly() {
        var table = defaultTable();
        table.put("radial", "F1");
        table.put("copy", null);
        table.put("library", "O");
        var byAction = new HashMap<String, WorkshopKeyHelp.Row>();
        for (var row : rows(table)) byAction.put(row.action(), row);
        assertEquals("L-radial · F1", byAction.get("radial").text());
        assertTrue(byAction.get("copy").text().contains("UNBOUND"));
        assertEquals("L-library · O", byAction.get("library").text());
    }

    @Test
    void serverActionsMapToTheRightBinding() {
        assertEquals("ai", WorkshopKeyHelp.bindingAction("workshop:ai"));
        assertEquals("selection", WorkshopKeyHelp.bindingAction("workshop:selection"));
        assertEquals("copy", WorkshopKeyHelp.bindingAction("workshop:build:copy"));
        assertEquals("paste", WorkshopKeyHelp.bindingAction("workshop:tool:paste"));
        assertEquals("undo", WorkshopKeyHelp.bindingAction("workshop:tool:undo"));
        assertEquals("redo", WorkshopKeyHelp.bindingAction("workshop:build:redo"));
        assertEquals("rotate", WorkshopKeyHelp.bindingAction("workshop:build:rotate"));
        assertEquals("extend", WorkshopKeyHelp.bindingAction("workshop:extend"));
        assertEquals("chain", WorkshopKeyHelp.bindingAction("workshop:chain"));
        assertEquals("ghost", WorkshopKeyHelp.bindingAction("workshop:ghost"));
        assertEquals("maps", WorkshopKeyHelp.bindingAction("workshop:hub"));
        assertEquals("maps", WorkshopKeyHelp.bindingAction("workshop:map:home:0"));
        assertEquals("maps", WorkshopKeyHelp.bindingAction("workshop:tpxz:1:2:3"));
        assertEquals("submit", WorkshopKeyHelp.bindingAction("workshop:mk:submit"));
        assertEquals("radial", WorkshopKeyHelp.bindingAction("menu"));
        assertEquals("keys", WorkshopKeyHelp.bindingAction("keys"));
        // No direct hotkey: cut, task cancel, AI undo, markup and tool lists stay bare.
        assertNull(WorkshopKeyHelp.bindingAction("workshop:build:cut"));
        assertNull(WorkshopKeyHelp.bindingAction("workshop:cancel"));
        assertNull(WorkshopKeyHelp.bindingAction("workshop:ai_undo"));
        assertNull(WorkshopKeyHelp.bindingAction("workshop:markup"));
        assertNull(WorkshopKeyHelp.bindingAction("workshop:tools"));
        assertNull(WorkshopKeyHelp.bindingAction(null));
        assertEquals("Label", WorkshopKeyHelp.withKey("Label", null));
        assertEquals("Label · Z", WorkshopKeyHelp.withKey("Label", "Z"));
    }

    @Test
    void keysEntryIsAppendedOnceAndRespectsTheSectorLimit() {
        var entries = new JsonArray();
        WorkshopRadialScreen.appendKeys(entries);
        assertEquals(1, entries.size());
        assertEquals("keys", entries.get(0).getAsJsonObject().get("action").getAsString());
        WorkshopRadialScreen.appendKeys(entries);
        assertEquals(1, entries.size());
        var full = new JsonArray();
        for (int i = 0; i < WorkshopRadial.MAX_SECTORS; i++) {
            var entry = new JsonObject();
            entry.addProperty("action", "workshop:tool:copy");
            full.add(entry);
        }
        WorkshopRadialScreen.appendKeys(full);
        assertEquals(WorkshopRadial.MAX_SECTORS, full.size());
    }

    @Test
    void ringGeometrySupportsTheKeysSector() {
        for (int i = 0; i < WorkshopRadial.MAX_SECTORS; i++) {
            double angle = i * Math.PI * 2 / WorkshopRadial.MAX_SECTORS;
            assertEquals(
                    i,
                    WorkshopRadial.sector(
                            60 * Math.sin(angle),
                            -60 * Math.cos(angle),
                            23,
                            100,
                            WorkshopRadial.MAX_SECTORS));
        }
        assertEquals(-1, WorkshopRadial.sector(60, 0, 23, 100, WorkshopRadial.MAX_SECTORS + 1));
    }

    @Test
    void radialKeysSectorUsesTheShortLabel() {
        var entry = new JsonObject();
        entry.addProperty("action", "keys");
        assertEquals("blockfield.workshop.radial.keys_label", WorkshopRadialScreen.label(entry));
        assertEquals("blockfield.workshop.radial.keys_hint", WorkshopRadialScreen.hint(entry));
        var copy = new JsonObject();
        copy.addProperty("label", "Копировать");
        copy.addProperty("action", "workshop:build:copy");
        // Unbound in the headless binding table: the bare server label survives.
        assertEquals("Копировать", WorkshopRadialScreen.label(copy));
        assertTrue(WorkshopRadialScreen.centerHint().startsWith("blockfield.workshop.radial."));
    }

    @Test
    void cardAutoShowsOncePerSessionUntilDismissed() {
        assertTrue(WorkshopKeyHelp.shouldAutoShow(true, false, false, false, false, false));
        assertFalse(WorkshopKeyHelp.shouldAutoShow(false, false, false, false, false, false));
        assertFalse(WorkshopKeyHelp.shouldAutoShow(true, true, false, false, false, false));
        assertFalse(WorkshopKeyHelp.shouldAutoShow(true, false, true, false, false, false));
        assertFalse(WorkshopKeyHelp.shouldAutoShow(true, false, false, true, false, false));
        assertFalse(WorkshopKeyHelp.shouldAutoShow(true, false, false, false, true, false));
        assertFalse(WorkshopKeyHelp.shouldAutoShow(true, false, false, false, false, true));
        WorkshopKeyHelp.resetSession();
        assertFalse(WorkshopKeyHelp.seenWorkshop());
        assertFalse(WorkshopKeyHelp.dismissed());
        WorkshopKeyHelp.dismiss();
        assertTrue(WorkshopKeyHelp.dismissed());
        WorkshopKeyHelp.resetSession();
        assertFalse(WorkshopKeyHelp.dismissed());
    }

    @Test
    void dismissalAndOptOutPersist() {
        var saved = new ArrayList<Boolean>();
        boolean[] backing = {false};
        WorkshopKeyHelp.store(
                new WorkshopKeyHelp.HideStore() {
                    @Override
                    public boolean get() {
                        return backing[0];
                    }

                    @Override
                    public void set(boolean hide) {
                        backing[0] = hide;
                        saved.add(hide);
                    }
                });
        try {
            WorkshopKeyHelp.resetSession();
            assertFalse(WorkshopKeyHelp.suppressAuto());
            WorkshopKeyHelp.setSuppressAuto(true);
            assertTrue(WorkshopKeyHelp.suppressAuto());
            assertFalse(
                    WorkshopKeyHelp.shouldAutoShow(
                            true, false, false, false, false, WorkshopKeyHelp.suppressAuto()));
            // A new session clears dismissal but keeps the stored opt-out.
            WorkshopKeyHelp.dismiss();
            WorkshopKeyHelp.resetSession();
            assertFalse(WorkshopKeyHelp.dismissed());
            assertTrue(WorkshopKeyHelp.suppressAuto());
            assertEquals(List.of(true), saved);
        } finally {
            WorkshopKeyHelp.resetSession();
            WorkshopKeyHelp.resetStore();
        }
    }

    @Test
    void everyRowIsReachableOnTheSmallestGui() {
        // 5 group titles + 15 bindings at 854×480 (427×240 GUI): the card is clamped (D6).
        int lines = 20;
        int[] size = WorkshopKeyHelp.cardSize(280, lines);
        int[] at = WorkshopKeyHelp.placeCard(427, 240, size[0], size[1]);
        int visible = WorkshopKeyHelp.visibleLines(at[3]);
        assertTrue(visible < lines);
        // Rows stop above the buttons (drawn from cardH − 26).
        assertTrue(24 + visible * 10 <= at[3] - 26);
        int last = WorkshopKeyHelp.clampScroll(Integer.MAX_VALUE, lines, visible);
        assertEquals(lines - 1, last + visible - 1);
        assertEquals(0, WorkshopKeyHelp.clampScroll(-3, lines, visible));
        assertEquals(0, WorkshopKeyHelp.clampScroll(5, lines, lines + 2));
    }

    @Test
    void cardPlacementClearsChatMinimapAndHotbar() {
        int[] size = WorkshopKeyHelp.cardSize(280, 19);
        assertArrayEquals(new int[] {296, 242}, size);
        boolean placed = false;
        for (int[] pixels : new int[][] {{1280, 720}, {1920, 1080}})
            for (int scale = 2; scale <= 4; scale++) {
                int sw = pixels[0] / scale, sh = pixels[1] / scale;
                int[] minimap = {4, 4, 80, 96};
                for (double chatScale : new double[] {0.5, 1})
                    for (int rows : new int[] {0, 5, 20})
                        for (boolean open : new boolean[] {false, true}) {
                            int[] ages = new int[rows];
                            java.util.Arrays.fill(ages, open ? 500 : 190);
                            int[] chat =
                                    WorkshopChatArea.bounds(
                                            320, sh, chatScale, 9, 40, 20, ages, open, false);
                            int[] panel =
                                    WorkshopToolsClient.placePanel(
                                            sw, sh, size[0], size[1], minimap, chat);
                            if (panel == null) continue;
                            placed = true;
                            assertTrue(
                                    panel[0] >= 0
                                            && panel[1] >= 0
                                            && panel[0] + panel[2] <= sw
                                            && panel[1] + panel[3] <= sh);
                            assertFalse(
                                    WorkshopToolsClient.intersects(
                                            panel[0], panel[1], panel[2], panel[3], minimap));
                            assertFalse(
                                    WorkshopToolsClient.intersects(
                                            panel[0],
                                            panel[1],
                                            panel[2],
                                            panel[3],
                                            WorkshopToolsClient.hotbarRect(sw, sh)));
                            if (chat != null)
                                assertFalse(
                                        WorkshopToolsClient.intersects(
                                                panel[0], panel[1], panel[2], panel[3], chat));
                        }
                // The fallback never leaves the screen, even on tiny GUI scales.
                int[] fallback = WorkshopKeyHelp.placeCard(sw, sh, size[0], size[1]);
                assertTrue(
                        fallback[0] >= 0
                                && fallback[1] >= 0
                                && fallback[0] + fallback[2] <= sw
                                && fallback[1] + fallback[3] <= sh);
            }
        assertTrue(placed);
    }
}
