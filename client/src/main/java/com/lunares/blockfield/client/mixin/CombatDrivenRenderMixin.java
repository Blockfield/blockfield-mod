package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.lunares.blockfield.client.combat.DrivenPresentation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every render pass of a driven remote (main, Iris shadow, others) samples the native renderer at
 * the frame's A endpoint partial, never the ambient one; R inputs are already collapsed (ADR4
 * §§2.4/5.3), and the pass reads the checkpoint inputs of {@link DrivenPresentation#scope} (U8b),
 * unwound on exceptions. Undriven entities and the local player keep the ambient partial and their
 * live values.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class CombatDrivenRenderMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 1)
    private float blockfield$drivenPartial(
            float partialTicks, @Local(argsOnly = true) Entity entity) {
        Float driven = DrivenPresentation.partial(entity);
        return driven == null ? partialTicks : driven;
    }

    @WrapMethod(method = "render")
    private <E extends Entity> void blockfield$drivenInputs(
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
        if (!DrivenPresentation.driven(entity)) {
            original.call(entity, x, y, z, yaw, partialTicks, poseStack, buffers, light);
            return;
        }
        try (var ignored = DrivenPresentation.scope(entity)) {
            original.call(entity, x, y, z, yaw, partialTicks, poseStack, buffers, light);
        }
    }
}
