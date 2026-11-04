package com.lunares.blockfield.client.mixin.sodium;

import com.lunares.blockfield.client.gui.BfTheme;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Свою тему Sodium задаёт явно в buildFullConfig (тил -7019309/-3342866/-8741218), минуя пресеты.
 * Перекрашиваем базу/светлый/тёмный варианты в янтарь Blockfield прямо в месте вызова
 * setFullThemeRGB: builder дополнительно прогоняет базу через HSV-ограничители.
 */
@Mixin(SodiumConfigBuilder.class)
public abstract class SodiumThemeMixin {

    @ModifyConstant(
            method = "buildFullConfig",
            constant = @Constant(intValue = -7019309),
            remap = false)
    private int blockfield$themeBase(int original) {
        return BfTheme.AMBER;
    }

    @ModifyConstant(
            method = "buildFullConfig",
            constant = @Constant(intValue = -3342866),
            remap = false)
    private int blockfield$themeLight(int original) {
        return BfTheme.AMBER_BRIGHT;
    }

    @ModifyConstant(
            method = "buildFullConfig",
            constant = @Constant(intValue = -8741218),
            remap = false)
    private int blockfield$themeDark(int original) {
        return BfTheme.AMBER_DIM;
    }
}
