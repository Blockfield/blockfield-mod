package com.lunares.blockfield.client.tacz;

import com.lunares.blockfield.client.render.EquipmentAnimations;
import com.lunares.blockfield.client.render.EquipmentInspect;
import com.lunares.blockfield.client.render.TabletAnimations;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.tacz.guns.client.gameplay.LocalPlayerInspect", remap = false)
public abstract class LocalPlayerEquipmentInspectMixin {
    @Shadow @Final private LocalPlayer player;

    @Inject(method = "inspect", at = @At("HEAD"), cancellable = true)
    private void blockfield$inspectEquipment(CallbackInfo ci) {
        if (EquipmentAnimations.inspect(player)
                || TabletAnimations.inspect(player)
                || EquipmentInspect.start(player)) ci.cancel();
    }
}
