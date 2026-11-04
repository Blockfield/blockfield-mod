package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.client.gui.BfTheme;
import org.junit.jupiter.api.Test;

class WorkshopAiLayoutTest {
    /** Table «b»: GUI screen → sheet W, H and prompt P, all unscaled. */
    @Test
    void sheetSizesFollowTheTable() {
        int[][] table = {
            {320, 240, 300, 224, 48},
            {427, 240, 300, 224, 48},
            {426, 266, 300, 250, 74},
            {480, 270, 300, 254, 78},
            {640, 360, 300, 264, 88},
            {960, 540, 340, 264, 88},
        };
        for (int[] row : table) {
            var layout = WorkshopAiLayout.fit(row[0], row[1]);
            String at = row[0] + "×" + row[1];
            assertEquals(row[2], layout.width(), at);
            assertEquals(row[3], layout.height(), at);
            assertEquals(row[4], layout.promptHeight(), at);
            assertEquals(1f, layout.scale(), at);
            // Docked right with an 8 px margin, inside the screen.
            assertEquals(row[0] - 8 - row[2], layout.x(), at);
            assertTrue(layout.y() >= 0 && layout.y() + layout.height() <= row[1], at);
        }
    }

    @Test
    void rowsStackWithoutOverlapAndActionsFillTheInnerWidth() {
        for (int[] screen : new int[][] {{427, 240}, {960, 540}}) {
            var l = WorkshopAiLayout.fit(screen[0], screen[1]);
            assertEquals(28 + l.promptHeight() + 6, l.targetY());
            assertEquals(l.targetY() + 24, l.speedY());
            assertEquals(l.speedY() + 28, l.statusY());
            assertEquals(l.statusY() + WorkshopStatusBlock.HEIGHT + 8, l.actionsY());
            assertEquals(l.height() - 12, l.actionsY() + 20);
            assertEquals(l.inner(), l.primaryWidth() + 4 + 80 + 4 + 56);
            assertEquals(l.inner(), l.speedWidth(true) + 4 + l.voiceWidth());
            assertEquals(92, l.voiceWidth());
            assertEquals(l.inner(), l.speedWidth(false));
        }
        assertEquals(132, WorkshopAiLayout.fit(427, 240).primaryWidth());
        assertEquals(172, WorkshopAiLayout.fit(960, 540).primaryWidth());
    }

    @Test
    void tinyNonStandardWindowsScaleTheWholeSheet() {
        var layout = WorkshopAiLayout.fit(300, 200);
        assertTrue(layout.scale() < 1f);
        assertTrue(layout.x() >= 0);
        assertTrue(layout.y() + layout.height() * layout.scale() <= 200);
        assertEquals(16, layout.localX(layout.x() + 16 * layout.scale()), 0.001);
        assertEquals(56, layout.localY(layout.y() + 56 * layout.scale()), 0.001);
    }

    @Test
    void segmentHitTestSplitsInHalves() {
        assertEquals(0, WorkshopSegmented.segmentAt(20, 12, 276));
        assertEquals(1, WorkshopSegmented.segmentAt(200, 12, 276));
        assertEquals(-1, WorkshopSegmented.segmentAt(10, 12, 276));
        assertEquals(-1, WorkshopSegmented.segmentAt(288, 12, 276));
    }

    @Test
    void statusMessageGetsTheRowsTheExtrasLeave() {
        var plain = WorkshopStatusBlock.Status.of(0, "m", 0);
        assertEquals(3, budget(plain));
        var working = plain.withBar(0.5f, 0).withTime("1:48", 0).withHint("h", 0).withWaiting("w");
        assertEquals(1, budget(working));
        assertEquals(2, budget(plain.withTitle("t", 0).withHint("h", 0)));
    }

    /**
     * Message line budget through the real row layout: a 60-word message always binds, while the
     * one-letter fixed rows stay on one line at this width.
     */
    private static int budget(WorkshopStatusBlock.Status s) {
        var probe =
                new WorkshopStatusBlock.Status(
                        s.accent(),
                        s.title(),
                        s.titleColor(),
                        "слово ".repeat(60).strip(),
                        s.messageColor(),
                        s.bar(),
                        s.barColor(),
                        s.time(),
                        s.timeColor(),
                        s.waiting(),
                        s.hint(),
                        s.hintColor());
        return WorkshopStatusBlock.layout(probe, 360, WorkshopAiLayoutTest::wrap6).message().size();
    }

    /** Worst-case 6 GUI px per character, greedy word wrap like the font splitter. */
    static BfTheme.Wrapped wrap6(String text, int width, int maxLines) {
        var lines = new java.util.ArrayList<String>();
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
        return new BfTheme.Wrapped(new java.util.ArrayList<>(lines.subList(0, maxLines)), true);
    }

    private static com.google.gson.JsonObject lang(String language) throws Exception {
        try (var stream =
                WorkshopAiLayoutTest.class.getResourceAsStream(
                        "/assets/blockfield/lang/" + language + ".json")) {
            return com.google.gson.JsonParser.parseReader(
                            new java.io.InputStreamReader(
                                    stream, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static String ai(com.google.gson.JsonObject t, String key) {
        return t.get("blockfield.workshop.ai." + key).getAsString();
    }

    private static String joined(java.util.List<String> lines) {
        return String.join(" ", lines);
    }

    /**
     * R4 follow-up 1: rows share one budget, so the total never exceeds the 50 px block (bar 7 px,
     * every text row 10 px). Fails on the old layout: bar + 1 title + 2 time + 2 hint rows stacked
     * 57 px while the hint anchored to the bottom. Widths are the block text widths of the scale
     * matrix (inner 276/316, with and without the 80 px undo slot).
     */
    @Test
    void statusRowsNeverExceedTheBlock() {
        String longText = "слово ".repeat(50).strip();
        String medium = "слово ".repeat(20).strip();
        int failures = 0;
        for (int width : new int[] {266, 186, 306, 226})
            for (boolean bar : new boolean[] {false, true})
                for (String title : new String[] {"", "Заголовок"})
                    for (String message : new String[] {"", "Коротко", longText})
                        for (String time : new String[] {"", medium})
                            for (String waiting : new String[] {"", medium})
                                for (String hint : new String[] {"", medium}) {
                                    var status =
                                            WorkshopStatusBlock.Status.of(0, message, 0)
                                                    .withTitle(title, 0)
                                                    .withTime(time, 0)
                                                    .withWaiting(waiting)
                                                    .withHint(hint, 0);
                                    if (bar) status = status.withBar(0.5f, 0);
                                    var rows =
                                            WorkshopStatusBlock.layout(
                                                    status, width, WorkshopAiLayoutTest::wrap6);
                                    int lines =
                                            rows.title().size()
                                                    + rows.message().size()
                                                    + rows.time().size()
                                                    + rows.waiting().size()
                                                    + rows.hint().size();
                                    int px = lines * 10 + (bar ? 7 : 0);
                                    if (px > WorkshopStatusBlock.HEIGHT - 2) failures++;
                                    assertTrue(
                                            px <= WorkshopStatusBlock.HEIGHT - 2,
                                            width
                                                    + " "
                                                    + lines
                                                    + " "
                                                    + title.isEmpty()
                                                    + message.isEmpty()
                                                    + time.isEmpty()
                                                    + waiting.isEmpty()
                                                    + hint.isEmpty());
                                }
        // The old code fails the all-long tracked-job case; the sweep above must stay clean.
        assertEquals(0, failures);
    }

    @Test
    void trackedJobWithWrappedTimeAndHintKeepsFourRows() {
        // The exact repro: bar, empty message, title + 2-line time + 2-line hint.
        var status =
                WorkshopStatusBlock.Status.of(0, "", 0)
                        .withTitle("t", 0)
                        .withBar(0.5f, 0)
                        .withTime("слово ".repeat(30).strip(), 0)
                        .withHint("слово ".repeat(30).strip(), 0);
        var rows = WorkshopStatusBlock.layout(status, 120, WorkshopAiLayoutTest::wrap6);
        assertEquals(1, rows.time().size());
        assertEquals(2, rows.hint().size());
        assertTrue(rows.timeCut());
        assertFalse(rows.messageCut());
        assertEquals("time", rows.moreOn());
        int lines = 1 + rows.message().size() + 1 + rows.waiting().size() + 2;
        assertTrue(lines * 10 + 7 <= WorkshopStatusBlock.HEIGHT - 2);
    }

    /**
     * R4 follow-up 5: only the cut row owns the «…Подробнее» cue. Old code ellipsized the last
     * message line whenever anything overflowed — even an uncut message — and showed no cue at all
     * when the message was empty.
     */
    @Test
    void moreCueBelongsToTheCutRow() {
        // Cut message, clean hint: cue on the message (existing behaviour).
        var cutMessage =
                WorkshopStatusBlock.layout(
                        WorkshopStatusBlock.Status.of(0, "слово ".repeat(60).strip(), 0)
                                .withHint("Коротко", 0),
                        360,
                        WorkshopAiLayoutTest::wrap6);
        assertTrue(cutMessage.messageCut());
        assertFalse(cutMessage.hintCut());
        assertEquals("message", cutMessage.moreOn());
        // Clean message, cut hint: cue on the hint, never on the message.
        var cutHint =
                WorkshopStatusBlock.layout(
                        WorkshopStatusBlock.Status.of(0, "Всё хорошо", 0)
                                .withHint("слово ".repeat(60).strip(), 0),
                        120,
                        WorkshopAiLayoutTest::wrap6);
        assertFalse(cutHint.messageCut());
        assertTrue(cutHint.hintCut());
        assertEquals("hint", cutHint.moreOn());
        // Empty message, cut hint: cue on the hint, not just a focus ring.
        var emptyMessage =
                WorkshopStatusBlock.layout(
                        WorkshopStatusBlock.Status.of(0, "", 0)
                                .withHint("слово ".repeat(60).strip(), 0),
                        120,
                        WorkshopAiLayoutTest::wrap6);
        assertTrue(emptyMessage.overflow());
        assertEquals("hint", emptyMessage.moreOn());
    }

    /**
     * A1/A10/A12 acceptance rows (screenshots {@code *-854-scale2.jpg}): the idle safety hint, the
     * overdue hint and the preview WARN + hint keep every word at every block width of the scale
     * matrix (inner 276/316, with and without the 80 px undo slot).
     */
    @Test
    void acceptanceRowsKeepEveryWord() throws Exception {
        for (String language : new String[] {"ru_ru", "en_us"}) {
            var t = lang(language);
            for (int width : new int[] {186, 226, 266, 306}) {
                String at = language + " w" + width;
                var idle =
                        WorkshopStatusBlock.layout(
                                WorkshopStatusBlock.Status.of(0, ai(t, "stage.idle"), 0)
                                        .withHint(ai(t, "idle_hint"), 0),
                                width,
                                WorkshopAiLayoutTest::wrap6);
                assertEquals(ai(t, "idle_hint"), joined(idle.hint()), at);
                assertFalse(idle.overflow(), at);
                var overdue =
                        WorkshopStatusBlock.layout(
                                WorkshopStatusBlock.Status.of(0, ai(t, "phase.generation"), 0)
                                        .withBar(0.95f, 0)
                                        .withTime(
                                                ai(t, "progress.elapsed").replace("%s", "0:27")
                                                        + " · "
                                                        + ai(t, "progress.longer"),
                                                0)
                                        .withHint(ai(t, "progress.longer_hint"), 0),
                                width,
                                WorkshopAiLayoutTest::wrap6);
                assertEquals(ai(t, "progress.longer_hint"), joined(overdue.hint()), at);
                // Preview never shares its block with the 80 px undo slot, so 186 px (slot
                // widths) is out of scope; 226 covers the narrowest slot-less block.
                if (width == 186) continue;
                var preview =
                        WorkshopStatusBlock.layout(
                                WorkshopStatusBlock.Status.of(0, ai(t, "stage.preview"), 0)
                                        .withTime(
                                                ai(t, "bounds")
                                                        .replace("%1$s", "907")
                                                        .replace("%2$s", "16")
                                                        .replace("%3$s", "8")
                                                        .replace("%4$s", "16"),
                                                0)
                                        .withWaiting(ai(t, "simplified_hint"))
                                        .withHint(ai(t, "preview_hint"), 0),
                                width,
                                WorkshopAiLayoutTest::wrap6);
                assertEquals(ai(t, "simplified_hint"), joined(preview.waiting()), at);
                assertEquals(ai(t, "preview_hint"), joined(preview.hint()), at);
            }
        }
    }
}
