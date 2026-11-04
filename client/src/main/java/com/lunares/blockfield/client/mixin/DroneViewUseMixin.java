package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.DroneLinkTransition;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds back the linked monitor's use until it is up at the eyes (DroneLinkTransition). */
@Mixin(Minecraft.class)
public abstract class DroneViewUseMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void blockfield$raiseMonitorFirst(CallbackInfo ci) {
        if (DroneLinkTransition.interceptUse((Minecraft) (Object) this)) ci.cancel();
    }
}
