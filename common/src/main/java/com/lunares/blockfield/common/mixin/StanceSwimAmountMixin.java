package com.lunares.blockfield.common.mixin;

import com.lunares.blockfield.common.StanceTransition;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The drawn body lies down and gets up over {@link StanceTransition#TICKS} like the hit body, but
 * interpolated per rendered frame: the tick advances one flat segment while the render partial
 * walks it (HOTFIX-2751 D5), so the transition stays smooth at any frame rate. Vanilla takes 11
 * ticks, so shots at the still-upright model would pass over the already low box.
 */
@Mixin(LivingEntity.class)
public abstract class StanceSwimAmountMixin {
    @Shadow private float swimAmount;
    @Shadow private float swimAmountO;

    @Inject(method = "tickHeadTurn", at = @At("HEAD"), cancellable = true)
    private void blockfield$crawlFacing(
            float movementYaw, float distance, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof Player player)) return;
        var stance = ((StanceTransition.Access) player).blockfield$stance();
        if (stance != null && stance.alignProneFacing(player)) cir.setReturnValue(distance);
    }

    @Inject(method = "updateSwimAmount", at = @At("HEAD"), cancellable = true)
    private void blockfield$crawlSwimStep(CallbackInfo ci) {
        if (!((Object) this instanceof Player player) || player.isInWater()) return;
        StanceTransition stance = ((StanceTransition.Access) player).blockfield$stance();
        if (stance == null || !stance.eases()) return;
        // Per-frame interpolation (HOTFIX-2751 D5): the tick carries the flat segment while
        // the render partial walks it, instead of stepping once per tick.
        swimAmountO = stance.flatness();
        swimAmount = stance.flatSegmentEnd();
        ci.cancel();
    }
}
