package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.PreMatchCombat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.item.gun.GunItem", remap = false)
public abstract class PreMatchSbwShootMixin {
    @Inject(
            method =
                    "canShoot(Lcom/atsuishio/superbwarfare/data/gun/GunData;Lnet/minecraft/class_1297;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void blockfield$noPredictedShotBeforeRound(
            @Coerce Object data, Entity shooter, CallbackInfoReturnable<Boolean> cir) {
        if (shooter instanceof LocalPlayer && PreMatchCombat.noAttacks()) cir.setReturnValue(false);
    }
}
