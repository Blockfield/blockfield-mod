package com.lunares.blockfield.client.mixin.sodium;

import com.lunares.blockfield.client.gui.BfTheme;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Карточка опции: фон, подсветка при наведении и цвет текста — палитра Blockfield. */
@Mixin(ControlElement.class)
public abstract class ControlElementMixin {

    @ModifyConstant(method = "render", constant = @Constant(intValue = 1073741824))
    private int blockfield$cardBackground(int original) {
        return BfTheme.CARD;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = -536870912))
    private int blockfield$cardHover(int original) {
        return BfTheme.POPOVER;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = -1))
    private int blockfield$text(int original) {
        return BfTheme.TEXT;
    }
}
