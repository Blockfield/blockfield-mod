package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.WorkshopAiPlanChunkS2CPacket;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorkshopAiMirrorTransferTest {
    @Test
    void previewUsesRequestedMirrorPlanesAndSketchKeepsWrapper() {
        String json =
                "{\"version\":3,\"operations\":[{\"type\":\"mirror\",\"axes\":\"xz\",\"operations\":[{\"type\":\"cuboid\",\"from\":[1,0,2],\"to\":[1,0,2],\"block\":\"minecraft:stone\",\"hollow\":false}]}]}";
        var p = WorkshopBlueprint.parse(json, 0, 0, 0, 7, 2, 9);
        assertEquals(4, p.touched());
        for (int x : new int[] {1, 5})
            for (int z : new int[] {2, 6}) assertTrue(p.block(x, 0, z) >= 0);
        var chunks =
                WorkshopAiPlanChunkS2CPacket.split(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BlockPos.ZERO,
                        "held",
                        7,
                        2,
                        9,
                        1,
                        true,
                        true,
                        json);
        var transfer = new WorkshopAiPlanTransfer();
        var complete = transfer.accept(chunks.getFirst(), 0);
        assertEquals(json, complete.plan());
        assertEquals(7, complete.header().sx());
        assertTrue(complete.header().mirror());
    }

    @Test
    void largePlansReassembleWithinBoundsAndDropOutOfOrderExpiredOrMismatchedTransfers() {
        String plan = "x".repeat(100_000);
        var chunks =
                WorkshopAiPlanChunkS2CPacket.split(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BlockPos.ZERO,
                        "",
                        16,
                        16,
                        16,
                        0,
                        false,
                        false,
                        plan);
        var transfer = new WorkshopAiPlanTransfer();
        WorkshopAiPlanTransfer.Complete complete = null;
        for (var chunk : chunks) complete = transfer.accept(chunk, 100);
        assertNotNull(complete);
        assertEquals(plan, complete.plan());
        transfer.clear();
        assertNull(transfer.accept(chunks.get(1), 100));
        assertNull(transfer.accept(chunks.getFirst(), 100));
        assertNull(transfer.accept(chunks.get(1), 20_101));
        transfer.clear();
        assertNull(transfer.accept(chunks.getFirst(), 100));
        assertNull(transfer.accept(chunks.get(2), 100));
        assertNull(transfer.accept(chunks.get(1), 100));
        transfer.clear();
        assertNull(transfer.accept(chunks.getFirst(), 100));
        var p = chunks.get(1);
        assertNull(
                transfer.accept(
                        new WorkshopAiPlanChunkS2CPacket(
                                p.transferId(),
                                p.ownerId(),
                                p.jobId(),
                                p.origin(),
                                p.name(),
                                17,
                                p.sy(),
                                p.sz(),
                                p.rotY(),
                                p.mirror(),
                                p.hand(),
                                p.index(),
                                p.count(),
                                p.data()),
                        100));
        assertThrows(
                RuntimeException.class,
                () ->
                        WorkshopAiPlanChunkS2CPacket.split(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                BlockPos.ZERO,
                                "",
                                16,
                                16,
                                16,
                                0,
                                false,
                                false,
                                "x".repeat(524_289)));
    }

    @Test
    void mergedPreviewKeepsAllOperationsAndTheUniqueCellBudget() {
        String leaf =
                "{\"type\":\"cuboid\",\"from\":[1,0,2],\"to\":[1,0,2],\"block\":\"minecraft:stone\",\"hollow\":false}";
        String plan =
                "{\"version\":3,\"operations\":["
                        + String.join(",", java.util.Collections.nCopies(768, leaf))
                        + "]}";
        assertTrue(plan.length() > 65536);
        assertEquals(1, WorkshopBlueprint.parse(plan, 0, 0, 0, 7, 3, 9).touched());
    }

    @Test
    void rotatedPreviewPreservesMirrorCopyOrderEvenForOverlappingSourceQuadrants() {
        String a =
                "{\"type\":\"cuboid\",\"from\":[0,0,3],\"to\":[0,0,3],\"block\":\"minecraft:stone\",\"hollow\":false}";
        String b =
                "{\"type\":\"cuboid\",\"from\":[3,0,0],\"to\":[3,0,0],\"block\":\"minecraft:dirt\",\"hollow\":false}";
        String json =
                "{\"version\":3,\"operations\":[{\"type\":\"mirror\",\"axes\":\"xz\",\"operations\":["
                        + a
                        + ","
                        + b
                        + "]}]}";
        for (int turns = 0; turns < 4; turns++)
            for (boolean mirror : new boolean[] {false, true}) {
                var p = WorkshopBlueprint.parse(json, 0, 0, 0, 4, 2, 4, turns, mirror);
                for (int x : new int[] {0, 3})
                    for (int z : new int[] {0, 3}) {
                        String expected = z == 0 ? "minecraft:stone" : "minecraft:dirt";
                        int tx = mirror ? 3 - x : x, tz = z;
                        for (int i = 0; i < turns; i++) {
                            int next = 3 - tz;
                            tz = tx;
                            tx = next;
                        }
                        assertEquals(expected, p.blocks().get(p.block(tx, 0, tz)));
                    }
            }
    }
}
