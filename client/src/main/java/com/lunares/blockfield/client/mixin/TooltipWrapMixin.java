package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.gui.TooltipWrap;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Long item tooltip lines wrap to the screen (TooltipWrap). */
@Mixin(GuiGraphics.class)
public abstract class TooltipWrapMixin {
    @Shadow
    public abstract int guiWidth();

    @WrapMethod(
            method =
                    "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V")
    private void blockfield$wrapLongLines(
            Font font,
            List<Component> lines,
            Optional<TooltipComponent> image,
            int mouseX,
            int mouseY,
            Operation<Void> original) {
        original.call(
                font, TooltipWrap.wrap(font, lines, mouseX, guiWidth()), image, mouseX, mouseY);
    }
}
