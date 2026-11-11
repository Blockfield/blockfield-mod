package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.screen.MenuLayout;
import com.lunares.blockfield.client.gui.screen.MenuScreen;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import org.junit.jupiter.api.Test;

/**
 * Round 5 (tablet + vote onto shared blocks): the tablet and the vote menu consume
 * BfTheme/BfIcon/UiMotion instead of private copies. Vote tables ellipsize through the shared
 * helper, so Cyrillic cells truncate the same way everywhere; the tab strip, chips, cards, buttons,
 * table rows and scrollbar render through BfTheme, with geometry in TabletFrame / MenuLayout.
 * Truncated cells report to the shared tooltip.
 */
class TabletSharedBlocksTest {
    /** Fixed 7px advance per codepoint, like HudTopBarTest: deterministic without a font reload. */
    static final Font METRICS =
            new Font(face -> null, false) {
                private final StringSplitter splitter =
                        new StringSplitter((codepoint, style) -> 7f);

                @Override
                public int width(String text) {
                    return text.length() * 7;
                }

                @Override
                public String plainSubstrByWidth(String text, int width) {
                    int n = Math.max(0, Math.min(text.length(), width / 7));
                    return text.substring(0, n);
                }

                @Override
                public StringSplitter getSplitter() {
                    return splitter;
                }
            };

    @Test
    void voteTablesUseSharedEllipsize() {
        assertThrows(
                NoSuchMethodException.class,
                () -> MenuScreen.class.getDeclaredMethod("ellipsize", String.class, int.class),
                "MenuScreen must not keep a private ellipsize copy; use BfTheme.ellipsize");
    }

    @Test
    void sharedEllipsizeKeepsVoteCellsReadable() {
        assertEquals("Фраги", BfTheme.ellipsize(METRICS, "Фраги", 200));
        String cut = BfTheme.ellipsize(METRICS, "ДлинноеНазваниеКарты", 70);
        assertTrue(cut.endsWith("…"), "truncated cell ends with an ellipsis: " + cut);
        assertTrue(METRICS.width(cut) <= 70, "truncated cell fits its column: " + cut);
    }

    @Test
    void voteChromeHasNoPrivateDrawingCopies() {
        var names = new java.util.HashSet<String>();
        for (var m : MenuScreen.class.getDeclaredMethods()) names.add(m.getName());
        for (String deleted :
                java.util.List.of(
                        "surface",
                        "renderCard",
                        "renderButton",
                        "renderCorner",
                        "ownRowSurface",
                        "renderScrollCue")) {
            assertTrue(
                    !names.contains(deleted),
                    "MenuScreen." + deleted + " must be deleted; render through BfTheme");
        }
    }

    @Test
    void truncatedCellsReachTheSharedTooltip() {
        boolean wired = false;
        for (var m : MenuScreen.class.getDeclaredMethods())
            if (m.getName().equals("renderTruncatedTip")) wired = true;
        assertTrue(wired, "truncated table cells must reach BfTheme.tooltip");
    }

    @Test
    void tabStripHitUsesSharedBounds() {
        assertEquals(0, BfTheme.tabAt(10, 30, 4, 0, 22, 106, 26));
        assertEquals(2, BfTheme.tabAt(2 * 106 + 50, 22, 4, 0, 22, 106, 26));
        assertEquals(3, BfTheme.tabAt(3 * 106 + 105, 47, 4, 0, 22, 106, 26));
        assertEquals(-1, BfTheme.tabAt(4 * 106, 30, 4, 0, 22, 106, 26));
        assertEquals(-1, BfTheme.tabAt(-1, 30, 4, 0, 22, 106, 26));
        assertEquals(-1, BfTheme.tabAt(50, 21, 4, 0, 22, 106, 26));
        assertEquals(-1, BfTheme.tabAt(50, 48, 4, 0, 22, 106, 26));
        assertEquals(0, BfTheme.tabAt(106, 22, 2, 50, 30, 100, 20));
        assertEquals(-1, BfTheme.tabAt(49, 30, 2, 50, 30, 100, 20));
    }

    @Test
    void tableColumnsFitNarrowScreens() {
        assertEquals(3, MenuLayout.tableNumbers(500, 5, BfTheme.TABLE_NUM_W));
        assertEquals(1, MenuLayout.tableNumbers(200, 5, BfTheme.TABLE_NUM_W));
        assertEquals(0, MenuLayout.tableNumbers(500, 2, BfTheme.TABLE_NUM_W));
        assertEquals(
                500 - 30 - 3 * BfTheme.TABLE_NUM_W - 6,
                MenuLayout.tableNameWidth(500, 3, BfTheme.TABLE_NUM_W));
        assertEquals(
                200 - 30 - BfTheme.TABLE_NUM_W - 6,
                MenuLayout.tableNameWidth(200, 1, BfTheme.TABLE_NUM_W));
    }

    @Test
    void mapChipsShareOneWidthRule() {
        assertEquals(
                METRICS.width("ПКМ — телепорт") + 6, BfTheme.chipWidth(METRICS, "ПКМ — телепорт"));
    }
}
