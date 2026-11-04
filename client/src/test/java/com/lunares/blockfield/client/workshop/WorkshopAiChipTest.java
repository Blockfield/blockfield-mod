package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.gui.BfTheme;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class WorkshopAiChipTest {
    /** Worst-case 6 GUI px per character, greedy word wrap like the font splitter. */
    private static BfTheme.Wrapped wrap(String text, int width, int maxLines) {
        var lines = new ArrayList<String>();
        String line = "";
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (next.length() * 6 > width && !line.isEmpty()) {
                lines.add(line);
                line = word;
            } else line = next;
        }
        if (!line.isEmpty()) lines.add(line);
        if (lines.size() <= maxLines) return new BfTheme.Wrapped(lines, false);
        return new BfTheme.Wrapped(new ArrayList<>(lines.subList(0, maxLines)), true);
    }

    private static JsonObject table(String language) throws Exception {
        try (var stream =
                WorkshopAiChipTest.class.getResourceAsStream(
                        "/assets/blockfield/lang/" + language + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static String tools(JsonObject t, String key) {
        return t.get("blockfield.workshop.tools." + key).getAsString();
    }

    @Test
    void handChipStaysWithinTwoLinesAndUncutFromGui427() throws Exception {
        for (String language : new String[] {"ru_ru", "en_us"}) {
            var t = table(language);
            var view =
                    new WorkshopAiChip.View(
                            0,
                            false,
                            tools(t, "panel.hand_state").replace("%1$s", "270"),
                            0,
                            tools(t, "panel.height_max").replace("%s", "+128"),
                            0,
                            -1,
                            0,
                            tools(t, "panel.hand")
                                    .replace("%1$s", "ПКМ")
                                    .replace("%2$s", "ЛКМ")
                                    .replace("%3$s", "R"),
                            "");
            String head = WorkshopAiChip.head(view);
            for (int screen : new int[] {320, 427, 480, 640, 960}) {
                int w = WorkshopAiChip.width(head.length() * 6, view.keys().length() * 6, screen);
                var lines = WorkshopAiChip.lines(view, w - 12, WorkshopAiChipTest::wrap);
                assertTrue(lines.size() <= WorkshopAiChip.MAX_LINES, language + " " + screen);
                if (screen >= 427) {
                    assertEquals(2, lines.size(), language + " " + screen);
                    assertFalse(lines.headCut(), language + " " + screen);
                    assertEquals(view.keys(), lines.keys().get(0), language + " " + screen);
                }
            }
        }
    }

    @Test
    void chipWithoutKeysMayWrapItsTitle() {
        var view =
                new WorkshopAiChip.View(0, false, "x ".repeat(40).strip(), 0, "", 0, -1, 0, "", "");
        var lines = WorkshopAiChip.lines(view, 200, WorkshopAiChipTest::wrap);
        assertEquals(2, lines.head().size());
        assertTrue(lines.keys().isEmpty());
    }
}
