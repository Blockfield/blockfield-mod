package com.lunares.blockfield.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Blockfield killfeed (KillfeedHud) owns entries, weapon silhouettes and the shared style.
 * SBW's overlay would draw every death twice; its client config is preserved per player, so it is
 * switched off here rather than there — same as plates and stamina in SbwBottomOverlayMixin.
 */
@Pseudo
@Mixin(
        targets = {"com.atsuishio.superbwarfare.client.overlay.KillMessageOverlay"},
        remap = false)
public abstract class SbwKillOverlayMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void blockfield$replacedByKillfeedHud(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
