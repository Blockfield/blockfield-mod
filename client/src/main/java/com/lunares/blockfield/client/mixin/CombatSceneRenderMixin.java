package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.combat.ClientCombatScene;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class CombatSceneRenderMixin {
    // Minecraft.runTick finishes native ticks/ride updates before this sole main render call.
    // Iris shadow rendering calls LevelRenderer, not this GameRenderer.render entry point.
    @Inject(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"),
            require = 1)
    private void blockfield$sampleScene(
            DeltaTracker tracker, boolean renderLevel, CallbackInfo ci) {
        ClientCombatScene.frameEntry(System.nanoTime());
    }

    // A capsule applied while the frame renders becomes a new revision of this frame; after it,
    // the next application waits for the next main-frame entry (ADR4 §3.1).
    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void blockfield$frameEnd(DeltaTracker tracker, boolean renderLevel, CallbackInfo ci) {
        ClientCombatScene.frameEnd();
    }
}
