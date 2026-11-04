package com.lunares.blockfield.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TooltipWrapTest {
    @Test
    void wrapsOnlyWhenNeitherSideOfTheCursorFits() {
        // Fits right of the cursor, or left of it: no wrap.
        assertEquals(200, TooltipWrap.width(200, 100, 480));
        assertEquals(200, TooltipWrap.width(200, 400, 480));
        // Too wide for both sides: wrap to the wider one.
        assertEquals(304, TooltipWrap.width(900, 160, 480));
        assertEquals(300, TooltipWrap.width(900, 320, 480));
        // A tiny window still leaves a readable column.
        assertEquals(60, TooltipWrap.width(900, 60, 100));
    }
}
