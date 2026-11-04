package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.animation.RemotePaPresentation;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A driven remote's PA stack samples at frac(A), never the ambient render partial. */
@Mixin(value = AnimationProcessor.class, remap = false)
public abstract class RemotePaTickDeltaMixin {
    @ModifyVariable(method = "setTickDelta", at = @At("HEAD"), argsOnly = true)
    private float blockfield$drivenFraction(float tickDelta) {
        Float fraction = RemotePaPresentation.fraction(this);
        return fraction == null ? tickDelta : fraction;
    }
}
