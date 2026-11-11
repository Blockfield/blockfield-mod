package com.lunares.blockfield.client.gui;

import com.lunares.blockfield.client.gui.screen.MenuLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Графитовые поверхности и оранжевые акценты Blockfield. Семантические цвета команд сохраняются на
 * картах и HUD; шрифт интерфейса подключается только игровыми меню.
 */
public final class BfTheme {
    private BfTheme() {}

    private static final net.minecraft.resources.ResourceLocation UI_FONT = face("ui");

    /** Resolve through the live font manager so resource reloads also refresh these faces. */
    private static final java.util.Map<net.minecraft.resources.ResourceLocation, Font> faces =
            new java.util.HashMap<>();

    private static Font sourceFont;

    private static net.minecraft.resources.ResourceLocation face(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("blockfield", path);
    }

    /** IBM Plex Sans of the menus. */
    public static Font font() {
        return font(UI_FONT);
    }

    /**
     * IBM Plex Sans Condensed SemiBold of the bottom HUD: {@code hud} for small labels, {@code
     * hud_figures} for mid-size figures, {@code hud_big} for the large numbers. Each is rasterised
     * near the size it is shown at, since font atlases have no mipmaps and a glyph shrunk too far
     * aliases.
     */
    public static Font hudFont(String name) {
        return font(face(name));
    }

    private static Font font(net.minecraft.resources.ResourceLocation face) {
        Font current = net.minecraft.client.Minecraft.getInstance().font;
        if (current != sourceFont) {
            faces.clear();
            sourceFont = current;
        }
        return faces.computeIfAbsent(
                face,
                id -> {
                    var source = (com.lunares.blockfield.client.mixin.FontAccessor) current;
                    return new Font(
                            requested ->
                                    source.blockfield$fonts()
                                            .apply(
                                                    requested.equals(
                                                                    net.minecraft.network.chat.Style
                                                                            .DEFAULT_FONT)
                                                            ? id
                                                            : requested),
                            false);
                });
    }

    public static String ellipsize(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    /** Wrapped lines; {@code overflow} means text past {@code maxLines} was cut. */
    public record Wrapped(java.util.List<String> lines, boolean overflow) {}

    /**
     * Word wrap to at most {@code maxLines}; the last kept line ends with «…» on overflow. The
     * caller shows the full text elsewhere (tooltip), so nothing is lost.
     */
    public static Wrapped wrap(Font font, String text, int width, int maxLines) {
        var lines = new java.util.ArrayList<String>();
        for (var line :
                font.getSplitter().splitLines(text, width, net.minecraft.network.chat.Style.EMPTY))
            lines.add(line.getString());
        if (lines.size() <= maxLines) return new Wrapped(lines, false);
        var kept = new java.util.ArrayList<>(lines.subList(0, Math.max(0, maxLines)));
        if (!kept.isEmpty()) {
            int last = kept.size() - 1;
            kept.set(last, ellipsize(font, kept.get(last) + "…", width));
        }
        return new Wrapped(kept, true);
    }

    public static int mix(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255;
            result |= Math.round(a + (((to >>> shift) & 255) - a) * t) << shift;
        }
        return result;
    }

    public static final int BG = 0xFF070604;
    public static final int BG_ELEVATED = 0xFF0B0906;
    public static final int CARD = 0xFF11100D;
    public static final int POPOVER = 0xFF18130D;
    public static final int BORDER = 0xFF3A2C1D;
    public static final int BORDER_SOFT = 0xFF18130D;
    public static final int BORDER_HOVER = 0xFF5E5040;
    public static final int AMBER = 0xFFF5A524;
    public static final int AMBER_BRIGHT = 0xFFFFC861;
    public static final int AMBER_DIM = 0xFF8A571C;
    public static final int TEXT = 0xFFF3E7D0;
    public static final int TEXT_SECONDARY = 0xFFC7AE86;
    public static final int MUTED = 0xFFA89373;
    public static final int MUTED_DARK = 0xFF8E7A5E;
    public static final int OK = 0xFF82D66B;
    public static final int WARN = 0xFFF2C94C;
    public static final int DANGER = 0xFFFF5C5C;
    public static final int RED_TEAM = DANGER;
    public static final int BLUE_TEAM = 0xFF3480FA;
    public static final int NEUTRAL = MUTED;

    /** Затемнение под полноэкранными меню. */
    public static final int SCRIM = 0xE6070604;

    public static int alpha(int color, int a) {
        return (a << 24) | (color & 0xFFFFFF);
    }

    public static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /**
     * Карточка: заливка CARD, рамка BORDER; glow — внешняя рамка янтарём на 20% как у GlowPanel.
     */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, boolean glow) {
        if (glow) outline(g, x - 1, y - 1, w + 2, h + 2, alpha(AMBER, 0x33));
        g.fill(x, y, x + w, y + h, CARD);
        outline(g, x, y, w, h, BORDER);
    }

    /** Уголки-засечки по четырём углам (Tick в лаунчере): две линии длиной len из каждого угла. */
    public static void ticks(GuiGraphics g, int x, int y, int w, int h, int len, int color) {
        g.fill(x, y, x + len, y + 1, color);
        g.fill(x, y, x + 1, y + len, color);
        g.fill(x + w - len, y, x + w, y + 1, color);
        g.fill(x + w - 1, y, x + w, y + len, color);
        g.fill(x, y + h - 1, x + len, y + h, color);
        g.fill(x, y + h - len, x + 1, y + h, color);
        g.fill(x + w - len, y + h - 1, x + w, y + h, color);
        g.fill(x + w - 1, y + h - len, x + w, y + h, color);
    }

    /** Фоновая сетка GridBackdrop: линии янтарём на 6%. */
    public static void grid(GuiGraphics g, int x, int y, int w, int h, int step) {
        int c = alpha(AMBER, 0x0F);
        for (int gx = x; gx < x + w; gx += step) g.fill(gx, y, gx + 1, y + h, c);
        for (int gy = y; gy < y + h; gy += step) g.fill(x, gy, x + w, gy + 1, c);
    }

    /**
     * SectionHeader: янтарный квадрат, подпись, тонкая линия до правого края, код справа
     * приглушённо. Занимает по высоте 9px от y.
     */
    public static void header(
            GuiGraphics g, Font font, int x, int y, int w, String label, String code) {
        header(g, font, x, y, w, label, code, MUTED_DARK, 0);
    }

    /**
     * Header with a status badge: {@code dotColor} != 0 draws a {@link #statusDot} before the code,
     * which is painted in {@code codeColor}.
     */
    public static void header(
            GuiGraphics g,
            Font font,
            int x,
            int y,
            int w,
            String label,
            String code,
            int codeColor,
            int dotColor) {
        g.fill(x, y + 3, x + 3, y + 6, AMBER);
        g.drawString(font, label, x + 7, y, TEXT, false);
        int labelEnd = x + 7 + font.width(label) + 6;
        int codeW = code == null || code.isEmpty() ? 0 : font.width(code);
        int badgeW = codeW + (codeW > 0 && dotColor != 0 ? 8 : 0);
        int lineEnd = x + w - (badgeW > 0 ? badgeW + 6 : 0);
        if (lineEnd > labelEnd) g.fill(labelEnd, y + 4, lineEnd, y + 5, BORDER_SOFT);
        if (codeW > 0) {
            if (dotColor != 0) statusDot(g, x + w - badgeW, y + 3, dotColor);
            g.drawString(font, code, x + w - codeW, y, codeColor, false);
        }
    }

    /** Button hierarchy of the Workshop sheets: one primary per state, the rest quieter. */
    public enum Style {
        PRIMARY,
        SECONDARY,
        TERTIARY,
        DESTRUCTIVE
    }

    public static void button(
            GuiGraphics g,
            Font font,
            int x,
            int y,
            int w,
            int h,
            String label,
            boolean hovered,
            boolean enabled,
            Style style) {
        int text;
        if (!enabled) {
            g.fill(x, y, x + w, y + h, BG_ELEVATED);
            outline(g, x, y, w, h, BORDER_SOFT);
            text = MUTED_DARK;
        } else
            switch (style) {
                case PRIMARY -> {
                    g.fill(x, y, x + w, y + h, hovered ? AMBER_BRIGHT : AMBER);
                    text = BG;
                }
                case TERTIARY -> {
                    text = hovered ? TEXT : TEXT_SECONDARY;
                    if (hovered) {
                        int lw = font.width(label);
                        int lx = x + (w - lw) / 2;
                        g.fill(lx, y + h - 5, lx + lw, y + h - 4, AMBER);
                    }
                }
                case DESTRUCTIVE -> {
                    g.fill(x, y, x + w, y + h, POPOVER);
                    outline(g, x, y, w, h, hovered ? DANGER : BORDER_HOVER);
                    text = DANGER;
                }
                default -> {
                    g.fill(x, y, x + w, y + h, POPOVER);
                    outline(g, x, y, w, h, hovered ? AMBER : BORDER_HOVER);
                    text = hovered ? TEXT : TEXT_SECONDARY;
                }
            }
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, text, false);
    }

    /** Keyboard focus: 1 px bright amber ring, 1 px outside the widget. */
    public static void focusRing(GuiGraphics g, int x, int y, int w, int h) {
        outline(g, x - 2, y - 2, w + 4, h + 4, AMBER_BRIGHT);
    }

    /**
     * Кнопка: primary — янтарная заливка с тёмным текстом; иначе карточка с рамкой, при наведении
     * янтарная рамка.
     */
    public static void button(
            GuiGraphics g,
            Font font,
            int x,
            int y,
            int w,
            int h,
            String label,
            boolean hovered,
            boolean enabled,
            boolean primary) {
        int fill, border, text;
        if (!enabled) {
            fill = BG_ELEVATED;
            border = BORDER_SOFT;
            text = MUTED;
        } else if (primary) {
            fill = hovered ? AMBER_BRIGHT : AMBER;
            border = fill;
            text = BG;
        } else {
            fill = hovered ? POPOVER : CARD;
            border = hovered ? AMBER : BORDER;
            text = hovered ? TEXT : TEXT_SECONDARY;
        }
        g.fill(x, y, x + w, y + h, fill);
        outline(g, x, y, w, h, border);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, text, false);
    }

    /** Полоса прогресса: тёмный трек с рамкой, заливка цветом. fraction 0..1. */
    public static void progress(
            GuiGraphics g, int x, int y, int w, int h, float fraction, int color) {
        g.fill(x, y, x + w, y + h, BG_ELEVATED);
        outline(g, x, y, w, h, BORDER);
        int fillW = Math.round(Math.max(0f, Math.min(1f, fraction)) * (w - 2));
        if (fillW > 0) g.fill(x + 1, y + 1, x + 1 + fillW, y + h - 1, color);
    }

    /**
     * Тултип вместо ванильного (в 1.21.1 его фиолетовая рамка зашита в код, спрайтов нет): панель
     * POPOVER с рамкой, строки с их собственными цветами, по умолчанию TEXT. Координаты экранные,
     * как у renderTooltip; начинается под курсором (не накрывает строку под ним), при нехватке
     * места — над курсором; прижимается к краям экрана.
     */
    public static void tooltip(
            GuiGraphics g,
            Font font,
            java.util.List<net.minecraft.network.chat.Component> lines,
            int mx,
            int my,
            int screenW,
            int screenH) {
        if (lines.isEmpty()) return;
        int w = 0;
        for (net.minecraft.network.chat.Component c : lines) w = Math.max(w, font.width(c));
        w += 8;
        int h = lines.size() * LINE_STEP + 6;
        int[] at = tooltipOrigin(mx, my, w, h, screenW, screenH);
        int x = at[0];
        int y = at[1];
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        outline(g, x - 1, y - 1, w + 2, h + 2, alpha(AMBER, 0x33));
        g.fill(x, y, x + w, y + h, POPOVER);
        outline(g, x, y, w, h, BORDER);
        int ty = y + 4;
        for (net.minecraft.network.chat.Component c : lines) {
            g.drawString(font, c, x + 4, ty, TEXT, false);
            ty += LINE_STEP;
        }
        g.pose().popPose();
    }

    /**
     * Шаг строк тултипа: глифы интерфейсного шрифта занимают почти всю высоту строки, поэтому шаг
     * равен шагу обычного текста (11), а не 10 -- иначе нижние выносные элементы строки ложатся на
     * верх соседней.
     */
    static final int LINE_STEP = 11;

    /**
     * Левый верхний угол тултипа w*h у курсора (mx,my), прижатый к экрану. Чистая геометрия для
     * тестов; поведение как раньше: справа от курсора, иначе слева; ниже, иначе выше.
     */
    static int[] tooltipOrigin(int mx, int my, int w, int h, int screenW, int screenH) {
        int cw = Math.max(0, Math.min(w, screenW));
        int ch = Math.max(0, Math.min(h, screenH));
        int x = mx + 10;
        if (x + cw > screenW) x = mx - 10 - cw;
        int y = my + 12;
        if (y + ch > screenH) y = my - 12 - ch;
        return new int[] {
            Math.max(0, Math.min(x, screenW - cw)), Math.max(0, Math.min(y, screenH - ch))
        };
    }

    /** Точка статуса (StatusDot): 3x3 квадрат с ореолом. */
    public static void statusDot(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 4, y + 4, alpha(color, 0x40));
        g.fill(x, y, x + 3, y + 3, color);
    }

    /**
     * Tablet glass: graphite fill over the frame rect (tabs strip + content). The border goes on
     * last via {@link #outline}, after the tab content is drawn.
     */
    public static void frame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BG);
    }

    /**
     * Tab strip: elevated band with one fixed-width tab per entry (icon + label), CARD fill on
     * hover/active, amber underline tracking the animated {@code underlineX} slot. Screens pass
     * icons/labels/active only; bounds come from their frame.
     */
    public static void tabs(
            GuiGraphics g,
            Font font,
            BfIcon[] icons,
            String[] labels,
            int active,
            int mouseX,
            int mouseY,
            int x,
            int y,
            int w,
            int tabW,
            int h,
            float underlineX) {
        g.fill(x, y, x + w, y + h, BG_ELEVATED);
        for (int i = 0; i < labels.length; i++) {
            int tx = x + i * tabW;
            boolean on = i == active;
            boolean hovered = hit(mouseX, mouseY, tx, y, tabW, h);
            if (hovered || on) g.fill(tx, y, tx + tabW, y + h, CARD);
            int ink = on ? AMBER : hovered ? TEXT : MUTED;
            icons[i].draw(g, tx + 10, y + 7, 12, ink);
            g.drawString(font, labels[i], tx + 30, y + 9, ink, false);
        }
        g.pose().pushPose();
        g.pose().translate(x + tabW * underlineX, y + h - 2, 0);
        g.fill(0, 0, tabW, 2, AMBER);
        g.pose().popPose();
    }

    /** Tab-strip hit test: index of the tab under the cursor, or -1. Pure geometry. */
    public static int tabAt(int mouseX, int mouseY, int count, int x, int y, int tabW, int h) {
        if (mouseY < y || mouseY >= y + h) return -1;
        int i = (mouseX - x) / tabW;
        return i >= 0 && i < count ? i : -1;
    }

    /**
     * Map label chip: single-line text on a graphite tag, 11px high with 3px text insets (ping
     * names, player names, coordinates, cursor hint). Returns the chip width.
     */
    public static int chipWidth(Font font, String text) {
        return font.width(text) + 6;
    }

    public static void chip(GuiGraphics g, Font font, int x, int y, String text, int ink, int bg) {
        g.fill(x, y, x + chipWidth(font, text), y + 11, bg);
        g.drawString(font, text, x + 3, y + 2, ink, false);
    }

    /** Chip background alone, for chips with extra graphics (the north arrow). */
    public static void chipBox(GuiGraphics g, int x, int y, int w, int bg) {
        g.fill(x, y, x + w, y + 11, bg);
    }

    /**
     * Selection surface of vote/mode cards and buttons: CARD/POPOVER mix on hover, accent border
     * (red for destructive), amber underline while hovered or selected.
     */
    public static void selectSurface(
            GuiGraphics g,
            int x,
            int y,
            int w,
            int h,
            boolean enabled,
            boolean selected,
            float hover,
            boolean destructive) {
        int accent = destructive ? DANGER : AMBER;
        g.fill(x, y, x + w, y + h, enabled ? mix(CARD, POPOVER, hover) : BG_ELEVATED);
        outline(g, x, y, w, h, enabled ? mix(BORDER, accent, selected ? 1f : hover) : BORDER_SOFT);
        if (hover > 0f || selected) {
            g.fill(x, y + h - 1, x + Math.round(w * (selected ? 1f : hover)), y + h, accent);
        }
    }

    /**
     * Vote/mode card: selection surface, leading icon, label at {@code scale}, hint pinned to the
     * bottom, arrow on hover. Screens pass data only (icon, texts, scale, padding).
     */
    public static void card(
            GuiGraphics g,
            Font font,
            int x,
            int y,
            int w,
            int h,
            BfIcon icon,
            String label,
            String hint,
            float scale,
            int pad,
            boolean compact,
            boolean enabled,
            float hover) {
        selectSurface(g, x, y, w, h, enabled, false, hover, false);
        float iconSize = compact ? 20 : 30;
        icon.draw(
                g, x + pad, y + pad, iconSize, enabled ? mix(TEXT_SECONDARY, AMBER, hover) : MUTED);
        BfIcon.ARROW.draw(
                g,
                x + w - pad - 12 + 2 * hover,
                y + pad,
                12,
                enabled ? mix(MUTED, AMBER, hover) : MUTED_DARK);
        int room = w - 2 * pad;
        int labelY = y + pad + (compact ? 27 : 42);
        g.pose().pushPose();
        g.pose().translate(x + pad, labelY, 0);
        g.pose().scale(scale, scale, 1f);
        int ly = 0;
        for (var line :
                font.getSplitter()
                        .splitLines(
                                label,
                                (int) (room / scale),
                                net.minecraft.network.chat.Style.EMPTY)) {
            g.drawString(font, line.getString(), 0, ly, enabled ? TEXT : MUTED, false);
            ly += 11;
        }
        g.pose().popPose();
        int hy = y + h - pad + 3;
        for (var line :
                font.getSplitter().splitLines(hint, room, net.minecraft.network.chat.Style.EMPTY)) {
            hy -= 11;
            g.drawString(font, line.getString(), x + pad, hy, MUTED, false);
        }
    }

    /**
     * Vote list / settings button: selection surface, leading icon, label with an inline or stacked
     * hint. {@code room} is the text width available right of the icon, {@code stacked} means the
     * hint did not fit beside the label.
     */
    public static void menuButton(
            GuiGraphics g,
            Font font,
            int x,
            int y,
            int w,
            int h,
            BfIcon icon,
            String label,
            String hint,
            boolean stacked,
            int room,
            boolean enabled,
            boolean primary,
            boolean destructive,
            float hover) {
        selectSurface(g, x, y, w, h, enabled, false, hover, destructive);
        if (primary && enabled && !destructive)
            g.fill(x, y, x + w, y + h, mix(AMBER, AMBER_BRIGHT, hover));
        int ink = !enabled ? MUTED : destructive ? DANGER : primary ? BG : TEXT;
        icon.draw(g, x + 7, y + (h - 11) / 2f, 11, ink);
        var splitter = font.getSplitter();
        var labelLines =
                splitter.splitLines(label, room, net.minecraft.network.chat.Style.EMPTY).stream()
                        .map(net.minecraft.network.chat.FormattedText::getString)
                        .toList();
        var hintLines =
                stacked
                        ? splitter
                                .splitLines(hint, room, net.minecraft.network.chat.Style.EMPTY)
                                .stream()
                                .map(net.minecraft.network.chat.FormattedText::getString)
                                .toList()
                        : java.util.List.<String>of();
        int y0 = y + (h - (labelLines.size() + hintLines.size()) * 11) / 2 + 1;
        int labelY = y0;
        for (String line : labelLines) {
            g.drawString(font, line, x + 26, y0, ink, false);
            y0 += 11;
        }
        int hintInk = primary && enabled ? BG : MUTED;
        for (String line : hintLines) {
            g.drawString(font, line, x + 26, y0, hintInk, false);
            y0 += 11;
        }
        if (!stacked && !hint.isEmpty())
            g.drawString(font, hint, x + w - 8 - font.width(hint), labelY, hintInk, false);
    }

    /** Table row height and numeric-column width: shared by menu layout and rendering. */
    public static final int TABLE_ROW_H = 16;

    public static final int TABLE_NUM_W = 72;

    /**
     * Table data row: rank, name (takes the slack, ellipsized), right-aligned numbers. Narrow
     * screens drop trailing-priority numbers first via {@link MenuLayout}. Truncated cells are
     * reported so the screen can show the full text in a shared tooltip.
     */
    public record TruncatedCell(int x, int y, int w, int h, String text) {}

    public static java.util.List<TruncatedCell> tableRow(
            GuiGraphics g,
            Font font,
            int x,
            int y,
            int w,
            java.util.List<String> cells,
            int rankInk,
            int nameInk,
            int numInk) {
        int nums = MenuLayout.tableNumbers(w, cells.size(), TABLE_NUM_W);
        int nameW = MenuLayout.tableNameWidth(w, nums, TABLE_NUM_W);
        var tips = new java.util.ArrayList<TruncatedCell>();
        int ty = y + (TABLE_ROW_H - 8) / 2;
        if (!cells.isEmpty()) g.drawString(font, cells.get(0), x + 6, ty, rankInk, false);
        if (cells.size() > 1) {
            String shown = ellipsize(font, cells.get(1), nameW);
            if (!shown.equals(cells.get(1)))
                tips.add(new TruncatedCell(x + 30, y, nameW, TABLE_ROW_H, cells.get(1)));
            g.drawString(font, shown, x + 30, ty, nameInk, false);
        }
        for (int i = 0; i < nums; i++) {
            String full = cells.get(2 + i);
            String shown = ellipsize(font, full, TABLE_NUM_W - 12);
            int right = x + w - 6 - (nums - 1 - i) * TABLE_NUM_W;
            if (!shown.equals(full))
                tips.add(
                        new TruncatedCell(
                                right - font.width(shown),
                                y,
                                font.width(shown),
                                TABLE_ROW_H,
                                full));
            g.drawString(font, shown, right - font.width(shown), ty, numInk, false);
        }
        return tips;
    }

    /**
     * Table row band: amber-edged CARD for the player's own row, graphite zebra otherwise. Screens
     * pass which row this is; style stays here.
     */
    public static void tableBand(GuiGraphics g, int x, int y, int w, boolean own, boolean zebra) {
        if (own) {
            g.fill(x, y, x + w, y + TABLE_ROW_H, CARD);
            g.fill(x, y, x + 2, y + TABLE_ROW_H, AMBER);
        } else if (zebra) {
            g.fill(x, y, x + w, y + TABLE_ROW_H, BG_ELEVATED);
        }
    }

    /**
     * Thin scrollbar in its own gutter: never sits over content (layout already narrowed the
     * content while scrolling). Screens pass the track x, viewport and content height.
     */
    public static void scrollbar(
            GuiGraphics g, int x, int top, int bottom, int contentH, int offset) {
        int viewportH = bottom - top;
        if (contentH <= viewportH) return;
        g.fill(x, top, x + 3, bottom, alpha(BORDER_SOFT, 0xC0));
        int thumbH = Math.max(20, viewportH * viewportH / contentH);
        int scrollRange = Math.max(1, contentH - viewportH);
        int thumbY = top + (viewportH - thumbH) * offset / scrollRange;
        g.fill(x, thumbY, x + 3, thumbY + thumbH, AMBER);
    }
}
