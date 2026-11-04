package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.common.combat.AnimationTypes;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inside a driven remote's render pass these living-entity getters return its checkpoint values
 * (ADR4 §§4.2/5.3, U8b) instead of the live synced state, for every reader in the pass (vanilla
 * renderer and model, TaCZ, LR, ParCool, SBW mixins). Outside the scope, and for undriven entities
 * and the local player, the native body runs unchanged.
 */
@Mixin(LivingEntity.class)
public abstract class CombatDrivenLivingInputsMixin {
    @Inject(method = "isUsingItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$usingItem(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.itemUse().usingItem());
    }

    @Inject(method = "getUsedItemHand", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$usedItemHand(CallbackInfoReturnable<InteractionHand> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null)
            cir.setReturnValue(
                    driven.itemUse().usedItemHand() == AnimationTypes.Hand.MAIN_HAND
                            ? InteractionHand.MAIN_HAND
                            : InteractionHand.OFF_HAND);
    }

    @Inject(method = "getUseItemRemainingTicks", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$useItemRemaining(CallbackInfoReturnable<Integer> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.itemUse().useItemRemaining());
    }

    @Inject(method = "getTicksUsingItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$ticksUsingItem(CallbackInfoReturnable<Integer> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.itemUse().ticksUsingItem());
    }

    @Inject(method = "isFallFlying", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$fallFlying(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().fallFlying());
    }

    @Inject(method = "isAutoSpinAttack", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$autoSpinAttack(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().autoSpinAttack());
    }

    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$climbing(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().climbing());
    }

    @Inject(method = "getBedOrientation", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$bedOrientation(CallbackInfoReturnable<Direction> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null)
            cir.setReturnValue(
                    driven.bedOrientation() == null
                            ? null
                            : Direction.valueOf(driven.bedOrientation().name()));
    }

    // U10 mixed-renderer gate: LivingEntityRenderer.render reads these live otherwise (root scale,
    // walk gate, baby model/walk), while the server pose reads the checkpoint.
    @Inject(method = "getScale", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$scale(CallbackInfoReturnable<Float> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.size().scale());
    }

    @Inject(method = "isAlive", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$alive(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().alive());
    }

    @Inject(method = "isBaby", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$baby(CallbackInfoReturnable<Boolean> cir) {
        var driven = DrivenPresentation.inputs(this);
        if (driven != null) cir.setReturnValue(driven.flags().baby());
    }

    /** SBW's prone model tail: the retained gun bit (FX-POSE, client review P0-3). */
    @Inject(method = "getMainHandItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$sbwTailMainHand(CallbackInfoReturnable<ItemStack> cir) {
        var held = DrivenPresentation.sbwTailMainHand(this);
        if (held != null) cir.setReturnValue(held);
    }

    /** bf24's custom arm hooks read getItemInHand(MAIN_HAND), rather than getMainHandItem. */
    @Inject(method = "getItemInHand", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockfield$sbwArmMainHand(
            InteractionHand hand, CallbackInfoReturnable<ItemStack> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        var held = DrivenPresentation.sbwTailMainHand(this);
        if (held != null) cir.setReturnValue(held);
    }
}
