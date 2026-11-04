package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CombatEntityDriverTest {
    private static final long CONNECTION = 3, STREAM = 17, SCENE = 1, SUBSCRIPTION = 5;
    private static final int FULL_RESET =
            AnimationRecipe.SnapshotRecord.FLAG_FULL | AnimationRecipe.SnapshotRecord.FLAG_RESET;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** One exact record at {@code tick} with an explicit previous/current origin pair. */
    private static AnimationRecipe.SnapshotRecord rec(
            int entity,
            int kind,
            long tick,
            double prevX,
            double prevZ,
            double x,
            double z,
            AnimationState.Angles angles) {
        var identity =
                new AnimationState.Identity(
                        CONNECTION,
                        STREAM,
                        SCENE,
                        entity,
                        kind,
                        SUBSCRIPTION,
                        7,
                        0,
                        0,
                        0,
                        0,
                        AnimationSchema.layoutDigest(),
                        ExactFixtures.ZERO);
        var base = ExactFixtures.checkpoint(identity, tick, x);
        var placement =
                new AnimationState.Placement(
                        new AnimationState.Vec3(prevX, 64, prevZ),
                        new AnimationState.Vec3(x, 64, z),
                        angles,
                        base.placement().walk(),
                        base.placement().deltaMovementOnPreviousTick(),
                        base.placement().deltaMovement(),
                        base.placement().vehicleAngles(),
                        List.of());
        var checkpoint =
                new AnimationState(
                        identity,
                        tick,
                        0,
                        tick * 256,
                        placement,
                        base.vanilla(),
                        base.stance(),
                        base.equipment(),
                        base.tacz(),
                        base.lr(),
                        base.graph(),
                        base.parcool(),
                        base.create(),
                        base.sbw(),
                        base.decisions());
        return new AnimationRecipe.SnapshotRecord(
                identity, FULL_RESET, checkpoint.placement(), checkpoint);
    }

    private static PlayerSnapshotS2CPacket packet(AnimationRecipe.SnapshotRecord... records) {
        return PlayerSnapshotS2CPacket.animation(
                STREAM,
                0,
                100,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(
                        CONNECTION, SCENE, List.of(records), List.of(), List.of()));
    }

    @Test
    void actualSetPosAndOldStateMakeAllPartialsAndEyePositionEqualSample() {
        var level = mock(Level.class);
        var boat = new Boat(EntityType.BOAT, level);
        var b = new ClientSnapshotBuffer();
        b.add(1, 1);
        assertTrue(
                b.apply(
                        packet(
                                rec(
                                        1,
                                        1,
                                        100,
                                        1,
                                        0,
                                        1,
                                        0,
                                        new AnimationState.Angles(10, 20, -5, 5, 30, 40, 31, 41))),
                        id -> false));
        var s = SnapshotResolver.exact(b.frames(1), Time.whole(100));
        ClientCombatScene.write(boat, s);
        for (float partial : new float[] {0, .5f, 1}) {
            assertEquals(new Vec3(1, 64, 0), boat.getPosition(partial));
            assertEquals(1, boat.getEyePosition(partial).x);
            assertEquals(64 + boat.getEyeHeight(), boat.getEyePosition(partial).y, 1e-6);
        }
        assertEquals(1, boat.xo);
        assertEquals(1, boat.xOld);
        assertEquals(64, boat.yo);
        assertEquals(64, boat.yOld);
        assertEquals(1, boat.getBoundingBox().getCenter().x, 1e-12);
    }

    @Test
    void passengerOwnEndpointsOverrideNonlinearNativeSeatMidpoint() {
        // r8 §2.5(3); bf24 VehicleEntity.kt:3576-3602: rotate a 2m offset then add origin.
        // At yaw 0 and 90 degrees endpoints are (2,0), (0,2). Native yaw=45 gives
        // (sqrt(2),sqrt(2)), while the accepted committed-feet chord is (1,1).
        double nativeX = 2 * Math.cos(Math.PI / 4);
        double nativeZ = 2 * Math.sin(Math.PI / 4);
        assertEquals(Math.sqrt(2), nativeX, 1e-12);
        assertEquals(Math.sqrt(2), nativeZ, 1e-12);
        assertTrue(Math.hypot(nativeX - 1, nativeZ - 1) > .58);
        var frames = new java.util.TreeMap<Long, SnapshotTransform>();
        var a = rec(1, 0, 100, 2, 0, 2, 0, new AnimationState.Angles(0, 0, 0, 0, 0, 0, 0, 0));
        var z = rec(1, 0, 101, 2, 0, 0, 2, new AnimationState.Angles(0, 90, 0, 0, 0, 0, 0, 0));
        frames.put(
                100L,
                SnapshotTransform.exact(
                        100, a.identity(), a.flags(), a.placement(), a.checkpoint()));
        frames.put(
                101L,
                SnapshotTransform.exact(
                        101, z.identity(), z.flags(), z.placement(), z.checkpoint()));
        var s = SnapshotResolver.exact(frames, new Time(100, 0x8000));
        var boat = new Boat(EntityType.BOAT, mock(Level.class));
        boat.setPos(nativeX, 64, nativeZ);
        ClientCombatScene.write(boat, s);
        assertEquals(new Vec3(1, 64, 1), boat.getPosition(.5f));
        assertEquals(45, boat.getYRot(), 1e-6);
        for (double time : new double[] {100, 101}) {
            ClientCombatScene.write(boat, SnapshotResolver.exact(frames, Time.whole((long) time)));
            Vec3 endpoint = time == 100 ? new Vec3(2, 64, 0) : new Vec3(0, 64, 2);
            assertEquals(endpoint, boat.getPosition(.5f));
        }
    }

    @Test
    void nativeBoatAndCartTargetsAndMotionRemainAvailableForFallback() {
        // Pinned 1.21.1 Boat.lerpTo steps=10; AbstractMinecart.lerpMotion stores target + motion.
        var level = mock(Level.class);
        var boat = new Boat(EntityType.BOAT, level);
        boat.lerpTo(10, 64, 2, 90, 20, 3);
        assertEquals(10, boat.lerpTargetX());
        assertEquals(90, boat.lerpTargetYRot());
        var cart = new Minecart(EntityType.MINECART, level);
        cart.lerpMotion(2, 3, 4);
        assertEquals(new Vec3(2, 3, 4), cart.getDeltaMovement());
        cart.lerpTo(10, 64, 2, 90, 20, 3);
        assertEquals(10, cart.lerpTargetX());
        var b = new ClientSnapshotBuffer();
        b.add(1, 1);
        assertTrue(
                b.apply(
                        packet(
                                rec(
                                        1,
                                        1,
                                        100,
                                        4,
                                        0,
                                        4,
                                        0,
                                        new AnimationState.Angles(10, 20, -5, 5, 30, 40, 31, 41))),
                        id -> false));
        var s = SnapshotResolver.exact(b.frames(1), Time.whole(100));
        ClientCombatScene.write(boat, s);
        ClientCombatScene.write(cart, s);
        assertEquals(new Vec3(4, 64, 0), boat.getPosition(.5f));
        assertEquals(new Vec3(4, 64, 0), cart.getPosition(.5f));
        boat.lerpTo(12, 64, 2, 90, 20, 3);
        cart.lerpTo(12, 64, 2, 90, 20, 3);
        assertEquals(12, boat.lerpTargetX());
        assertEquals(12, cart.lerpTargetX());
    }

    @Test
    void remotePlayerWriteFreezesBodyAndHeadAndEveryPartialReadsTheSample() {
        var level = mock(net.minecraft.client.multiplayer.ClientLevel.class);
        when(level.getSharedSpawnPos()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        var player =
                new net.minecraft.client.player.RemotePlayer(
                        level,
                        new com.mojang.authlib.GameProfile(new java.util.UUID(0, 9), "victim"));
        var frames = new java.util.TreeMap<Long, SnapshotTransform>();
        // u16 turns: 16384 = 90 deg yaw, 63716 = -9.998 deg pitch, 8192 = 45 deg body,
        // 24576 = 135 deg head; feet move 280 mm along x between the two ticks.
        float pitch = (float) ((63716 - 65536) * 360.0 / 65536);
        var angles = new AnimationState.Angles(90, 90, pitch, pitch, 45, 45, 135, 135);
        var a = rec(9, 0, 100, 1, 2, 1, 2, angles);
        var z = rec(9, 0, 101, 1, 2, 1.28, 2, angles);
        frames.put(
                100L,
                SnapshotTransform.exact(
                        100, a.identity(), a.flags(), a.placement(), a.checkpoint()));
        frames.put(
                101L,
                SnapshotTransform.exact(
                        101, z.identity(), z.flags(), z.placement(), z.checkpoint()));
        player.setPos(9, 70, 9);
        player.setOldPosAndRot();
        ClientCombatScene.write(player, SnapshotResolver.exact(frames, new Time(100, 0x8000)));
        for (float partial : new float[] {0, .5f, 1}) {
            assertEquals(1.14, player.getPosition(partial).x, 1e-12);
            assertEquals(64, player.getPosition(partial).y);
            assertEquals(2, player.getPosition(partial).z);
            assertEquals(64 + 1.62, player.getEyePosition(partial).y, 1e-6);
            // Pinned LivingEntity.getViewYRot reads yHeadRotO/yHeadRot: the head is the view.
            assertEquals(135, player.getViewYRot(partial), 1e-4);
            assertEquals(90, player.getYRot());
            assertEquals(pitch, player.getViewXRot(partial), 1e-4);
        }
        assertEquals(45, player.yBodyRot, 1e-4);
        assertEquals(45, player.yBodyRotO, 1e-4);
        assertEquals(135, player.yHeadRot, 1e-4);
        assertEquals(135, player.yHeadRotO, 1e-4);
        assertEquals(1.14, player.getBoundingBox().getCenter().x, 1e-9);
    }
}
