package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.SpawnPreload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.LevelLoadStatusManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelLoadStatusManager.class)
public abstract class LevelLoadPreloadMixin {

    @Shadow @Final private ClientLevel level;

    @Unique private final SpawnPreload blockfield$preload = new SpawnPreload();

    @Redirect(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean blockfield$waitForSurroundings(LevelRenderer renderer, BlockPos pos) {
        return blockfield$preload.ready(
                renderer.isSectionCompiled(pos), blockfield$chunksAround(pos));
    }

    @Unique
    private boolean blockfield$chunksAround(BlockPos pos) {
        // Дальше эффективной прорисовки сервер чанков не пришлёт: ждали бы до потолка при каждой
        // высадке.
        int radius =
                Math.min(
                        SpawnPreload.RADIUS,
                        Minecraft.getInstance().options.getEffectiveRenderDistance() - 1);
        int cx = SectionPos.blockToSectionCoord(pos.getX()),
                cz = SectionPos.blockToSectionCoord(pos.getZ());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return true;
    }
}
