package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADR4 §8.3 capture validity: every main frame resets MAIN captures, and renders inside the main
 * {@code renderLevel} call are the MAIN pass (Iris shadow renders inside it are SHADOW); renders
 * outside it (GUI player previews) are OTHER.
 */
@Mixin(GameRenderer.class)
public abstract class CombatCaptureFrameMixin {
    private static final String RENDER_LEVEL =
            "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V";

    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void blockfield$captureFrame(
            DeltaTracker tracker, boolean renderLevel, CallbackInfo ci) {
        BodyPartHitboxRenderer.levelPass(false);
        BodyPartHitboxRenderer.frameEntry();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = RENDER_LEVEL), require = 1)
    private void blockfield$captureLevelStart(
            DeltaTracker tracker, boolean renderLevel, CallbackInfo ci) {
        BodyPartHitboxRenderer.levelPass(true);
    }

    @Inject(
            method = "render",
            at = @At(value = "INVOKE", target = RENDER_LEVEL, shift = At.Shift.AFTER),
            require = 1)
    private void blockfield$captureLevelEnd(
            DeltaTracker tracker, boolean renderLevel, CallbackInfo ci) {
        BodyPartHitboxRenderer.levelPass(false);
    }
}
