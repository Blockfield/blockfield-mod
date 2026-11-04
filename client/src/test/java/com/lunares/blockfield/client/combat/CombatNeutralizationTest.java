package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.mixin.CombatBoatInterpolationMixin;
import com.lunares.blockfield.client.mixin.CombatLivingInterpolationMixin;
import com.lunares.blockfield.client.mixin.CombatMinecartInterpolationMixin;
import com.lunares.blockfield.client.mixin.CombatRemoteInterpolationMixin;
import com.lunares.blockfield.client.mixin.CombatSbwMotionMixin;
import com.lunares.blockfield.client.mixin.CombatSnapshotPacketsMixin;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CombatNeutralizationTest {
    static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        var f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    static Object get(Object object, Class<?> owner, String name) throws Exception {
        var f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    @Test
    void eachConcreteAdapterClearsItsOwnPendingState() throws Exception {
        var boat = mock(CombatBoatInterpolationMixin.class, CALLS_REAL_METHODS);
        set(boat, CombatBoatInterpolationMixin.class, "lerpSteps", 10);
        boat.blockfield$clearInterpolation();
        assertEquals(0, get(boat, CombatBoatInterpolationMixin.class, "lerpSteps"));
        var cart = mock(CombatMinecartInterpolationMixin.class, CALLS_REAL_METHODS);
        set(cart, CombatMinecartInterpolationMixin.class, "lerpSteps", 3);
        set(cart, CombatMinecartInterpolationMixin.class, "targetDeltaMovement", new Vec3(2, 3, 4));
        cart.blockfield$clearInterpolation();
        assertEquals(0, get(cart, CombatMinecartInterpolationMixin.class, "lerpSteps"));
        assertEquals(
                Vec3.ZERO,
                get(cart, CombatMinecartInterpolationMixin.class, "targetDeltaMovement"));
        var living = mock(CombatLivingInterpolationMixin.class, CALLS_REAL_METHODS);
        for (String name : List.of("lerpSteps", "lerpHeadSteps"))
            set(living, CombatLivingInterpolationMixin.class, name, 3);
        living.blockfield$clearInterpolation();
        for (String name : List.of("lerpSteps", "lerpHeadSteps"))
            assertEquals(0, get(living, CombatLivingInterpolationMixin.class, name));
        var remote = mock(CombatRemoteInterpolationMixin.class, CALLS_REAL_METHODS);
        set(remote, CombatRemoteInterpolationMixin.class, "lerpDeltaMovementSteps", 3);
        remote.blockfield$clearMotionInterpolation();
        assertEquals(
                0, get(remote, CombatRemoteInterpolationMixin.class, "lerpDeltaMovementSteps"));
    }

    @Test
    void vanillaAndSbwImpulsesBothPassUnmodifiedWhenUndrivenAndStopWhenDriven() throws Exception {
        var entity = mock(Entity.class);
        var applied = new AtomicInteger();
        Operation<Void> original =
                args -> {
                    assertSame(entity, args[0]);
                    assertEquals(2.0, args[1]);
                    assertEquals(3.0, args[2]);
                    assertEquals(4.0, args[3]);
                    applied.incrementAndGet();
                    return null;
                };
        try (var scene = mockStatic(ClientCombatScene.class)) {
            for (Class<?> owner :
                    List.of(CombatSnapshotPacketsMixin.class, CombatSbwMotionMixin.class)) {
                String name =
                        owner == CombatSbwMotionMixin.class
                                ? "blockfield$remoteMotion"
                                : "blockfield$motionOnly";
                var mixin = mock(owner, CALLS_REAL_METHODS);
                var method =
                        owner.getDeclaredMethod(
                                name,
                                Entity.class,
                                double.class,
                                double.class,
                                double.class,
                                Operation.class);
                method.setAccessible(true);
                scene.when(() -> ClientCombatScene.driven(entity)).thenReturn(true);
                method.invoke(mixin, entity, 2.0, 3.0, 4.0, original);
                assertEquals(0, applied.get());
                scene.when(() -> ClientCombatScene.driven(entity)).thenReturn(false);
                method.invoke(mixin, entity, 2.0, 3.0, 4.0, original);
                assertEquals(1, applied.get());
                applied.set(0);
            }
        }
    }
}
