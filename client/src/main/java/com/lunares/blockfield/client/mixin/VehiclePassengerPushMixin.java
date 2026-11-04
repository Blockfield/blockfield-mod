package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.HudRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A rider's vanilla collision must not reintroduce the blocked vehicle push. */
@Mixin(LivingEntity.class)
public abstract class VehiclePassengerPushMixin {
    @Inject(method = "doPush", at = @At("HEAD"), cancellable = true)
    private void blockfield$passengerPush(Entity other, CallbackInfo ci) {
        var self = (Entity) (Object) this;
        Entity player =
                other.isPassenger() && self instanceof LocalPlayer
                        ? self
                        : self.isPassenger() && other instanceof LocalPlayer ? other : null;
        if (player == null) return;
        var dimension = player.level().dimension();
        if (dimension.location().toString().equals("blockfield_shared:firing_range")
                || dimension == Level.OVERWORLD && !HudRenderer.isDeployed()) ci.cancel();
    }
}
