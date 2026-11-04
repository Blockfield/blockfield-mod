package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.gui.tablet.Tablet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** С открытым планшетом ПКМ жмёт кнопки, а не целится: заявку на ADS отбрасываем. */
@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerAim", remap = false)
public abstract class TabletAimBlockMixin {

    @Inject(method = "aim", at = @At("HEAD"), cancellable = true, remap = false)
    private void blockfield$tabletBlocksAim(boolean isAim, CallbackInfo ci) {
        if (isAim && Tablet.isOpen()) ci.cancel();
    }
}
