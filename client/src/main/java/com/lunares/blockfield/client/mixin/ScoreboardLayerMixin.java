package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lunares.blockfield.client.render.TabMenuRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class ScoreboardLayerMixin {
    @WrapOperation(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private void blockfield$scoreboardAboveHud(
            Gui gui, GuiGraphics graphics, DeltaTracker delta, Operation<Void> original) {
        original.call(gui, graphics, delta);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null
                || mc.options.hideGui
                || !mc.options.keyPlayerList.isDown()
                || !TabMenuRenderer.isTabActive()) return;
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        TabMenuRenderer.renderTabMenu(graphics);
        graphics.flush();
        graphics.pose().popPose();
    }
}
