package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.TabMenuRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ванильный таб (хаб, полигон): вместо полосок — пинг в мс. */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabPingMixin {

    /** 13 px ваниль оставляет в колонке под иконку полосок; «999» с отступом шире. */
    @ModifyConstant(method = "render", constant = @Constant(intValue = 13))
    private int blockfield$pingColumnWidth(int iconWidth) {
        return 24;
    }

    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
    private void blockfield$renderPingNumber(
            GuiGraphics g, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
        var font = Minecraft.getInstance().font;
        String ping = TabMenuRenderer.pingText(info);
        g.pose().pushPose();
        g.pose().translate(0, 0, 100);
        g.drawString(
                font,
                ping,
                x + width - 1 - font.width(ping),
                y,
                TabMenuRenderer.pingColor(info),
                true);
        g.pose().popPose();
        ci.cancel();
    }
}
