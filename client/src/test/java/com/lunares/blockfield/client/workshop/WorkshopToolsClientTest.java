package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkshopToolsClientTest {
    @BeforeEach
    void resetWheel() {
        WorkshopToolsClient.resetWheelSession();
    }

    @Test
    void onlyExplicitCrouchingUndoesAndAnActiveJobAlwaysCancelsInstead() {
        for (String stage : new String[] {"complete", "cancelled", "error", "idle"}) {
            assertNull(WorkshopToolsClient.leftAction(stage, true, false));
            assertEquals("undo", WorkshopToolsClient.leftAction(stage, true, true));
            assertNull(WorkshopToolsClient.leftAction(stage, false, true));
        }
        for (String stage :
                new String[] {"preview", "planning", "transcribing", "applying", "undoing"}) {
            assertEquals("cancel", WorkshopToolsClient.leftAction(stage, true, true));
            assertEquals("cancel", WorkshopToolsClient.leftAction(stage, false, false));
        }
    }

    @Test
    void panelShowsOnlyTheActionsValidInTheCurrentState() {
        // A sketch in hand wins over every job stage.
        for (String stage :
                new String[] {
                    "idle", "planning", "preview", "applying", "complete", "error", "disabled"
                }) assertEquals("hand", WorkshopToolsClient.panelKind(stage, true, true), stage);
        assertEquals("working", WorkshopToolsClient.panelKind("planning", false, false));
        assertEquals("working", WorkshopToolsClient.panelKind("transcribing", false, false));
        assertEquals("preview", WorkshopToolsClient.panelKind("preview", false, false));
        assertEquals("progress", WorkshopToolsClient.panelKind("applying", false, false));
        assertEquals("progress", WorkshopToolsClient.panelKind("undoing", false, false));
        assertEquals("done", WorkshopToolsClient.panelKind("complete", false, true));
        assertEquals("done", WorkshopToolsClient.panelKind("cancelled", false, true));
        assertEquals("done", WorkshopToolsClient.panelKind("error", false, true));
        assertEquals("idle", WorkshopToolsClient.panelKind("complete", false, false));
        assertEquals("idle", WorkshopToolsClient.panelKind("idle", false, false));
        assertEquals("idle", WorkshopToolsClient.panelKind("disabled", false, false));
    }

    @Test
    void stampProgressKeepsTheTrackedBaseJobId() {
        UUID owner = UUID.randomUUID(), base = UUID.randomUUID(), stamp = UUID.randomUUID();
        var preview = state(owner, base, "preview");
        // Stamp planning/preview under its own id must not take over the tracked state,
        // or the following apply (base-id match on the server) would be refused.
        assertTrue(WorkshopAiClient.isStampProgress(preview, state(owner, stamp, "planning")));
        assertTrue(WorkshopAiClient.isStampProgress(preview, state(owner, stamp, "preview")));
        assertTrue(
                WorkshopAiClient.isStampProgress(
                        state(owner, base, "planning"), state(owner, stamp, "preview")));
        // Terminal stamp outcomes must not hijack the id either: a failed copy leaves the
        // base preview usable, and an empty copy (old servers) carries nothing to track.
        assertTrue(WorkshopAiClient.isStampProgress(preview, state(owner, stamp, "error")));
        assertTrue(WorkshopAiClient.isStampProgress(preview, state(owner, stamp, "complete", 0)));
        // Every foreign stamp outcome keeps the base id; only a fresh job or server idle
        // after expiry can replace it.
        assertFalse(WorkshopAiClient.isStampProgress(preview, state(owner, base, "planning")));
        assertFalse(
                WorkshopAiClient.isStampProgress(
                        state(owner, base, "idle"), state(owner, stamp, "planning")));
        assertTrue(WorkshopAiClient.isStampProgress(preview, state(owner, stamp, "applying")));
        assertTrue(WorkshopAiClient.isStampProgress(preview, state(owner, stamp, "complete", 3)));
        assertTrue(WorkshopAiClient.isStampProgress(preview, state(owner, stamp, "idle")));
        assertFalse(
                WorkshopAiClient.isStampProgress(
                        preview,
                        state(
                                owner,
                                com.lunares.blockfield.network.packet.WorkshopAiRequestC2SPacket
                                        .ZERO_ID,
                                "idle")));
    }

    @Test
    void stampNoticesReachTheOwnerWithoutReplacingTheBaseState() {
        UUID owner = UUID.randomUUID(), base = UUID.randomUUID(), stamp = UUID.randomUUID();
        List<String> notices = new ArrayList<>();
        WorkshopAiClient.availability(false);
        try {
            WorkshopAiClient.accept(state(owner, base, "preview"), owner, notices::add);
            var baseState = WorkshopAiClient.state();
            for (String[] outcome :
                    new String[][] {
                        {"planning", "Готовим копию"},
                        {"preview", "Копия готова"},
                        {"error", "Копирование запрещено"},
                        {"complete", "Копирование завершено"}
                    }) {
                var packet = notice(owner, stamp, outcome[0], outcome[1]);
                WorkshopAiClient.accept(packet, UUID.randomUUID(), notices::add);
                assertTrue(notices.isEmpty(), "another owner's notices stay private");
                WorkshopAiClient.accept(packet, owner, notices::add);
                WorkshopAiClient.accept(packet, owner, notices::add);
                assertEquals(
                        WorkshopToolsClient.busy(outcome[0]) ? List.of() : List.of(outcome[1]),
                        notices);
                assertSame(baseState, WorkshopAiClient.state());
                notices.clear();
            }
            WorkshopAiClient.accept(
                    notice(owner, UUID.randomUUID(), "complete", "Копирование завершено"),
                    owner,
                    notices::add);
            assertEquals(List.of("Копирование завершено"), notices);
            assertSame(baseState, WorkshopAiClient.state());
            // Apply-wait and same-spot refusals use the base id and must reach the HUD too.
            for (String message :
                    new String[] {"Готовим копию, подождите", "В копии нет подходящих блоков"}) {
                WorkshopAiClient.accept(
                        notice(owner, base, "preview", message), owner, notices::add);
                assertEquals(base, WorkshopAiClient.state().jobId());
                assertEquals(message, notices.getLast());
            }
        } finally {
            WorkshopAiClient.availability(false);
        }
    }

    private static WorkshopAiStateS2CPacket notice(
            UUID owner, UUID job, String stage, String message) {
        return new WorkshopAiStateS2CPacket(
                owner, job, stage, message, "", BlockPos.ZERO, BlockPos.ZERO, 1, 0, false, false);
    }

    @Test
    void sneakPickupWinsOverApplyAtEveryHandState() {
        var fixed = WorkshopSketchPlacement.Phase.FIXED;
        var awaiting = WorkshopSketchPlacement.Phase.AWAITING;
        var held = WorkshopSketchPlacement.Phase.HELD;
        // WSAI-ACCEPT-1: Shift+RMB on a FIXED or AWAITING sketch picks it up, never applies.
        assertEquals(
                WorkshopToolsClient.AiUse.PICKUP_FIXED,
                WorkshopToolsClient.routeAiUse(true, true, fixed, true, "preview", true, false));
        assertEquals(
                WorkshopToolsClient.AiUse.RECALL_AWAITING,
                WorkshopToolsClient.routeAiUse(true, true, awaiting, true, "preview", true, false));
        // Plain RMB on FIXED still builds; a sketch already in hand still places, sneak or not.
        assertEquals(
                WorkshopToolsClient.AiUse.USE_ACTIVE,
                WorkshopToolsClient.routeAiUse(true, true, fixed, false, "preview", true, false));
        assertEquals(
                WorkshopToolsClient.AiUse.USE_ACTIVE,
                WorkshopToolsClient.routeAiUse(
                        true, true, awaiting, false, "preview", true, false));
        assertEquals(
                WorkshopToolsClient.AiUse.USE_ACTIVE,
                WorkshopToolsClient.routeAiUse(true, true, held, true, "preview", true, false));
        assertEquals(
                WorkshopToolsClient.AiUse.USE_ACTIVE,
                WorkshopToolsClient.routeAiUse(true, true, held, false, "idle", false, false));
        // No hand: preview pickup, apply, and missing-preview branches are unchanged.
        assertEquals(
                WorkshopToolsClient.AiUse.PICKUP_PREVIEW,
                WorkshopToolsClient.routeAiUse(true, false, held, true, "preview", true, false));
        assertEquals(
                WorkshopToolsClient.AiUse.APPLY,
                WorkshopToolsClient.routeAiUse(true, false, held, false, "preview", true, false));
        assertEquals(
                WorkshopToolsClient.AiUse.PREVIEW_MISSING,
                WorkshopToolsClient.routeAiUse(true, false, held, false, "preview", false, false));
        assertEquals(
                WorkshopToolsClient.AiUse.OPEN_SCREEN,
                WorkshopToolsClient.routeAiUse(true, false, held, false, "idle", false, false));
        assertEquals(
                WorkshopToolsClient.AiUse.BUSY,
                WorkshopToolsClient.routeAiUse(true, false, held, false, "applying", false, false));
        assertEquals(
                WorkshopToolsClient.AiUse.BUSY,
                WorkshopToolsClient.routeAiUse(true, false, held, false, "idle", false, true));
        assertEquals(
                WorkshopToolsClient.AiUse.UNAVAILABLE,
                WorkshopToolsClient.routeAiUse(false, true, fixed, true, "preview", true, false));
    }

    @Test
    void awaitingRecallKeepsTheHandTransformUntilServerAcknowledgement() {
        WorkshopSketchPlacement.clear();
        try {
            var packet =
                    new com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket(
                            UUID.randomUUID(),
                            "recall",
                            3,
                            1,
                            1,
                            "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[2,0,0],"
                                    + "\"block\":\"minecraft:stone\",\"hollow\":false}]}",
                            1,
                            false);
            WorkshopSketchPlacement.accept(packet);
            assertTrue(WorkshopSketchPlacement.active());
            assertEquals(1, WorkshopSketchPlacement.hand().rotY());
            WorkshopSketchPlacement.awaiting(UUID.randomUUID());
            assertEquals(WorkshopSketchPlacement.Phase.AWAITING, WorkshopSketchPlacement.phase());
            assertTrue(WorkshopSketchPlacement.recall());
            assertEquals(WorkshopSketchPlacement.Phase.HELD, WorkshopSketchPlacement.phase());
            assertTrue(WorkshopSketchPlacement.active());
            assertEquals(1, WorkshopSketchPlacement.hand().rotY());
            assertEquals(0, WorkshopSketchPlacement.hand().dy());
            assertFalse(WorkshopSketchPlacement.recall());
        } finally {
            WorkshopSketchPlacement.clear();
        }
        assertFalse(WorkshopSketchPlacement.recall());
    }

    @Test
    void keyPanelAvoidsMinimapHotbarAndChatAtAllowedGuiScales() {
        // Vanilla chrome, derived from the vanilla rules, not from the panel implementation.
        assertArrayEquals(new int[] {229, 338, 182, 22}, WorkshopToolsClient.hotbarRect(640, 360));

        // An open chat is a screen and hides the panel outright.
        assertTrue(WorkshopToolsClient.panelVisible(false, false));
        assertFalse(WorkshopToolsClient.panelVisible(true, false));
        assertFalse(WorkshopToolsClient.panelVisible(false, true));
        // Default minimap reservation, as MinimapHud.rect() reports it at default size.
        int[] minimap = {4, 4, 80, 96};
        java.util.List<Integer> scales1280 = new java.util.ArrayList<>();
        java.util.List<Integer> scales960 = new java.util.ArrayList<>();
        for (int[] window : new int[][] {{1280, 720}, {960, 540}}) {
            for (int scale = 1;
                    ceilDiv(window[0], scale) >= 320 && ceilDiv(window[1], scale) >= 240;
                    scale++) {
                (window[0] == 1280 ? scales1280 : scales960).add(scale);
                int sw = ceilDiv(window[0], scale), sh = ceilDiv(window[1], scale);
                int[] hotbar = WorkshopToolsClient.hotbarRect(sw, sh);
                int[] chat =
                        WorkshopChatArea.bounds(
                                320,
                                sh,
                                1,
                                9,
                                40,
                                20,
                                new int[] {0, 20, 100, 170, 190},
                                false,
                                false);
                for (int lines = 1; lines <= 4; lines++) {
                    int h = 5 + lines * 10;
                    for (int width : new int[] {140, 300, 500}) {
                        int[] box = WorkshopToolsClient.placePanel(sw, sh, width, h, minimap, chat);
                        String where = sw + "x" + sh + " w" + width + " lines" + lines;
                        if (box == null) continue;
                        assertOnScreen(box, sw, sh, where);
                        assertFalse(overlaps(box, minimap), "panel avoids minimap at " + where);
                        assertFalse(overlaps(box, hotbar), "panel avoids hotbar at " + where);
                        assertFalse(overlaps(box, chat), "panel avoids chat at " + where);
                        // A hidden minimap keeps the old top-centre position untouched.
                        int[] plain = WorkshopToolsClient.placePanel(sw, sh, width, h, null, chat);
                        assertOnScreen(plain, sw, sh, where);
                        assertEquals(4, plain[1]);
                        assertEquals((sw - plain[2]) / 2, plain[0]);
                        assertFalse(overlaps(plain, hotbar), "panel avoids hotbar at " + where);
                        assertFalse(overlaps(plain, chat), "panel avoids chat at " + where);
                    }
                }
            }
        }
        assertEquals(java.util.List.of(1, 2, 3), scales1280);
        assertEquals(java.util.List.of(1, 2), scales960);
    }

    private static void assertOnScreen(int[] box, int sw, int sh, String where) {
        assertTrue(
                box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= sw && box[1] + box[3] <= sh,
                "panel stays on screen at " + where);
    }

    private static int ceilDiv(int value, int scale) {
        return (value + scale - 1) / scale;
    }

    private static boolean overlaps(int[] box, int[] other) {
        return WorkshopToolsClient.intersects(box[0], box[1], box[2], box[3], other);
    }

    @Test
    void aiChipStaysOverChatButNotOverMenusOrF1() {
        // B8: an open ChatScreen keeps the chip of a running job (S5/D14).
        assertTrue(WorkshopToolsClient.chipVisible(true, true, false, true));
        assertTrue(WorkshopToolsClient.chipVisible(false, false, false, true));
        // … but never the idle key lines, and nothing over inventory/menus …
        assertFalse(WorkshopToolsClient.chipVisible(true, false, false, true));
        assertFalse(WorkshopToolsClient.chipVisible(true, true, false, false));
        assertFalse(WorkshopToolsClient.chipVisible(false, false, false, false));
        // … and F1 always wins.
        assertFalse(WorkshopToolsClient.chipVisible(false, false, true, true));
        assertFalse(WorkshopToolsClient.chipVisible(true, true, true, true));
    }

    @Test
    void wandDirectionPicksTheDominantLookAxis() {
        assertEquals("up", WorkshopToolsClient.expandDirection(0.1, 1, 0.1));
        assertEquals("down", WorkshopToolsClient.expandDirection(0.2, -1, 0.3));
        assertEquals("east", WorkshopToolsClient.expandDirection(1, 0.1, 0.2));
        assertEquals("west", WorkshopToolsClient.expandDirection(-1, 0, 0.5));
        assertEquals("south", WorkshopToolsClient.expandDirection(0.2, 0, 1));
        assertEquals("north", WorkshopToolsClient.expandDirection(0, 0.1, -1));
        // Diagonals go to the longest axis; ties prefer vertical, then x.
        assertEquals("up", WorkshopToolsClient.expandDirection(0.7, 0.7, 0));
        assertEquals("east", WorkshopToolsClient.expandDirection(0.7, 0.1, 0.7));
        assertEquals("south", WorkshopToolsClient.expandDirection(0.1, 0.2, 1));
    }

    @Test
    void wandCommandSignPicksExpandVsContract() {
        assertEquals("/expand 4 up", WorkshopToolsClient.wandCommand(4, "up"));
        // Looking up and scrolling down lowers the top face: contract toward the opposite side.
        assertEquals("/contract 1 down", WorkshopToolsClient.wandCommand(-1, "up"));
        assertEquals("/contract 2 south", WorkshopToolsClient.wandCommand(-2, "north"));
        assertNull(WorkshopToolsClient.wandCommand(0, "up"));
        assertNull(WorkshopToolsClient.wandCommand(1, null));
    }

    @Test
    void wandNotchesCoalesceAndFlush() {
        assertNull(WorkshopToolsClient.accumulateWand(1, "up", 1000));
        assertNull(WorkshopToolsClient.accumulateWand(2, "up", 1050));
        assertNull(
                WorkshopToolsClient.drainWand(1050 + WorkshopToolsClient.WAND_FLUSH_MS - 1, false));
        assertEquals(
                "/expand 3 up",
                WorkshopToolsClient.drainWand(1050 + WorkshopToolsClient.WAND_FLUSH_MS, false));
        assertNull(WorkshopToolsClient.drainWand(9999, true));
    }

    @Test
    void wandTurnAndSignChangePreserveBatchOrderAndTotals() {
        WorkshopToolsClient.accumulateWand(3, "up", 1000);
        WorkshopToolsClient.accumulateWand(-2, "up", 1010);
        WorkshopToolsClient.accumulateWand(4, "east", 1020);
        assertEquals("/expand 3 up", WorkshopToolsClient.drainWand(1020, true));
        assertEquals("/contract 2 down", WorkshopToolsClient.drainWand(2520, true));
        assertEquals("/expand 4 east", WorkshopToolsClient.drainWand(4020, true));
        assertNull(WorkshopToolsClient.drainWand(5520, true));
    }

    @Test
    void wandScrollConsumeDecision() {
        // Wand+Shift in the Workshop consumes; the sketch hand keeps priority.
        assertTrue(WorkshopToolsClient.wandConsumes(false, true, true, true, false, 1));
        assertTrue(WorkshopToolsClient.wandConsumes(false, true, true, true, false, -1));
        assertFalse(WorkshopToolsClient.wandConsumes(true, true, true, true, false, 1));
        // Plain wheel, other items, open screen, outside Workshop: hotbar as usual.
        assertFalse(WorkshopToolsClient.wandConsumes(false, true, false, true, false, 1));
        assertFalse(WorkshopToolsClient.wandConsumes(false, false, true, true, false, 1));
        assertFalse(WorkshopToolsClient.wandConsumes(false, true, true, true, true, 1));
        assertFalse(WorkshopToolsClient.wandConsumes(false, true, true, false, false, 1));
        assertFalse(WorkshopToolsClient.wandConsumes(false, true, true, true, false, 0));
    }

    @Test
    void directionChangesNeverSendFromTheScrollCallback() {
        WorkshopToolsClient.drainWand(0, true);
        for (int i = 0; i < 12; i++) {
            assertNull(
                    WorkshopToolsClient.accumulateWand(
                            1, i % 2 == 0 ? "east" : "south", 1000 + i * 10));
        }
    }

    @Test
    void forcedFlushCannotBypassTheSendInterval() {
        WorkshopToolsClient.drainWand(0, true);
        WorkshopToolsClient.accumulateWand(1, "up", 1000);
        assertEquals("/expand 1 up", WorkshopToolsClient.drainWand(1000, true));
        WorkshopToolsClient.accumulateWand(1, "east", 1010);
        assertNull(WorkshopToolsClient.drainWand(1010, true));
    }

    @Test
    void alternatingBurstSendsAtMostOneAndEventuallyPreservesEveryBatch() {
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            WorkshopToolsClient.accumulateWand(1, i % 2 == 0 ? "east" : "south", i * 10);
            String command = WorkshopToolsClient.drainWand(i * 10, true);
            if (command != null) sent.add(command);
        }
        assertEquals(1, sent.size());
        for (long now = 1500; now <= 18000; now += 1500) {
            String command = WorkshopToolsClient.drainWand(now, true);
            if (command != null) sent.add(command);
        }
        assertEquals(12, sent.size());
        for (int i = 0; i < sent.size(); i++)
            assertEquals("/expand 1 " + (i % 2 == 0 ? "east" : "south"), sent.get(i));
    }

    @Test
    void thirtySecondsOfScrollingStaysBelowVanillaBudgetAndRetainsTotals() {
        int budget = 0, peak = 0, sends = 0, total = 0;
        long lastSend = -WorkshopToolsClient.WAND_SEND_MS;
        // 120 notches, one every 250 ms; tick the sender and vanilla decay at 20 TPS.
        for (long now = 0; now <= 33000; now += 50) {
            budget = Math.max(0, budget - 1);
            if (now < 30000 && now % 250 == 0) WorkshopToolsClient.accumulateWand(1, "up", now);
            String command = WorkshopToolsClient.drainWand(now, now >= 30000);
            if (command == null) continue;
            assertTrue(now - lastSend >= WorkshopToolsClient.WAND_SEND_MS);
            lastSend = now;
            budget += 20;
            peak = Math.max(peak, budget);
            sends++;
            total += Integer.parseInt(command.split(" ")[1]);
        }
        assertTrue(sends <= 23);
        assertEquals(20, peak);
        assertEquals(120, total);
        assertNull(WorkshopToolsClient.drainWand(35000, true));
    }

    @Test
    void queueCeilingsRejectOnlyNewestInputAndCancellationKeepsTheRateGate() {
        WorkshopToolsClient.accumulateWand(WorkshopToolsClient.WAND_MAX_STEPS, "up", 1000);
        WorkshopToolsClient.accumulateWand(1, "east", 1001);
        assertEquals("/expand 4096 up", WorkshopToolsClient.drainWand(1001, true));
        assertNull(WorkshopToolsClient.drainWand(2501, true));
        for (int i = 0; i < WorkshopToolsClient.WAND_MAX_BATCHES + 1; i++)
            WorkshopToolsClient.accumulateWand(1, i % 2 == 0 ? "east" : "south", 3000);
        int count = 0;
        for (long now = 3000; now < 200000; now += 1500)
            if (WorkshopToolsClient.drainWand(now, true) != null) count++;
        assertEquals(WorkshopToolsClient.WAND_MAX_BATCHES, count);
        WorkshopToolsClient.accumulateWand(1, "up", 200000);
        assertNotNull(WorkshopToolsClient.drainWand(200000, true));
        WorkshopToolsClient.accumulateWand(2, "up", 200010);
        WorkshopToolsClient.clearWand();
        WorkshopToolsClient.accumulateWand(3, "east", 200020);
        assertNull(WorkshopToolsClient.drainWand(200020, true));
        assertEquals("/expand 3 east", WorkshopToolsClient.drainWand(201500, true));
    }

    @Test
    void wheelPreservesAggregatedAndFractionalMagnitude() {
        var wheel = new WorkshopToolsClient.WheelSteps();
        assertEquals(3, wheel.steps("wand:up", 3, false, 1));
        assertEquals(-3, wheel.steps("wand:up", -3, false, 1));
        for (int i = 0; i < 3; i++) assertEquals(0, wheel.steps("wand:up", 0.25, false, 1));
        assertEquals(1, wheel.steps("wand:up", 0.25, false, 1));
        for (int i = 0; i < 3; i++) assertEquals(0, wheel.steps("hand", 0.25, false, 1));
        assertEquals(1, wheel.steps("hand", 0.25, false, 1));
    }

    @Test
    void wheelMatchesVanillaDiscreteSensitivityAndSignReset() {
        var wheel = new WorkshopToolsClient.WheelSteps();
        assertEquals(1, wheel.steps("wand:up", 3, true, 1));
        assertEquals(-1, wheel.steps("wand:up", -0.25, true, 1));
        assertEquals(3, wheel.steps("wand:up", 1.5, false, 2));
        assertEquals(0, wheel.steps("wand:up", 0.75, false, 1));
        assertEquals(0, wheel.steps("wand:up", -0.25, false, 1));
        assertEquals(-1, wheel.steps("wand:up", -0.75, false, 1));
        assertEquals(0, wheel.steps("wand:up", 3, false, 0));
    }

    @Test
    void wheelRemainderNeverCrossesConsumerOrDirectionChanges() {
        var wheel = new WorkshopToolsClient.WheelSteps();
        String[] consumers = {"wand:up", "wand:east", "hand", "wand:up"};
        for (String consumer : consumers) {
            assertEquals(0, wheel.steps(consumer, 0.25, false, 1));
            assertEquals(0, wheel.steps(consumer, 0.5, false, 1));
        }
        // Shift release, put-away, screen, hand end and disconnect all select no consumer.
        wheel.select(null);
        assertEquals(0, wheel.steps("wand:up", 0.25, false, 1));
        assertEquals(1, wheel.steps("wand:up", 0.75, false, 1));
    }

    private static WorkshopAiStateS2CPacket state(UUID owner, UUID job, String stage) {
        return state(owner, job, stage, 0);
    }

    private static WorkshopAiStateS2CPacket state(UUID owner, UUID job, String stage, int total) {
        return new WorkshopAiStateS2CPacket(
                owner, job, stage, "", "", BlockPos.ZERO, BlockPos.ZERO, total, 0, false, false);
    }
}
