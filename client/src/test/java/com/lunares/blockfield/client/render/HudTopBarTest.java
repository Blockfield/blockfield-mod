package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lunares.blockfield.client.gui.BfTheme;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import org.junit.jupiter.api.Test;

/**
 * Shared top-bar geometry: the bar stays on screen at narrow widths, long names are cut without
 * touching neighbors, and GUI scale only changes the units the game already provides.
 */
class HudTopBarTest {
    /** Fixed 7px advance per codepoint, so truncation is deterministic without a font reload. */
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
    void narrowScreenClampsTheBarInside() {
        for (int screenWidth : new int[] {320, 360, 480, 640, 854, 1280, 1920}) {
            var bar = HudTopBar.layout(screenWidth, 100, 200, 100);
            assertTrue(bar.left() >= 0, "left edge at " + screenWidth);
            assertTrue(bar.centerX() + bar.half() <= screenWidth, "right edge at " + screenWidth);
            assertEquals(screenWidth / 2, bar.centerX());
            assertEquals(HudTopBar.Y + HudTopBar.BAR_H, bar.bottom());
        }
    }

    @Test
    void hugeScoresDoNotPushTheBarOffScreen() {
        var bar = HudTopBar.layout(320, 700, 700, 700);
        assertEquals(1, bar.left());
        assertEquals(159, bar.half());
    }

    @Test
    void centerCellKeepsItsPadding() {
        var bar = HudTopBar.layout(854, 0, 200, 0);
        assertTrue(bar.half() * 2 >= 200 + HudTopBar.EDGE * 2);
    }

    @Test
    void longLeaderNameIsCutBeforeTheBarEdge() {
        int screenWidth = 854;
        int avail = HudTopBar.sideAvail(screenWidth);
        String leader = "ОченьДлинныйНикнеймИгрокаСервера_который_точно_не_влезет_15";
        String cut = BfTheme.ellipsize(METRICS, leader, avail);
        assertTrue(METRICS.width(cut) <= avail);
        assertTrue(cut.endsWith("…"));
        var bar =
                HudTopBar.layout(
                        screenWidth,
                        METRICS.width("7/30"),
                        METRICS.width("7:52"),
                        METRICS.width(cut));
        assertTrue(bar.centerX() + bar.half() <= screenWidth);
    }

    @Test
    void guiScaleRangeKeepsFittings() {
        // GUI units already include the scale; the block only needs widths 320..1920 to fit.
        for (int screenWidth : new int[] {320, 427, 640, 960}) {
            String leader =
                    BfTheme.ellipsize(
                            METRICS, "Лидер_с_длинным_именем 12", HudTopBar.sideAvail(screenWidth));
            var bar =
                    HudTopBar.layout(
                            screenWidth,
                            METRICS.width("12/30"),
                            METRICS.width("12:34 · до 30"),
                            METRICS.width(leader));
            assertTrue(bar.left() >= 0);
            assertTrue(bar.centerX() + bar.half() <= screenWidth);
        }
    }

    @Test
    void pointRowWidthMatchesDiamonds() {
        assertEquals(0, HudTopBar.pointsWidth(0));
        assertEquals(11, HudTopBar.pointsWidth(1));
        assertEquals(3 * 11 + 2 * 6, HudTopBar.pointsWidth(3));
    }

    @Test
    void chipWidthFitsItsText() {
        String text = HudRenderer.protectionText(5);
        int width = HudTopBar.chipWidth(METRICS, text);
        assertTrue(width >= METRICS.width(text) + 20);
        // Centered on any screen: 320 is the narrowest supported GUI width.
        assertTrue(width < 320);
    }

    @Test
    void protectionWordingKeepsSeconds() {
        assertEquals("Защита · 5 с", HudRenderer.protectionText(5));
        assertEquals(0, HudRenderer.protectionSecs(null));
        var ui = new com.google.gson.JsonObject();
        ui.addProperty("protection", 3);
        assertEquals(3, HudRenderer.protectionSecs(ui));
    }
}
