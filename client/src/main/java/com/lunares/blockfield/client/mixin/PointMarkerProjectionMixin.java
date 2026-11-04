package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.lunares.blockfield.client.render.PointMarkerRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class PointMarkerProjectionMixin {
    // The first projection uses the actual world FOV, before bobbing and screen distortions are
    // applied.
    @ModifyExpressionValue(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/GameRenderer;getProjectionMatrix(D)Lorg/joml/Matrix4f;",
                            ordinal = 0))
    private Matrix4f blockfield$captureMarkerProjection(Matrix4f projection) {
        PointMarkerRenderer.captureProjection(projection);
        return projection;
    }
}
