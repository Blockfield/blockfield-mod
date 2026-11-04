package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkshopRadialTest {
    @Test
    void anglesDeadZoneAndOuterLimitUseTheSameGeometryAsDrawing() {
        for (int count = 1; count <= 8; count++)
            for (int i = 0; i < count; i++) {
                double angle = i * Math.PI * 2 / count;
                assertEquals(
                        i,
                        WorkshopRadial.sector(
                                60 * Math.sin(angle), -60 * Math.cos(angle), 23, 100, count));
            }
        assertEquals(-1, WorkshopRadial.sector(0, 0, 23, 100, 8));
        assertEquals(-1, WorkshopRadial.sector(23, 0, 23, 100, 8));
        assertEquals(-1, WorkshopRadial.sector(101, 0, 23, 100, 8));
        assertEquals(2, WorkshopRadial.sector(60, 0, 23, 100, 8));
        assertEquals(4, WorkshopRadial.sector(0, 60, 23, 100, 8));
        assertEquals(6, WorkshopRadial.sector(-60, 0, 23, 100, 8));
    }

    @Test
    void holdReleaseTapAndOneLevelNesting() {
        // A quick tap always cancels: it must never leave the menu latched open.
        assertEquals(WorkshopRadial.Release.CANCEL, WorkshopRadial.release(179, -1));
        assertEquals(WorkshopRadial.Release.CANCEL, WorkshopRadial.release(100, 2));
        assertEquals(WorkshopRadial.Release.CANCEL, WorkshopRadial.release(180, -1));
        assertEquals(WorkshopRadial.Release.ACTIVATE, WorkshopRadial.release(500, 3));
        assertTrue(WorkshopRadial.nested(false, 8, false));
        assertFalse(WorkshopRadial.nested(true, 8, false));
        assertFalse(WorkshopRadial.nested(false, 9, false));
        assertFalse(WorkshopRadial.nested(false, 3, true));
    }

    @Test
    void panelsLeaveWorldVisibleAtEveryRequestedResolutionAndGuiScale() {
        for (int[] pixels : new int[][] {{1280, 720}, {1920, 1080}})
            for (int scale = 2; scale <= 4; scale++) {
                int sw = pixels[0] / scale, sh = pixels[1] / scale;
                var panel = WorkshopAiLayout.fit(sw, sh);
                // The 300 px minimum sheet covers most of a 320 px GUI; wider GUIs keep the world.
                if (sw >= 426) assertTrue(panel.width() * panel.scale() <= sw * .72);
                assertTrue(panel.x() + panel.width() * panel.scale() <= sw - 7);
                assertTrue(panel.y() > 0);
                assertTrue(panel.y() + panel.height() * panel.scale() < sh);
            }
    }

    @Test
    void labelsSharingARowNeverOverlap() { // GUI scale 4 on 1280x720 gives radius 72; the
        // seven-entry clipboard group put its
        // bottom pair 42 px apart while each label could take 48 px.
        int r = 72, line = 9;
        int[] left = WorkshopRadial.labelOffset(3, 7, r),
                right = WorkshopRadial.labelOffset(4, 7, r);
        assertEquals(left[1], right[1]);
        int gap = Math.abs(right[0] - left[0]);
        assertEquals(42, gap);
        assertEquals(38, WorkshopRadial.labelWidth(3, 7, r, line));
        assertEquals(38, WorkshopRadial.labelWidth(4, 7, r, line));
        // A label alone on its row keeps the full width.
        assertEquals(48, WorkshopRadial.labelWidth(0, 7, r, line));
        assertEquals(48, WorkshopRadial.labelWidth(2, 8, r, line));
        for (int count = 1; count <= WorkshopRadial.MAX_SECTORS; count++)
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                int[] a = WorkshopRadial.labelOffset(i, count, r),
                        b = WorkshopRadial.labelOffset(j, count, r);
                if (i == j || Math.abs(a[1] - b[1]) > line) continue;
                int half =
                        (WorkshopRadial.labelWidth(i, count, r, line)
                                        + WorkshopRadial.labelWidth(j, count, r, line))
                                / 2;
                assertTrue(half < Math.abs(a[0] - b[0]), count + ":" + i);
            }
    }

    /** Worst-case 6 GUI px per character, greedy word wrap like the font splitter. */
    private static java.util.List<String> wrap6(String text, int width, int maxLines) {
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
        return lines.size() <= maxLines
                ? lines
                : new java.util.ArrayList<>(lines.subList(0, maxLines));
    }

    @Test
    void sectorCaptionsWrapToTwoLinesWithoutLosingWords() {
        // D2 (`D2-radial-map-854.jpg`): the matrix minimum is radius 98 (854×480, GUI 2);
        // every real caption keeps its words in at most two lines at the narrowest chord of
        // 4..9 sectors there. Server labels are literals (no lang keys), so they are listed.
        int worst = Integer.MAX_VALUE;
        for (int count = 4; count <= WorkshopRadial.MAX_SECTORS; count++)
            for (int i = 0; i < count; i++)
                worst =
                        Math.min(
                                worst,
                                WorkshopRadial.labelWidth(
                                        i, count, WorkshopRadialScreen.radiusForHeight(240), 9));
        String[] captions = {
            "ИИ",
            "Выделение",
            "Буфер",
            "Карты",
            "Люди",
            "Разметка",
            "На проверку",
            "Продлить",
            "Клавиши",
            "Описать",
            "Библиотека",
            "Отменить задачу",
            "Отменить постройку",
            "Включить",
            "Выключить",
            "Инструменты",
            "Жезл WorldEdit",
            "Угол А",
            "Угол Б",
            "Заполнить",
            "Стены",
            "Очистить",
            "Копировать",
            "Вырезать",
            "Вставить",
            "Повернуть",
            "Отменить",
            "Повторить",
            "Копия встык",
        };
        for (String caption : captions) {
            var lines = wrap6(caption, worst, 99);
            assertTrue(lines.size() <= 2, worst + " " + caption);
            assertEquals(caption, String.join(" ", lines), worst + " " + caption);
        }
    }

    @Test
    void edgeCoverageAntiAliasesRimAndSeparators() {
        // Interior and exterior pixels are exact; rim and separator pixels are partial.
        assertEquals(1.0, WorkshopRadial.coverage(59, 0, 23, 100, 8, 2));
        assertEquals(0.0, WorkshopRadial.coverage(200, 0, 23, 100, 8, 2));
        assertEquals(0.0, WorkshopRadial.coverage(0, 0, 23, 100, 8, 2));
        double rim = WorkshopRadial.coverage(98, -18, 23, 100, 8, 2);
        assertTrue(rim > 0 && rim < 1, "rim " + rim);
        // The 22.5° ray is the sector 0/1 divider: pixel (22,-56) is clipped by the
        // separator gap (2/16 in sector 0, the rest gap), the neighbour across it is full.
        assertEquals(2 / 16.0, WorkshopRadial.coverage(22, -56, 23, 100, 8, 0));
        assertEquals(0.0, WorkshopRadial.coverage(22, -56, 23, 100, 8, 1));
        assertEquals(1.0, WorkshopRadial.coverage(24, -56, 23, 100, 8, 1));
    }

    @Test
    void drawSectorKeepsHitGeometryMinusTheGap() {
        assertEquals(2, WorkshopRadial.drawSector(60, 0.5, 23, 100, 8));
        assertEquals(
                -1,
                WorkshopRadial.drawSector(
                        60 * Math.sin(Math.PI / 8), -60 * Math.cos(Math.PI / 8), 23, 100, 8));
        assertEquals(-1, WorkshopRadial.drawSector(0, 0, 23, 100, 8));
        assertEquals(-1, WorkshopRadial.drawSector(101, 0, 23, 100, 8));
    }

    @Test
    void labelsShrinkToFitNarrowSectors() {
        assertEquals(1f, WorkshopRadial.labelScale(4, 98, 70));
        assertEquals(1f, WorkshopRadial.labelScale(8, 98, 10));
        assertEquals(1f, WorkshopRadial.labelScale(1, 98, 50));
        assertEquals(1f, WorkshopRadial.labelScale(8, 98, 0));
        // «Выделение» (59 px ink) in an 8-sector wheel at radius 98: the chord is ~55 px.
        float scale = WorkshopRadial.labelScale(8, 98, 59);
        assertTrue(scale < 1, "" + scale);
        double chord = 2 * (98 * .68) * Math.tan(Math.PI / 8) - 4;
        assertTrue(scale * 59 <= chord + 1e-6, scale + " " + chord);
        // The floor keeps captions readable instead of vanishing.
        assertEquals(0.7f, WorkshopRadial.labelScale(9, 98, 1000));
    }

    @Test
    void hoverCentreKeepsTheFullLabel() {
        assertEquals(
                "Продлить · N — умная заливка",
                WorkshopRadialScreen.hoverText("Продлить", "N", "умная заливка"));
        assertEquals("Клавиши · Z", WorkshopRadialScreen.hoverText("Клавиши", "Z", ""));
        assertEquals("Разметка", WorkshopRadialScreen.hoverText("Разметка", null, "Разметка"));
        assertEquals("Буфер", WorkshopRadialScreen.hoverText("Буфер", null, ""));
        // The longest real hover line still fits two centre lines (radius 98 → 137 px) …
        var lines =
                wrap6(
                        WorkshopRadialScreen.hoverText("Продлить", "E", "Дождись конца задачи"),
                        137,
                        99);
        assertEquals(2, lines.size());
        // … and past the budget the bare label (never «…») is what the wheel falls back to.
        var longHover =
                WorkshopRadialScreen.hoverText(
                        "Копия встык", "K", "копия эскиза рядом без AI-вызова");
        assertTrue(wrap6(longHover, 137, 99).size() > 2, longHover);
        assertTrue(longHover.startsWith("Копия встык · K"));
    }

    @Test
    void captionsNeverSplitAWordAndRowPairsStayApart() {
        // R3 (stand 3): «Разметка» got 40 px at 854×480 GUI 2 and the splitter drew «Размет/ка».
        // Widths are IBM Plex Sans at the UI size 11, rounded up.
        var plex = java.util.Map.of("Разметка", 49, "Люди", 30, "Повернуть", 56, "Отменить", 51);
        assertEquals(49, WorkshopRadialScreen.captionWidth("Разметка", 40, plex::get));
        assertEquals(66, WorkshopRadialScreen.captionWidth("На проверку", 66, s -> s.length() * 6));
        // The only row-sharing pairs of real menus: map wheel (9) and the nested buffer (7).
        String[][] pairs = {{"Люди", "Разметка", "9", "4"}, {"Повернуть", "Отменить", "7", "3"}};
        for (int radius : new int[] {98, 112})
            for (String[] pair : pairs) {
                int count = Integer.parseInt(pair[2]), i = Integer.parseInt(pair[3]);
                int dx =
                        Math.abs(
                                WorkshopRadial.labelOffset(i, count, radius)[0]
                                        - WorkshopRadial.labelOffset(i + 1, count, radius)[0]);
                assertTrue(
                        (plex.get(pair[0]) + plex.get(pair[1])) / 2 < dx,
                        radius + " " + pair[0] + "/" + pair[1]);
            }
        // Smallest wheel (radiusForHeight reserves the hotbar only): the nudge clamps at 6,
        // so the full 8 px need not hold — but with the common wheel scale the pair must not
        // overlap (the scale shrinks ink, never the centre offsets or the nudge).
        int small = WorkshopRadialScreen.radiusForHeight(240);
        for (String[] pair : pairs) {
            int count = Integer.parseInt(pair[2]), i = Integer.parseInt(pair[3]);
            int[] a = WorkshopRadial.labelOffset(i, count, small),
                    b = WorkshopRadial.labelOffset(i + 1, count, small);
            int[] ink = inkForPair(count, i, new int[] {plex.get(pair[0]), plex.get(pair[1])});
            int[] shift = WorkshopRadial.captionShifts(small, count, ink, 9);
            float common = WorkshopRadial.commonScale(count, small, ink);
            double gap =
                    Math.abs(a[0] - b[0])
                            + Math.abs(shift[i])
                            + Math.abs(shift[i + 1])
                            - common * (ink[i] + ink[i + 1]) / 2.0;
            assertTrue(gap > 0, small + " " + pair[0] + "/" + pair[1] + " " + gap);
        }
    }

    @Test
    void hintPlacementKeepsWheelLarge() {
        // S4: the wheel reserves the hotbar only, so at 427×240 it stays large (radius 96)
        // and the hint moves to the wheel's right, where the full height is free; at 640×360
        // and up the hint stays below the wheel, clear of the wheel and the hotbar.
        for (int height = 240; height <= 540; height++) {
            int cy = height / 2,
                    radius = Math.min(112, WorkshopRadialScreen.radiusForHeight(height));
            assertEquals(Math.min(112, height / 2 - 24), radius, "" + height);
            if (WorkshopRadialScreen.hintY(cy, radius) + 2 * 10 <= height - 24) {
                assertTrue(WorkshopRadialScreen.hintBelow(cy, radius, height), "" + height);
                assertEquals(cy + radius + 7, WorkshopRadialScreen.hintY(cy, radius));
            } else assertFalse(WorkshopRadialScreen.hintBelow(cy, radius, height), "" + height);
        }
        assertFalse(WorkshopRadialScreen.hintBelow(120, 96, 240));
        assertTrue(WorkshopRadialScreen.hintBelow(180, 112, 360));
    }

    @Test
    void keysSectorLabelIsOneShortWordInBothLanguages() throws Exception {
        for (String language : new String[] {"ru_ru", "en_us"}) {
            try (var stream =
                    WorkshopRadialTest.class.getResourceAsStream(
                            "/assets/blockfield/lang/" + language + ".json")) {
                var table =
                        com.google.gson.JsonParser.parseReader(
                                        new java.io.InputStreamReader(
                                                stream, java.nio.charset.StandardCharsets.UTF_8))
                                .getAsJsonObject();
                String label = table.get("blockfield.workshop.radial.keys_label").getAsString();
                assertFalse(label.contains("…"), language);
                assertTrue(wrap6(label, 38, 2).size() <= 2, language + " " + label);
            }
        }
    }

    /**
     * IBM Plex Sans 11 ink widths, measured from the bundled {@code plex-sans.ttf} (pointsize 11
     * render + trim, rounded up; the four pair words match the acceptance values exactly). Centred
     * ink, so the rect is symmetric.
     */
    static final java.util.Map<String, Integer> PLEX =
            java.util.Map.ofEntries(
                    java.util.Map.entry("ИИ", 16),
                    java.util.Map.entry("Выделение", 59),
                    java.util.Map.entry("Буфер", 33),
                    java.util.Map.entry("Карты", 32),
                    java.util.Map.entry("Продлить", 50),
                    java.util.Map.entry("Клавиши", 47),
                    java.util.Map.entry("Копировать", 61),
                    java.util.Map.entry("Вырезать", 50),
                    java.util.Map.entry("Вставить", 49),
                    java.util.Map.entry("Повернуть", 56),
                    java.util.Map.entry("Отменить", 51),
                    java.util.Map.entry("Повторить", 55),
                    java.util.Map.entry("Копия", 31),
                    java.util.Map.entry("встык", 32),
                    java.util.Map.entry("Включить", 51),
                    java.util.Map.entry("Инструменты", 70),
                    java.util.Map.entry("Жезл", 29),
                    java.util.Map.entry("WorldEdit", 49),
                    java.util.Map.entry("Угол", 29),
                    java.util.Map.entry("А", 11),
                    java.util.Map.entry("Б", 11),
                    java.util.Map.entry("Заполнить", 56),
                    java.util.Map.entry("Стены", 33),
                    java.util.Map.entry("Очистить", 49),
                    java.util.Map.entry("Люди", 30),
                    java.util.Map.entry("Разметка", 49),
                    java.util.Map.entry("На", 14),
                    java.util.Map.entry("проверку", 49));

    /** Whole-line ink where bearings make words-plus-spaces wrong; else words + 4 px spaces. */
    static final java.util.Map<String, Integer> PHRASE =
            java.util.Map.of(
                    "Угол А", 34,
                    "Угол Б", 33,
                    "Жезл WorldEdit", 80,
                    "Копия встык", 65);

    /** Greedy wrap by real ink widths, mirroring the font splitter (no word is ever split). */
    static java.util.List<java.util.List<String>> wrapReal(String text, int box) {
        var lines = new java.util.ArrayList<java.util.List<String>>();
        var line = new java.util.ArrayList<String>();
        int width = 0;
        for (String word : text.split(" ")) {
            int next = width + (line.isEmpty() ? 0 : 4) + PLEX.get(word);
            if (!line.isEmpty() && next > box) {
                lines.add(line);
                line = new java.util.ArrayList<>();
                width = 0;
                next = PLEX.get(word);
            }
            line.add(word);
            width = next;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    static int inkOf(java.util.List<String> line) {
        String joined = String.join(" ", line);
        if (PHRASE.containsKey(joined)) return PHRASE.get(joined);
        return line.stream().mapToInt(PLEX::get).sum() + (line.size() - 1) * 4;
    }

    /** Real nested wheels in screenshot order: the 7-sector buffer and the 8-sector selection. */
    static java.util.List<String> wheel(int count) {
        return switch (count) {
            case 4 -> java.util.List.of("Включить", "Инструменты", "Жезл WorldEdit", "Угол А");
            case 5 ->
                    java.util.List.of(
                            "Включить", "Инструменты", "Жезл WorldEdit", "Угол А", "Угол Б");
            case 6 ->
                    java.util.List.of(
                            "Включить",
                            "Инструменты",
                            "Жезл WorldEdit",
                            "Угол А",
                            "Угол Б",
                            "Заполнить");
            case 7 ->
                    java.util.List.of(
                            "Копировать",
                            "Вырезать",
                            "Вставить",
                            "Повернуть",
                            "Отменить",
                            "Повторить",
                            "Копия встык");
            default ->
                    java.util.List.of(
                            "Включить",
                            "Инструменты",
                            "Жезл WorldEdit",
                            "Угол А",
                            "Угол Б",
                            "Заполнить",
                            "Стены",
                            "Очистить");
        };
    }

    static boolean overlap(int[] a, int[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    static boolean hitsHole(int[] rect) {
        int nx = Math.min(Math.max(0, rect[0]), rect[2]);
        int ny = Math.min(Math.max(0, rect[1]), rect[3]);
        return nx * nx + ny * ny < 23 * 23;
    }

    /**
     * R4 follow-up 2: the nested group name («Выделение» wider than the hole, crossed by the
     * crosshair) now sits in a slot just below the hole whose width clears every caption and icon.
     * Counts 7/8 are the real nested wheels; 4–6 are representative smoke. Radius 72 (below every
     * release-stand window) only keeps the word whole and clear of the hole.
     */
    @Test
    void groupSlotBelowTheHoleClearsCaptionsAndHole() {
        for (int radius : new int[] {98, 112})
            for (int count = 4; count <= 8; count++) {
                var captions = wheel(count);
                String group = count == 8 ? "Выделение" : "Буфер";
                int[] halves = new int[count], rows = new int[count];
                for (int i = 0; i < count; i++) {
                    int box =
                            WorkshopRadialScreen.captionWidth(
                                    captions.get(i),
                                    WorkshopRadial.labelWidth(i, count, radius, 9),
                                    PLEX::get);
                    var lines = wrapReal(captions.get(i), box);
                    halves[i] =
                            lines.stream().mapToInt(WorkshopRadialTest::inkOf).max().orElse(0) / 2
                                    + 1;
                    rows[i] = lines.size();
                }
                int word = PLEX.get(group);
                int slot = WorkshopRadial.groupWidth(radius, count, halves, rows, word);
                String at = radius + ":" + count;
                assertTrue(slot >= word, at);
                assertTrue(slot <= (int) (radius * 1.4), at);
                var grouped = wrapReal(group, slot);
                assertTrue(grouped.size() <= 2, at);
                int[] rect = {
                    -(slot / 2),
                    WorkshopRadial.GROUP_TOP,
                    slot - slot / 2,
                    WorkshopRadial.GROUP_TOP + 10 * grouped.size()
                };
                assertFalse(hitsHole(rect), at);
                for (int i = 0; i < count; i++) {
                    int[] c = WorkshopRadial.labelOffset(i, count, radius);
                    int box =
                            WorkshopRadialScreen.captionWidth(
                                    captions.get(i),
                                    WorkshopRadial.labelWidth(i, count, radius, 9),
                                    PLEX::get);
                    var lines = wrapReal(captions.get(i), box);
                    int ink = lines.stream().mapToInt(WorkshopRadialTest::inkOf).max().orElse(0);
                    assertFalse(
                            overlap(rect, new int[] {c[0] - 6, c[1] - 13, c[0] + 6, c[1] - 1}),
                            at + ":" + i);
                    assertFalse(
                            overlap(
                                    rect,
                                    new int[] {
                                        c[0] - ink / 2,
                                        c[1] + 3,
                                        c[0] + ink - ink / 2,
                                        c[1] + 3 + 10 * lines.size()
                                    }),
                            at + ":" + i + " " + captions.get(i));
                }
            }
    }

    @Test
    void tinyWheelsKeepTheGroupWordWholeAndOffTheHole() {
        // Radius 72 never occurs on the release stand (minimum is 98 at 854×480 GUI 2):
        // the slot falls back to the whole word, clear of the hole and the crosshair.
        for (int count : new int[] {7, 8}) {
            var captions = wheel(count);
            String group = count == 8 ? "Выделение" : "Буфер";
            int[] halves = new int[count];
            java.util.Arrays.fill(halves, 24);
            int[] rows = new int[count];
            java.util.Arrays.fill(rows, 1);
            int slot = WorkshopRadial.groupWidth(72, count, halves, rows, PLEX.get(group));
            assertEquals(PLEX.get(group), slot, "" + count);
            int[] rect = {
                -(slot / 2),
                WorkshopRadial.GROUP_TOP,
                slot - slot / 2,
                WorkshopRadial.GROUP_TOP + 10
            };
            assertFalse(hitsHole(rect), "" + count);
        }
    }

    @Test
    void wheelLabelsShareOneScale() {
        // S3: per-label fit shrank «Выделение»/«На проверку» alone next to full-size «Карты».
        // The wheel now uses the smallest fit of all its labels, so every caption matches.
        int count = 9, radius = 98;
        int[] ink = {16, 59, 33, 32, 50, 30, 49, 55, 47};
        float min = 1;
        for (int w : ink) min = Math.min(min, WorkshopRadial.labelScale(count, radius, w));
        assertEquals(min, WorkshopRadial.commonScale(count, radius, ink));
        assertTrue(min < 1, "" + min);
        assertTrue(min >= 0.7f, "" + min);
        double chord = 2 * (radius * .68) * Math.tan(Math.PI / count) - 4;
        for (int w : ink) assertTrue(min * w <= chord - 2 + 1e-6, w + " " + min);
        assertEquals(1f, WorkshopRadial.commonScale(count, radius, new int[0]));
    }

    @Test
    void singleLineFitFallsBackToWrappedBlock() {
        // S4: one line while every caption fits its chord above the readable minimum,
        // otherwise the fit runs on the wrapped block. 9-item map wheel, widths in PLEX px.
        int[] nine = {16, 59, 33, 32, 50, 30, 49, 67, 56};
        assertTrue(WorkshopRadial.fitsSingleLine(9, 112, nine));
        assertFalse(WorkshopRadial.fitsSingleLine(9, 96, nine));
        assertTrue(
                WorkshopRadial.fitsSingleLine(
                        9, 96, new int[] {16, 59, 33, 32, 50, 30, 49, 32, 56}));
        assertTrue(WorkshopRadial.fitsSingleLine(4, 96, new int[] {16, 47, 32, 30}));
    }

    @Test
    void pairShiftKeepsEightPxBetweenInks() {
        assertEquals(2, WorkshopRadial.pairShift(44, 30, 49));
        assertEquals(3, WorkshopRadial.pairShift(56, 56, 51));
        assertEquals(0, WorkshopRadial.pairShift(100, 30, 30));
        assertEquals(0, WorkshopRadial.pairShift(48, 30, 49));
        assertEquals(6, WorkshopRadial.pairShift(10, 60, 60));
    }

    /**
     * R4 follow-up 3: «Люди»/«Разметка» (9-sector map wheel) and «Повернуть»/«Отменить» (7-sector
     * buffer) nearly touched (~3–5 px). Every row-sharing pair now keeps ≥ 8 px of ink-to-ink gap
     * at radius 98 and up.
     */
    @Test
    void bottomPairInkKeepsAnEightPxGap() {
        String[][] pairs = {{"Люди", "Разметка", "9", "4"}, {"Повернуть", "Отменить", "7", "3"}};
        for (int radius : new int[] {98, 112})
            for (String[] pair : pairs) {
                int count = Integer.parseInt(pair[2]), i = Integer.parseInt(pair[3]);
                int[] a = WorkshopRadial.labelOffset(i, count, radius),
                        b = WorkshopRadial.labelOffset(i + 1, count, radius);
                int dx = Math.abs(a[0] - b[0]);
                int[] ink = {PLEX.get(pair[0]), PLEX.get(pair[1])};
                int[] shift =
                        WorkshopRadial.captionShifts(radius, count, inkForPair(count, i, ink), 9);
                int s = Math.abs(shift[i]) + Math.abs(shift[i + 1]);
                double gap = dx + s - (ink[0] + ink[1]) / 2.0;
                assertTrue(gap >= 8, radius + " " + pair[0] + "/" + pair[1] + " " + gap);
            }
    }

    /**
     * S4: at GUI scale auto (427×240) the wheel stays large (hotbar reserve only, hint to the side)
     * and every label block — icon plus all caption lines at the common scale — stays inside its
     * own sector: inset corners (1.5 px rasterization slack for int-truncated centres and font
     * advances) clear the wedge, the rim and the hole, and no two labels' ink rects overlap. Counts
     * 4/8/9 at 427×240 and 640×360; fails on the S3 geometry (the wrapped «проверку» crossed into
     * the neighbour wedge and its icon, «Выделение» crossed the separator).
     */
    @Test
    void wheelLabelsStayInsideTheirSectors() {
        var wheels =
                java.util.Map.of(
                        4, java.util.List.of("ИИ", "Клавиши", "Карты", "Люди"),
                        8, wheel(8),
                        9,
                                java.util.List.of(
                                        "ИИ",
                                        "Выделение",
                                        "Буфер",
                                        "Карты",
                                        "Люди",
                                        "Разметка",
                                        "На проверку",
                                        "Продлить",
                                        "Клавиши"));
        for (int[] gui : new int[][] {{427, 240}, {640, 360}})
            for (var entry : wheels.entrySet()) {
                int count = entry.getKey(),
                        r = Math.min(112, Math.min(gui[0] / 2 - 8, gui[1] / 2 - 24));
                var texts = entry.getValue();
                String at = gui[0] + "x" + gui[1] + ":" + count;
                int[] single =
                        texts.stream()
                                .mapToInt(t -> inkOf(java.util.List.of(t.split(" "))))
                                .toArray();
                boolean oneLine = WorkshopRadial.fitsSingleLine(count, r, single);
                var caps = new java.util.ArrayList<java.util.List<java.util.List<String>>>();
                int[] ink = new int[count];
                for (int i = 0; i < count; i++) {
                    java.util.List<java.util.List<String>> lines;
                    if (oneLine)
                        lines = java.util.List.of(java.util.List.of(texts.get(i).split(" ")));
                    else {
                        int box =
                                WorkshopRadialScreen.captionWidth(
                                        texts.get(i),
                                        WorkshopRadial.labelWidth(i, count, r, 9),
                                        PLEX::get);
                        lines = wrapReal(texts.get(i), box);
                    }
                    caps.add(lines);
                    ink[i] = lines.stream().mapToInt(WorkshopRadialTest::inkOf).max().orElse(0);
                }
                int[] shift = WorkshopRadial.captionShifts(r, count, ink, 9);
                float common = WorkshopRadial.commonScale(count, r, ink);
                var rects = new java.util.ArrayList<double[]>();
                for (int i = 0; i < count; i++) {
                    int[] c = WorkshopRadial.labelOffset(i, count, r);
                    double lx = c[0] + shift[i], ly = c[1];
                    rects.add(
                            rect(
                                    lx - 6 * common,
                                    ly - 13 * common,
                                    lx + 6 * common,
                                    ly - common,
                                    i));
                    var lines = caps.get(i);
                    for (int k = 0; k < lines.size(); k++) {
                        double hw = inkOf(lines.get(k)) * common / 2;
                        rects.add(
                                rect(
                                        lx - hw,
                                        ly + (3 + 10 * k) * common,
                                        lx + hw,
                                        ly + (3 + 10 * k) * common + 9 * common,
                                        i));
                    }
                }
                for (double[] q : rects) {
                    // 1.5 px inset: stated rasterization slack; S3 violated it by 3–11 px.
                    double[] inset = {q[0] + 1.5, q[1] + 1.5, q[2] - 1.5, q[3] - 1.5};
                    for (double[] p :
                            new double[][] {
                                {inset[0], inset[1]},
                                {inset[2], inset[1]},
                                {inset[0], inset[3]},
                                {inset[2], inset[3]}
                            })
                        assertEquals(
                                (int) q[4],
                                WorkshopRadial.sector(p[0], p[1], 23, r, count),
                                at + " " + texts.get((int) q[4]) + " " + p[0] + "," + p[1]);
                }
                for (int a = 0; a < rects.size(); a++)
                    for (int b = a + 1; b < rects.size(); b++) {
                        double[] x = rects.get(a), y = rects.get(b);
                        if (x[4] == y[4]) continue;
                        assertFalse(overlap(box(x), box(y)), at + " " + a + " " + b);
                    }
            }
    }

    /** Outward-rounded int box for the strict ink-overlap check. */
    private static int[] box(double[] r) {
        return new int[] {
            (int) Math.floor(r[0]),
            (int) Math.floor(r[1]),
            (int) Math.ceil(r[2]),
            (int) Math.ceil(r[3])
        };
    }

    /** Ink rect with its label index: {x0, y0, x1, y1, label}. */
    private static double[] rect(double x0, double y0, double x1, double y1, int label) {
        return new double[] {x0, y0, x1, y1, label};
    }

    /** Ink array with the real pair widths at their slots, estimates elsewhere (unused). */
    private static int[] inkForPair(int count, int i, int[] pair) {
        int[] ink = new int[count];
        java.util.Arrays.fill(ink, 30);
        ink[i] = pair[0];
        ink[i + 1] = pair[1];
        return ink;
    }
}
