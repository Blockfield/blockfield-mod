package com.lunares.blockfield.client.mixin;

import com.alrex.parcool.config.ParCoolConfig;
import com.lunares.blockfield.client.combat.AnimationPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces the three geometry-sensitive ParCool toggles on while connected (ADR4 §6). The stored user
 * value is never rewritten, so disconnect restores it with no extra write.
 */
@Mixin(value = ParCoolConfig.Client.Booleans.class, remap = false)
public abstract class AnimationPolicyParCoolMixin {
    @Inject(
            method = "get()Ljava/lang/Boolean;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private void blockfield$forcePolicy(CallbackInfoReturnable<Boolean> cir) {
        if (AnimationPolicy.lockedParCool(this.toString())) cir.setReturnValue(Boolean.TRUE);
    }
}
