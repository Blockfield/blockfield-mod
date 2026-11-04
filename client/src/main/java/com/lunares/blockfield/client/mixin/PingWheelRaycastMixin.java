package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.DroneCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.math.Raycast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Начало луча пинга — глаза дрона. Заодно дрон становится исключением трассировки, иначе луч из
 * камеры сразу упирается в его же хитбокс.
 */
@Mixin(Raycast.class)
public class PingWheelRaycastMixin {

    @Redirect(
            method = "traceDirectional",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/client/Minecraft;cameraEntity:Lnet/minecraft/world/entity/Entity;"))
    private static Entity blockfield$traceFromDrone(Minecraft mc) {
        return DroneCamera.pingSource(mc);
    }

    @Redirect(
            method = "traceDistantAsync",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/client/Minecraft;cameraEntity:Lnet/minecraft/world/entity/Entity;"))
    private static Entity blockfield$traceDistantFromDrone(Minecraft mc) {
        return DroneCamera.pingSource(mc);
    }
}
