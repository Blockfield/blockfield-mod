package com.lunares.blockfield.client.gui.tablet;

import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Shared loading state; percentage describes received tiles, never server preparation. */
public final class MapLoading {
    private MapLoading() {}

    public static void render(GuiGraphics g, JsonObject map, int x, int y, int w, int h) {
        boolean loading =
                map != null
                        && map.has("serverSnapshot")
                        && map.get("serverSnapshot").getAsBoolean();
        float progress = MapSnapshots.progress(Minecraft.getInstance(), map);
        boolean compact = w < 160 || h < 80;
        double phase = (System.nanoTime() % 2_400_000_000L) / 2_400_000_000d;
        g.fill(x, y, x + w, y + h, BfTheme.BG);
        BfTheme.grid(g, x, y, w, h, compact ? 12 : 24);
        if (loading) {
            int scan = y + (int) ((h - 1) * phase);
            g.fillGradient(
                    x, Math.max(y, scan - 14), x + w, scan, 0, BfTheme.alpha(BfTheme.AMBER, 0x18));
            g.fill(x, scan, x + w, scan + 1, BfTheme.alpha(BfTheme.AMBER, 0x40));
        }

        int width = Math.min(w - 12, 220), left = x + (w - width) / 2, cy = y + h / 2;
        g.fill(left, cy - 23, left + width, cy + 23, BfTheme.alpha(BfTheme.BG, 0xEE));
        String title = compact ? "КАРТА" : "ЗАГРУЗКА КАРТЫ";
        String status =
                !loading
                        ? "Нет снимка карты"
                        : progress < 0
                                ? (compact ? "Загрузка…" : "Проводится сканирование местности")
                                : (compact ? "" : "Получаем данные · ")
                                        + Math.round(progress * 100)
                                        + "%";
        text(
                g,
                !loading && !compact ? "КАРТА" : title,
                x + w / 2,
                cy - 15,
                width - 6,
                BfTheme.TEXT);
        text(g, status, x + w / 2, cy, width - 6, BfTheme.MUTED);
        if (loading) {
            int barW = width - 12, barX = left + 6;
            g.fill(barX, cy + 15, barX + barW, cy + 17, BfTheme.BORDER);
            int from =
                    progress < 0
                            ? (int) ((barW * 3 / 4) * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)))
                            : 0;
            int filled = progress < 0 ? Math.max(3, barW / 4) : Math.round(barW * progress);
            g.fill(barX + from, cy + 15, barX + from + filled, cy + 17, BfTheme.AMBER);
        }
        BfTheme.outline(g, x, y, w, h, BfTheme.BORDER);
        BfTheme.ticks(g, x, y, w, h, 5, BfTheme.AMBER);
    }

    private static void text(GuiGraphics g, String text, int cx, int y, int width, int color) {
        var font = BfTheme.font();
        float scale = Math.min(1f, (float) width / Math.max(1, font.width(text)));
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, color, false);
        g.pose().popPose();
    }
}
