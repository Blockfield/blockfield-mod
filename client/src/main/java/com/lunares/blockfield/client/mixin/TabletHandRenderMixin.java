package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.render.HeldEquipmentMovement;
import com.lunares.blockfield.client.render.MedkitAnimations;
import com.lunares.blockfield.client.render.TabletAnimations;
import com.lunares.blockfield.common.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandRenderer.class)
public abstract class TabletHandRenderMixin {
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;

    @Shadow
    public abstract void renderItem(
            LivingEntity entity,
            ItemStack stack,
            ItemDisplayContext context,
            boolean left,
            PoseStack pose,
            MultiBufferSource buffers,
            int light);

    // Wraps TaCZ's own head injection too, and Iris calls this method directly for shader packs.
    @WrapMethod(method = "renderHandsWithItems")
    private void blockfield$virtualTablet(
            float partialTick,
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            LocalPlayer player,
            int light,
            Operation<Void> original) {
        pose.pushPose();
        try {
            if (!TabletAnimations.renderHands(player, pose, buffers, light, partialTick)
                    && !MedkitAnimations.renderHands(player, pose, buffers, light, partialTick)) {
                original.call(partialTick, pose, buffers, player, light);
            }
        } finally {
            pose.popPose();
        }
    }

    @WrapMethod(method = "renderArmWithItem")
    private void blockfield$twoHandedTablet(
            AbstractClientPlayer player,
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swing,
            ItemStack stack,
            float equip,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            Operation<Void> original) {
        // TaCZ's hand event inside the original call draws the animated tablet.
        if (player.isScoping()
                || (hand == InteractionHand.MAIN_HAND && TabletAnimations.animates(stack))) {
            original.call(
                    player, partialTick, pitch, hand, swing, stack, equip, pose, buffers, light);
            return;
        }
        // Match the renderer's cached stacks throughout equip transitions.
        if (hand == InteractionHand.MAIN_HAND
                && stack.isEmpty()
                && offHandItem.is(ModItems.COMMAND_TABLET)) return;
        ItemStack other = hand == InteractionHand.MAIN_HAND ? offHandItem : mainHandItem;
        if (!stack.is(ModItems.COMMAND_TABLET)) {
            original.call(
                    player, partialTick, pitch, hand, swing, stack, equip, pose, buffers, light);
            return;
        }
        pose.pushPose();
        try {
            if (!other.isEmpty()) {
                HumanoidArm arm =
                        hand == InteractionHand.MAIN_HAND
                                ? player.getMainArm()
                                : player.getMainArm().getOpposite();
                HeldEquipmentMovement.apply(
                        pose, player, partialTick, arm == HumanoidArm.RIGHT ? 1 : -1);
                original.call(
                        player,
                        partialTick,
                        pitch,
                        hand,
                        swing,
                        stack,
                        equip,
                        pose,
                        buffers,
                        light);
                return;
            }
            float arc = Mth.sin(Mth.sqrt(swing) * Mth.PI);
            pose.translate(0, -.26 - equip * .6 - arc * .05, -1 - Mth.sin(swing * Mth.PI) * .05);
            HeldEquipmentMovement.apply(pose, player, partialTick, 1);
            pose.mulPose(Axis.XP.rotationDegrees(-10 - arc * 4));
            pose.scale(.75f, .75f, .75f);
            if (!player.isInvisible()) {
                blockfield$grip(
                        player, stack, ItemDisplayContext.FIXED, false, pose, buffers, light);
                blockfield$grip(
                        player, stack, ItemDisplayContext.FIXED, true, pose, buffers, light);
            }
            renderItem(player, stack, ItemDisplayContext.FIXED, false, pose, buffers, light);
        } finally {
            pose.popPose();
        }
    }

    @WrapOperation(
            method = "renderArmWithItem",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void blockfield$holdTablet(
            ItemInHandRenderer instance,
            LivingEntity entity,
            ItemStack stack,
            ItemDisplayContext context,
            boolean left,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            Operation<Void> original) {
        // SBM has already drawn/holstered it; vanilla can retain the tablet after that instance
        // ends.
        if (blockfield$suppressTabletFallback(TabletAnimations.animates(stack), context)) return;
        if (stack.is(ModItems.COMMAND_TABLET)
                && entity instanceof AbstractClientPlayer player
                && !player.isInvisible()) {
            blockfield$grip(player, stack, context, left, pose, buffers, light);
        }
        original.call(instance, entity, stack, context, left, pose, buffers, light);
    }

    @Unique
    private static boolean blockfield$suppressTabletFallback(
            boolean animated, ItemDisplayContext context) {
        return animated
                && (context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                        || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
    }

    @Unique
    private static void blockfield$grip(
            AbstractClientPlayer player,
            ItemStack stack,
            ItemDisplayContext context,
            boolean left,
            PoseStack pose,
            MultiBufferSource buffers,
            int light) {
        Minecraft mc = Minecraft.getInstance();
        PlayerRenderer renderer =
                (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
        pose.pushPose();
        try {
            mc.getItemRenderer()
                    .getModel(stack, player.level(), player, player.getId() + context.ordinal())
                    .getTransforms()
                    .getTransform(context)
                    .apply(context != ItemDisplayContext.FIXED && left, pose);
            pose.translate(-.5, -.5, -.5);
            // Seat the inner palms against the side grips, with elbows below and toward the player.
            pose.translate((left ? -.6 : 16.6) / 16, 8 / 16.0, 7.65 / 16);
            pose.mulPose(Axis.ZP.rotationDegrees(left ? -18 : 18));
            pose.mulPose(Axis.XP.rotationDegrees(-35));
            pose.mulPose(Axis.YP.rotationDegrees(left ? -12 : 12));
            boolean slim = player.getSkin().model() == PlayerSkin.Model.SLIM;
            double halfWidth = slim ? .5 : 1;
            // Native hand rendering retains the ±0.1-radian idle tilt around the arm pivot.
            double tipX = 5 + halfWidth * Math.cos(.1) + 10 * Math.sin(.1);
            double tipY = (slim ? 2.5 : 2) + 10 * Math.cos(.1) - halfWidth * Math.sin(.1);
            pose.translate((left ? -tipX : tipX) / 16, -tipY / 16, 0);
            if (left) renderer.renderLeftHand(pose, buffers, light, player);
            else renderer.renderRightHand(pose, buffers, light, player);
        } finally {
            pose.popPose();
        }
    }
}
