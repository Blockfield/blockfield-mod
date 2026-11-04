package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.lunares.blockfield.client.combat.CombatVehicleScope;
import com.lunares.blockfield.oracle.OracleReflection;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * U6 boat/minecart transformed pins (ADR4 §§4.5/5.3): the real vanilla formulas run WITH production
 * mixins through the client oracle. The boat attachment reads the boat yaw (driven R override in
 * {@code CombatBoatInterpolationMixin}); the minecart attachment is yaw-free (no override, proven
 * here by varying the yaw under a fixed expected value).
 */
class VehicleOracleFixture {
    @Test
    void nativeBoatAttachmentUsesBoatYaw() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "boat-attachment-yaw",
                () -> {
                    var boat = mock(Boat.class, org.mockito.Mockito.CALLS_REAL_METHODS);
                    var passenger = mock(Entity.class);
                    var dimensions = EntityDimensions.scalable(1.2f, 1.8f);
                    // getSinglePassengerXOffset is a native constant 0; only the mock subclass
                    // cannot reach the protected stub, so the real constant runs.
                    org.mockito.Mockito.doReturn(List.of(passenger)).when(boat).getPassengers();
                    org.mockito.Mockito.doReturn(Boat.Type.OAK).when(boat).getVariant();
                    setYaw(boat, 90.0f);
                    Vec3 attached = callAttachment(boat, Boat.class, passenger, dimensions, 0.5f);
                    // Native formula over the live yaw: independent transcription, not the scope
                    // helper's own path.
                    float angle = -90.0f * net.minecraft.util.Mth.DEG_TO_RAD;
                    Vec3 expected = new Vec3(0, 1.8f / 3.0f, 0.0).yRot(angle);
                    assertEquals(expected.x, attached.x, 1e-6);
                    assertEquals(expected.y, attached.y, 1e-6);
                    assertEquals(expected.z, attached.z, 1e-6);
                    // The driven scope helper agrees with the native formula at the same yaw.
                    Vec3 scoped = CombatVehicleScope.boatAttachment(0.0, 1.8f / 3.0f, 90.0f);
                    assertEquals(attached.x, scoped.x, 1e-6);
                    assertEquals(attached.y, scoped.y, 1e-6);
                    assertEquals(attached.z, scoped.z, 1e-6);
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("attached", attached.toString());
                    return result;
                });
    }

    @Test
    void minecartAttachmentIgnoresYaw() {
        AnimSyncOracleRunner.run(
                "vanilla",
                "minecart-attachment-yaw-free",
                () -> {
                    var cart =
                            mock(
                                    net.minecraft.world.entity.vehicle.Minecart.class,
                                    org.mockito.Mockito.CALLS_REAL_METHODS);
                    var passenger = mock(Entity.class);
                    var dimensions = EntityDimensions.scalable(0.6f, 1.8f);
                    org.mockito.Mockito.doReturn(List.of(passenger)).when(cart).getPassengers();
                    setYaw(cart, 0.0f);
                    Vec3 straight =
                            callAttachment(
                                    cart,
                                    net.minecraft.world.entity.vehicle.AbstractMinecart.class,
                                    passenger,
                                    dimensions,
                                    0.5f);
                    setYaw(cart, 127.0f);
                    Vec3 turned =
                            callAttachment(
                                    cart,
                                    net.minecraft.world.entity.vehicle.AbstractMinecart.class,
                                    passenger,
                                    dimensions,
                                    0.5f);
                    // Yaw-free by construction: identical results under different yaws, so no
                    // driven R-yaw override exists or is needed for minecarts.
                    assertEquals(straight.x, turned.x, 0);
                    assertEquals(straight.y, turned.y, 0);
                    assertEquals(straight.z, turned.z, 0);
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("attached", straight.toString());
                    return result;
                });
    }

    static void setYaw(Entity entity, float yaw) throws Exception {
        var field = OracleReflection.field(Entity.class, "yRot", "F");
        field.setAccessible(true);
        field.setFloat(entity, yaw);
    }

    /** Protected vanilla getter by signature scan; direct calls cannot compile across packages. */
    static Vec3 callAttachment(
            Object vehicle,
            Class<?> target,
            Entity passenger,
            EntityDimensions dimensions,
            float partial)
            throws Exception {
        for (var method : target.getDeclaredMethods()) {
            if (!method.getReturnType().equals(Vec3.class)) continue;
            var params = method.getParameterTypes();
            if (params.length == 3
                    && params[0].equals(Entity.class)
                    && params[1].equals(EntityDimensions.class)
                    && params[2].equals(float.class)) {
                method.setAccessible(true);
                return (Vec3) method.invoke(vehicle, passenger, dimensions, partial);
            }
        }
        throw new NoSuchMethodException(target.getName() + " attachment getter");
    }
}
