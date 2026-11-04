package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class WorkshopMenuPanelTest {
    private static JsonObject map(String label, String hint, String version, boolean special) {
        var entry = new JsonObject();
        entry.addProperty("label", label);
        entry.addProperty("action", "workshop:map:" + label);
        if (hint != null) entry.addProperty("hint", hint);
        if (version != null) entry.addProperty("version", version);
        if (special) entry.addProperty("special", true);
        return entry;
    }

    @Test
    void versionLeavesTheRowForTheIconTooltip() {
        JsonObject entry = map("Ancient", "вы здесь · work-8a40f350", "work-8a40f350", false);
        assertEquals("work-8a40f350", WorkshopMenuPanel.versionOf(entry));
        assertEquals("вы здесь", WorkshopMenuPanel.statusOf(entry));
    }

    @Test
    void hintEqualToVersionLeavesNoStatus() {
        JsonObject entry = map("AWP Ice", "legacy-20261008", "legacy-20261008", false);
        assertEquals("", WorkshopMenuPanel.statusOf(entry));
    }

    @Test
    void legacyServersWithoutThePropertyKeepTheFullHint() {
        JsonObject entry = map("Anubis", "work-8a40f350", null, false);
        assertEquals("", WorkshopMenuPanel.versionOf(entry));
        assertEquals("work-8a40f350", WorkshopMenuPanel.statusOf(entry));
    }

    @Test
    void specialsGroupUnderACaption() {
        var entries = new JsonArray();
        entries.add(map("Ancient", null, "work-1", false));
        entries.add(map("Лимбо", null, "work-2", true));
        entries.add(map("Бахмут", null, "work-3", false));
        JsonArray grouped = WorkshopMenuPanel.grouped(entries, "Особые");
        assertEquals(4, grouped.size());
        assertEquals("Ancient", grouped.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("Бахмут", grouped.get(1).getAsJsonObject().get("label").getAsString());
        assertTrue(WorkshopMenuPanel.isCaption(grouped.get(2).getAsJsonObject()));
        assertEquals("Лимбо", grouped.get(3).getAsJsonObject().get("label").getAsString());
    }

    @Test
    void singleGroupListsStayFlat() {
        var entries = new JsonArray();
        entries.add(map("Ancient", null, "work-1", false));
        entries.add(map("Бахмут", null, "work-3", false));
        assertEquals(2, WorkshopMenuPanel.grouped(entries, "Особые").size());
        var specials = new JsonArray();
        specials.add(map("Лимбо", null, "work-2", true));
        assertEquals(1, WorkshopMenuPanel.grouped(specials, "Особые").size());
    }

    @Test
    void captionBreaksWithItsFirstSpecial() {
        // Seven 20 px rows, an 18 px caption and two specials in a 160 px room: the caption
        // must not dangle at the bottom of page one.
        int[] heights = {20, 20, 20, 20, 20, 20, 20, 18, 20, 20};
        boolean[] keep = new boolean[heights.length];
        keep[7] = true;
        assertEquals(java.util.List.of(0, 7), WorkshopMenuPanel.pageBreaks(heights, keep, 160));
        // Without the flag the old greedy split strands it.
        assertEquals(
                java.util.List.of(0, 8),
                WorkshopMenuPanel.pageBreaks(heights, new boolean[heights.length], 160));
    }
}
