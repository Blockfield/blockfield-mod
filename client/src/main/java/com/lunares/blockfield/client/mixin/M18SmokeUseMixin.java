package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.grenade.M18SmokeAnimations;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class M18SmokeUseMixin {
    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void blockfield$nativeM18Release(CallbackInfo ci) {
        M18SmokeAnimations.onNativeRelease((LivingEntity) (Object) this, false);
    }

    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void blockfield$nativeM18CookingComplete(CallbackInfo ci) {
        M18SmokeAnimations.onNativeRelease((LivingEntity) (Object) this, true);
    }
}
