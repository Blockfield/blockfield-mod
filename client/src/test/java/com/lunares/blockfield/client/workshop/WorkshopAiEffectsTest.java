package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.WorkshopAiPreviewS2CPacket;
import com.lunares.blockfield.network.packet.WorkshopAiStateS2CPacket;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkshopAiEffectsTest {
    @AfterEach
    void clearEffects() {
        WorkshopAiEffects.clear();
    }

    @Test
    void ownerPreviewSurvivesTimeButEndsOnJobStageScopeAndDisconnect() {
        UUID owner = UUID.randomUUID(), job = UUID.randomUUID();
        var packet =
                new WorkshopAiPreviewS2CPacket(
                        owner,
                        job,
                        BlockPos.ZERO,
                        "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[1,0,1],\"block\":\"minecraft:stone\",\"hollow\":false}]}");
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, BlockPos.ZERO, 1);
        WorkshopAiEffects.expire(Level.OVERWORLD, owner, 600_000_000_000L);
        assertTrue(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
        WorkshopAiEffects.ownerState(
                new WorkshopAiStateS2CPacket(
                        owner,
                        UUID.randomUUID(),
                        "preview",
                        "",
                        "",
                        BlockPos.ZERO,
                        BlockPos.ZERO,
                        1,
                        0,
                        false,
                        false),
                owner);
        assertFalse(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, BlockPos.ZERO, 2);
        WorkshopAiEffects.ownerState(
                new WorkshopAiStateS2CPacket(
                        owner,
                        job,
                        "planning",
                        "",
                        "",
                        BlockPos.ZERO,
                        BlockPos.ZERO,
                        1,
                        0,
                        false,
                        false),
                owner);
        assertFalse(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, BlockPos.ZERO, 3);
        WorkshopAiEffects.expire(Level.NETHER, owner, 4);
        assertFalse(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, BlockPos.ZERO, 5);
        WorkshopAiEffects.clear();
        assertFalse(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
    }

    @Test
    void remoteEffectsCannotEvictTheOwnersPersistentPreview() {
        UUID owner = UUID.randomUUID(), job = UUID.randomUUID();
        String plan =
                "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[0,0,0],\"block\":\"minecraft:stone\",\"hollow\":false}]}";
        var packet = new WorkshopAiPreviewS2CPacket(owner, job, BlockPos.ZERO, plan);
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, BlockPos.ZERO, 1);
        WorkshopAiEffects.ownerState(
                WorkshopAiEffects.previewState(packet, WorkshopBlueprint.parse(plan, 0, 0, 0)),
                owner);
        for (int i = 0; i < 10; i++)
            WorkshopAiEffects.acceptPreview(
                    new WorkshopAiPreviewS2CPacket(
                            UUID.randomUUID(), UUID.randomUUID(), BlockPos.ZERO, plan),
                    Level.OVERWORLD,
                    BlockPos.ZERO,
                    i + 2);
        assertTrue(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
    }

    @Test
    void farCornerInsideMaximumFloorKeepsPreviewWhileRadiusAndWorldRemainBounded() {
        UUID owner = UUID.randomUUID(), job = UUID.randomUUID();
        BlockPos origin = new BlockPos(10, 60, -5);
        String plan =
                "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[127,0,127],\"block\":\"minecraft:stone\",\"hollow\":false}]}";
        var packet = new WorkshopAiPreviewS2CPacket(owner, job, origin, plan);
        var state = WorkshopAiEffects.previewState(packet, WorkshopBlueprint.parse(plan, 0, 0, 0));
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, origin, 1);
        WorkshopAiEffects.accept(state, Level.OVERWORLD, 2);
        Vec3 farCorner = new Vec3(137.5, 60.5, 122.5);
        assertTrue(farCorner.distanceToSqr(Vec3.atCenterOf(origin)) > 128 * 128);
        assertTrue(WorkshopAiEffects.withinRenderDistance(state, farCorner));
        assertTrue(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, farCorner));
        assertFalse(WorkshopAiEffects.hasPreview(owner, job, Level.NETHER, farCorner));

        Vec3 boundary = new Vec3(266, 60.5, 60);
        Vec3 outside = new Vec3(266.01, 60.5, 60);
        assertTrue(WorkshopAiEffects.withinRenderDistance(state, boundary));
        assertTrue(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, boundary));
        assertFalse(WorkshopAiEffects.withinRenderDistance(state, outside));
        assertFalse(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, outside));
        assertFalse(WorkshopAiEffects.withinRenderDistance(state, new Vec3(238, 60.5, 223)));
        assertFalse(WorkshopAiEffects.withinRenderDistance(state, new Vec3(60, 189.01, 60)));
    }

    private WorkshopAiStateS2CPacket preview(BlockPos min, BlockPos max) {
        return new WorkshopAiStateS2CPacket(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "preview",
                "",
                "",
                min,
                max,
                100,
                0,
                false,
                false);
    }

    @Test
    void boundedJobsAreRenderableButInvalidAndOverflowingBoundsAreRejected() {
        assertTrue(
                WorkshopAiEffects.validBounds(
                        preview(new BlockPos(10, 60, -5), new BlockPos(25, 72, 4))));
        assertTrue(
                WorkshopAiEffects.validBounds(preview(BlockPos.ZERO, new BlockPos(127, 127, 127))));
        for (BlockPos max :
                new BlockPos[] {
                    new BlockPos(128, 0, 0), new BlockPos(0, 128, 0), new BlockPos(0, 0, 128)
                }) assertFalse(WorkshopAiEffects.validBounds(preview(BlockPos.ZERO, max)));
        assertFalse(WorkshopAiEffects.validBounds(preview(BlockPos.ZERO, new BlockPos(-1, 0, 0))));
        assertFalse(
                WorkshopAiEffects.validBounds(
                        preview(
                                new BlockPos(Integer.MAX_VALUE, 0, 0),
                                new BlockPos(Integer.MIN_VALUE, 0, 0))));
        assertFalse(
                WorkshopAiEffects.validBounds(
                        preview(new BlockPos(30_000_001, 0, 0), new BlockPos(30_000_002, 0, 0))));
    }

    @Test
    void previewBeforeStatusUsesTheActualHighriseBoundsAndRetainsItsGhosts() {
        UUID owner = UUID.randomUUID(), job = UUID.randomUUID();
        BlockPos origin = new BlockPos(10, 60, -5);
        String plan =
                "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[15,79,15],\"block\":\"minecraft:stone\",\"hollow\":true}]}";
        var packet = new WorkshopAiPreviewS2CPacket(owner, job, origin, plan);
        var blueprint = WorkshopBlueprint.parse(plan, 0, 0, 0);
        var state = WorkshopAiEffects.previewState(packet, blueprint);
        assertEquals(origin, state.min());
        assertEquals(origin.offset(15, 79, 15), state.max());
        assertEquals(5_192, state.totalBlocks());
        assertTrue(WorkshopAiEffects.validBounds(state));
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, origin, 1);
        assertTrue(
                WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.atCenterOf(origin)));
        WorkshopAiEffects.accept(state, Level.OVERWORLD, 2);
        assertTrue(
                WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.atCenterOf(origin)));
    }

    @Test
    void sameJobIdFromAnotherOwnerCannotCancelTheFirstOwnersPreview() {
        UUID ownerA = UUID.randomUUID(), ownerB = UUID.randomUUID(), job = UUID.randomUUID();
        String plan =
                "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"minecraft:stone\",\"hollow\":false}]}";
        WorkshopAiEffects.acceptPreview(
                new WorkshopAiPreviewS2CPacket(ownerA, job, BlockPos.ZERO, plan),
                Level.OVERWORLD,
                BlockPos.ZERO,
                1);
        WorkshopAiEffects.acceptPreview(
                new WorkshopAiPreviewS2CPacket(ownerB, job, BlockPos.ZERO, plan),
                Level.OVERWORLD,
                BlockPos.ZERO,
                2);
        assertTrue(WorkshopAiEffects.hasPreview(ownerA, job, Level.OVERWORLD, Vec3.ZERO));
        assertTrue(WorkshopAiEffects.hasPreview(ownerB, job, Level.OVERWORLD, Vec3.ZERO));

        WorkshopAiEffects.accept(
                new WorkshopAiStateS2CPacket(
                        ownerB,
                        job,
                        "cancelled",
                        "",
                        "",
                        BlockPos.ZERO,
                        BlockPos.ZERO,
                        0,
                        0,
                        false,
                        false),
                Level.OVERWORLD,
                3);
        assertTrue(WorkshopAiEffects.hasPreview(ownerA, job, Level.OVERWORLD, Vec3.ZERO));
        assertFalse(WorkshopAiEffects.hasPreview(ownerB, job, Level.OVERWORLD, Vec3.ZERO));

        WorkshopAiEffects.acceptPreview(
                new WorkshopAiPreviewS2CPacket(ownerB, job, BlockPos.ZERO, "{}"),
                Level.OVERWORLD,
                BlockPos.ZERO,
                4);
        assertTrue(WorkshopAiEffects.hasPreview(ownerA, job, Level.OVERWORLD, Vec3.ZERO));

        WorkshopAiEffects.accept(
                new WorkshopAiStateS2CPacket(
                        ownerA,
                        job,
                        "cancelled",
                        "",
                        "",
                        BlockPos.ZERO,
                        BlockPos.ZERO,
                        0,
                        0,
                        false,
                        false),
                Level.OVERWORLD,
                5);
        assertFalse(WorkshopAiEffects.hasPreview(ownerA, job, Level.OVERWORLD, Vec3.ZERO));
    }

    @Test
    void ownerAwaitingGhostSurvivesTheWholeQueueWaitButEndsOnTerminalReply() {
        UUID owner = UUID.randomUUID(), job = UUID.randomUUID(), stranger = UUID.randomUUID();
        var packet =
                new WorkshopAiPreviewS2CPacket(
                        owner,
                        job,
                        BlockPos.ZERO,
                        "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[1,0,1],\"block\":\"minecraft:stone\",\"hollow\":false}]}");
        WorkshopAiEffects.acceptPreview(packet, Level.OVERWORLD, BlockPos.ZERO, 1);
        var awaiting =
                new WorkshopAiStateS2CPacket(
                        owner,
                        job,
                        "awaiting",
                        "",
                        "",
                        BlockPos.ZERO,
                        BlockPos.ZERO,
                        1,
                        0,
                        false,
                        false);
        WorkshopAiEffects.accept(awaiting, Level.OVERWORLD, 2);
        WorkshopAiEffects.accept(
                new WorkshopAiStateS2CPacket(
                        stranger,
                        UUID.randomUUID(),
                        "awaiting",
                        "",
                        "",
                        BlockPos.ZERO,
                        BlockPos.ZERO,
                        1,
                        0,
                        false,
                        false),
                Level.OVERWORLD,
                3);
        // 31 s: past the generic stale window but inside the valid 120 s queue wait.
        WorkshopAiEffects.expire(Level.OVERWORLD, owner, 31_000_000_000L);
        assertTrue(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
        WorkshopAiEffects.ownerState(awaiting, owner);
        assertTrue(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
        // A terminal queue reply still clears the ghost after its finish fade.
        WorkshopAiEffects.accept(
                new WorkshopAiStateS2CPacket(
                        owner,
                        job,
                        "cancelled",
                        "",
                        "",
                        BlockPos.ZERO,
                        BlockPos.ZERO,
                        1,
                        0,
                        false,
                        false),
                Level.OVERWORLD,
                32_000_000_000L);
        WorkshopAiEffects.expire(Level.OVERWORLD, owner, 32_000_000_000L + 900_000_000L + 1);
        assertFalse(WorkshopAiEffects.hasPreview(owner, job, Level.OVERWORLD, Vec3.ZERO));
    }

    @Test
    void previewViewExposesThePlanForHandPickup() {
        UUID owner = UUID.randomUUID(), job = UUID.randomUUID();
        BlockPos origin = new BlockPos(10, 60, -5);
        String plan =
                "{\"version\":1,\"operations\":[{\"from\":[0,0,0],\"to\":[2,0,0],\"block\":\"minecraft:stone\",\"hollow\":false}]}";
        assertNull(WorkshopAiEffects.previewView(owner, job));
        WorkshopAiEffects.acceptPreview(
                new WorkshopAiPreviewS2CPacket(owner, job, origin, plan),
                Level.OVERWORLD,
                origin,
                1);
        var view = WorkshopAiEffects.previewView(owner, job);
        assertNotNull(view);
        assertEquals(origin, view.origin());
        assertEquals(3, view.blueprint().ghosts().size());
        assertNull(WorkshopAiEffects.previewView(owner, UUID.randomUUID()));
    }
}
