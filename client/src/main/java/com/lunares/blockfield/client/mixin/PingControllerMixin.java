package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.DroneCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.core.PingController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Направление пинга берётся с дрона, а не с игрока (см. DroneCamera). */
@Mixin(PingController.class)
public class PingControllerMixin {

    @Redirect(
            method = "performPingAction",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/client/Minecraft;cameraEntity:Lnet/minecraft/world/entity/Entity;"))
    private static Entity blockfield$pingFromDrone(Minecraft mc) {
        return DroneCamera.pingSource(mc);
    }
}
