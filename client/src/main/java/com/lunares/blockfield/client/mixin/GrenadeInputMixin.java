package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.grenade.GrenadeThrowInput;
import com.lunares.blockfield.common.grenade.GrenadeThrowMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * С поддерживаемой гранатой в руке ЛКМ и ПКМ запускают нативный бросок мода (дальний/короткий), не
 * бьют и не ломают блок. Use идёт мимо useItemOn: иначе при прицеле в блок граната не бросалась бы.
 */
@Mixin(Minecraft.class)
public abstract class GrenadeInputMixin {
    @Shadow private int rightClickDelay;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void blockfield$grenadeAttack(CallbackInfoReturnable<Boolean> cir) {
        if (GrenadeThrowInput.start((Minecraft) (Object) this, GrenadeThrowMode.OVERHAND))
            cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void blockfield$grenadeContinueAttack(boolean leftClick, CallbackInfo ci) {
        if (GrenadeThrowInput.holdingSupported((Minecraft) (Object) this)) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void blockfield$grenadeUse(CallbackInfo ci) {
        if (GrenadeThrowInput.start((Minecraft) (Object) this, GrenadeThrowMode.UNDERHAND)) {
            this.rightClickDelay = 4;
            ci.cancel();
        }
    }
}
