package com.lunares.blockfield.client.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(RenderStateShard.TextureStateShard.class)
public abstract class UiFontFilterMixin {
    // Oversampled TTF strokes disappear under nearest sampling on the tablet's fractional canvas;
    // the HUD faces scale their glyphs too.
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static boolean blockfield$smoothUiFont(
            boolean value, ResourceLocation texture, boolean blur, boolean mipmap) {
        return value
                || (texture.getNamespace().equals("blockfield")
                        && (texture.getPath().startsWith("ui/")
                                || texture.getPath().startsWith("hud")));
    }
}
