package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.UniformRenderers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CapeLayer.class)
public abstract class ArmoredCapeMixin {
    @Inject(
            method =
                    "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void blockfield$preventClipping(
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            AbstractClientPlayer player,
            float swing,
            float amount,
            float partialTick,
            float age,
            float yaw,
            float pitch,
            CallbackInfo ci) {
        for (var armor : player.getArmorSlots()) {
            if (!armor.isEmpty()) {
                ci.cancel();
                return;
            }
        }
        if (FabricLoader.getInstance().isModLoaded("accessories")
                && UniformRenderers.isEquipped(player)) ci.cancel();
    }
}
