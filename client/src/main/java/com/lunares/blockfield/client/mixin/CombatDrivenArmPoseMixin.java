package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.lunares.blockfield.common.combat.AnimationTypes;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Equipment-resolved selection outcomes of a driven remote (ADR4 §4.2, U8b): its arm poses are the
 * checkpoint's per-hand {@code mainArmPose/offArmPose} through the native {@code
 * setModelProperties} assignment rule (two-handed main pose: off hand EMPTY or ITEM), whatever the
 * live held stacks, Create's or other {@code getArmPose} hooks computed.
 */
@Mixin(PlayerRenderer.class)
public abstract class CombatDrivenArmPoseMixin {
    @Inject(method = "setModelProperties", at = @At("TAIL"), require = 1)
    private void blockfield$drivenArmPoses(AbstractClientPlayer player, CallbackInfo ci) {
        var driven = DrivenPresentation.inputs(player);
        if (driven == null || player.isSpectator()) return;
        var model = ((LivingEntityRenderer<?, ?>) (Object) this).getModel();
        if (!(model instanceof PlayerModel<?> players)) return;
        var main = pose(driven.itemUse().mainArmPose());
        var off = pose(driven.itemUse().offArmPose());
        // Native: an empty off hand is EMPTY from getArmPose, so the checkpoint's off pose says it.
        if (main.isTwoHanded())
            off =
                    driven.itemUse().offArmPose() == AnimationTypes.ArmPose.EMPTY
                            ? HumanoidModel.ArmPose.EMPTY
                            : HumanoidModel.ArmPose.ITEM;
        boolean right = driven.mainArm() == AnimationTypes.Arm.RIGHT;
        players.rightArmPose = right ? main : off;
        players.leftArmPose = right ? off : main;
    }

    private static HumanoidModel.ArmPose pose(AnimationTypes.ArmPose value) {
        return HumanoidModel.ArmPose.valueOf(value.name());
    }

    /** The authoritative charge duration, never recalculated from the live use stack. */
    @Mixin(AnimationUtils.class)
    public abstract static class CrossbowCharge {
        @WrapOperation(
                method = "animateCrossbowCharge",
                at =
                        @At(
                                value = "INVOKE",
                                target =
                                        "Lnet/minecraft/world/item/CrossbowItem;getChargeDuration(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)I"),
                require = 1)
        private static int blockfield$drivenChargeDuration(
                ItemStack stack, LivingEntity entity, Operation<Integer> original) {
            var driven = DrivenPresentation.inputs(entity);
            return driven != null
                    ? driven.itemUse().crossbowChargeDuration()
                    : original.call(stack, entity);
        }
    }

    /** The Dinnerbone/Grumm + cape branch is the checkpoint's {@code upsideDown}. */
    @Mixin(LivingEntityRenderer.class)
    public abstract static class UpsideDown {
        @Inject(method = "isEntityUpsideDown", at = @At("HEAD"), cancellable = true, require = 1)
        private static void blockfield$drivenUpsideDown(
                LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
            var driven = DrivenPresentation.inputs(entity);
            if (driven != null) cir.setReturnValue(driven.flags().upsideDown());
        }
    }
}
