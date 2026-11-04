package com.lunares.blockfield.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Item tooltips wrap like on Forge, where SuperbWarfare and other ported mods wrote their one-line
 * descriptions: vanilla Fabric never wraps, so a long line ran off the screen (TooltipWrapMixin).
 */
public final class TooltipWrap {
    private static final int MIN_WIDTH = 60;

    private TooltipWrap() {}

    /** Line width that fits right or left of the cursor, else the wider side (Forge's rule). */
    static int width(int textWidth, int mouseX, int screenWidth) {
        if (mouseX + 12 + textWidth + 4 <= screenWidth || mouseX - 16 - textWidth >= 4)
            return textWidth;
        int side = mouseX > screenWidth / 2 ? mouseX - 12 - 8 : screenWidth - 16 - mouseX;
        return Math.max(MIN_WIDTH, side);
    }

    public static List<Component> wrap(
            Font font, List<Component> lines, int mouseX, int screenWidth) {
        int textWidth = 0;
        for (Component line : lines) textWidth = Math.max(textWidth, font.width(line));
        int width = width(textWidth, mouseX, screenWidth);
        if (width >= textWidth) return lines;
        List<Component> wrapped = new ArrayList<>();
        for (Component line : lines) {
            if (font.width(line) <= width) {
                wrapped.add(line);
                continue;
            }
            for (FormattedText part : font.getSplitter().splitLines(line, width, Style.EMPTY))
                wrapped.add(component(part));
        }
        return wrapped;
    }

    private static Component component(FormattedText text) {
        MutableComponent out = Component.empty();
        text.visit(
                (style, piece) -> {
                    out.append(Component.literal(piece).setStyle(style));
                    return Optional.empty();
                },
                Style.EMPTY);
        return out;
    }
}
