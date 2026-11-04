package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.TabMenuRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Замена forge-евого RenderGuiEvent.Pre + setCanceled: у Fabric отменяемого HUD-события нет,
 * поэтому отменяем ванильный список; нашу таблицу рисует ScoreboardLayerMixin после HUD модов.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void blockfield$renderMatchScoreboard(
            GuiGraphics guiGraphics,
            int width,
            Scoreboard scoreboard,
            Objective objective,
            CallbackInfo ci) {
        if (TabMenuRenderer.isTabActive()) {
            ci.cancel();
        }
    }
}
