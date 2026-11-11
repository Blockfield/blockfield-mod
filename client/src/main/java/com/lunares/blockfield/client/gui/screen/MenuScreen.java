package com.lunares.blockfield.client.gui.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.gui.BfIcon;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.UiMotion;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import com.lunares.blockfield.network.packet.UiJson;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;

public class MenuScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int MAX_CONTENT_W = 760;
    private static final int MAX_COLUMNS = 2;
    private static final int BTN_H = 22;
    private static final int BTN_GAP = MenuLayout.BUTTON_GAP;
    private static final int TIMER_W = 140;
    private static final int TIMER_H = 40;
    private static final int SCROLL_STEP = 24;

    /**
     * CARD — крупная кнопка основного действия, CORNER — мелкая второстепенная в нижнем правом
     * углу.
     */
    private enum Kind {
        HEADER,
        LINE,
        BUTTON,
        CARD,
        CORNER,
        TABLE,
        PAGE
    }

    /** scale — label scale shared by a card section; unused by other kinds. */
    private record Item(
            Kind kind,
            int x,
            int y,
            int w,
            int h,
            String label,
            String code,
            JsonObject btn,
            float scale,
            int tableRow,
            boolean compactCard) {
        String action() {
            return kind == Kind.PAGE ? "page:" + code : str(btn, "action", "");
        }

        Item(Kind kind, int x, int y, int w, int h, String label, String code, JsonObject btn) {
            this(kind, x, y, w, h, label, code, btn, 0f, -1, false);
        }

        Item(
                Kind kind,
                int x,
                int y,
                int w,
                int h,
                String label,
                String code,
                JsonObject btn,
                float scale,
                boolean compactCard) {
            this(kind, x, y, w, h, label, code, btn, scale, -1, compactCard);
        }
    }

    /**
     * viewportTop/Bottom bound which items are actually clickable/visible (scrolled-out items are
     * neither).
     */
    private record Layout(
            int mouseX,
            int mouseY,
            int contentX,
            int contentWidth,
            int columns,
            int viewportTop,
            int viewportBottom,
            int contentHeight,
            int scrollOffset,
            List<Item> items,
            int pages) {}

    private record Geom(
            int columns,
            int colW,
            int col1X,
            List<MenuLayout.Placement> placements,
            int contentHeight) {}

    private JsonObject data = new JsonObject();
    private long timerEndMs = -1;
    private int scrollOffset = 0;
    private boolean paged;
    private int page;

    public void setPaged(boolean value) {
        paged = value;
        scrollOffset = 0;
    }

    /**
     * Экран этого меню никогда не становится реальным {@code Minecraft.screen} -- его хост
     * (планшет) рисует и кликает его вручную внутри своего кадра, поэтому «закрыть» -- это действие
     * хоста, а не {@code Minecraft.setScreen(null)} напрямую: иначе кнопка confirm обрывала бы
     * закрывающую анимацию хоста, минуя её.
     */
    private Runnable closeAction =
            () -> {
                if (this.minecraft != null) this.minecraft.setScreen(null);
            };

    public MenuScreen(String json) {
        super(Component.literal("Blockfield"));
        parse(json, true);
        entrance.to(1f, System.nanoTime());
    }

    public void setCloseAction(Runnable action) {
        this.closeAction = action;
    }

    public String id() {
        return str(this.data, "id", "");
    }

    private long lastAutoRefresh;
    private final java.util.Map<String, UiMotion> hoverMotion = new java.util.HashMap<>();
    private final UiMotion entrance = new UiMotion();
    private String focusedAction = "";
    private double previousMouseX = Double.NaN;
    private double previousMouseY = Double.NaN;

    public void update(String json) {
        parse(json, false);
    }

    private void parse(String json, boolean fresh) {
        JsonObject next;
        try {
            next = UiJson.parse(json);
        } catch (Exception e) {
            return;
        }
        this.data = next;
        if (fresh) page = 0;
        if (next.has("timer")) {
            int seconds = UiJson.integer(next, "timer", 0);
            // timer 0 = the server cleared the countdown; an empty "0 С" box only confuses.
            this.timerEndMs = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : -1;
        } else if (fresh) {
            this.timerEndMs = -1;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return UiJson.str(o, key, def);
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return UiJson.bool(o, key, def);
    }

    static boolean shouldCloseOnClick(JsonObject btn, JsonObject menu) {
        return bool(btn, "closeOnClick", bool(menu, "closeOnClick", false));
    }

    private int secondsLeft() {
        if (this.timerEndMs < 0) return -1;
        long ms = this.timerEndMs - System.currentTimeMillis();
        return ms <= 0 ? 0 : (int) ((ms + 999) / 1000);
    }

    private Geom geom(List<Integer> heights, int contentWidth, int contentX) {
        int columns = MenuLayout.columnsFor(contentWidth, MAX_COLUMNS);
        int colW = MenuLayout.columnWidth(contentWidth, columns);
        int col1X = contentX + colW + MenuLayout.COLUMN_GUTTER;
        List<MenuLayout.Placement> placements = MenuLayout.place(heights, columns);
        int contentHeight = MenuLayout.contentHeight(heights, placements, columns);
        return new Geom(columns, colW, col1X, placements, contentHeight);
    }

    private List<String> wrap(String text, int width) {
        if (text == null || text.isEmpty()) return List.of();
        return this.font.getSplitter().splitLines(text, Math.max(1, width), Style.EMPTY).stream()
                .map(FormattedText::getString)
                .toList();
    }

    private List<String> sectionLines(JsonObject section, int width) {
        List<String> lines = new ArrayList<>();
        for (JsonElement line : UiJson.arr(section, "lines")) {
            String text = UiJson.str(line, null);
            if (text != null) lines.addAll(wrap(text, width));
        }
        return lines;
    }

    private boolean stackedHint(JsonObject button, int width) {
        String hint = str(button, "hint", "");
        return !hint.isEmpty()
                && buttonLabelWidth(button) + this.font.width(hint) + 12
                        > width - MenuLayout.BUTTON_PAD_W;
    }

    private int buttonHeight(JsonObject button, int width) {
        int room = width - MenuLayout.BUTTON_PAD_W;
        int lines = wrap(str(button, "label", ""), room).size();
        if (bool(button, "destructive", false))
            lines = Math.max(lines, wrap(MenuLayout.CONFIRM_LABEL, room).size());
        if (stackedHint(button, width)) lines += wrap(str(button, "hint", ""), room).size();
        return Math.max(BTN_H, lines * 11 + 8);
    }

    private String buttonLabel(JsonObject b) {
        return bool(b, "destructive", false)
                        && DestructiveConfirm.armed(str(b, "action", ""), Util.getMillis())
                ? MenuLayout.CONFIRM_LABEL
                : str(b, "label", "");
    }

    private int buttonLabelWidth(JsonObject b) {
        int w = this.font.width(str(b, "label", ""));
        return bool(b, "destructive", false)
                ? Math.max(w, this.font.width(MenuLayout.CONFIRM_LABEL))
                : w;
    }

    private int buttonWidth(JsonObject b) {
        return MenuLayout.buttonWidth(
                this.font.width(str(b, "label", "")),
                bool(b, "destructive", false) ? this.font.width(MenuLayout.CONFIRM_LABEL) : 0);
    }

    private int buttonColumns(JsonObject section, int width, boolean grid) {
        int minimum = grid ? MenuLayout.GRID_MIN_W : 120;
        for (JsonObject button : UiJson.objects(UiJson.arr(section, "buttons")))
            minimum = Math.max(minimum, Math.min(250, buttonWidth(button)));
        return MenuLayout.buttonColumns(width, UiJson.arr(section, "buttons").size(), minimum);
    }

    private int buttonSectionHeight(JsonObject section, int width, boolean grid) {
        int columns = buttonColumns(section, width, grid);
        int buttonW = MenuLayout.segmentedButtonWidth(width, columns);
        int rowHeight = BTN_H;
        for (JsonObject button : UiJson.objects(UiJson.arr(section, "buttons")))
            rowHeight = Math.max(rowHeight, buttonHeight(button, buttonW));
        int count = UiJson.arr(section, "buttons").size();
        int lines = sectionLines(section, width).size();
        return (str(section, "label", null) == null ? 0 : 15)
                + lines * 11
                + (lines == 0 ? 0 : 3)
                + MenuLayout.cardRows(count, columns) * (rowHeight + BTN_GAP)
                + 10;
    }

    private List<Integer> flowHeights(List<JsonObject> sections, int width) {
        int columnWidth = MenuLayout.columnWidth(width, MenuLayout.columnsFor(width, MAX_COLUMNS));
        List<Integer> heights = new ArrayList<>();
        for (JsonObject section : sections) {
            int lines = sectionLines(section, columnWidth).size();
            int h =
                    (str(section, "label", null) == null ? 0 : 15)
                            + lines * 11
                            + (lines == 0 ? 0 : 3)
                            + 10;
            for (JsonObject button : UiJson.objects(UiJson.arr(section, "buttons")))
                h += buttonHeight(button, columnWidth) + BTN_GAP;
            heights.add(h);
        }
        return heights;
    }

    /**
     * Builds the whole screen geometry: content width/columns from the window size, section heights
     * and their height-balanced column assignment (see {@link MenuLayout}), the scrollable
     * viewport, and every header/line/button item positioned (and scroll-clamped) within it. Mouse
     * coordinates pass straight through: unlike the old fixed-canvas layout this uses the same
     * coordinate space GuiGraphics already renders in, so no extra scale is needed.
     *
     * <p>Column layout is computed twice: once at the full width to see whether the content
     * overflows the viewport, then again narrowed by {@link MenuLayout#SCROLLBAR_GUTTER} if it
     * does, so headers/codes/hints/buttons never sit under the scrollbar. contentX stays anchored
     * to the full-width centering in both passes, so the freed-up space always lands on the right
     * (where the bar is drawn), not split evenly by re-centering.
     */
    private Layout layout(double mouseX, double mouseY) {
        int fullWidth = Math.min(this.width - 2 * MARGIN, MAX_CONTENT_W);
        fullWidth =
                Math.max(fullWidth, Math.min(this.width - 2 * MARGIN, MenuLayout.MIN_COLUMN_WIDTH));
        int contentX = (this.width - fullWidth) / 2;

        List<JsonObject> cardSections = new ArrayList<>();
        List<JsonObject> segmentedSections = new ArrayList<>();
        List<JsonObject> gridSections = new ArrayList<>();
        List<JsonObject> tableSections = new ArrayList<>();
        List<JsonObject> flow = new ArrayList<>();
        List<JsonObject> cornerButtons = new ArrayList<>();
        for (JsonElement se : UiJson.arr(this.data, "sections")) {
            JsonObject section = UiJson.asObject(se);
            if (section == null) continue;
            switch (str(section, "style", "")) {
                case "cards" -> cardSections.add(section);
                case "segmented" -> segmentedSections.add(section);
                case "grid" -> gridSections.add(section);
                case "table" -> tableSections.add(section);
                case "corner" -> {
                    cornerButtons.addAll(UiJson.objects(UiJson.arr(section, "buttons")));
                }
                default -> flow.add(section);
            }
        }
        List<Integer> heights = flowHeights(flow, fullWidth);

        String footer = str(this.data, "footer", null);
        int viewportBottom =
                this.height
                        - (footer != null ? wrap(footer, fullWidth).size() * 11 + 9 : 8)
                        - 8
                        - (cornerButtons.isEmpty()
                                ? 0
                                : MenuLayout.CORNER_H + MenuLayout.CORNER_GAP);

        Geom g = geom(heights, fullWidth, contentX);
        int contentTop = headerBlockBottom(fullWidth) + 10;
        int viewportH = Math.max(0, viewportBottom - contentTop);
        int stackedH =
                cardsHeight(cardSections, fullWidth)
                        + segmentedHeight(segmentedSections, fullWidth)
                        + gridHeight(gridSections, fullWidth)
                        + tablesHeight(tableSections);
        boolean scrollable =
                MenuLayout.withoutTrailingGap(stackedH + g.contentHeight()) > viewportH;

        boolean showPages = paged && scrollable;
        if (showPages) {
            viewportBottom -= BTN_H + 8;
            viewportH = Math.max(1, viewportBottom - contentTop);
        }
        int contentWidth = MenuLayout.contentWidthFor(fullWidth, scrollable && !paged);
        if (contentWidth != fullWidth) {
            g = geom(flowHeights(flow, contentWidth), contentWidth, contentX);
            contentTop = headerBlockBottom(contentWidth) + 10;
            viewportH = Math.max(0, viewportBottom - contentTop);
            stackedH =
                    cardsHeight(cardSections, contentWidth)
                            + segmentedHeight(segmentedSections, contentWidth)
                            + gridHeight(gridSections, contentWidth)
                            + tablesHeight(tableSections);
        }
        int viewportTop = contentTop;
        int totalHeight = MenuLayout.withoutTrailingGap(stackedH + g.contentHeight());
        this.scrollOffset =
                paged ? 0 : MenuLayout.clampScroll(this.scrollOffset, totalHeight, viewportH);
        // Экран из одних карточек (хаб, Casual) — карточки по центру, а не прижаты к заголовку.
        int top =
                viewportTop
                        - this.scrollOffset
                        + (flow.isEmpty() ? MenuLayout.centerOffset(totalHeight, viewportH) : 0);

        List<Item> items = new ArrayList<>();
        int y = top;
        for (JsonObject section : cardSections) {
            String label = str(section, "label", null);
            if (label != null) {
                items.add(
                        new Item(
                                Kind.HEADER,
                                contentX,
                                y,
                                contentWidth,
                                9,
                                label,
                                str(section, "code", ""),
                                null));
                y += 15;
            }
            List<JsonObject> cards = new ArrayList<>();
            cards.addAll(UiJson.objects(UiJson.arr(section, "buttons")));
            int perRow = MenuLayout.cardsPerRow(contentWidth, cards.size(), cardTextWidth(cards));
            // A lone card is as wide as one of a pair, not a full-width bar.
            int slots =
                    cards.size() == 1
                                    && cardTextWidth(cards)
                                            <= (contentWidth - MenuLayout.CARD_GAP) / 2
                            ? MenuLayout.cardsPerRow(contentWidth, 2)
                            : perRow;
            int cardW = (contentWidth - (slots - 1) * MenuLayout.CARD_GAP) / slots;
            int cardH = cardHeight(cards, cardW);
            boolean compact = MenuLayout.compactCards(cards.size());
            float scale = cardLabelScale(cards, cardW, compact);
            for (int i = 0; i < cards.size(); i++) {
                // A shorter last row (5 modes: 3+2) sits centred under the full one.
                int inRow = Math.min(perRow, cards.size() - (i / perRow) * perRow);
                int cx =
                        contentX
                                + (perRow - inRow) * (cardW + MenuLayout.CARD_GAP) / 2
                                + (i % perRow) * (cardW + MenuLayout.CARD_GAP);
                int cy = y + (i / perRow) * (cardH + MenuLayout.CARD_GAP);
                items.add(
                        new Item(
                                Kind.CARD,
                                cx,
                                cy,
                                cardW,
                                cardH,
                                null,
                                null,
                                cards.get(i),
                                scale,
                                compact));
            }
            int rows = MenuLayout.cardRows(cards.size(), perRow);
            y += rows * cardH + Math.max(0, rows - 1) * MenuLayout.CARD_GAP;
            List<String> lines = sectionLines(section, contentWidth);
            if (lines.size() > 0) y += 6;
            for (String line : lines) {
                items.add(new Item(Kind.LINE, contentX, y, contentWidth, 8, line, null, null));
                y += 11;
            }
            y += 10;
        }

        for (JsonObject section : segmentedSections) {
            String label = str(section, "label", null);
            int sectionStartY = y;
            if (label != null) {
                items.add(
                        new Item(
                                Kind.HEADER,
                                contentX,
                                y,
                                contentWidth,
                                9,
                                label,
                                str(section, "code", ""),
                                null));
                y += 15;
            }
            List<String> lines = sectionLines(section, contentWidth);
            for (String line : lines) {
                items.add(new Item(Kind.LINE, contentX, y, contentWidth, 8, line, null, null));
                y += 11;
            }
            if (!lines.isEmpty()) y += 3;
            List<JsonObject> variants = new ArrayList<>();
            variants.addAll(UiJson.objects(UiJson.arr(section, "buttons")));
            int columns = buttonColumns(section, contentWidth, false);
            int w = MenuLayout.segmentedButtonWidth(contentWidth, columns);
            int rowHeight = BTN_H;
            for (JsonObject variant : variants)
                rowHeight = Math.max(rowHeight, buttonHeight(variant, w));
            for (int i = 0; i < variants.size(); i++) {
                int x = contentX + (i % columns) * (w + MenuLayout.BUTTON_GAP);
                items.add(
                        new Item(
                                Kind.BUTTON,
                                x,
                                y + (i / columns) * (rowHeight + BTN_GAP),
                                w,
                                rowHeight,
                                null,
                                null,
                                variants.get(i)));
            }
            // Та же формула, что и в segmentedHeight(): резерв и фактическая раскладка не должны
            // разойтись.
            y = sectionStartY + buttonSectionHeight(section, contentWidth, false);
        }

        for (JsonObject section : gridSections) {
            String label = str(section, "label", null);
            int sectionStartY = y;
            if (label != null) {
                items.add(
                        new Item(
                                Kind.HEADER,
                                contentX,
                                y,
                                contentWidth,
                                9,
                                label,
                                str(section, "code", ""),
                                null));
                y += 15;
            }
            List<String> lines = sectionLines(section, contentWidth);
            for (String line : lines) {
                items.add(new Item(Kind.LINE, contentX, y, contentWidth, 8, line, null, null));
                y += 11;
            }
            if (lines.size() > 0) y += 3;
            List<JsonObject> buttons = new ArrayList<>();
            buttons.addAll(UiJson.objects(UiJson.arr(section, "buttons")));
            int columns = buttonColumns(section, contentWidth, true);
            int w = MenuLayout.segmentedButtonWidth(contentWidth, columns);
            int rowHeight = BTN_H;
            for (JsonObject button : buttons)
                rowHeight = Math.max(rowHeight, buttonHeight(button, w));
            for (int i = 0; i < buttons.size(); i++) {
                int x = contentX + (i % columns) * (w + MenuLayout.BUTTON_GAP);
                items.add(
                        new Item(
                                Kind.BUTTON,
                                x,
                                y + (i / columns) * (rowHeight + BTN_GAP),
                                w,
                                rowHeight,
                                null,
                                null,
                                buttons.get(i)));
            }
            // Same formula as gridHeight(): the reserved and the laid-out height must not drift
            // apart.
            y = sectionStartY + buttonSectionHeight(section, contentWidth, true);
        }

        for (JsonObject section : tableSections) {
            String label = str(section, "label", null);
            if (label != null) {
                items.add(
                        new Item(
                                Kind.HEADER,
                                contentX,
                                y,
                                contentWidth,
                                9,
                                label,
                                str(section, "code", ""),
                                null));
                y += 15;
            }
            items.add(new Item(Kind.TABLE, contentX, y, contentWidth, ROW_H, null, null, section));
            y += ROW_H;
            int rows = Math.max(1, UiJson.arr(section, "rows").size());
            for (int row = 0; row < rows; row++, y += ROW_H)
                items.add(
                        new Item(
                                Kind.TABLE,
                                contentX,
                                y,
                                contentWidth,
                                ROW_H,
                                null,
                                null,
                                section,
                                0f,
                                row,
                                false));
            y += 10;
        }

        for (int i = 0; i < flow.size(); i++) {
            JsonObject section = flow.get(i);
            MenuLayout.Placement p = g.placements().get(i);
            int x = p.column() == 0 ? contentX : g.col1X();
            y = top + stackedH + p.y();

            String label = str(section, "label", null);
            if (label != null) {
                items.add(
                        new Item(
                                Kind.HEADER,
                                x,
                                y,
                                g.colW(),
                                9,
                                label,
                                str(section, "code", ""),
                                null));
                y += 15;
            }
            List<String> lines = sectionLines(section, g.colW());
            for (String line : lines) {
                items.add(new Item(Kind.LINE, x, y, g.colW(), 8, line, null, null));
                y += 11;
            }
            if (lines.size() > 0) y += 3;
            for (JsonObject be : UiJson.objects(UiJson.arr(section, "buttons"))) {
                int buttonH = buttonHeight(be, g.colW());
                items.add(new Item(Kind.BUTTON, x, y, g.colW(), buttonH, null, null, be));
                y += buttonH + BTN_GAP;
            }
        }

        List<Integer> offsets =
                showPages
                        ? MenuLayout.pageOffsets(
                                items.stream()
                                        .map(
                                                it ->
                                                        new MenuLayout.Span(
                                                                it.y() - viewportTop, it.h()))
                                        .toList(),
                                viewportH)
                        : List.of(0);
        if (showPages) {
            // Tablet.fitMenu lays out unpaged first on every update; clamping there lost the page.
            page = Math.max(0, Math.min(page, offsets.size() - 1));
            int offset = offsets.get(page);
            items.replaceAll(
                    it ->
                            new Item(
                                    it.kind(),
                                    it.x(),
                                    it.y() - offset,
                                    it.w(),
                                    it.h(),
                                    it.label(),
                                    it.code(),
                                    it.btn(),
                                    it.scale(),
                                    it.tableRow(),
                                    it.compactCard()));
            int pagerX = contentX + (contentWidth - 200) / 2;
            items.add(
                    new Item(
                            Kind.PAGE,
                            pagerX,
                            viewportBottom + 4,
                            70,
                            BTN_H,
                            "Назад",
                            "prev",
                            null));
            items.add(
                    new Item(
                            Kind.PAGE,
                            pagerX + 130,
                            viewportBottom + 4,
                            70,
                            BTN_H,
                            "Далее",
                            "next",
                            null));
        }
        int right = contentX + fullWidth;
        for (int i = cornerButtons.size() - 1; i >= 0; i--) {
            JsonObject b = cornerButtons.get(i);
            int w = buttonWidth(b);
            right -= w;
            items.add(
                    new Item(
                            Kind.CORNER,
                            right,
                            viewportBottom + (showPages ? BTN_H + 8 : 0) + MenuLayout.CORNER_GAP,
                            w,
                            MenuLayout.CORNER_H,
                            null,
                            null,
                            b));
            right -= MenuLayout.CORNER_GAP;
        }

        return new Layout(
                (int) mouseX,
                (int) mouseY,
                contentX,
                contentWidth,
                g.columns(),
                viewportTop,
                viewportBottom,
                totalHeight,
                this.scrollOffset,
                items,
                offsets.size());
    }

    /**
     * Largest label scale (down to 1x) at which every card label of the section fits; one size per
     * row.
     */
    private float cardLabelScale(List<JsonObject> cards, int w, boolean compact) {
        int room = w - 2 * MenuLayout.cardPadding(compact);
        float scale = compact ? 1.5f : 2.0f;
        for (JsonObject card : cards) {
            int width = this.font.width(str(card, "label", ""));
            while (scale > 1.0f && width * scale > room) scale -= 0.25f;
        }
        return scale;
    }

    /** Widest label (at 1x, the smallest scale) or hint of the cards, plus the card padding. */
    private int cardTextWidth(List<JsonObject> cards) {
        int widest = 0;
        for (JsonObject card : cards) {
            widest = Math.max(widest, this.font.width(str(card, "label", "")));
            widest = Math.max(widest, this.font.width(str(card, "hint", "")));
        }
        return widest + 2 * MenuLayout.cardPadding(MenuLayout.compactCards(cards.size()));
    }

    private int cardHeight(List<JsonObject> cards, int width) {
        int height = MenuLayout.cardHeight(cards.size());
        // Text can grow a compact card past CARD_H without changing its padding, icon or scale.
        boolean compact = MenuLayout.compactCards(cards.size());
        int room = width - 2 * MenuLayout.cardPadding(compact);
        float scale = cardLabelScale(cards, width, compact);
        for (JsonObject card : cards) {
            int labels = wrap(str(card, "label", ""), (int) (room / scale)).size();
            int hints = wrap(str(card, "hint", ""), room).size();
            height =
                    Math.max(
                            height,
                            MenuLayout.cardHeight(
                                    cards.size(),
                                    (int) Math.ceil(Math.max(0, labels * 11 - 2) * scale),
                                    Math.max(0, hints * 11 - 3)));
        }
        return height;
    }

    private int cardsHeight(List<JsonObject> cardSections, int width) {
        int h = 0;
        for (JsonObject section : cardSections) {
            List<JsonObject> cards = new ArrayList<>();
            cards.addAll(UiJson.objects(UiJson.arr(section, "buttons")));
            int perRow = MenuLayout.cardsPerRow(width, cards.size(), cardTextWidth(cards));
            int slots =
                    cards.size() == 1 && cardTextWidth(cards) <= (width - MenuLayout.CARD_GAP) / 2
                            ? MenuLayout.cardsPerRow(width, 2)
                            : perRow;
            int cardW = (width - (slots - 1) * MenuLayout.CARD_GAP) / slots;
            int rows = MenuLayout.cardRows(cards.size(), perRow);
            int lines = sectionLines(section, width).size();
            h +=
                    (str(section, "label", null) == null ? 0 : 15)
                            + rows * cardHeight(cards, cardW)
                            + Math.max(0, rows - 1) * MenuLayout.CARD_GAP
                            + (lines == 0 ? 0 : 6 + lines * 11)
                            + 10;
        }
        return h;
    }

    private static final int ROW_H = 16;
    private static final int NUM_COL_W = 72;

    /** Header row, data rows (or one note row when empty), trailing section gap. */
    private static int tableHeight(JsonObject section) {
        int rows = Math.max(1, UiJson.arr(section, "rows").size());
        return (str(section, "label", null) != null ? 15 : 0) + ROW_H + rows * ROW_H + 10;
    }

    private static int tablesHeight(List<JsonObject> sections) {
        int h = 0;
        for (JsonObject section : sections) h += tableHeight(section);
        return h;
    }

    private void renderTable(GuiGraphics g, Item it) {
        JsonObject section = it.btn();
        JsonArray rows = UiJson.arr(section, "rows");
        int y = it.y();
        if (it.tableRow() < 0) {
            tableRow(
                    g,
                    it,
                    y,
                    UiJson.arr(section, "columns"),
                    BfTheme.MUTED_DARK,
                    BfTheme.MUTED_DARK,
                    BfTheme.MUTED_DARK);
            g.fill(it.x(), y + ROW_H - 1, it.x() + it.w(), y + ROW_H, BfTheme.BORDER);
            return;
        }
        if (rows.isEmpty()) {
            drawLines(
                    g,
                    str(section, "empty", ""),
                    it.x() + 6,
                    y + (ROW_H - 8) / 2,
                    it.w() - 12,
                    BfTheme.MUTED);
            return;
        }
        JsonArray cells = UiJson.asArray(rows.get(it.tableRow()));
        if (cells == null) return;
        String own =
                this.minecraft != null && this.minecraft.player != null
                        ? this.minecraft.player.getGameProfile().getName()
                        : "";
        boolean mine = cells.size() > 1 && own.equals(UiJson.str(cells.get(1), ""));
        if (mine) ownRowSurface(g, it, y);
        else if (it.tableRow() % 2 == 1)
            g.fill(it.x(), y, it.x() + it.w(), y + ROW_H, BfTheme.BG_ELEVATED);
        boolean podium =
                cells.size() > 0 && List.of("1", "2", "3").contains(UiJson.str(cells.get(0), ""));
        tableRow(
                g,
                it,
                y,
                cells,
                podium ? BfTheme.AMBER : BfTheme.MUTED,
                mine ? BfTheme.AMBER : BfTheme.TEXT,
                BfTheme.TEXT_SECONDARY);
    }

    private void ownRowSurface(GuiGraphics g, Item it, int y) {
        g.fill(it.x(), y, it.x() + it.w(), y + ROW_H, BfTheme.CARD);
        g.fill(it.x(), y, it.x() + 2, y + ROW_H, BfTheme.AMBER);
    }

    /**
     * First cell is the rank, second the name (takes the slack), the rest are right-aligned
     * numbers.
     */
    private void tableRow(
            GuiGraphics g, Item it, int y, JsonArray cells, int rankInk, int nameInk, int numInk) {
        int nums = Math.max(0, cells.size() - 2);
        // Narrow GUI scales keep rank, name and as many trailing-priority numbers as fit beside a
        // readable name.
        while (nums > 1 && it.w() - 30 - nums * NUM_COL_W < 90) nums--;
        int ty = y + (ROW_H - 8) / 2;
        if (cells.size() > 0)
            g.drawString(this.font, UiJson.str(cells.get(0), ""), it.x() + 6, ty, rankInk, false);
        int nameW = it.w() - 30 - nums * NUM_COL_W - 6;
        if (cells.size() > 1)
            g.drawString(
                    this.font,
                    BfTheme.ellipsize(this.font, UiJson.str(cells.get(1), ""), nameW),
                    it.x() + 30,
                    ty,
                    nameInk,
                    false);
        for (int i = 0; i < nums; i++) {
            String cell =
                    BfTheme.ellipsize(this.font, UiJson.str(cells.get(2 + i), ""), NUM_COL_W - 12);
            int right = it.x() + it.w() - 6 - (nums - 1 - i) * NUM_COL_W;
            g.drawString(this.font, cell, right - this.font.width(cell), ty, numInk, false);
        }
    }

    private int gridHeight(List<JsonObject> sections, int width) {
        return sections.stream()
                .mapToInt(section -> buttonSectionHeight(section, width, true))
                .sum();
    }

    private int segmentedHeight(List<JsonObject> sections, int width) {
        return sections.stream()
                .mapToInt(section -> buttonSectionHeight(section, width, false))
                .sum();
    }

    private boolean timerBeside(int width) {
        int title =
                2 * this.font.width(str(this.data, "title", ""))
                        + 24
                        + this.font.width(str(this.data, "code", ""));
        return width - TIMER_W - 12
                >= Math.max(title, this.font.width(str(this.data, "subtitle", "")));
    }

    private boolean codeBeside(int width) {
        return str(this.data, "code", "").isEmpty()
                || 2 * this.font.width(str(this.data, "title", ""))
                                + this.font.width(str(this.data, "code", ""))
                                + 12
                        <= width;
    }

    private int titleBottom(int width) {
        int codeW = codeBeside(width) ? this.font.width(str(this.data, "code", "")) + 12 : 0;
        return 12
                + Math.max(
                                1,
                                wrap(str(this.data, "title", "BLOCKFIELD"), (width - codeW) / 2)
                                        .size())
                        * 22;
    }

    private int headerTextBottom(int width) {
        int y = titleBottom(width);
        if (!codeBeside(width)) y += 4 + wrap(str(this.data, "code", ""), width).size() * 11;
        if (!str(this.data, "subtitle", "").isEmpty())
            y += 4 + wrap(str(this.data, "subtitle", ""), width).size() * 11;
        return y;
    }

    private int headerBlockBottom(int width) {
        boolean beside = secondsLeft() >= 0 && timerBeside(width);
        int textBottom = headerTextBottom(width - (beside ? TIMER_W + 12 : 0));
        if (secondsLeft() < 0) return textBottom;
        return beside ? Math.max(textBottom, 14 + TIMER_H) : textBottom + 6 + TIMER_H;
    }

    private int drawLines(GuiGraphics g, String text, int x, int y, int width, int ink) {
        for (String line : wrap(text, width)) {
            g.drawString(this.font, line, x, y, ink, false);
            y += 11;
        }
        return y;
    }

    /**
     * На сколько пикселей содержимое не влезает в видимую область при текущем размере экрана.
     * Планшет по этому числу подбирает виртуальный размер меню: прокрутки внутри планшета нет.
     */
    public int overflowHeight() {
        Layout l = layout(-1, -1);
        return Math.max(0, l.contentHeight() - (l.viewportBottom() - l.viewportTop()));
    }

    private static boolean itemVisible(Item it, Layout l) {
        if (it.kind() == Kind.CORNER || it.kind() == Kind.PAGE) return true;
        return it.y() >= l.viewportTop() && it.y() + it.h() <= l.viewportBottom();
    }

    @Override
    protected void init() {
        this.font = BfTheme.font();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Живой режим: меню обновляется само, пока открыто.
        if (bool(this.data, "live", false)
                && this.minecraft != null
                && this.minecraft.player != null
                && System.currentTimeMillis() - lastAutoRefresh > 3000) {
            lastAutoRefresh = System.currentTimeMillis();
            ClientPlayNetworking.send(new MenuActionC2SPacket("menu_auto"));
        }
        // Опаковая (не полупрозрачная) заливка: HUD (сердца/еда/хотбар) рисуется до экрана и
        // просвечивал бы через полупрозрачный SCRIM, как у
        // ClassSelectionScreen/SpawnSelectionScreen.
        guiGraphics.fill(0, 0, this.width, this.height, BfTheme.BG);

        // The moving glass changes local coordinates even when the physical cursor stays still.
        double pointerX = this.minecraft.mouseHandler.xpos();
        double pointerY = this.minecraft.mouseHandler.ypos();
        if (pointerX != previousMouseX || pointerY != previousMouseY) focusedAction = "";
        previousMouseX = pointerX;
        previousMouseY = pointerY;
        Layout l = layout(mouseX, mouseY);
        boolean wide = secondsLeft() >= 0 && timerBeside(l.contentWidth());

        int titleWidth = l.contentWidth() - (wide ? TIMER_W + 12 : 0);
        String code = str(this.data, "code", "");
        boolean inlineCode = codeBeside(titleWidth);
        int codeW = inlineCode ? this.font.width(code) + 12 : 0;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(l.contentX(), 12, 0);
        guiGraphics.pose().scale(2f, 2f, 1f);
        drawLines(
                guiGraphics,
                str(this.data, "title", "BLOCKFIELD"),
                0,
                0,
                (titleWidth - codeW) / 2,
                BfTheme.TEXT);
        guiGraphics.pose().popPose();
        int afterSubtitleY = titleBottom(titleWidth);
        if (inlineCode)
            guiGraphics.drawString(
                    this.font,
                    code,
                    l.contentX() + titleWidth - this.font.width(code),
                    18,
                    BfTheme.MUTED,
                    false);
        else
            afterSubtitleY =
                    drawLines(
                            guiGraphics,
                            code,
                            l.contentX(),
                            afterSubtitleY + 4,
                            titleWidth,
                            BfTheme.MUTED);
        String subtitle = str(this.data, "subtitle", "");
        if (!subtitle.isEmpty())
            afterSubtitleY =
                    drawLines(
                            guiGraphics,
                            subtitle,
                            l.contentX(),
                            afterSubtitleY + 4,
                            titleWidth,
                            BfTheme.MUTED);
        int left = secondsLeft();
        if (left >= 0) {
            int timerY = wide ? 14 : afterSubtitleY + 6;
            renderTimer(guiGraphics, l.contentX() + l.contentWidth() - TIMER_W, timerY, left);
        }

        guiGraphics.enableScissor(
                l.contentX(), l.viewportTop(), l.contentX() + l.contentWidth(), l.viewportBottom());
        for (Item it : l.items()) {
            if (!itemVisible(it, l)) continue;
            switch (it.kind()) {
                case HEADER ->
                        BfTheme.header(
                                guiGraphics,
                                this.font,
                                it.x(),
                                it.y(),
                                it.w(),
                                it.label(),
                                it.code());
                case LINE ->
                        guiGraphics.drawString(
                                this.font, it.label(), it.x(), it.y(), BfTheme.MUTED, false);
                case BUTTON -> renderButton(guiGraphics, it, l);
                case CARD -> renderCard(guiGraphics, it, l);
                case CORNER, PAGE -> {}
                case TABLE -> renderTable(guiGraphics, it);
            }
        }
        guiGraphics.disableScissor();
        for (Item it : l.items()) {
            if (it.kind() == Kind.CORNER) renderCorner(guiGraphics, it, l);
            if (it.kind() == Kind.PAGE) {
                boolean enabled = it.code().equals("prev") ? page > 0 : page + 1 < l.pages();
                BfTheme.button(
                        guiGraphics,
                        this.font,
                        it.x(),
                        it.y(),
                        it.w(),
                        it.h(),
                        it.label(),
                        BfTheme.hit(mouseX, mouseY, it.x(), it.y(), it.w(), it.h())
                                || it.action().equals(focusedAction),
                        enabled,
                        false);
            }
        }

        if (l.pages() > 1)
            guiGraphics.drawCenteredString(
                    this.font,
                    (page + 1) + " / " + l.pages(),
                    l.contentX() + l.contentWidth() / 2,
                    l.viewportBottom() + 11,
                    BfTheme.MUTED);
        if (!paged) renderScrollCue(guiGraphics, l);

        String footer = str(this.data, "footer", null);
        if (footer != null)
            drawLines(
                    guiGraphics,
                    footer,
                    l.contentX(),
                    this.height - 9 - wrap(footer, l.contentWidth()).size() * 11,
                    l.contentWidth(),
                    BfTheme.MUTED);

        float reveal = entrance.value(System.nanoTime());
        if (reveal < 1f) {
            guiGraphics.flush();
            guiGraphics.fill(
                    0,
                    0,
                    this.width,
                    this.height,
                    BfTheme.alpha(BfTheme.BG, Math.round(190 * (1f - reveal))));
        }
    }

    private void renderTimer(GuiGraphics g, int x, int y, int secondsLeft) {
        BfTheme.panel(g, x, y, TIMER_W, TIMER_H, true);
        BfTheme.ticks(g, x, y, TIMER_W, TIMER_H, 5, BfTheme.AMBER);
        String secs = secondsLeft + " С";
        g.pose().pushPose();
        g.pose().translate(x + TIMER_W / 2.0f, y + 12, 0);
        g.pose().scale(1.4f, 1.4f, 1.0f);
        g.drawString(this.font, secs, -this.font.width(secs) / 2, 0, BfTheme.AMBER, false);
        g.pose().popPose();
        g.drawCenteredString(
                this.font, "ДО КОНЦА", x + TIMER_W / 2, y + TIMER_H - 11, BfTheme.MUTED);
    }

    /**
     * Thin scrollbar in the gutter {@link MenuLayout#contentWidthFor} already reserved to its
     * right, so it never sits over headers/codes/hints/buttons (which were laid out narrower
     * whenever this is visible).
     */
    private void renderScrollCue(GuiGraphics g, Layout l) {
        int viewportH = l.viewportBottom() - l.viewportTop();
        if (l.contentHeight() <= viewportH) return;
        int w = MenuLayout.SCROLLBAR_WIDTH;
        int trackX = l.contentX() + l.contentWidth() + MenuLayout.SCROLLBAR_GAP;
        g.fill(
                trackX,
                l.viewportTop(),
                trackX + w,
                l.viewportBottom(),
                BfTheme.alpha(BfTheme.BORDER_SOFT, 0xC0));
        int thumbH = Math.max(20, viewportH * viewportH / l.contentHeight());
        int scrollRange = Math.max(1, l.contentHeight() - viewportH);
        int thumbY = l.viewportTop() + (viewportH - thumbH) * l.scrollOffset() / scrollRange;
        g.fill(trackX, thumbY, trackX + w, thumbY + thumbH, BfTheme.AMBER);
    }

    private float hover(Item it, Layout l) {
        String action = str(it.btn(), "action", "");
        boolean active =
                bool(it.btn(), "enabled", true)
                        && (action.equals(focusedAction)
                                || BfTheme.hit(
                                        l.mouseX(), l.mouseY(), it.x(), it.y(), it.w(), it.h()));
        UiMotion motion = hoverMotion.computeIfAbsent(action, key -> new UiMotion());
        long now = System.nanoTime();
        motion.to(active ? 1f : 0f, now);
        return motion.value(now);
    }

    private void surface(GuiGraphics g, Item it, float hover) {
        boolean enabled = bool(it.btn(), "enabled", true);
        boolean selected = bool(it.btn(), "selected", false);
        int accent = bool(it.btn(), "destructive", false) ? BfTheme.DANGER : BfTheme.AMBER;
        g.fill(
                it.x(),
                it.y(),
                it.x() + it.w(),
                it.y() + it.h(),
                enabled ? BfTheme.mix(BfTheme.CARD, BfTheme.POPOVER, hover) : BfTheme.BG_ELEVATED);
        BfTheme.outline(
                g,
                it.x(),
                it.y(),
                it.w(),
                it.h(),
                enabled
                        ? BfTheme.mix(BfTheme.BORDER, accent, selected ? 1f : hover)
                        : BfTheme.BORDER_SOFT);
        if (hover > 0f || selected) {
            g.fill(
                    it.x(),
                    it.y() + it.h() - 1,
                    it.x() + Math.round(it.w() * (selected ? 1f : hover)),
                    it.y() + it.h(),
                    accent);
        }
    }

    private void renderCard(GuiGraphics g, Item it, Layout l) {
        JsonObject b = it.btn();
        boolean enabled = bool(b, "enabled", true);
        float hover = hover(it, l);
        surface(g, it, hover);
        int pad = MenuLayout.cardPadding(it.compactCard());
        float iconSize = it.compactCard() ? 20 : 30;
        BfIcon icon = enabled ? BfIcon.forAction(str(b, "action", "")) : BfIcon.LOCK;
        icon.draw(
                g,
                it.x() + pad,
                it.y() + pad,
                iconSize,
                enabled
                        ? BfTheme.mix(BfTheme.TEXT_SECONDARY, BfTheme.AMBER, hover)
                        : BfTheme.MUTED);
        BfIcon.ARROW.draw(
                g,
                it.x() + it.w() - pad - 12 + 2 * hover,
                it.y() + pad,
                12,
                enabled ? BfTheme.mix(BfTheme.MUTED, BfTheme.AMBER, hover) : BfTheme.MUTED_DARK);
        int labelY = it.y() + pad + MenuLayout.cardLabelTop(it.compactCard());
        float scale = it.scale();
        int room = it.w() - 2 * pad;
        g.pose().pushPose();
        g.pose().translate(it.x() + pad, labelY, 0);
        g.pose().scale(scale, scale, 1f);
        drawLines(
                g,
                str(b, "label", ""),
                0,
                0,
                (int) (room / scale),
                enabled ? BfTheme.TEXT : BfTheme.MUTED);
        g.pose().popPose();
        String hint = str(b, "hint", "");
        drawLines(
                g,
                hint,
                it.x() + pad,
                it.y() + it.h() - pad - wrap(hint, room).size() * 11 + 3,
                room,
                BfTheme.MUTED);
    }

    private void renderCorner(GuiGraphics g, Item it, Layout l) {
        renderButton(g, it, l);
    }

    private void renderButton(GuiGraphics g, Item it, Layout l) {
        JsonObject b = it.btn();
        boolean enabled = bool(b, "enabled", true);
        boolean primary = bool(b, "primary", false);
        boolean destructive = bool(b, "destructive", false);
        float hover = hover(it, l);
        surface(g, it, hover);
        if (primary && enabled && !destructive)
            g.fill(
                    it.x(),
                    it.y(),
                    it.x() + it.w(),
                    it.y() + it.h(),
                    BfTheme.mix(BfTheme.AMBER, BfTheme.AMBER_BRIGHT, hover));
        int ink =
                !enabled
                        ? BfTheme.MUTED
                        : destructive ? BfTheme.DANGER : primary ? BfTheme.BG : BfTheme.TEXT;
        BfIcon icon = enabled ? BfIcon.forAction(str(b, "action", "")) : BfIcon.LOCK;
        icon.draw(g, it.x() + 7, it.y() + (it.h() - 11) / 2f, 11, ink);
        String label = buttonLabel(b);
        String hint = str(b, "hint", "");
        boolean stacked = stackedHint(b, it.w());
        int room = it.w() - MenuLayout.BUTTON_PAD_W;
        int lines = wrap(label, room).size() + (stacked ? wrap(hint, room).size() : 0);
        int y = it.y() + (it.h() - lines * 11) / 2 + 1;
        int nextY = drawLines(g, label, it.x() + 26, y, room, ink);
        if (stacked)
            drawLines(
                    g,
                    hint,
                    it.x() + 26,
                    nextY,
                    room,
                    primary && enabled ? BfTheme.BG : BfTheme.MUTED);
        else if (!hint.isEmpty())
            g.drawString(
                    this.font,
                    hint,
                    it.x() + it.w() - 8 - this.font.width(hint),
                    y,
                    primary && enabled ? BfTheme.BG : BfTheme.MUTED,
                    false);
    }

    private void activate(JsonObject button) {
        String action = str(button, "action", "");
        if (bool(button, "destructive", false)
                && !DestructiveConfirm.confirm(action, Util.getMillis())) return;
        if (!action.isEmpty() && this.minecraft != null && this.minecraft.player != null) {
            // The server lists this row only when AI is available; the sheet itself explains a
            // stale client flag instead of a click that silently does nothing.
            if (action.equals("workshop:ai")) {
                this.minecraft.setScreen(
                        new com.lunares.blockfield.client.workshop.WorkshopAiScreen());
                return;
            }
            ClientPlayNetworking.send(new MenuActionC2SPacket(action));
            if (shouldCloseOnClick(button, this.data)) closeAction.run();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        Layout l = layout(mouseX, mouseY);
        for (Item it : l.items()) {
            if (it.kind() == Kind.PAGE
                    && BfTheme.hit(l.mouseX(), l.mouseY(), it.x(), it.y(), it.w(), it.h())) {
                page =
                        Math.max(
                                0,
                                Math.min(
                                        l.pages() - 1, page + (it.code().equals("next") ? 1 : -1)));
                focusedAction = "";
                return true;
            }
            if (it.kind() != Kind.BUTTON && it.kind() != Kind.CARD && it.kind() != Kind.CORNER)
                continue;
            if (!itemVisible(it, l)) continue;
            if (!BfTheme.hit(l.mouseX(), l.mouseY(), it.x(), it.y(), it.w(), it.h())) continue;
            if (!bool(it.btn(), "enabled", true)) return true;

            activate(it.btn());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (paged) return false;
        this.scrollOffset -= (int) Math.round(scrollY * SCROLL_STEP);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB
                || keyCode == GLFW.GLFW_KEY_ENTER
                || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            Layout l = layout(-1, -1);
            List<Item> buttons =
                    l.items().stream()
                            .filter(
                                    it ->
                                            !it.action().isEmpty()
                                                    && (it.kind() == Kind.PAGE
                                                            ? (it.code().equals("prev")
                                                                    ? page > 0
                                                                    : page + 1 < l.pages())
                                                            : bool(it.btn(), "enabled", true))
                                                    && itemVisible(it, l))
                            .toList();
            if (buttons.isEmpty()) return true;
            int index = -1;
            for (int i = 0; i < buttons.size(); i++)
                if (buttons.get(i).action().equals(focusedAction)) index = i;
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                int next =
                        index < 0
                                ? ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? buttons.size() - 1 : 0)
                                : Math.floorMod(
                                        index + ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1),
                                        buttons.size());
                focusedAction = buttons.get(next).action();
            } else if (index >= 0) {
                Item chosen = buttons.get(index);
                if (chosen.kind() == Kind.PAGE) {
                    page += chosen.code().equals("next") ? 1 : -1;
                    focusedAction = "";
                } else activate(chosen.btn());
            }
            return true;
        }
        if (paged
                && (keyCode == GLFW.GLFW_KEY_DOWN
                        || keyCode == GLFW.GLFW_KEY_UP
                        || keyCode == GLFW.GLFW_KEY_PAGE_DOWN
                        || keyCode == GLFW.GLFW_KEY_PAGE_UP)) {
            Layout l = layout(-1, -1);
            int step = keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1;
            page = Math.max(0, Math.min(l.pages() - 1, page + step));
            focusedAction = "";
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            this.scrollOffset += SCROLL_STEP;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            this.scrollOffset -= SCROLL_STEP;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            this.scrollOffset += this.height / 2;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            this.scrollOffset -= this.height / 2;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
