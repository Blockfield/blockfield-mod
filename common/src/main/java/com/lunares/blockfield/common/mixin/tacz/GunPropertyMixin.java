package com.lunares.blockfield.common.mixin.tacz;

import com.lunares.blockfield.common.balance.GunBalanceData;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.AttachmentPropertyEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.event.ChangeGunPropertyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChangeGunPropertyEvent.class, remap = false)
public abstract class GunPropertyMixin {
    @Inject(method = "internalOnAttachmentPropertyEvent", at = @At("HEAD"))
    private static void blockfield$normalizeSelectedMode(
            AttachmentPropertyEvent event, CallbackInfo ci) {
        var stack = event.getGunItem();
        var gun = IGun.getIGunOrNull(stack);
        if (gun != null)
            TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                    .ifPresent(index -> GunBalanceData.normalizeMode(stack, index.getGunData()));
    }
}
