package com.lunares.blockfield.client.gui.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for {@link MenuScreen}: how many side-by-side columns fit the available width, a
 * height-balanced assignment of whole sections to those columns (a section never splits across
 * columns, so related lines/buttons stay together), and scroll-offset clamping. No Minecraft
 * classes, so this is unit-testable without the game.
 */
public final class MenuLayout {
    private MenuLayout() {}

    public static final int MIN_COLUMN_WIDTH = 300;
    public static final int COLUMN_GUTTER = 24;

    /**
     * Scrollbar thickness and the gap between it and the content, reserved only while scrolling.
     */
    public static final int SCROLLBAR_WIDTH = 3;

    public static final int SCROLLBAR_GAP = 7;
    public static final int SCROLLBAR_GUTTER = SCROLLBAR_WIDTH + SCROLLBAR_GAP;

    private static final int SECTION_LABEL_H = 15;
    private static final int LINE_H = 11;
    private static final int LINES_TRAILING_GAP = 3;
    private static final int BUTTON_ROW_H = 26; // button height (22) + gap (4)
    private static final int SECTION_GAP = 10;
    public static final int BUTTON_GAP = 4;

    /**
     * How many columns of {@link #MIN_COLUMN_WIDTH} fit contentWidth, capped at maxColumns (>=1).
     */
    public static int columnsFor(int contentWidth, int maxColumns) {
        int cap = Math.max(1, maxColumns);
        for (int cols = cap; cols > 1; cols--) {
            int needed = cols * MIN_COLUMN_WIDTH + (cols - 1) * COLUMN_GUTTER;
            if (contentWidth >= needed) return cols;
        }
        return 1;
    }

    public static int columnWidth(int contentWidth, int columns) {
        return (contentWidth - (columns - 1) * COLUMN_GUTTER) / Math.max(1, columns);
    }

    /**
     * Width actually available to headers/codes/hints/buttons: the full width when nothing scrolls,
     * or full width minus the scrollbar gutter (clamped to {@link #MIN_COLUMN_WIDTH}) once a
     * scrollbar has to be drawn, so the bar never sits over right-aligned content.
     */
    public static int contentWidthFor(int fullWidth, boolean scrollable) {
        if (!scrollable) return fullWidth;
        return Math.max(MIN_COLUMN_WIDTH, fullWidth - SCROLLBAR_GUTTER);
    }

    /**
     * Vertical space a section occupies, given whether it has a label and how many lines/buttons it
     * has.
     */
    public static int sectionHeight(boolean hasLabel, int lineCount, int buttonCount) {
        int h = hasLabel ? SECTION_LABEL_H : 0;
        h += lineCount * LINE_H;
        if (lineCount > 0) h += LINES_TRAILING_GAP;
        h += buttonCount * BUTTON_ROW_H;
        return h + SECTION_GAP;
    }

    public static final int CARD_H = 112;

    /**
     * More than {@link #BIG_CARDS_MAX} cards (a map vote) use lower, narrower cards so the rest
     * still fits.
     */
    public static final int COMPACT_CARD_H = 72;

    public static final int BIG_CARDS_MAX = 3;
    public static final int CARD_GAP = 12;
    public static final int MIN_CARD_W = 150;
    public static final int MIN_COMPACT_CARD_W = 110;

    /**
     * A compact group this small (map vote: 3 maps + "Случайно") shrinks below {@link
     * #MIN_COMPACT_CARD_W} to stay on one row — the label scale already shrinks to fit — rather
     * than wrap at all.
     */
    public static final int SHARE_ROW_MAX = 5;

    public static final int MIN_SHARED_ROW_CARD_W = 90;
    public static final int CORNER_H = 22;
    public static final int CORNER_GAP = 6;
    public static final int BUTTON_PAD_W = 34;
    public static final String CONFIRM_LABEL = "Точно?";
    private static final int CARD_LINES_GAP = 6;

    public static boolean compactCards(int count) {
        return count > BIG_CARDS_MAX;
    }

    public static int cardHeight(int count) {
        return compactCards(count) ? COMPACT_CARD_H : CARD_H;
    }

    static int cardPadding(boolean compact) {
        return compact ? 8 : 12;
    }

    static int cardLabelTop(boolean compact) {
        return compact ? 27 : 42;
    }

    static int cardHeight(int count, int labelHeight, int hintHeight) {
        boolean compact = compactCards(count);
        return Math.max(
                cardHeight(count),
                2 * cardPadding(compact) + cardLabelTop(compact) + labelHeight + 4 + hintHeight);
    }

    public static int cardsPerRow(int width, int count) {
        return cardsPerRow(width, count, 0);
    }

    /**
     * Cards per row: a small compact group (up to {@link #SHARE_ROW_MAX}) shares one row as long as
     * shrinking to fit still leaves a sane width; otherwise, as many minimum-width cards as fit,
     * never more than there are cards. Once that leaves a remainder, spread the same card count
     * evenly across the rows it actually needs instead — e.g. 4 cards fitting only 3 per row become
     * 2+2, never a lone card on row 2. textW is the widest card text (label at its smallest scale
     * or hint, plus padding): no card is squeezed below it, so a row of long mode names wraps
     * instead of cutting every label and hint to "…".
     */
    public static int cardsPerRow(int width, int count, int textW) {
        if (compactCards(count) && count <= SHARE_ROW_MAX) {
            int shared = (width - (count - 1) * CARD_GAP) / count;
            if (shared >= Math.max(MIN_SHARED_ROW_CARD_W, textW)) return count;
        }
        int min = Math.max(textW, compactCards(count) ? MIN_COMPACT_CARD_W : MIN_CARD_W);
        int maxPerRow = Math.max(1, Math.min(count, (width + CARD_GAP) / (min + CARD_GAP)));
        int rows = Math.max(1, cardRows(count, maxPerRow));
        return Math.max(1, (count + rows - 1) / rows);
    }

    public static int cardRows(int count, int perRow) {
        return count == 0 ? 0 : (count + perRow - 1) / perRow;
    }

    /** Full-width card section: optional label, card rows, then its lines under the cards. */
    public static int cardSectionHeight(boolean hasLabel, int lineCount, int cardCount, int width) {
        return cardSectionHeight(hasLabel, lineCount, cardCount, width, 0);
    }

    public static int cardSectionHeight(
            boolean hasLabel, int lineCount, int cardCount, int width, int textW) {
        int h = hasLabel ? SECTION_LABEL_H : 0;
        int rows = cardRows(cardCount, cardsPerRow(width, cardCount, textW));
        h += rows * cardHeight(cardCount) + Math.max(0, rows - 1) * CARD_GAP;
        if (lineCount > 0) h += CARD_LINES_GAP + lineCount * LINE_H;
        return h + SECTION_GAP;
    }

    /**
     * A vote setting's row of small per-variant buttons (segmented control): one click votes for
     * that variant directly, replacing the old cycle-to-vote single button. Always one row -- a
     * setting has at most a handful of variants, never enough to need wrapping.
     */
    public static int segmentedSectionHeight(boolean hasLabel) {
        return sectionHeight(hasLabel, 0, 1);
    }

    /**
     * A grid section (the workshop map list): plain one-line buttons in as many columns of at least
     * {@link #GRID_MIN_W} as fit. Denser than compact cards, so a long list fits the tablet
     * unscaled.
     */
    public static final int GRID_MIN_W = 120;

    public static int buttonWidth(int labelWidth, int confirmWidth) {
        return Math.max(labelWidth, confirmWidth) + BUTTON_PAD_W;
    }

    public static int buttonColumns(int width, int count, int minWidth) {
        int min = Math.max(1, minWidth);
        return Math.max(1, Math.min(Math.max(1, count), (width + BUTTON_GAP) / (min + BUTTON_GAP)));
    }

    public static int gridColumns(int width, int count) {
        return buttonColumns(width, count, GRID_MIN_W);
    }

    public static int gridRows(int width, int count) {
        int columns = gridColumns(width, count);
        return (Math.max(0, count) + columns - 1) / columns;
    }

    public static int gridSectionHeight(boolean hasLabel, int lineCount, int count, int width) {
        return sectionHeight(hasLabel, lineCount, gridRows(width, count));
    }

    /**
     * Equal-width buttons sharing one row, gapped by {@link #BUTTON_GAP}; never wider than width
     * allows.
     */
    public static int segmentedButtonWidth(int width, int count) {
        int n = Math.max(1, count);
        return (width - (n - 1) * BUTTON_GAP) / n;
    }

    /**
     * Content height without the gap after the last section: that gap alone must not trigger
     * scrolling.
     */
    public static int withoutTrailingGap(int contentHeight) {
        return Math.max(0, contentHeight - SECTION_GAP);
    }

    /** Vertical offset that centres content shorter than the viewport; 0 once it has to scroll. */
    public static int centerOffset(int contentHeight, int viewportHeight) {
        return Math.max(0, (viewportHeight - contentHeight) / 2);
    }

    public record Placement(int column, int y) {}

    public record Span(int y, int height) {}

    /** Page boundaries follow whole rows, so a visible button never loses its lower hit area. */
    public static List<Integer> pageOffsets(List<Span> spans, int viewportHeight) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        int offset = 0;
        for (Span span :
                spans.stream().sorted(java.util.Comparator.comparingInt(Span::y)).toList()) {
            if (span.y() > offset && span.y() + span.height() - offset > viewportHeight) {
                offset = span.y();
                offsets.add(offset);
            }
        }
        return offsets;
    }

    /**
     * Assigns whole sections to columns in order, each next section going to whichever column
     * currently has the least accumulated height (classic LPT-style balancing). This keeps related
     * content together and balances total height instead of a single overflowing column.
     */
    public static List<Placement> place(List<Integer> sectionHeights, int columns) {
        int cols = Math.max(1, columns);
        int[] colHeight = new int[cols];
        List<Placement> out = new ArrayList<>(sectionHeights.size());
        for (int h : sectionHeights) {
            int col = 0;
            for (int c = 1; c < cols; c++) if (colHeight[c] < colHeight[col]) col = c;
            out.add(new Placement(col, colHeight[col]));
            colHeight[col] += h;
        }
        return out;
    }

    /** Total scrollable content height: the tallest column. */
    public static int contentHeight(
            List<Integer> sectionHeights, List<Placement> placements, int columns) {
        int cols = Math.max(1, columns);
        int[] colHeight = new int[cols];
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            colHeight[p.column()] = Math.max(colHeight[p.column()], p.y() + sectionHeights.get(i));
        }
        int max = 0;
        for (int h : colHeight) max = Math.max(max, h);
        return max;
    }

    /** Clamps a scroll offset to [0, max(0, contentHeight - viewportHeight)]. */
    public static int clampScroll(int offset, int contentHeight, int viewportHeight) {
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        return Math.max(0, Math.min(offset, maxScroll));
    }

    /**
     * Visible numeric columns of a table row with {@code cells} cells in width {@code w}: narrow
     * screens drop trailing-priority numbers first, keeping at least one beside a readable name.
     * Pure geometry for {@link com.lunares.blockfield.client.gui.BfTheme#tableRow}.
     */
    public static int tableNumbers(int w, int cells, int numColW) {
        int nums = Math.max(0, cells - 2);
        while (nums > 1 && w - 30 - nums * numColW < 90) nums--;
        return nums;
    }

    /** Name column width once {@code nums} numbers are shown. */
    public static int tableNameWidth(int w, int nums, int numColW) {
        return w - 30 - nums * numColW - 6;
    }
}
