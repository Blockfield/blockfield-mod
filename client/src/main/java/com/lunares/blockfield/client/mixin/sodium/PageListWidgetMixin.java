package com.lunares.blockfield.client.mixin.sodium;

import com.lunares.blockfield.client.gui.BfTheme;
import net.caffeinemc.mods.sodium.client.gui.widgets.PageListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Градиент левой панели страниц: верх CARD, низ BG вместо серо-синих плашек. */
@Mixin(PageListWidget.class)
public abstract class PageListWidgetMixin {

    @ModifyConstant(
            method = "renderBackgroundGradient",
            constant = @Constant(intValue = 1073741824))
    private static int blockfield$gradientStart(int original) {
        return BfTheme.CARD;
    }

    @ModifyConstant(
            method = "renderBackgroundGradient",
            constant = @Constant(intValue = -1879048192))
    private static int blockfield$gradientEnd(int original) {
        return BfTheme.BG;
    }
}
