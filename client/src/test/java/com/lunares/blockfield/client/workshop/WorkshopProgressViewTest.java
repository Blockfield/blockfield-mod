package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.workshop.WorkshopAiProgress;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkshopProgressViewTest {
    @Test
    void monotonicPhaseClockAndParts() {
        UUID owner = UUID.randomUUID(), job = UUID.randomUUID();
        var phase = WorkshopAiProgress.Phase.APPLYING;
        var progress =
                new WorkshopAiProgressState(
                        owner, job, new WorkshopAiProgress(phase, 2, 4, 1000, 121000), 11000, 0);
        assertEquals("2/4", progress.parts());
        assertEquals("0:15", progress.elapsed(5_000_000_000L));
        assertEquals("1:45", progress.remaining(5_000_000_000L));
    }

    @Test
    void hudNeverOverlapsChatMinimapOrHotbarAndHidesWithOpenChat() {
        for (int[] pixels : new int[][] {{1280, 720}, {1920, 1080}})
            for (int scale = 2; scale <= 4; scale++) {
                int w = pixels[0] / scale, h = pixels[1] / scale;
                int[] chat = {0, h - 100, w * 3 / 5, 100}, map = {w - 100, 0, 100, 100};
                int[] panel = WorkshopToolsClient.placePanel(w, h, 220, 55, map, chat);
                if (panel != null) {
                    assertFalse(
                            WorkshopToolsClient.intersects(
                                    panel[0], panel[1], panel[2], panel[3], chat));
                    assertFalse(
                            WorkshopToolsClient.intersects(
                                    panel[0], panel[1], panel[2], panel[3], map));
                    assertFalse(
                            WorkshopToolsClient.intersects(
                                    panel[0],
                                    panel[1],
                                    panel[2],
                                    panel[3],
                                    WorkshopToolsClient.hotbarRect(w, h)));
                }
            }
        assertFalse(WorkshopToolsClient.panelVisible(true, false));
    }
}
