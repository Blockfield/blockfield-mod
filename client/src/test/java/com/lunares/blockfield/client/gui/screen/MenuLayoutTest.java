package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MenuLayoutTest {

    @org.junit.jupiter.api.Test
    void pagesKeepEveryRowWholeAndReachable() {
        var spans =
                java.util.List.of(
                        new MenuLayout.Span(0, 72), new MenuLayout.Span(0, 72),
                        new MenuLayout.Span(84, 72), new MenuLayout.Span(168, 72),
                        new MenuLayout.Span(250, 16), new MenuLayout.Span(266, 16));
        var pages = MenuLayout.pageOffsets(spans, 160);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(0, 168), pages);
        for (var row : spans)
            org.junit.jupiter.api.Assertions.assertTrue(
                    pages.stream()
                            .anyMatch(
                                    offset ->
                                            row.y() >= offset
                                                    && row.y() + row.height() <= offset + 160));
    }

    @Test
    void narrowWidthStaysSingleColumn() {
        assertEquals(1, MenuLayout.columnsFor(624 - 1, 2));
    }

    @Test
    void wideEnoughWidthGetsTwoColumns() {
        assertEquals(
                2,
                MenuLayout.columnsFor(
                        2 * MenuLayout.MIN_COLUMN_WIDTH + MenuLayout.COLUMN_GUTTER, 2));
    }

    @Test
    void maxColumnsCapsEvenOnVeryWideScreens() {
        assertEquals(1, MenuLayout.columnsFor(10_000, 1));
    }

    @Test
    void columnWidthSplitsGutterEvenly() {
        // 624 = 2*300 + 24
        assertEquals(300, MenuLayout.columnWidth(624, 2));
    }

    @Test
    void sectionHeightMatchesLabelLinesAndButtons() {
        // label(15) + 2 lines*11 + trailing gap(3) + 1 button*26 + section gap(10)
        assertEquals(15 + 22 + 3 + 26 + 10, MenuLayout.sectionHeight(true, 2, 1));
        // no label, no lines, no buttons: just the section gap
        assertEquals(10, MenuLayout.sectionHeight(false, 0, 0));
    }

    @Test
    void placeBalancesHeightAcrossColumnsKeepingSectionsWhole() {
        List<Integer> heights = List.of(100, 50, 50);
        List<MenuLayout.Placement> placed = MenuLayout.place(heights, 2);

        assertEquals(new MenuLayout.Placement(0, 0), placed.get(0)); // 100 -> col0 (tie -> first)
        assertEquals(new MenuLayout.Placement(1, 0), placed.get(1)); // 50 -> col1 (0 < 100)
        assertEquals(new MenuLayout.Placement(1, 50), placed.get(2)); // 50 -> col1 (50 < 100)

        assertEquals(100, MenuLayout.contentHeight(heights, placed, 2));
    }

    @Test
    void singleColumnPlacementStacksSectionsInOrder() {
        List<Integer> heights = List.of(20, 30, 15);
        List<MenuLayout.Placement> placed = MenuLayout.place(heights, 1);

        assertEquals(new MenuLayout.Placement(0, 0), placed.get(0));
        assertEquals(new MenuLayout.Placement(0, 20), placed.get(1));
        assertEquals(new MenuLayout.Placement(0, 50), placed.get(2));
        assertEquals(65, MenuLayout.contentHeight(heights, placed, 1));
    }

    @Test
    void contentWidthReservesGutterOnlyWhileScrolling() {
        assertEquals(760, MenuLayout.contentWidthFor(760, false));
        assertEquals(760 - MenuLayout.SCROLLBAR_GUTTER, MenuLayout.contentWidthFor(760, true));
        // never shrinks below the minimum column width, even on a very narrow scrollable menu
        assertEquals(
                MenuLayout.MIN_COLUMN_WIDTH,
                MenuLayout.contentWidthFor(MenuLayout.MIN_COLUMN_WIDTH + 5, true));
    }

    @Test
    void clampScrollKeepsOffsetWithinBounds() {
        assertEquals(0, MenuLayout.clampScroll(-5, 500, 200));
        assertEquals(300, MenuLayout.clampScroll(1000, 500, 200));
        assertEquals(0, MenuLayout.clampScroll(50, 150, 200)); // content shorter than viewport
        assertEquals(120, MenuLayout.clampScroll(120, 500, 200));
    }

    @Test
    void cardsShareTheRowUntilTheyGetTooNarrow() {
        assertEquals(2, MenuLayout.cardsPerRow(760, 2));
        assertEquals(3, MenuLayout.cardsPerRow(760, 3));
        assertEquals(1, MenuLayout.cardsPerRow(300, 3));
        assertEquals(4, MenuLayout.cardsPerRow(624, 4));
        assertEquals(2, MenuLayout.cardsPerRow(300, 4));
        assertEquals(1, MenuLayout.cardsPerRow(10, 0));
    }

    @Test
    void fourCardsNeverSplitThreeAndOneAndShareOneRowWhenThereIsRoom() {
        // The vote screen (3 maps + "Случайно"): a wide GUI has plenty of room, so all 4 share one
        // row,
        // shrunk below MIN_COMPACT_CARD_W rather than wrapped. This is the owner's actual reported
        // width.
        assertEquals(4, MenuLayout.cardsPerRow(760, 4));
        assertEquals(4, MenuLayout.cardsPerRow(624, 4));
        assertEquals(
                4,
                MenuLayout.cardsPerRow(396, 4)); // shared width lands exactly on the sane minimum
        // Below that, even shrunk cards would be too narrow to read: fall back to an even split,
        // never 3+1.
        for (int width = 0; width < 900; width++) {
            int perRow = MenuLayout.cardsPerRow(width, 4);
            assertNotEquals(3, perRow, "width " + width + " gave perRow " + perRow);
        }
        assertEquals(2, MenuLayout.cardsPerRow(395, 4));
        assertEquals(2, MenuLayout.cardsPerRow(300, 4));
    }

    @Test
    void cardSectionGrowsByRowsAndLines() {
        int oneRow = MenuLayout.cardSectionHeight(false, 0, 2, 760);
        assertEquals(MenuLayout.CARD_H + 10, oneRow);
        assertEquals(
                oneRow + 2 * (MenuLayout.CARD_H + MenuLayout.CARD_GAP),
                MenuLayout.cardSectionHeight(false, 0, 3, 300));
        assertTrue(MenuLayout.cardSectionHeight(true, 1, 2, 760) > oneRow);
        assertEquals(10, MenuLayout.cardSectionHeight(false, 0, 0, 760));
    }

    @Test
    void manyCardsGoCompact() {
        assertEquals(
                MenuLayout.COMPACT_CARD_H + 10, MenuLayout.cardSectionHeight(false, 0, 4, 624));
        assertEquals(MenuLayout.CARD_H, MenuLayout.cardHeight(3));
    }

    @Test
    void shortContentIsCentredAndScrollingContentIsNot() {
        assertEquals(50, MenuLayout.centerOffset(100, 200));
        assertEquals(0, MenuLayout.centerOffset(300, 200));
    }

    @Test
    void trailingGapIsNotContent() {
        assertEquals(
                MenuLayout.CARD_H,
                MenuLayout.withoutTrailingGap(MenuLayout.cardSectionHeight(false, 0, 2, 760)));
        assertEquals(0, MenuLayout.withoutTrailingGap(0));
    }

    /**
     * Владелец (1.18.22, скриншот с планшета): карточка «Случайно» без правой рамки и подсказка
     * настройки обрезаны у правого края -- содержимое оказалось шире холста. Виновата была не сама
     * математика раскладки (columnsFor/cardsPerRow гарантированно укладываются в ширину делением с
     * округлением вниз), а округление прямоугольника scissor планшета (см. ScissorMathTest) и
     * старая настройка-переключатель, которую заменил сегментированный ряд. Здесь -- регрессионный
     * тест самой раскладки: правый край любой карточки/колонки/сегмента не должен вылезать за
     * ширину холста ни при одном сочетании счётчиков, для ширин, которые реально получает планшет
     * на четырёх GUI-размерах владельца (1920x1080 GUI 4 = 480x270, 640x360, 427x240, 960x540) --
     * см. подбор холста в {@code Tablet.fitMenu}/{@code TabletCanvas}. Так проверяются голосование
     * (карты + сегменты настроек), хаб, casual, ready, матч, полигон и меню класса разом, не завися
     * от их конкретного числа кнопок.
     */
    @Test
    void cardRowsNeverExceedTheCanvasWidthOnAnyTabletGuiSize() {
        int[] tabletCanvasWidths = {737, 698, 760, 664};
        for (int width : tabletCanvasWidths) {
            for (int count = 1; count <= 10; count++) {
                int perRow = MenuLayout.cardsPerRow(width, count);
                int slots = count == 1 ? MenuLayout.cardsPerRow(width, 2) : perRow;
                int cardW = (width - (slots - 1) * MenuLayout.CARD_GAP) / slots;
                int rowRight = (perRow - 1) * (cardW + MenuLayout.CARD_GAP) + cardW;
                assertTrue(
                        rowRight <= width,
                        "width=" + width + " count=" + count + " right=" + rowRight);
            }
        }
    }

    @Test
    void columnsNeverExceedTheCanvasWidthOnAnyTabletGuiSize() {
        int[] tabletCanvasWidths = {737, 698, 760, 664};
        for (int width : tabletCanvasWidths) {
            int columns = MenuLayout.columnsFor(width, 2);
            int colW = MenuLayout.columnWidth(width, columns);
            int right = (columns - 1) * (colW + MenuLayout.COLUMN_GUTTER) + colW;
            assertTrue(right <= width, "width=" + width + " right=" + right);
        }
    }

    @Test
    void segmentedVoteButtonsNeverExceedTheCanvasWidthOnAnyTabletGuiSize() {
        int[] tabletCanvasWidths = {737, 698, 760, 664};
        for (int width : tabletCanvasWidths) {
            for (int variants = 1; variants <= 5; variants++) {
                int w = MenuLayout.segmentedButtonWidth(width, variants);
                int rowRight = (variants - 1) * (w + MenuLayout.BUTTON_GAP) + w;
                assertTrue(
                        rowRight <= width,
                        "width=" + width + " variants=" + variants + " right=" + rowRight);
            }
        }
    }

    @Test
    void gridPacksAMapListIntoColumns() {
        assertEquals(4, MenuLayout.gridColumns(560, 18));
        assertEquals(5, MenuLayout.gridRows(560, 18));
        assertEquals(2, MenuLayout.gridColumns(560, 2), "never more columns than buttons");
        assertEquals(1, MenuLayout.gridColumns(60, 18), "a narrow screen still gets one column");
        assertEquals(
                MenuLayout.sectionHeight(true, 0, 5),
                MenuLayout.gridSectionHeight(true, 0, 18, 560));
    }

    @Test
    void longCardTextWrapsInsteadOfTruncating() {
        // Five casual modes whose widest text needs 190 px: one shared row would leave 118 px each.
        assertEquals(3, MenuLayout.cardsPerRow(640, 5, 190));
        assertEquals(2, MenuLayout.cardRows(5, 3));
        // A map vote with short names still shares one row.
        assertEquals(4, MenuLayout.cardsPerRow(396, 4, 80));
    }

    @Test
    void confirmationWidthFitsBeforeAndAfterArmingAndMovesWholeButtonRows() {
        int width = MenuLayout.buttonWidth(30, 42);
        assertEquals(76, width);
        assertEquals(134, MenuLayout.buttonWidth(100, 42));
        for (int screenWidth : new int[] {100, 608, 760}) {
            int columns = MenuLayout.buttonColumns(screenWidth, 4, width);
            assertTrue(
                    MenuLayout.segmentedButtonWidth(screenWidth, columns) - MenuLayout.BUTTON_PAD_W
                            >= 42);
        }
        assertEquals(1, MenuLayout.buttonColumns(100, 2, width));
    }

    @Test
    void rosterPagingCountsClampsStepsAndLabels() {
        // Shared by TAB and the results roster: 15 rows never fit one TAB page (13 rows).
        assertEquals(2, MenuLayout.pagesFor(15, 13));
        assertEquals(1, MenuLayout.pagesFor(13, 13));
        assertEquals(0, MenuLayout.pagesFor(0, 13));
        assertEquals(0, MenuLayout.pagesFor(10, 0));
        assertEquals(0, MenuLayout.clampPage(5, 0));
        assertEquals(0, MenuLayout.clampPage(-1, 3));
        assertEquals(2, MenuLayout.clampPage(9, 3));
        assertEquals(1, MenuLayout.scrollPage(0, -1, 3));
        assertEquals(0, MenuLayout.scrollPage(0, 1, 3));
        assertEquals(0, MenuLayout.scrollPage(0, 0, 3));
        assertEquals(2, MenuLayout.scrollPage(2, -1, 3));
        assertEquals("‹ стр. 2/3 · колесо ›", MenuLayout.pageLabel(1, 3));
    }
}
