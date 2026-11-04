package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.gui.screen.MenuScreen;
import com.lunares.blockfield.client.gui.tablet.TabletScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Gui.class, priority = 1500)
public abstract class ModalHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void blockfield$hideBehindModal(
            GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof TabletScreen || screen instanceof MenuScreen) {
            ci.cancel();
        }
    }
}
