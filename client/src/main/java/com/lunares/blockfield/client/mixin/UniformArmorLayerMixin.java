package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.UniformRenderers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress visuals only; SBW armor values, durability and plates are untouched. */
@Mixin(value = HumanoidArmorLayer.class, priority = 1100)
public abstract class UniformArmorLayerMixin {
    @Inject(
            method =
                    "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void blockfield$uniform(
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            LivingEntity entity,
            float limbSwing,
            float limbAmount,
            float partialTick,
            float age,
            float yaw,
            float pitch,
            CallbackInfo ci) {
        if (FabricLoader.getInstance().isModLoaded("accessories")
                && UniformRenderers.isEquipped(entity)) ci.cancel();
    }
}
