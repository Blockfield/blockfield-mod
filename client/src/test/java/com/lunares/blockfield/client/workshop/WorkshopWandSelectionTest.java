package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkshopWandSelectionTest {
    @AfterEach
    void clearSelection() {
        WorkshopWandSelection.clear();
    }

    private void cuboid(int x0, int y0, int z0, int x1, int y1, int z1) {
        WorkshopWandSelection.parse("s|cuboid");
        WorkshopWandSelection.parse("p|0|" + x0 + "|" + y0 + "|" + z0 + "|100");
        WorkshopWandSelection.parse("p|1|" + x1 + "|" + y1 + "|" + z1 + "|100");
    }

    public record InstalledCuiPacket(String text) implements CustomPacketPayload {
        public static final Type<InstalledCuiPacket> TYPE =
                new Type<>(WorkshopWandSelection.CuiPayload.TYPE.id());

        @Override
        public Type<InstalledCuiPacket> type() {
            return TYPE;
        }
    }

    @Test
    void farClickSelectsTheExtendedRayBlock() {
        // WSAI-ACCEPT-2 repro: gold block ~28 away, outside vanilla reach, negative coordinates.
        BlockPos far = new BlockPos(-20, 200, -4);
        assertEquals(far, WorkshopWandSelection.resolveClick(null, far));
        assertEquals(
                new BlockPos(1, 64, 1),
                WorkshopWandSelection.resolveClick(new BlockPos(1, 64, 1), far));
        assertNull(WorkshopWandSelection.resolveClick(null, null));
        var first = WorkshopWandSelection.useStep(WorkshopWandSelection.WandPhase.IDLE, far, false);
        assertEquals(WorkshopWandSelection.WandPhase.SELECTING, first.phase());
        assertEquals("/pos1 -20,200,-4", first.command());
        int[] live = WorkshopWandSelection.liveBox(far, new BlockPos(-14, 204, 4));
        assertArrayEquals(new int[] {-20, 200, -4, -14, 204, 4}, live);
        assertArrayEquals(new int[] {7, 5, 9}, WorkshopWandSelection.dimensions(live));
        assertEquals(315, WorkshopWandSelection.blockCount(live));
        assertEquals("7×5×9", WorkshopWandSelection.dims(live));
    }

    @Test
    void onlyClientsWithoutWorldEditRegisterTheirOwnCodec() {
        assertTrue(WorkshopWandSelection.registerOwnType(false));
        assertFalse(WorkshopWandSelection.registerOwnType(true));
    }

    @Test
    void installedPacketUsesItsTypeConstructorAndBoundedTextAccessor() {
        var api = new WorkshopWorldEditCui(InstalledCuiPacket.class);
        assertSame(InstalledCuiPacket.TYPE, api.type());
        var handshake = api.payload(WorkshopWandSelection.HANDSHAKE);
        assertInstanceOf(InstalledCuiPacket.class, handshake);
        assertEquals("v|4", api.text(handshake));
        assertEquals("p|0|1|64|-3|9", api.text(api.payload("p|0|1|64|-3|9")));
        assertNull(api.text(api.payload("x".repeat(WorkshopWandSelection.MAX_TEXT + 1))));
        assertNull(api.text(api.payload(null)));
    }

    @Test
    void cuboidCornersNormalizeToInclusiveBounds() {
        cuboid(5, 70, 5, 1, 64, -3);
        assertArrayEquals(new int[] {1, 64, -3, 5, 70, 5}, WorkshopWandSelection.box());
    }

    @Test
    void singleCornerShowsThatBlock() {
        WorkshopWandSelection.parse("s|cuboid");
        WorkshopWandSelection.parse("p|0|10|64|-7|0");
        assertArrayEquals(new int[] {10, 64, -7, 10, 64, -7}, WorkshopWandSelection.box());
        WorkshopWandSelection.parse("p|1|12|66|-5|9");
        assertArrayEquals(new int[] {10, 64, -7, 12, 66, -5}, WorkshopWandSelection.box());
    }

    @Test
    void shapeSelectClearsPreviousCorners() {
        cuboid(1, 64, 1, 5, 70, 5);
        assertNotNull(WorkshopWandSelection.box());
        // //desel clears the selector, then pushes the shape event with no points.
        WorkshopWandSelection.parse("s|cuboid");
        assertNull(WorkshopWandSelection.box());
    }

    @Test
    void unknownAndMalformedLinesNeverCrashOrChangeState() {
        cuboid(1, 64, 1, 5, 70, 5);
        int[] before = WorkshopWandSelection.box();
        assertDoesNotThrow(
                () -> {
                    WorkshopWandSelection.parse("");
                    WorkshopWandSelection.parse("p|0|1|2");
                    WorkshopWandSelection.parse("p|0|a|b|c|d");
                    WorkshopWandSelection.parse("p|2|1|64|1|9");
                    WorkshopWandSelection.parse("p|0|30000001|64|1|9");
                    WorkshopWandSelection.parse("p|0|1|3000|1|9");
                    WorkshopWandSelection.parse("bogus|1|2|3");
                    WorkshopWandSelection.parse("p2|0|1|2|3");
                    WorkshopWandSelection.parse("mm|a|b");
                    WorkshopWandSelection.parse("cyl|1|2");
                    WorkshopWandSelection.parse("e|0|1|2");
                    WorkshopWandSelection.parse("s|cuboid|extra");
                    WorkshopWandSelection.parse("s|" + "x".repeat(WorkshopWandSelection.MAX_TEXT));
                    WorkshopWandSelection.parse("p|0|21474836470|64|1|9");
                });
        assertArrayEquals(before, WorkshopWandSelection.box());
    }

    @Test
    void ellipsoidFallsBackToCenterPlusMinusRadii() {
        WorkshopWandSelection.parse("s|ellipsoid");
        WorkshopWandSelection.parse("e|0|100|64|100");
        WorkshopWandSelection.parse("e|1|5|3|7");
        assertArrayEquals(new int[] {95, 61, 93, 105, 67, 107}, WorkshopWandSelection.box());
    }

    @Test
    void cylinderFallsBackToRadiusBoxWithMinMaxHeight() {
        WorkshopWandSelection.parse("s|cylinder");
        WorkshopWandSelection.parse("cyl|100|64|100|4.5|2.5");
        assertNull(WorkshopWandSelection.box());
        WorkshopWandSelection.parse("mm|60|70");
        assertArrayEquals(new int[] {95, 60, 97, 105, 70, 103}, WorkshopWandSelection.box());
    }

    @Test
    void polygonFallsBackToVertexBoundingBox() {
        WorkshopWandSelection.parse("s|polyhedron");
        WorkshopWandSelection.parse("p|0|1|64|1|9");
        WorkshopWandSelection.parse("p|1|9|70|1|9");
        WorkshopWandSelection.parse("p|2|5|66|8|9");
        WorkshopWandSelection.parse("poly|0|1|2");
        assertArrayEquals(new int[] {1, 64, 1, 9, 70, 8}, WorkshopWandSelection.box());
        WorkshopWandSelection.parse("mm|62|68");
        assertArrayEquals(new int[] {1, 62, 1, 9, 68, 8}, WorkshopWandSelection.box());
    }

    @Test
    void unknownShapeFallsBackToPointBoundingBoxOrHides() {
        WorkshopWandSelection.parse("s|weird");
        assertNull(WorkshopWandSelection.box());
        WorkshopWandSelection.parse("p|0|1|64|1|9");
        WorkshopWandSelection.parse("p|1|9|70|8|9");
        assertArrayEquals(new int[] {1, 64, 1, 9, 70, 8}, WorkshopWandSelection.box());
    }

    @Test
    void visibilityNeedsWorkshopToolAndBox() {
        assertTrue(WorkshopWandSelection.visible(true, true, true));
        assertFalse(WorkshopWandSelection.visible(false, true, true));
        assertFalse(WorkshopWandSelection.visible(true, false, true));
        assertFalse(WorkshopWandSelection.visible(true, true, false));
        assertFalse(WorkshopWandSelection.visible(false, false, false));
    }

    @Test
    void twoClickUseCyclesSelectingAndIssuesPosCommands() {
        var first =
                WorkshopWandSelection.useStep(
                        WorkshopWandSelection.WandPhase.IDLE, new BlockPos(12, 64, -7), false);
        assertEquals(WorkshopWandSelection.WandPhase.SELECTING, first.phase());
        assertEquals("/pos1 12,64,-7", first.command());
        var second =
                WorkshopWandSelection.useStep(
                        WorkshopWandSelection.WandPhase.SELECTING, new BlockPos(-3, 70, 25), false);
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, second.phase());
        assertEquals("/pos2 -3,70,25", second.command());
        // Shift keeps the clipboard path and air clicks do nothing.
        var shifted =
                WorkshopWandSelection.useStep(
                        WorkshopWandSelection.WandPhase.IDLE, BlockPos.ZERO, true);
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, shifted.phase());
        assertNull(shifted.command());
        var air = WorkshopWandSelection.useStep(WorkshopWandSelection.WandPhase.IDLE, null, false);
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, air.phase());
        assertNull(air.command());
        var airSelecting =
                WorkshopWandSelection.useStep(
                        WorkshopWandSelection.WandPhase.SELECTING, null, false);
        assertEquals(WorkshopWandSelection.WandPhase.SELECTING, airSelecting.phase());
        assertNull(airSelecting.command());
    }

    @Test
    void attackCancelsSelectingAndClearsOnlyAnExistingSelection() {
        var cancel =
                WorkshopWandSelection.attackStep(WorkshopWandSelection.WandPhase.SELECTING, false);
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, cancel.phase());
        assertEquals("/desel", cancel.command());
        var clear = WorkshopWandSelection.attackStep(WorkshopWandSelection.WandPhase.IDLE, true);
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, clear.phase());
        assertEquals("/desel", clear.command());
        var nothing = WorkshopWandSelection.attackStep(WorkshopWandSelection.WandPhase.IDLE, false);
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, nothing.phase());
        assertNull(nothing.command());
    }

    @Test
    void liveBoxNormalizesCornersAndReportsDimensions() {
        int[] live = WorkshopWandSelection.liveBox(new BlockPos(5, 70, 5), new BlockPos(1, 64, -3));
        assertArrayEquals(new int[] {1, 64, -3, 5, 70, 5}, live);
        assertArrayEquals(new int[] {5, 7, 9}, WorkshopWandSelection.dimensions(live));
        assertEquals(315, WorkshopWandSelection.blockCount(live));
        assertEquals("5×7×9", WorkshopWandSelection.dims(live));
        assertFalse(WorkshopWandSelection.tooBig(live));
        assertNull(WorkshopWandSelection.liveBox(null, new BlockPos(1, 64, 1)));
        assertTrue(WorkshopWandSelection.tooBig(new int[] {0, 0, 0, 200, 0, 0}));
    }

    @Test
    void faceSlabHugsTheLookedAtFace() {
        int[] box = {1, 64, -3, 5, 70, 5};
        double[] east = WorkshopWandSelection.faceSlab(box, "east");
        assertEquals(6 - 0.06, east[0], 1e-9);
        assertEquals(6 + 0.06, east[3], 1e-9);
        assertEquals(64, east[1], 1e-9);
        assertEquals(71, east[4], 1e-9);
        double[] up = WorkshopWandSelection.faceSlab(box, "up");
        assertEquals(71 - 0.06, up[1], 1e-9);
        assertEquals(71 + 0.06, up[4], 1e-9);
        // Unknown directions fall back to the north face.
        assertArrayEquals(
                WorkshopWandSelection.faceSlab(box, "north"),
                WorkshopWandSelection.faceSlab(box, "bogus"),
                1e-9);
    }

    @Test
    void effectiveBoxPrefersLiveThenPendingThenCui() {
        int[] live = {1, 1, 1, 2, 2, 2};
        int[] cui = {0, 0, 0, 0, 0, 0};
        int[] pending = {3, 3, 3, 4, 4, 4};
        assertSame(live, WorkshopWandSelection.effectiveBox(true, live, cui, pending));
        assertSame(cui, WorkshopWandSelection.effectiveBox(true, null, cui, pending));
        assertSame(pending, WorkshopWandSelection.effectiveBox(false, live, cui, pending));
        assertSame(cui, WorkshopWandSelection.effectiveBox(false, null, cui, null));
        assertNull(WorkshopWandSelection.effectiveBox(false, null, null, null));
    }

    @Test
    void projectionRejectsBehindCameraAndCentersAhead() {
        assertNull(WorkshopWandSelection.project(0, 0, -1, 1, 1, 100, 100));
        assertNull(WorkshopWandSelection.project(0, 0, 0.05, 1, 1, 100, 100));
        assertArrayEquals(
                new double[] {50, 50},
                WorkshopWandSelection.project(0, 0, 5, 1, 1, 100, 100),
                1e-9);
        assertNull(WorkshopWandSelection.project(10, 0, 5, 1, 1, 100, 100));
    }

    @Test
    void hintKeysFollowPhaseAndSelection() {
        assertEquals(
                List.of("wand_select", "wand_expand"),
                WorkshopWandSelection.hintKeys(false, false));
        assertEquals(
                List.of("wand_select", "wand_clear", "wand_expand"),
                WorkshopWandSelection.hintKeys(false, true));
        assertEquals(
                List.of("wand_selecting", "wand_expand"),
                WorkshopWandSelection.hintKeys(true, false));
    }

    @Test
    void finishedBoxShowsUntilCuiCatchesUp() {
        BlockPos first = new BlockPos(1, 64, 1);
        BlockPos second = new BlockPos(3, 66, 4);
        WorkshopWandSelection.beginSelect(first);
        assertEquals(WorkshopWandSelection.WandPhase.SELECTING, WorkshopWandSelection.wandPhase());
        WorkshopWandSelection.finishSelect(second);
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, WorkshopWandSelection.wandPhase());
        int[] optimistic = WorkshopWandSelection.liveBox(first, second);
        assertArrayEquals(optimistic, WorkshopWandSelection.pending());
        assertArrayEquals(
                optimistic,
                WorkshopWandSelection.effectiveBox(
                        false, null, new int[] {1, 64, 1, 1, 64, 1}, optimistic));
        // Intermediate CUI bursts never match, so the optimistic box holds steady.
        WorkshopWandSelection.parse("s|cuboid");
        assertArrayEquals(optimistic, WorkshopWandSelection.pending());
        WorkshopWandSelection.parse("p|0|1|64|1|9");
        assertArrayEquals(optimistic, WorkshopWandSelection.pending());
        // The matching confirmation retires it.
        WorkshopWandSelection.parse("p|1|3|66|4|9");
        assertNull(WorkshopWandSelection.pending());
        assertArrayEquals(optimistic, WorkshopWandSelection.box());
        // Cancelling drops everything.
        WorkshopWandSelection.beginSelect(first);
        WorkshopWandSelection.cancelSelect();
        assertEquals(WorkshopWandSelection.WandPhase.IDLE, WorkshopWandSelection.wandPhase());
        assertNull(WorkshopWandSelection.pending());
    }

    @Test
    void payloadRoundTripsRawUtf8() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        WorkshopWandSelection.CuiPayload sent =
                new WorkshopWandSelection.CuiPayload("p|0|1|64|-3|9");
        WorkshopWandSelection.CuiPayload.CODEC.encode(buf, sent);
        WorkshopWandSelection.CuiPayload received =
                WorkshopWandSelection.CuiPayload.CODEC.decode(buf);
        assertEquals(sent, received);
        assertEquals("worldedit:cui", WorkshopWandSelection.CuiPayload.TYPE.id().toString());
        assertEquals("v|4", WorkshopWandSelection.HANDSHAKE);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkshopWandSelection.CuiPayload(
                                "x".repeat(WorkshopWandSelection.MAX_TEXT + 1)));
        buf.release();
    }
}
