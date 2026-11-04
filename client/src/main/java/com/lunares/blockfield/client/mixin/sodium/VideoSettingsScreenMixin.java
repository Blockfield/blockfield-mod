package com.lunares.blockfield.client.mixin.sodium;

import com.lunares.blockfield.client.gui.BfTheme;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Подложка экрана настроек — фон Blockfield вместо ванильного затемнения. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"))
    private void blockfield$background(
            GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), BfTheme.BG);
    }
}
