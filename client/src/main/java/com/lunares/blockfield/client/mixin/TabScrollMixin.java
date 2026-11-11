package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.render.TabMenuRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TAB shows one roster page at a time; while it is held, the wheel flips pages instead of the
 * hotbar. Single-page tables pass the scroll through untouched.
 */
@Mixin(MouseHandler.class)
public abstract class TabScrollMixin {
    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void blockfield$tabPage(long window, double x, double y, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow()
                || mc.screen != null
                || !TabMenuRenderer.isTabActive()
                // tabActive means the room has a table; only a held TAB owns the wheel.
                || !mc.options.keyPlayerList.isDown()) return;
        int pages =
                TabMenuRenderer.pageCount(
                        mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        if (TabMenuRenderer.scrollPages(y, pages)) ci.cancel();
    }
}
