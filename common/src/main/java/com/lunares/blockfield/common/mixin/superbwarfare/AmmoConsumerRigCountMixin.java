package com.lunares.blockfield.common.mixin.superbwarfare;

import com.lunares.blockfield.common.compat.SuperbWarfareRigAmmo;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SBW backup ammo (RPG rockets) includes the chest rig on both sides: the client HUD and reload
 * check count on their own. The server drains the rig in
 * server.mixin.superbwarfare.AmmoConsumerRigMixin.
 */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.data.gun.AmmoConsumer", remap = false)
public abstract class AmmoConsumerRigCountMixin {
    @Inject(
            method =
                    "count(Lcom/atsuishio/superbwarfare/data/gun/GunData;Lnet/minecraft/class_1297;)I",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private void blockfield$countRig(
            @Coerce Object data, Entity entity, CallbackInfoReturnable<Integer> cir) {
        int extra = SuperbWarfareRigAmmo.countInEquippedRig(entity, this);
        if (extra > 0) cir.setReturnValue(cir.getReturnValue() + extra);
    }
}
