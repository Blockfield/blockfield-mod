package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class WorkshopAiLibraryScreenTest {
    @Test
    void wholeCardsFitTheSheet() {
        // 854×480 (427×240 GUI) shows three cards; 1080p shows four at GUI 2, 3 and 4/auto (C2).
        assertEquals(
                3, WorkshopAiLibraryScreen.visibleCards(WorkshopAiLayout.fit(427, 240).height()));
        for (int scale = 2; scale <= 4; scale++)
            assertEquals(
                    4,
                    WorkshopAiLibraryScreen.visibleCards(
                            WorkshopAiLayout.fit(1920 / scale, 1080 / scale).height()),
                    "GUI " + scale);
    }

    @Test
    void scrollingNeverStrandsEmptySlots() {
        assertEquals(0, WorkshopAiLibraryScreen.clampFirst(5, 2, 3));
        assertEquals(9, WorkshopAiLibraryScreen.clampFirst(20, 12, 3));
        assertEquals(0, WorkshopAiLibraryScreen.clampFirst(-1, 12, 3));
    }

    @Test
    void selectionScrollsIntoView() {
        assertEquals(4, WorkshopAiLibraryScreen.reveal(2, 6, 3));
        assertEquals(1, WorkshopAiLibraryScreen.reveal(2, 1, 3));
        assertEquals(2, WorkshopAiLibraryScreen.reveal(2, 3, 3));
    }

    @Test
    void countsGroupWithANoBreakSpace() {
        assertEquals("1 240", WorkshopAiFormat.count(1240));
        assertEquals("214", WorkshopAiFormat.count(214));
    }

    @Test
    void olderDatesUseTheClientLocale() {
        var zone = ZoneId.of("UTC");
        long at = LocalDateTime.of(2026, 10, 14, 18, 3).toInstant(ZoneOffset.UTC).toEpochMilli();
        long now = LocalDateTime.of(2026, 10, 19, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        assertEquals(
                "14 окт., 18:03",
                WorkshopAiFormat.date(at, now, zone, Locale.forLanguageTag("ru-RU")));
        assertEquals(
                "14 Oct, 18:03",
                WorkshopAiFormat.date(at, now, zone, Locale.forLanguageTag("en-US")));
    }

    @Test
    void destructiveButtonsNeedASecondPressWithinThreeSeconds() {
        assertFalse(WorkshopButton.confirmed(true, Long.MIN_VALUE, 10));
        assertTrue(WorkshopButton.confirmed(true, 1_000, 3_999));
        assertFalse(WorkshopButton.confirmed(true, 1_000, 4_000));
        assertTrue(WorkshopButton.confirmed(false, Long.MIN_VALUE, 10));
    }

    @Test
    void widestMetaWrapsInsideTheFixedCard() {
        // Narrowest text width: W=300 sheet → inner 276 → card 270 → text 152 px.
        assertEquals(2, WorkshopAiLibraryScreen.metaBudget(1));
        assertEquals(1, WorkshopAiLibraryScreen.metaBudget(2));
        String meta = "128 × 128 × 128 · блоков: " + WorkshopAiFormat.count(2_097_152);
        assertEquals(2, WorkshopAiLayoutTest.wrap6(meta, 152, 2).lines().size());
        assertEquals(meta, String.join(" ", WorkshopAiLayoutTest.wrap6(meta, 152, 2).lines()));
        // Name(≤2) + meta + date share four 10 px rows starting at card top + 4.
        assertTrue(1 + 2 + 1 <= 4);
    }

    @Test
    void tooltipHitCoversEveryTextRow() {
        // R4 follow-up 4: the old 20 px hit area covered only rows 1–2, so a cut row 3
        // (2-line name + cut meta, or its 2nd line) showed no tooltip. Text starts at
        // card top + 4, so row 3 ends at +34 and four rows end at +44, inside the card.
        assertEquals(4, WorkshopAiLibraryScreen.textRows(2, 1));
        assertEquals(4, WorkshopAiLibraryScreen.textRows(1, 2));
        assertEquals(3, WorkshopAiLibraryScreen.textRows(1, 1));
        for (int rows :
                new int[] {
                    WorkshopAiLibraryScreen.textRows(2, 1), WorkshopAiLibraryScreen.textRows(1, 2)
                }) {
            assertTrue(rows * 10 >= 34);
            assertTrue(4 + rows * 10 <= WorkshopAiLibraryScreen.CARD);
        }
    }
}
