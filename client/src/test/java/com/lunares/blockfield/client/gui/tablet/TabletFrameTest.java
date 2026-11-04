package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TabletFrameTest {

    @Test
    void fillsTheScreenMinusMargins() {
        TabletFrame f = TabletFrame.of(1920, 1080);
        assertEquals(TabletFrame.MARGIN, f.contentX());
        assertEquals(1920 - 2 * TabletFrame.MARGIN, f.contentW());
        assertTrue(f.contentH() > 0);
        assertTrue(
                f.contentY() >= f.tabsY() + f.tabsH(),
                "содержимое должно начинаться не выше вкладок");
    }

    @Test
    void tinyWindowStillProducesAUsableContentRect() {
        TabletFrame f = TabletFrame.of(320, 180);
        assertTrue(f.contentW() > 0 && f.contentH() > 0);
        assertTrue(f.inContent(f.contentX() + 1, f.contentY() + 1));
        assertFalse(f.inContent(f.contentX() - 1, f.contentY() + 1));
    }

    @Test
    void widthGrowsWithTheWindow() {
        TabletFrame narrow = TabletFrame.of(640, 360);
        TabletFrame wide = TabletFrame.of(1920, 1080);
        assertTrue(wide.contentW() > narrow.contentW());
    }
}
