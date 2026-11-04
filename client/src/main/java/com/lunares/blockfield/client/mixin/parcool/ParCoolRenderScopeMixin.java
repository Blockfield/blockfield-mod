package com.lunares.blockfield.client.mixin.parcool;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.animation.RemoteParCoolPresentation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * ADR4 §§5.2/5.3: every render pass of a ParCool-driven remote (main, shadow, others) runs the
 * native ParCool root/model stages over fresh scratch materialized from its installed frame; the
 * scope unwinds on exceptions. Undriven entities and the local player render natively.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ParCoolRenderScopeMixin {
    @WrapMethod(method = "render")
    private <E extends Entity> void blockfield$parcoolScratch(
            E entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            Operation<Void> original) {
        if (!(entity instanceof AbstractClientPlayer player)
                || !RemoteParCoolPresentation.driven(player)) {
            original.call(entity, x, y, z, yaw, partialTicks, poseStack, buffers, light);
            return;
        }
        try (var ignored = RemoteParCoolPresentation.scope(player)) {
            original.call(entity, x, y, z, yaw, partialTicks, poseStack, buffers, light);
        }
    }
}
