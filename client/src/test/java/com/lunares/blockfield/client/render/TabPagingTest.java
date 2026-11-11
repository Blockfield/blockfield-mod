package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lunares.blockfield.client.gui.BfTheme;
import org.junit.jupiter.api.Test;

/** TAB audit items 2-4: width fits narrow GUI, 15+ rows page, long names never overlap columns. */
class TabPagingTest {
    @Test
    void narrowScreensShrinkTheTableInside() {
        assertEquals(400, TabMenuRenderer.tabWidth(1920));
        assertEquals(400, TabMenuRenderer.tabWidth(854));
        assertEquals(304, TabMenuRenderer.tabWidth(320));
        assertTrue(TabMenuRenderer.tabWidth(320) <= 320);
    }

    @Test
    void designWidthKeepsExactColumns() {
        assertEquals(10, TabMenuRenderer.leftX(10, 400));
        assertEquals(95, TabMenuRenderer.leftX(95, 400));
        assertEquals(165, TabMenuRenderer.leftX(165, 400));
    }

    @Test
    void columnsStayOrderedOnNarrowScreens() {
        for (int screenWidth : new int[] {200, 320, 427, 640}) {
            int w = TabMenuRenderer.tabWidth(screenWidth);
            int name = TabMenuRenderer.leftX(10, w);
            int cls = TabMenuRenderer.leftX(95, w);
            int score = TabMenuRenderer.leftX(165, w);
            assertTrue(name < cls && cls < score, "ordered at " + screenWidth);
            assertTrue(
                    score < w - TabMenuRenderer.RIGHT_ZONE,
                    "score clears numbers at " + screenWidth);
        }
    }

    @Test
    void fifteenRowsNeedTwoPages() {
        int perPage = TabMenuRenderer.rowsPerPage(216);
        assertEquals(13, perPage);
        assertEquals(2, TabMenuRenderer.pagesFor(15, perPage));
        assertEquals(1, TabMenuRenderer.pagesFor(13, perPage));
        assertEquals(0, TabMenuRenderer.pagesFor(0, perPage));
    }

    @Test
    void wheelPagingClampsAndPassesThrough() {
        assertFalse(TabMenuRenderer.scrollPages(-1, 1));
        assertFalse(TabMenuRenderer.scrollPages(-1, 0));
        TabMenuRenderer.setTabActive(true);
        assertTrue(TabMenuRenderer.scrollPages(-1, 3));
        assertEquals(1, TabMenuRenderer.currentPage(3));
        assertTrue(TabMenuRenderer.scrollPages(-1, 3));
        assertEquals(2, TabMenuRenderer.currentPage(3));
        assertTrue(TabMenuRenderer.scrollPages(-1, 3));
        assertEquals(2, TabMenuRenderer.currentPage(3));
        assertTrue(TabMenuRenderer.scrollPages(1, 3));
        assertEquals(1, TabMenuRenderer.currentPage(3));
        TabMenuRenderer.setTabActive(false);
        assertEquals(0, TabMenuRenderer.currentPage(3));
    }

    @Test
    void longNamesEllipsizeIntoTheirSlot() {
        int w = TabMenuRenderer.tabWidth(320);
        int nameAvail = TabMenuRenderer.leftX(95, w) - TabMenuRenderer.leftX(10, w) - 4;
        String cut =
                BfTheme.ellipsize(
                        HudTopBarTest.METRICS,
                        "ОченьДлинныйНикнеймИгрокаСервера_который_не_влезет",
                        nameAvail);
        assertTrue(HudTopBarTest.METRICS.width(cut) <= nameAvail);
        assertTrue(cut.endsWith("…"));
    }
}
