package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.WeaponHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.client.overlay.AmmoBarOverlay", remap = false)
public abstract class SbwWeaponHudMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void blockfield$ourRpgPanel(CallbackInfoReturnable<Boolean> cir) {
        if (WeaponHud.replacesSbwAmmoHud()) cir.setReturnValue(false);
    }
}
