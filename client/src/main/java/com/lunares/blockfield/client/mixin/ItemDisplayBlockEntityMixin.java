package com.lunares.blockfield.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Signs and banners under ParCool-sealing barriers (blockfield-modpack docs/CODRE-MAPS.md):
 * block_display would draw them blank, so the map keeps them as an item_display whose item carries
 * custom_data {bf_block_entity: {state: {Name, Properties}, nbt: {id, patterns | front_text,
 * ...}}}. Render that block entity with its own renderer at the entity position, as the block it
 * replaced (text, patterns, waving banner).
 */
@Mixin(DisplayRenderer.ItemDisplayRenderer.class)
public class ItemDisplayBlockEntityMixin {
    @Unique private static final String KEY = "bf_block_entity";
    // Keyed by the synced stack instance: a new stack (entity data update) builds a new block
    // entity.
    @Unique private static final Map<ItemStack, BlockEntity> CACHE = new WeakHashMap<>();

    @Inject(
            method =
                    "renderInner(Lnet/minecraft/world/entity/Display$ItemDisplay;Lnet/minecraft/world/entity/Display$ItemDisplay$ItemRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void blockfield$renderBlockEntity(
            Display.ItemDisplay display,
            Display.ItemDisplay.ItemRenderState state,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            float partialTick,
            CallbackInfo ci) {
        ItemStack stack = state.itemStack();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) {
            return;
        }
        BlockEntity blockEntity =
                CACHE.computeIfAbsent(
                        stack, s -> blockfield$load(display, data.copyTag().getCompound(KEY)));
        if (blockEntity != null) {
            // A cached value's level would retain the display and its weak ItemStack key.
            blockEntity.setLevel(display.level());
            try {
                Minecraft.getInstance()
                        .getBlockEntityRenderDispatcher()
                        .renderItem(blockEntity, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            } finally {
                blockEntity.setLevel(null);
            }
        }
        ci.cancel();
    }

    @Unique
    private static BlockEntity blockfield$load(Display.ItemDisplay display, CompoundTag tag) {
        var level = display.level();
        var blockState =
                NbtUtils.readBlockState(
                        level.holderLookup(Registries.BLOCK), tag.getCompound("state"));
        BlockEntity blockEntity =
                BlockEntity.loadStatic(
                        display.blockPosition(),
                        blockState,
                        tag.getCompound("nbt"),
                        level.registryAccess());
        return blockEntity;
    }
}
