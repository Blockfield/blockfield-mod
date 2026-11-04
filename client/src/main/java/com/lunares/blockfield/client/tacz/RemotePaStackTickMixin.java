package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.animation.RemotePaPresentation;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A driven player's client tick must never advance the installed render scratch. */
@Mixin(value = AnimationStack.class, remap = false)
public abstract class RemotePaStackTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void blockfield$drivenTick(CallbackInfo ci) {
        if (RemotePaPresentation.driven((AnimationStack) (Object) this)) ci.cancel();
    }
}
