package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkshopSketchPlacementTest {
    @Test
    void anchorSitsBottomCentredOnTheHitFace() {
        // 3-wide footprint on top of a block: centred.
        assertEquals(
                new BlockPos(9, 65, 9),
                WorkshopSketchPlacement.anchorOnFace(
                        new BlockPos(10, 64, 10), Direction.UP, 3, 3, 0));
        // Even sides bias toward the minimum corner, plus the wheel height.
        assertEquals(
                new BlockPos(8, 67, 8),
                WorkshopSketchPlacement.anchorOnFace(
                        new BlockPos(10, 64, 10), Direction.UP, 4, 4, 2));
        // Side faces grow outward from the wall.
        assertEquals(
                new BlockPos(10, 64, 9),
                WorkshopSketchPlacement.anchorOnFace(
                        new BlockPos(10, 64, 10), Direction.EAST, 3, 3, 0));
        assertEquals(
                new BlockPos(9, 62, 9),
                WorkshopSketchPlacement.anchorOnFace(
                        new BlockPos(10, 64, 10), Direction.DOWN, 3, 3, -1));
    }

    @Test
    void rotationOnlyPermutesHorizontalSides() {
        assertArrayEquals(new int[] {2, 5, 7}, WorkshopSketchPlacement.rotatedSides(2, 5, 7, 0));
        assertArrayEquals(new int[] {7, 5, 2}, WorkshopSketchPlacement.rotatedSides(2, 5, 7, 1));
        assertArrayEquals(new int[] {2, 5, 7}, WorkshopSketchPlacement.rotatedSides(2, 5, 7, 2));
        assertArrayEquals(new int[] {7, 5, 2}, WorkshopSketchPlacement.rotatedSides(2, 5, 7, 3));
    }

    @Test
    void rotatedViewMatchesTheServerTransform() {
        var blueprint =
                WorkshopBlueprint.parse(
                        "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[2,0,0],"
                                + "\"block\":\"minecraft:stone\",\"hollow\":false}]}",
                        0,
                        0,
                        0);
        var held =
                new WorkshopSketchPlacement.Hand(
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        "bar",
                        blueprint,
                        3,
                        1,
                        1,
                        1,
                        false,
                        0);
        var seen = WorkshopSketchPlacement.view(held, new BlockPos(10, 64, 10));
        // A 3×1 bar along X turns into a 1×3 bar along Z at the same corner.
        assertEquals(3, seen.cells().size());
        assertTrue(
                seen.cells().stream()
                        .allMatch(cell -> cell.x() == 0 && cell.z() >= 0 && cell.z() < 3));
        assertEquals(new BlockPos(10, 64, 10), seen.origin());
    }

    @Test
    void partialFootprintMatchesServerMathForEveryRotationAndMirror() {
        int sx = 16, sz = 23;
        for (boolean mirror : new boolean[] {false, true}) {
            for (int rot = 0; rot < 4; rot++) {
                for (int x = 0; x < 2; x++)
                    for (int z = 0; z < 2; z++) {
                        int mx = mirror ? sx - 1 - x : x;
                        int[] expected =
                                switch (rot) {
                                    case 0 -> new int[] {mx, 4, z};
                                    case 1 -> new int[] {sz - 1 - z, 4, mx};
                                    case 2 -> new int[] {sx - 1 - mx, 4, sz - 1 - z};
                                    default -> new int[] {z, 4, sx - 1 - mx};
                                };
                        assertArrayEquals(
                                expected,
                                WorkshopSketchPlacement.mapCell(x, 4, z, sx, sz, rot, mirror));
                    }
            }
        }
        assertArrayEquals(
                new int[] {22, 0, 0}, WorkshopSketchPlacement.mapCell(0, 0, 0, sx, sz, 1, false));
    }

    @Test
    void missBlockHitUsesEightBlockFallbackInsteadOfTheRayEndpoint() {
        Vec3 eye = new Vec3(0.5, 64, 0.5), look = new Vec3(1, 0, 0);
        var miss =
                net.minecraft.world.phys.BlockHitResult.miss(
                        eye.add(look.scale(64)), Direction.WEST, new BlockPos(64, 64, 0));
        assertEquals(
                new BlockPos(7, 64, -1),
                WorkshopSketchPlacement.anchorForHit(miss, eye, look, 3, 3, 0));
        assertEquals(
                new BlockPos(62, 64, -1),
                WorkshopSketchPlacement.anchorForHit(
                        new net.minecraft.world.phys.BlockHitResult(
                                eye.add(look.scale(64)),
                                Direction.WEST,
                                new BlockPos(64, 64, 0),
                                false),
                        eye,
                        look,
                        3,
                        3,
                        0));
    }

    @Test
    void sketchPacketRoundTripsSourceFootprintAndRejectsOutOfRangeRotation() {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var packet =
                    new com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket(
                            java.util.UUID.randomUUID(), "partial", 16, 8, 23, "{}", 3, true);
            com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket.CODEC.encode(
                    buffer, packet);
            assertEquals(
                    packet,
                    com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket.CODEC
                            .decode(buffer));
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            buffer.writeUUID(packet.sketchId());
            buffer.writeUtf(packet.name());
            buffer.writeVarInt(16);
            buffer.writeVarInt(8);
            buffer.writeVarInt(23);
            buffer.writeUtf("{}");
            buffer.writeVarInt(99);
            buffer.writeBoolean(true);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            com.lunares.blockfield.network.packet.WorkshopAiSketchPlanS2CPacket
                                    .CODEC
                                    .decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void fallbackFloatsAheadAndHeightClamps() {
        BlockPos at =
                WorkshopSketchPlacement.fallbackAhead(
                        new Vec3(0.5, 64.0, 0.5), new Vec3(1, 0, 0), 8, 3, 3, 0);
        assertEquals(new BlockPos(7, 64, -1), at);
        var held =
                new WorkshopSketchPlacement.Hand(
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        "x",
                        WorkshopBlueprint.parse(
                                "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[0,0,0],"
                                        + "\"block\":\"minecraft:stone\",\"hollow\":false}]}",
                                0,
                                0,
                                0),
                        1,
                        1,
                        1,
                        0,
                        false,
                        120);
        assertEquals(128, held.heightened(64).dy());
        assertEquals(-128, held.heightened(-512).dy());
        assertEquals(1, held.rotated().rotY());
        assertEquals(0, held.rotated().rotated().rotated().rotated().rotY());
    }

    @Test
    void waterGhostIdentitySurvivesAllRotationsMirrorsAndHeight() {
        var blueprint =
                WorkshopBlueprint.parse(
                        """
                {"version":2,"operations":[{"from":[0,1,2],"to":[0,1,2],
                "block":"minecraft:water","hollow":false,"match":"air"}]}
                """,
                        0,
                        0,
                        0);
        assertTrue(blueprint.ghosts().getFirst().water());
        assertFalse(blueprint.ghosts().getFirst().erase());
        for (boolean mirror : new boolean[] {false, true})
            for (int rotation = 0; rotation < 4; rotation++) {
                var hand =
                        new WorkshopSketchPlacement.Hand(
                                java.util.UUID.randomUUID(),
                                java.util.UUID.randomUUID(),
                                "Water",
                                blueprint,
                                3,
                                4,
                                3,
                                rotation,
                                mirror,
                                5);
                var view = WorkshopSketchPlacement.view(hand, new BlockPos(10, 69, 10));
                var cell = view.cells().getFirst();
                int[] expected = WorkshopSketchPlacement.mapCell(0, 1, 2, 3, 3, rotation, mirror);
                assertTrue(cell.water());
                assertArrayEquals(expected, new int[] {cell.x(), cell.y(), cell.z()});
                assertEquals(70, view.origin().getY() + cell.y());
                assertEquals(6, hand.heightened(1).dy());
            }
    }
}
