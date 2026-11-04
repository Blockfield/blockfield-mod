package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The one Workshop button: BfTheme style, a «Точно?» second click for destructive actions and a
 * BfTheme tooltip (the screen draws it, since the sheet is rendered under a scaled pose).
 */
final class WorkshopButton extends AbstractButton {
    static final long CONFIRM_MS = 3000;
    static final int HEIGHT = 20;
    private BfTheme.Style style;
    private Runnable action;
    private Component tooltip;
    private long armedAt = Long.MIN_VALUE;
    private boolean confirm = true;

    WorkshopButton(int x, int y, int width, BfTheme.Style style, Runnable action) {
        super(x, y, width, HEIGHT, Component.empty());
        this.style = style;
        this.action = action;
    }

    /** Re-labels the slot for the current state; a changed meaning drops a pending confirm. */
    WorkshopButton set(Component label, BfTheme.Style style, boolean enabled, Runnable action) {
        if (!label.equals(getMessage()) || style != this.style) armedAt = Long.MIN_VALUE;
        setMessage(label);
        this.style = style;
        this.active = enabled;
        this.action = action;
        return this;
    }

    WorkshopButton tooltip(Component tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    Component tooltip() {
        return tooltip;
    }

    void confirmless() {
        confirm = false;
    }

    void disarm() {
        armedAt = Long.MIN_VALUE;
    }

    boolean armed(long now) {
        return within(armedAt, now);
    }

    // MIN_VALUE means «not armed»; now - MIN_VALUE would overflow into a false «armed».
    private static boolean within(long armedAt, long now) {
        return armedAt != Long.MIN_VALUE && now - armedAt < CONFIRM_MS;
    }

    /** First press of a destructive button only arms it; the second within 3 s runs it. */
    static boolean confirmed(boolean destructive, long armedAt, long now) {
        return !destructive || within(armedAt, now);
    }

    @Override
    public void onPress() {
        long now = Util.getMillis();
        if (!confirmed(confirm && style == BfTheme.Style.DESTRUCTIVE, armedAt, now)) {
            armedAt = now;
            return;
        }
        armedAt = Long.MIN_VALUE;
        if (action != null) action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var font = BfTheme.font();
        String label =
                armed(Util.getMillis())
                        ? Component.translatable("blockfield.workshop.ai.confirm").getString()
                        : getMessage().getString();
        BfTheme.button(
                g,
                font,
                getX(),
                getY(),
                width,
                height,
                label,
                isHovered() || isFocused(),
                active,
                style);
        if (isFocused() && active) BfTheme.focusRing(g, getX(), getY(), width, height);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
        if (tooltip != null) output.add(NarratedElementType.HINT, tooltip);
    }
}
