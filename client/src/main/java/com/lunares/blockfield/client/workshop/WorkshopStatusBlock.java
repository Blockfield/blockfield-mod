package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Fixed-height status of the AI panel: a stage-coloured bar on the left, the message wrapped to at
 * most three lines, then optional ETA bar, time, waiting and hint lines. A cut message ends with
 * «Подробнее», which is focusable and shows the whole text in a tooltip.
 */
final class WorkshopStatusBlock extends AbstractWidget {
    static final int HEIGHT = 50;
    static final int SLOT = 80;
    static final int TOOLTIP_WIDTH = 260;

    /** {@code bar} < 0 hides the bar; colours of 0 hide nothing but fall back to defaults. */
    record Status(
            int accent,
            String title,
            int titleColor,
            String message,
            int messageColor,
            float bar,
            int barColor,
            String time,
            int timeColor,
            String waiting,
            String hint,
            int hintColor) {
        static Status of(int accent, String message, int messageColor) {
            return new Status(
                    accent, "", 0, message, messageColor, -1, 0, "", 0, "", "", BfTheme.MUTED);
        }

        Status withTitle(String text, int color) {
            return new Status(
                    accent,
                    text,
                    color,
                    message,
                    messageColor,
                    bar,
                    barColor,
                    time,
                    timeColor,
                    waiting,
                    hint,
                    hintColor);
        }

        Status withBar(float fill, int color) {
            return new Status(
                    accent,
                    title,
                    titleColor,
                    message,
                    messageColor,
                    fill,
                    color,
                    time,
                    timeColor,
                    waiting,
                    hint,
                    hintColor);
        }

        Status withTime(String text, int color) {
            return new Status(
                    accent,
                    title,
                    titleColor,
                    message,
                    messageColor,
                    bar,
                    barColor,
                    text,
                    color,
                    waiting,
                    hint,
                    hintColor);
        }

        Status withWaiting(String text) {
            return new Status(
                    accent,
                    title,
                    titleColor,
                    message,
                    messageColor,
                    bar,
                    barColor,
                    time,
                    timeColor,
                    text,
                    hint,
                    hintColor);
        }

        Status withHint(String text, int color) {
            return new Status(
                    accent,
                    title,
                    titleColor,
                    message,
                    messageColor,
                    bar,
                    barColor,
                    time,
                    timeColor,
                    waiting,
                    text,
                    color);
        }
    }

    private Status status = Status.of(BfTheme.MUTED, "", BfTheme.TEXT);
    private boolean slot;
    private boolean overflow;

    {
        // Only a cut message makes the block a Tab stop; render() keeps this in sync.
        active = false;
    }

    WorkshopStatusBlock(int x, int y, int width) {
        super(x, y, width, HEIGHT, Component.empty());
    }

    void show(Status status, boolean slot) {
        this.status = status;
        this.slot = slot;
    }

    /** Word-wrap seam, so the row budget is testable without a font. */
    interface Wrap {
        BfTheme.Wrapped apply(String text, int width, int maxLines);
    }

    /** Wrapped rows; a cut flag per row (trimmed or wrapped past the budget). */
    record Layout(
            java.util.List<String> title,
            java.util.List<String> message,
            java.util.List<String> time,
            java.util.List<String> waiting,
            java.util.List<String> hint,
            boolean titleCut,
            boolean messageCut,
            boolean timeCut,
            boolean waitingCut,
            boolean hintCut) {
        boolean overflow() {
            return titleCut || messageCut || timeCut || waitingCut || hintCut;
        }

        /**
         * Row owning the «…Подробнее» cue: the cut row, message first so a cut message keeps its
         * existing cue. When the owning row has no lines left (budgeted out entirely), the cue pins
         * to the last visible row; the tooltip always owns the full text.
         */
        String moreOn() {
            if (messageCut && !message.isEmpty()) return "message";
            if (timeCut && !time.isEmpty()) return "time";
            if (waitingCut && !waiting.isEmpty()) return "waiting";
            if (hintCut && !hint.isEmpty()) return "hint";
            if (titleCut && !title.isEmpty()) return "title";
            if (!overflow()) return "";
            if (!hint.isEmpty()) return "hint";
            if (!waiting.isEmpty()) return "waiting";
            if (!time.isEmpty()) return "time";
            if (!message.isEmpty()) return "message";
            return "title";
        }
    }

    /**
     * Fixed-height budget: the bar takes 7 px, every other row is 10 px. All rows share one budget
     * ({@code cap} text rows); the safety hint and the WARN row keep their lines first, then the
     * title, the time and finally the message (at most three). Anything past the budget is cut and
     * sets its flag, so the «Подробнее» tooltip shows every row in full. No row is cut silently.
     */
    static Layout layout(Status s, int width, Wrap wrap) {
        var hint = wrapped(s.hint(), width, 2, wrap);
        var waiting = wrapped(s.waiting(), width, 2, wrap);
        var title = wrapped(s.title(), width, 1, wrap);
        var time = wrapped(s.time(), width, 2, wrap);
        int cap = (HEIGHT - 2 - (s.bar() >= 0 ? 7 : 0)) / 10;
        // Trim the lowest priority first: time, title, waiting, hint.
        var rows =
                new java.util.ArrayList<java.util.List<String>>(
                        java.util.List.of(
                                new java.util.ArrayList<>(time.lines()),
                                new java.util.ArrayList<>(title.lines()),
                                new java.util.ArrayList<>(waiting.lines()),
                                new java.util.ArrayList<>(hint.lines())));
        boolean[] fixedCut = {
            time.overflow(), title.overflow(), waiting.overflow(), hint.overflow()
        };
        int total = rows.stream().mapToInt(java.util.List::size).sum();
        for (int i = 0; i < rows.size() && total > cap; i++) {
            while (!rows.get(i).isEmpty() && total > cap) {
                rows.get(i).remove(rows.get(i).size() - 1);
                fixedCut[i] = true;
                total--;
            }
        }
        int budget = Math.min(3, cap - total);
        var message =
                s.message().isEmpty() || budget <= 0
                        ? new BfTheme.Wrapped(java.util.List.of(), !s.message().isEmpty())
                        : wrap.apply(s.message(), width, budget);
        return new Layout(
                rows.get(1),
                message.lines(),
                rows.get(0),
                rows.get(2),
                rows.get(3),
                fixedCut[1],
                message.overflow(),
                fixedCut[0],
                fixedCut[2],
                fixedCut[3]);
    }

    private static BfTheme.Wrapped wrapped(String text, int width, int maxLines, Wrap wrap) {
        return text.isEmpty()
                ? new BfTheme.Wrapped(java.util.List.of(), false)
                : wrap.apply(text, width, maxLines);
    }

    private int textWidth() {
        return width - 10 - (slot ? SLOT : 0);
    }

    private void drawRow(
            GuiGraphics g, Font font, int x, int y, int w, String line, int color, boolean more) {
        if (more && !line.isEmpty()) {
            String cue = Component.translatable("blockfield.workshop.ai.status_more").getString();
            int cueW = font.width(cue);
            line = BfTheme.ellipsize(font, line, w - cueW - 4);
            int mx = x + font.width(line) + 4;
            boolean lit = isFocused() || isHovered();
            g.drawString(font, cue, mx, y, lit ? BfTheme.AMBER_BRIGHT : BfTheme.AMBER, false);
            g.fill(mx, y + 9, mx + cueW, y + 10, lit ? BfTheme.AMBER_BRIGHT : BfTheme.AMBER_DIM);
        }
        g.drawString(font, line, x, y, color, false);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = BfTheme.font();
        int x = getX() + 8, y = getY() + 1, w = textWidth();
        g.fill(getX(), getY(), getX() + 2, getY() + height, status.accent());
        Layout rows = layout(status, w, (text, width, max) -> BfTheme.wrap(font, text, width, max));
        overflow = rows.overflow();
        String more = rows.moreOn();
        for (int i = 0; i < rows.title().size(); i++) {
            drawRow(
                    g,
                    font,
                    x,
                    y,
                    w,
                    rows.title().get(i),
                    status.titleColor(),
                    more.equals("title") && i == rows.title().size() - 1);
            y += 10;
        }
        for (int i = 0; i < rows.message().size(); i++) {
            drawRow(
                    g,
                    font,
                    x,
                    y,
                    w,
                    rows.message().get(i),
                    status.messageColor(),
                    more.equals("message") && i == rows.message().size() - 1);
            y += 10;
        }
        if (status.bar() >= 0) {
            BfTheme.progress(g, x, y + 1, w, 4, status.bar(), status.barColor());
            y += 7;
        }
        for (int i = 0; i < rows.time().size(); i++) {
            drawRow(
                    g,
                    font,
                    x,
                    y,
                    w,
                    rows.time().get(i),
                    status.timeColor(),
                    more.equals("time") && i == rows.time().size() - 1);
            y += 10;
        }
        for (int i = 0; i < rows.waiting().size(); i++) {
            drawRow(
                    g,
                    font,
                    x,
                    y,
                    w,
                    rows.waiting().get(i),
                    BfTheme.WARN,
                    more.equals("waiting") && i == rows.waiting().size() - 1);
            y += 10;
        }
        // The hint keeps the bottom rows, so rows above can change without moving it. The
        // shared budget above guarantees the rows above end before the hint starts.
        int hy = getY() + HEIGHT - 9 - Math.max(0, rows.hint().size() - 1) * 10;
        for (int i = 0; i < rows.hint().size(); i++) {
            drawRow(
                    g,
                    font,
                    x,
                    hy,
                    w,
                    rows.hint().get(i),
                    status.hintColor(),
                    more.equals("hint") && i == rows.hint().size() - 1);
            hy += 10;
        }
        active = overflow;
        if (isFocused() && overflow) BfTheme.focusRing(g, getX(), getY(), width, height);
    }

    /** The whole text of a cut block while hovered ({@code focused}: while focused), else empty. */
    List<Component> tooltip(boolean focused) {
        if (!overflow || !(focused ? isFocused() : isHovered())) return List.of();
        var lines = new ArrayList<Component>();
        // Every row, in screen order: a cut WARN/hint is as important as a cut message.
        String full =
                (status.title()
                                + " "
                                + status.message()
                                + " "
                                + status.time()
                                + " "
                                + status.waiting()
                                + " "
                                + status.hint())
                        .strip();
        for (String line : BfTheme.wrap(BfTheme.font(), full, TOOLTIP_WIDTH, 64).lines())
            lines.add(Component.literal(line));
        return lines;
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(
                NarratedElementType.TITLE,
                Component.literal(status.title() + " " + status.message()));
    }
}
