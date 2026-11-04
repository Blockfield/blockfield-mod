package com.lunares.blockfield.client.mixin.sodium;

import com.lunares.blockfield.client.gui.BfTheme;
import net.caffeinemc.mods.sodium.client.gui.widgets.FlatButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Кнопки Sodium: рамка, янтарная линия выбранного пункта и дефолтные стили из <clinit>. */
@Mixin(FlatButtonWidget.class)
public abstract class FlatButtonWidgetMixin {

    @ModifyConstant(method = "render", constant = @Constant(intValue = -2147418130))
    private int blockfield$buttonBorder(int original) {
        return BfTheme.BORDER;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = -7019309))
    private int blockfield$selectedLine(int original) {
        return BfTheme.AMBER;
    }

    @ModifyConstant(method = "<clinit>", constant = @Constant(intValue = -1879048192))
    private static int blockfield$buttonBackground(int original) {
        return BfTheme.CARD;
    }

    @ModifyConstant(method = "<clinit>", constant = @Constant(intValue = -536870912))
    private static int blockfield$buttonHover(int original) {
        return BfTheme.POPOVER;
    }

    @ModifyConstant(method = "<clinit>", constant = @Constant(intValue = -5592406))
    private static int blockfield$disabledText(int original) {
        return BfTheme.MUTED;
    }
}
