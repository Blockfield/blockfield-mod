package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.render.HudRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SBW predicts vehicle pushes locally, independently of the server's consent gate. */
@Pseudo
@Mixin(
        targets = "com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils",
        remap = false)
public abstract class VehicleImpactMixin {
    private static boolean blockfield$safeArea(Entity entity) {
        if (!(entity instanceof LocalPlayer)) return false;
        var dimension = entity.level().dimension();
        return dimension.location().toString().equals("blockfield_shared:firing_range")
                || dimension == Level.OVERWORLD && !HudRenderer.isDeployed();
    }

    @WrapOperation(
            method = "crushEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1297;method_5762(DDD)V"))
    private static void blockfield$crushPush(
            Entity entity, double x, double y, double z, Operation<Void> original) {
        if (!blockfield$safeArea(entity)) original.call(entity, x, y, z);
    }

    @Inject(method = "handleEntityObbCollision", at = @At("HEAD"), cancellable = true)
    private void blockfield$collision(@Coerce Object vehicle, Entity entity, CallbackInfo ci) {
        if (blockfield$safeArea(entity)) ci.cancel();
    }
}
