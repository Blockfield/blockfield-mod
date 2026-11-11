package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TabletResultTabTest {

    /** ФРАГИ / МЕСТО / ВРЕМЯ БОЯ с реальными ширинами шрифта из скриншота стекла. */
    private static final int[] LABELS = {30, 30, 54};

    private static final int[] VALUES = {6, 28, 29};

    private static int rowWidth(float scale) {
        int total = 20 * (LABELS.length - 1);
        for (int i = 0; i < LABELS.length; i++)
            total += Math.max(LABELS[i], Math.round(scale * VALUES[i]));
        return total;
    }

    @Test
    void statsRowFitsTheColumnOfTheRealGlass() {
        // Кадр стекла 590x380 -> холст 574 px, колонка справа от карты ~333 px.
        float scale = TabletResultTab.scoreScale(LABELS, VALUES, 333);
        assertEquals(2f, scale, 0.001f);
        assertTrue(rowWidth(scale) <= 333);
    }

    @Test
    void aNarrowColumnShrinksTheDigitsInsteadOfOverflowing() {
        int width = rowWidth(1.5f);
        assertEquals(1.5f, TabletResultTab.scoreScale(LABELS, VALUES, width), 0.001f);
        assertEquals(1f, TabletResultTab.scoreScale(LABELS, VALUES, 10), 0.001f);
    }

    @Test
    void longStatLabelsChooseFewerColumnsAtFullTextSize() {
        int columns = TabletResultTab.gridColumns(333, 88, 8);
        assertEquals(3, columns);
        assertTrue((333 - (columns - 1) * 3) / columns - 14 >= 88);
        assertEquals(4, TabletResultTab.gridColumns(500, 88, 8));
    }

    private static List<MatchResult.Player> twentyPlayers() {
        List<MatchResult.Player> players = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            players.add(
                    new MatchResult.Player(
                            "p" + i,
                            "u" + i,
                            i % 2 == 0 ? "BlueTeam" : "RedTeam",
                            1000 - i * 10,
                            10,
                            2,
                            0,
                            0,
                            0,
                            0,
                            "",
                            false,
                            0,
                            0));
        return players;
    }

    @Test
    void fifteenRowsNeedSeveralPages() {
        // availH=100 px: 6 rows without the pager, 5 once it is drawn.
        assertEquals(6, TabletResultTab.perPage(100, false));
        assertEquals(5, TabletResultTab.perPage(100, true));
        assertEquals(3, TabletResultTab.pageCount(15, 100));
        assertEquals(4, TabletResultTab.pageCount(20, 100));
        assertEquals(1, TabletResultTab.pageCount(5, 100));
    }

    @Test
    void rosterPagesSliceRowsAndPinOwnRow() {
        List<MatchResult.Player> roster = twentyPlayers();
        MatchResult.Player me = roster.get(17);
        List<MatchResult.Player> first = TabletResultTab.visiblePage(roster, me, 100, 0);
        assertEquals(5, first.size());
        assertEquals("p0", first.get(0).name());
        assertEquals("u17", first.get(4).uuid());
        List<MatchResult.Player> last = TabletResultTab.visiblePage(roster, me, 100, 3);
        assertEquals(5, last.size());
        assertTrue(last.contains(me));
        assertEquals("p15", last.get(0).name());
    }

    @Test
    void singlePageShowsAllRows() {
        List<MatchResult.Player> roster = twentyPlayers().subList(0, 4);
        List<MatchResult.Player> shown = TabletResultTab.visiblePage(roster, roster.get(3), 400, 0);
        assertEquals(4, shown.size());
    }
}
