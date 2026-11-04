package com.lunares.blockfield.client.mixin.sodium;

import com.lunares.blockfield.client.gui.BfTheme;
import net.caffeinemc.mods.sodium.client.gui.widgets.CenteredFlatWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Центрированные виджеты (переключатели, слайдеры значений): рамка фокуса. */
@Mixin(CenteredFlatWidget.class)
public abstract class CenteredFlatWidgetMixin {

    @ModifyConstant(method = "render", constant = @Constant(intValue = -2147418130))
    private int blockfield$focusBorder(int original) {
        return BfTheme.BORDER;
    }
}
