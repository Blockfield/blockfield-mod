package com.lunares.blockfield.client.sodium;

import com.lunares.blockfield.client.gui.BfTheme;
import java.util.Arrays;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;

/** Темы-пресеты Sodium: страницы модов без явной темы выбирают пресет по хешу id. */
public final class SodiumTheme {
    private SodiumTheme() {}

    public static void apply() {
        ColorTheme amber = new ColorTheme(BfTheme.AMBER, BfTheme.AMBER_BRIGHT, BfTheme.AMBER_DIM);
        Arrays.fill(ColorTheme.PRESETS, amber);
    }
}
