package com.lunares.blockfield.client.mixin.lrtactical;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.render.HeldEquipmentMovement;
import com.lunares.blockfield.common.grenade.GrenadeThrowSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "me.xjqsh.lrtactical.client.renderer.item.ThrowableItemRendererWrapper")
public abstract class LRThrowableMovementRenderMixin {
    @WrapMethod(method = "renderFirstPerson", remap = false)
    private void blockfield$moveHeldThrowable(
            LocalPlayer player,
            ItemStack stack,
            ItemDisplayContext context,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            float partialTick,
            Operation<Void> original) {
        if (player.isScoping() || GrenadeThrowSupport.kind(stack) != GrenadeThrowSupport.Kind.LR) {
            original.call(player, stack, context, pose, buffers, light, partialTick);
            return;
        }
        // Without shaders SBM creates this pose after vanilla's item-in-hand transforms.
        pose.pushPose();
        try {
            // Move the body and its gripping palm together above the bottom HUD.
            pose.translate(0, .10, 0);
            if (!player.isUsingItem()
                    && HeldEquipmentMovement.isIdle(
                            (AnimateGeoItemRenderer<?, ?>) (Object) this, stack)) {
                int side = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ? -1 : 1;
                HeldEquipmentMovement.apply(pose, player, partialTick, side);
            }
            original.call(player, stack, context, pose, buffers, light, partialTick);
        } finally {
            pose.popPose();
        }
    }
}
