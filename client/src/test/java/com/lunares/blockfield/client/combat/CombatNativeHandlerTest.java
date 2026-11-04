package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.mixin.CombatSnapshotPacketsMixin;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CombatNativeHandlerTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static void field(Object object, String name, Object value) throws Exception {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(object, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void realNativeMoveAndTeleportKeepCodecBaseAndOnGroundAcrossDrivenFallback() throws Exception {
        var level = mock(ClientLevel.class);
        var mc = mock(Minecraft.class);
        when(mc.isSameThread()).thenReturn(true);
        var listener = mock(ClientPacketListener.class, CALLS_REAL_METHODS);
        field(listener, "minecraft", mc);
        field(listener, "level", level);
        var boat = spy(new Boat(EntityType.BOAT, level));
        boat.setId(7);
        when(level.getEntity(7)).thenReturn(boat);
        doReturn(false).when(boat).isControlledByLocalInstance();
        boat.syncPacketPositionCodec(1, 64, 0);
        var mixin = mock(CombatSnapshotPacketsMixin.class, CALLS_REAL_METHODS);
        var wrap =
                CombatSnapshotPacketsMixin.class.getDeclaredMethod(
                        "blockfield$transformOnly",
                        Entity.class,
                        double.class,
                        double.class,
                        double.class,
                        float.class,
                        float.class,
                        int.class,
                        Operation.class);
        wrap.setAccessible(true);
        doAnswer(
                        call -> {
                            Operation<Void> nativeLerp =
                                    args -> {
                                        try {
                                            call.callRealMethod();
                                            return null;
                                        } catch (Throwable ex) {
                                            throw new RuntimeException(ex);
                                        }
                                    };
                            return wrap.invoke(
                                    mixin,
                                    boat,
                                    call.getArgument(0),
                                    call.getArgument(1),
                                    call.getArgument(2),
                                    call.getArgument(3),
                                    call.getArgument(4),
                                    call.getArgument(5),
                                    nativeLerp);
                        })
                .when(boat)
                .lerpTo(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(), anyInt());
        try (var scene = mockStatic(ClientCombatScene.class)) {
            scene.when(() -> ClientCombatScene.driven(boat)).thenReturn(true);
            // Pinned ClientPacketListener.handleMoveEntity offsets 38..66 decode + setBase,
            // 148/207 lerpTo only; onGround at 215 must still execute.
            listener.handleMoveEntity(
                    new ClientboundMoveEntityPacket.PosRot(
                            7, (short) 4096, (short) 0, (short) 0, (byte) 64, (byte) 0, true));
            assertEquals(new Vec3(2, 64, 0), boat.getPositionCodec().getBase());
            assertTrue(boat.onGround());
            assertEquals(0, boat.getX());
            scene.when(() -> ClientCombatScene.driven(boat)).thenReturn(false);
            listener.handleMoveEntity(
                    new ClientboundMoveEntityPacket.Pos(
                            7, (short) 4096, (short) 0, (short) 0, false));
            assertEquals(new Vec3(3, 64, 0), boat.getPositionCodec().getBase());
            assertEquals(3, boat.lerpTargetX());
            assertFalse(boat.onGround());
            scene.when(() -> ClientCombatScene.driven(boat)).thenReturn(true);
            var source = new Boat(EntityType.BOAT, level);
            source.setId(7);
            source.setPos(20, 65, 3);
            source.setOnGround(true);
            // Pinned teleport offsets 49 syncPacketPositionCodec, 100 lerpTo, 108 onGround.
            listener.handleTeleportEntity(new ClientboundTeleportEntityPacket(source));
            assertEquals(new Vec3(20, 65, 3), boat.getPositionCodec().getBase());
            assertTrue(boat.onGround());
            assertEquals(3, boat.lerpTargetX());
            scene.when(() -> ClientCombatScene.driven(boat)).thenReturn(false);
            listener.handleMoveEntity(
                    new ClientboundMoveEntityPacket.Pos(
                            7, (short) 4096, (short) 0, (short) 0, false));
            assertEquals(new Vec3(21, 65, 3), boat.getPositionCodec().getBase());
            assertEquals(21, boat.lerpTargetX());
        }
    }
}
