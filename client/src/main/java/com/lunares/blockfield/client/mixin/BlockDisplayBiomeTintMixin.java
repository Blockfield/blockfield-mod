package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla renders block_display without a level, so grass, leaves and vines get the default tint
 * instead of the biome's. Maps keep decor under ParCool-sealing barriers as block_display
 * (blockfield-modpack docs/CODRE-MAPS.md): tint it from the level at the entity's position, like
 * the block it replaced.
 */
@Mixin(DisplayRenderer.BlockDisplayRenderer.class)
public class BlockDisplayBiomeTintMixin {
    @WrapOperation(
            method =
                    "renderInner(Lnet/minecraft/world/entity/Display$BlockDisplay;Lnet/minecraft/world/entity/Display$BlockDisplay$BlockRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
    private void blockfield$biomeTint(
            BlockRenderDispatcher dispatcher,
            BlockState state,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay,
            Operation<Void> original,
            @Local(argsOnly = true) Display.BlockDisplay display) {
        int color =
                state.getRenderShape() == RenderShape.MODEL
                        ? Minecraft.getInstance()
                                .getBlockColors()
                                .getColor(state, display.level(), display.blockPosition(), 0)
                        : -1;
        if (color == -1) {
            original.call(dispatcher, state, pose, buffers, light, overlay);
            return;
        }
        // Same as the MODEL branch of renderSingleBlock, with the level's tint.
        dispatcher
                .getModelRenderer()
                .renderModel(
                        pose.last(),
                        buffers.getBuffer(ItemBlockRenderTypes.getRenderType(state, false)),
                        state,
                        dispatcher.getBlockModel(state),
                        (color >> 16 & 255) / 255.0F,
                        (color >> 8 & 255) / 255.0F,
                        (color & 255) / 255.0F,
                        light,
                        overlay);
    }
}
