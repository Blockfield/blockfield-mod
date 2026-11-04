package com.lunares.blockfield.client.mixin.tweaks;

import com.lunares.blockfield.client.combat.AnimationPolicy;
import me.muksc.tacztweaks.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces TaCZ Tweaks {@code smootherCrawlAnimation} on while connected (ADR4 §§5.3/6). The stored
 * user value is never rewritten, so disconnect restores it with no extra write. Gated behind the
 * tweaks plugin: without the mod there is no getter to force and the U9b manifest gate refuses the
 * exact cohort instead.
 */
@Mixin(value = Config.AudioAndVisuals.Visuals.class, remap = false)
public abstract class AnimationPolicyTweaksMixin {
    @Inject(
            method = "smootherCrawlAnimation()Z",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private static void blockfield$forceSmoother(CallbackInfoReturnable<Boolean> cir) {
        if (AnimationPolicy.forcing()) cir.setReturnValue(true);
    }
}
