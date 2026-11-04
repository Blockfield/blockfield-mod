package com.lunares.blockfield.client.mixin;

import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.common.attachment.client.Animation;
import com.lunares.blockfield.client.combat.AnimationPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces every registered ParCool animator on while connected (ADR4 §6): this is the only {@code
 * canAnimate} consumer, so bypassing both gates here is exactly the forced-true policy. The stored
 * per-animator values are never rewritten.
 */
@Mixin(value = Animation.class, remap = false)
public abstract class AnimationPolicyAnimatorMixin {
    @Shadow private Animator animator;

    @Inject(
            method = "setAnimator(Lcom/alrex/parcool/client/animation/Animator;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void blockfield$forceAnimator(Animator animator, CallbackInfo ci) {
        if (animator != null && AnimationPolicy.forcing()) {
            this.animator = animator;
            ci.cancel();
        }
    }
}
