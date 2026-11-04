package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.workshop.WorkshopToolsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wheel height for a held sketch: while a sketch is in hand, the wheel adjusts its height instead
 * of the hotbar. Fabric exposes no in-game scroll event, so this HEAD-cancel on vanilla's scroll
 * handler is the only hook; it passes everything through otherwise.
 */
@Mixin(MouseHandler.class)
public abstract class WorkshopAiGrabMixin {
    @Inject(method = "onPress", at = @At("HEAD"))
    private void blockfield$releaseUse(
            long window, int button, int action, int mods, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window == mc.getWindow().getWindow()
                && action == 0
                && mc.options.keyUse.matchesMouse(button)) WorkshopToolsClient.releaseUse(false);
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void blockfield$grabNudge(long window, double x, double y, CallbackInfo ci) {
        if (WorkshopToolsClient.nudgeGrabbed(y)) ci.cancel();
    }
}
