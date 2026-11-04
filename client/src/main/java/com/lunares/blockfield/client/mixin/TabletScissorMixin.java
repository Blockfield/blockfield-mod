package com.lunares.blockfield.client.mixin;

import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.client.gui.tablet.TabletScreenTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Scissor coordinates bypass the pose stack; include both canvas scaling and tablet opening motion.
 */
@Mixin(GuiGraphics.class)
public abstract class TabletScissorMixin {
    @Shadow
    public abstract PoseStack pose();

    @Shadow
    public abstract void flush();

    /** Inside the tablet glass framebuffer the window's size and GUI scale do not apply. */
    @Inject(method = "applyScissor", at = @At("HEAD"), cancellable = true)
    private void blockfield$glassScissor(@Nullable ScreenRectangle rect, CallbackInfo ci) {
        if (!TabletScreenTarget.rendering()) return;
        // Preserve GuiGraphics' flush before changing the clip: queued text uses the old scissor.
        flush();
        ci.cancel();
        if (rect == null) {
            RenderSystem.disableScissor();
            return;
        }
        double scale = TabletScreenTarget.pixelScale();
        RenderSystem.enableScissor(
                (int) (rect.left() * scale),
                (int) (TabletScreenTarget.pixelHeight() - rect.bottom() * scale),
                Math.max(0, (int) (rect.width() * scale)),
                Math.max(0, (int) (rect.height() * scale)));
    }

    @ModifyArgs(
            method = "enableScissor",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/navigation/ScreenRectangle;<init>(IIII)V"))
    private void blockfield$shiftTabletScissor(Args args) {
        if (!Tablet.scissorShift()) return;
        var matrix = pose().last().pose();
        int x = args.get(0), y = args.get(1), w = args.get(2), h = args.get(3);
        int left = (int) Math.floor(x * matrix.m00() + matrix.m30());
        int top = (int) Math.floor(y * matrix.m11() + matrix.m31());
        int right = (int) Math.ceil((x + w) * matrix.m00() + matrix.m30());
        int bottom = (int) Math.ceil((y + h) * matrix.m11() + matrix.m31());
        args.set(0, left);
        args.set(1, top);
        args.set(2, right - left);
        args.set(3, bottom - top);
    }
}
