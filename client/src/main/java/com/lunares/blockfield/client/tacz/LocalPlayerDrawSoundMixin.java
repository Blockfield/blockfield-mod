package com.lunares.blockfield.client.tacz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TACZ Refabricated 0.7.0 synced upstream commit 47c078f0 only halfway: draw/put-away sounds moved
 * into {@code AnimateGeoItemRenderer.triggerDraw/triggerPutAway}, but {@code LocalPlayerDraw.draw}
 * still calls {@code doPutAway} (put-away sound + exit) and {@code doDraw} (draw sound on a timer),
 * which Forge 1.1.8 comments out. Every weapon switch played both sounds twice. Forge's remaining
 * {@code doDraw} body only cancels that timer, so both methods are skipped whole.
 */
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerDraw", remap = false)
public abstract class LocalPlayerDrawSoundMixin {

    @Inject(
            method = {"doDraw", "doPutAway"},
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void blockfield$soundsComeFromRenderer(CallbackInfo ci) {
        ci.cancel();
    }
}
