package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.workshop.WorkshopChainPlacement;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorkshopChainPlacementTest {
    @Test
    void everyRotationMirrorOddAndEvenFootprintTilesWithNoGapOrOverlap() {
        BlockPos from = new BlockPos(-20, 64, 30);
        for (int sx : new int[] {3, 4, 7, 8})
            for (int sz : new int[] {1, 2, 5, 6})
                for (int rot = 0; rot < 4; rot++)
                    for (boolean mirror : new boolean[] {false, true})
                        for (int[] look : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            int rx = (rot % 2 == 0 ? sx : sz), rz = (rot % 2 == 0 ? sz : sx);
                            BlockPos next =
                                    WorkshopChainPlacement.next(
                                            from, sx, sz, rot, look[0], look[1]);
                            assertEquals(from.offset(look[0] * rx, 0, look[1] * rz), next);
                            assertTrue(WorkshopChainPlacement.adjacent(from, next, sx, sz, rot));
                            for (int x = 0; x < sx; x++)
                                for (int z = 0; z < sz; z++) {
                                    int[] cell =
                                            WorkshopSketchPlacement.mapCell(
                                                    x, 0, z, sx, sz, rot, mirror);
                                    BlockPos point = next.offset(cell[0], 0, cell[2]);
                                    assertFalse(
                                            point.getX() >= from.getX()
                                                    && point.getX() < from.getX() + rx
                                                    && point.getZ() >= from.getZ()
                                                    && point.getZ() < from.getZ() + rz);
                                }
                            assertFalse(
                                    WorkshopChainPlacement.adjacent(
                                            from, next.above(), sx, sz, rot));
                            assertFalse(
                                    WorkshopChainPlacement.adjacent(
                                            from, next.offset(look[0], 0, look[1]), sx, sz, rot));
                        }
    }

    @Test
    void diagonalLookPrefersLongAxisAndDecisiveLookCanChooseEitherAxis() {
        assertEquals(
                new BlockPos(8, 0, 0), WorkshopChainPlacement.next(BlockPos.ZERO, 8, 2, 0, 1, 1));
        assertEquals(
                new BlockPos(0, 0, -8),
                WorkshopChainPlacement.next(BlockPos.ZERO, 8, 2, 1, -1, -1));
        assertEquals(
                new BlockPos(0, 0, 2), WorkshopChainPlacement.next(BlockPos.ZERO, 8, 2, 0, .1, 1));
        assertEquals(
                new BlockPos(-8, 0, 0),
                WorkshopChainPlacement.next(BlockPos.ZERO, 8, 2, 0, -1, .1));
    }
}
