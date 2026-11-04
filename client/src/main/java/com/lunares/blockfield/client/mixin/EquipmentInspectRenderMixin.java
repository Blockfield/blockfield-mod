package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.render.EquipmentInspect;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemInHandRenderer.class)
public abstract class EquipmentInspectRenderMixin {
    @WrapMethod(method = "renderArmWithItem")
    private void blockfield$inspectHeldEquipment(
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
        EquipmentInspect.Pose inspection = EquipmentInspect.pose(player, hand, stack);
        if (inspection.equals(EquipmentInspect.Pose.IDENTITY)) {
            original.call(
                    player, partialTick, pitch, hand, swing, stack, equip, pose, buffers, light);
            return;
        }
        pose.pushPose();
        try {
            int side = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
            float gripX = .56f * side;
            float gripY = -.52f - equip * .6f;
            float gripZ = -.72f;
            // The pivot follows vanilla's hand placement, keeping the assembly in the grip.
            pose.translate(inspection.x() * side, inspection.y(), inspection.z());
            pose.translate(gripX, gripY, gripZ);
            pose.mulPose(Axis.XP.rotationDegrees(inspection.pitch()));
            pose.mulPose(Axis.YP.rotationDegrees(inspection.yaw() * side));
            pose.mulPose(Axis.ZP.rotationDegrees(inspection.roll() * side));
            pose.translate(-gripX, -gripY, -gripZ);
            original.call(
                    player, partialTick, pitch, hand, swing, stack, equip, pose, buffers, light);
        } finally {
            pose.popPose();
        }
    }
}
