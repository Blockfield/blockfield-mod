package com.lunares.blockfield.client.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BfThemeTooltipTest {
    @Test
    void lineStepFitsRealGlyphExtent() {
        // Plex Sans 11: cap top ~+1.3, descenders to ~+11.2 over a ~+9 baseline, so a 10px step
        // lets descenders touch the next line; the shared step leaves a full pixel of air.
        assertTrue(BfTheme.LINE_STEP >= 11);
    }

    @Test
    void tooltipStaysRightOfCursorWhenItFits() {
        assertArrayEquals(new int[] {110, 112}, BfTheme.tooltipOrigin(100, 100, 60, 30, 400, 300));
    }

    @Test
    void tooltipFlipsLeftWhenItWouldLeaveTheScreen() {
        // 310 + 100 > 400: left of the cursor.
        assertArrayEquals(new int[] {200, 112}, BfTheme.tooltipOrigin(310, 100, 100, 30, 400, 300));
    }

    @Test
    void tooltipFlipsUpWhenItWouldLeaveTheBottom() {
        assertArrayEquals(new int[] {110, 238}, BfTheme.tooltipOrigin(100, 280, 60, 30, 400, 300));
    }

    @Test
    void tooltipNeverLeavesAnyEdge() {
        // Cursor in the corner: pinned inside, not past the edge.
        int[] at = BfTheme.tooltipOrigin(395, 295, 100, 30, 400, 300);
        assertArrayEquals(new int[] {285, 253}, at);
        // Degenerate: wider than the screen still starts at 0 and ends at the edge.
        int[] wide = BfTheme.tooltipOrigin(200, 150, 900, 40, 400, 300);
        assertArrayEquals(new int[] {0, 162}, wide);
        int[] tall = BfTheme.tooltipOrigin(200, 150, 60, 900, 400, 300);
        assertArrayEquals(new int[] {210, 0}, tall);
    }
}
