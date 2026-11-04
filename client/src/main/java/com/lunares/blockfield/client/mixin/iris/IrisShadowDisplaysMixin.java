package com.lunares.blockfield.client.mixin.iris;

import com.llamalad7.mixinextras.sugar.Local;
import com.lunares.blockfield.client.render.DisplayBatcher;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Baked displays leave the entity lists (DisplayBatcher), so Iris's shadow entity pass would skip
 * them: their buffers are drawn there with its shadow programs, its frustum and its matrices. Runs
 * only when the pack renders entity shadows.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public class IrisShadowDisplaysMixin {
    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void blockfield$bakedDisplays(
            CallbackInfoReturnable<Integer> cir,
            @Local(argsOnly = true) PoseStack modelView,
            @Local(argsOnly = true) Frustum frustum,
            @Local(argsOnly = true, ordinal = 0) double x,
            @Local(argsOnly = true, ordinal = 1) double y,
            @Local(argsOnly = true, ordinal = 2) double z) {
        DisplayBatcher.drawShadow(
                modelView.last().pose(), RenderSystem.getProjectionMatrix(), frustum, x, y, z);
    }
}
