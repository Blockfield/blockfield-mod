package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.gui.BfTheme;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.FastColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Экран загрузки в палитре лаунчера: фон вместо красного Mojang, прогресс янтарный. */
@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
    private int blockfield$background(IntSupplier brand) {
        return BfTheme.BG;
    }

    @Redirect(
            method = "drawProgressBar",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/util/FastColor$ARGB32;color(IIII)I"))
    private int blockfield$progressColor(int alpha, int r, int g, int b) {
        return FastColor.ARGB32.color(
                alpha,
                (BfTheme.AMBER >> 16) & 0xFF,
                (BfTheme.AMBER >> 8) & 0xFF,
                BfTheme.AMBER & 0xFF);
    }
}
