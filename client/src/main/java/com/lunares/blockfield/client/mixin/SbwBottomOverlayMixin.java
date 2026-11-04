package com.lunares.blockfield.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WeaponHud shows plates and stamina itself. SBW's plate bar (bottom left) and its gun-sprint line
 * over the hotbar would sit on top of it; their client config is preserved per player, so they are
 * switched off here rather than there.
 */
@Pseudo
@Mixin(
        targets = {
            "com.atsuishio.superbwarfare.client.overlay.ArmorPlateOverlay",
            "com.atsuishio.superbwarfare.client.overlay.StaminaOverlay"
        },
        remap = false)
public abstract class SbwBottomOverlayMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void blockfield$replacedByWeaponHud(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
