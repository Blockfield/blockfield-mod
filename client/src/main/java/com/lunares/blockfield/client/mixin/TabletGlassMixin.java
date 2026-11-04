package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.TabletAnimations;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tablet glass texture is drawn before the frame: render targets must not switch mid-world
 * (Iris).
 */
@Mixin(GameRenderer.class)
public abstract class TabletGlassMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void blockfield$refreshTabletGlass(
            DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        TabletAnimations.refreshScreen();
    }
}
