package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.render.WeaponHud;
import java.util.Optional;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Workshop tablet actions answer only in chat, and the open tablet hides the whole HUD with it: the
 * latest server line is repeated on the tablet so a refused or finished action is not silent.
 */
public final class WorkshopFeedback {
    static final long SHOW_NS = 8_000_000_000L;
    private static String text = "";
    private static int color;
    private static long at;

    private WorkshopFeedback() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> {
                    if (overlay || !WeaponHud.inWorkshop()) return;
                    // WorldEdit colours by style, our server by legacy codes inside the text.
                    accept(message.getString(), styled(message), System.nanoTime());
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> text = "");
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> text = "");
    }

    static Integer styled(Component message) {
        return message.visit(
                        (style, part) ->
                                Optional.ofNullable(style.getColor())
                                        .map(TextColor::getValue)
                                        .filter(rgb -> severity(rgb) != 0),
                        Style.EMPTY)
                .orElse(null);
    }

    static void accept(String raw, Integer styled, long now) {
        String plain = ChatFormatting.stripFormatting(raw);
        if (plain == null || plain.isBlank()) return;
        text = plain.strip();
        color = color(raw, styled);
        at = now;
    }

    /** The line still worth showing, or an empty string. */
    public static String current(long now) {
        return now - at <= SHOW_NS ? text : "";
    }

    public static int color() {
        return color;
    }

    /** First non-grey legacy code wins (tags such as "§7[Разметка] §c…" stay neutral). */
    static int color(String raw, Integer styled) {
        for (int i = raw.indexOf('§');
                i >= 0 && i + 1 < raw.length();
                i = raw.indexOf('§', i + 1)) {
            ChatFormatting code = ChatFormatting.getByCode(raw.charAt(i + 1));
            if (code != null && code.getColor() != null && severity(code.getColor()) != 0)
                return severity(code.getColor());
        }
        int fromStyle = styled == null ? 0 : severity(styled);
        return fromStyle != 0 ? fromStyle : BfTheme.TEXT_SECONDARY;
    }

    private static int severity(int rgb) {
        if (rgb == ChatFormatting.RED.getColor() || rgb == ChatFormatting.DARK_RED.getColor())
            return BfTheme.DANGER;
        if (rgb == ChatFormatting.YELLOW.getColor() || rgb == ChatFormatting.GOLD.getColor())
            return BfTheme.WARN;
        if (rgb == ChatFormatting.GREEN.getColor() || rgb == ChatFormatting.DARK_GREEN.getColor())
            return BfTheme.OK;
        return 0;
    }
}
