package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.workshop.WorkshopGhostClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A ghost sees through blocks like a spectator. Vanilla passes {@code isSpectator()} into {@code
 * setupRender}, and Sodium's overwrite forwards that same flag into its terrain/occlusion setup, so
 * this one redirect covers both renderers.
 */
@Mixin(LevelRenderer.class)
public abstract class WorkshopGhostViewMixin {
    @Redirect(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private boolean blockfield$ghostSeesThrough(LocalPlayer player) {
        return player.isSpectator() || WorkshopGhostClient.inGhost();
    }
}
