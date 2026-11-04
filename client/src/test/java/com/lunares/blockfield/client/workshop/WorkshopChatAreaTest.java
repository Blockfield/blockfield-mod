package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkshopChatAreaTest {
    @Test
    void onlyRecentVisibleRowsReserveTheActualConfiguredChatArea() {
        assertNull(
                WorkshopChatArea.bounds(
                        320, 240, 1, 9, 40, 20, new int[] {200, 300}, false, false));
        assertNull(WorkshopChatArea.bounds(320, 240, 1, 9, 40, 20, new int[] {0}, false, true));
        int[] fading =
                WorkshopChatArea.bounds(
                        320, 240, 1, 9, 40, 20, new int[] {0, 150, 199, 200}, false, false);
        assertArrayEquals(new int[] {0, 171, 336, 31}, fading);
        int[] open =
                WorkshopChatArea.bounds(
                        320, 240, 1, 9, 40, 20, new int[] {200, 300, 400, 500}, true, false);
        assertTrue(open[1] < fading[1]);
        int[] larger =
                WorkshopChatArea.bounds(
                        640,
                        240,
                        0.5,
                        18,
                        50,
                        20,
                        new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
                        false,
                        false);
        assertTrue(larger[2] > fading[2]);
        assertTrue(larger[1] < fading[1]);
    }

    @Test
    void panelAvoidsFadingAndOpenChatAtBothResolutionsAllRequestedScalesAndSettings() {
        for (int[] window : new int[][] {{1280, 720}, {960, 540}})
            for (int scale = 1; scale <= 4; scale++) {
                int sw = (window[0] + scale - 1) / scale, sh = (window[1] + scale - 1) / scale;
                int[] minimap = {4, 4, 80, 96};
                for (double chatScale : new double[] {0.5, 0.75, 1})
                    for (int chatWidth : new int[] {40, 320, 640})
                        for (int rows : new int[] {0, 1, 5, 10, 20})
                            for (boolean open : new boolean[] {false, true}) {
                                int[] ages = new int[rows];
                                java.util.Arrays.fill(ages, open ? 500 : 190);
                                int[] chat =
                                        WorkshopChatArea.bounds(
                                                chatWidth, sh, chatScale, 9, 40, 20, ages, open,
                                                false);
                                for (int panelW : new int[] {140, 300, 500})
                                    for (int panelH : new int[] {15, 45}) {
                                        int[] panel =
                                                WorkshopToolsClient.placePanel(
                                                        sw, sh, panelW, panelH, minimap, chat);
                                        if (panel == null) continue;
                                        assertTrue(
                                                panel[0] >= 0
                                                        && panel[1] >= 0
                                                        && panel[0] + panel[2] <= sw
                                                        && panel[1] + panel[3] <= sh);
                                        assertFalse(
                                                WorkshopToolsClient.intersects(
                                                        panel[0], panel[1], panel[2], panel[3],
                                                        minimap));
                                        assertFalse(
                                                WorkshopToolsClient.intersects(
                                                        panel[0],
                                                        panel[1],
                                                        panel[2],
                                                        panel[3],
                                                        WorkshopToolsClient.hotbarRect(sw, sh)));
                                        if (chat != null)
                                            assertFalse(
                                                    WorkshopToolsClient.intersects(
                                                            panel[0], panel[1], panel[2], panel[3],
                                                            chat));
                                        if (open)
                                            assertFalse(
                                                    WorkshopToolsClient.panelVisible(true, false));
                                    }
                            }
            }
        assertNull(
                WorkshopToolsClient.placePanel(
                        427, 240, 500, 45, new int[] {4, 4, 80, 96}, new int[] {0, 80, 427, 130}));
    }

    @Test
    void absentChatKeepsDefaultPositionAndFullCoverageHidesPanel() {
        assertArrayEquals(
                new int[] {250, 4, 140, 15},
                WorkshopToolsClient.placePanel(640, 360, 140, 15, null, null));
        assertNull(
                WorkshopToolsClient.placePanel(
                        320, 240, 300, 15, null, new int[] {0, 0, 320, 240}));
    }
}
