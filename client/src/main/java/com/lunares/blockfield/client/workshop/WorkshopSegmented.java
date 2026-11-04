package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.client.gui.BfTheme;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Two always-visible options instead of a cycling «Куда: …» button; ←/→ switch with focus. */
final class WorkshopSegmented extends AbstractWidget {
    private final Component caption;
    private final Component[] labels;
    private final Component[] hints;
    private final IntConsumer onSelect;
    private int selected;

    WorkshopSegmented(
            int x,
            int y,
            int width,
            Component caption,
            Component[] labels,
            Component[] hints,
            IntConsumer onSelect) {
        super(x, y, width, WorkshopButton.HEIGHT, caption);
        this.caption = caption;
        this.labels = labels;
        this.hints = hints;
        this.onSelect = onSelect;
    }

    int selected() {
        return selected;
    }

    void select(int index) {
        selected = Math.clamp(index, 0, 1);
    }

    /** Segment under a sheet-local x, or -1 outside the widget. */
    static int segmentAt(double mouseX, int x, int width) {
        if (mouseX < x || mouseX >= x + width) return -1;
        return mouseX < x + width / 2 ? 0 : 1;
    }

    /** Hint of the hovered segment, or ({@code focused}) of the selected one; null otherwise. */
    Component tooltip(double mouseX, boolean focused) {
        if (!visible) return null;
        if (focused) return isFocused() ? hints[selected] : null;
        return isHovered() ? hints[Math.max(0, segmentAt(mouseX, getX(), width))] : null;
    }

    private void choose(int index) {
        if (!active || index < 0 || index == selected) return;
        selected = index;
        onSelect.accept(index);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        choose(segmentAt(mouseX, getX(), width));
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!active || !isFocused()) return false;
        if (key == GLFW.GLFW_KEY_LEFT) choose(0);
        else if (key == GLFW.GLFW_KEY_RIGHT) choose(1);
        else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) choose(1 - selected);
        else return false;
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var font = BfTheme.font();
        int x = getX(), y = getY(), half = width / 2;
        g.fill(x, y, x + width, y + height, BfTheme.BG);
        BfTheme.outline(g, x, y, width, height, BfTheme.BORDER_HOVER);
        int hover = isHovered() && active ? segmentAt(mouseX, x, width) : -1;
        for (int i = 0; i < 2; i++) {
            int sx = x + 1 + i * half, sw = (i == 0 ? half : width - half) - 2;
            int ink;
            if (i == selected) {
                g.fill(sx, y + 1, sx + sw, y + height - 1, BfTheme.alpha(BfTheme.AMBER, 0x26));
                g.fill(
                        sx,
                        y + height - 3,
                        sx + sw,
                        y + height - 1,
                        active ? BfTheme.AMBER : BfTheme.AMBER_DIM);
                ink = active ? BfTheme.TEXT : BfTheme.MUTED;
            } else {
                if (hover == i) g.fill(sx, y + 1, sx + sw, y + height - 1, BfTheme.POPOVER);
                ink = active ? BfTheme.TEXT_SECONDARY : BfTheme.MUTED_DARK;
            }
            String text = labels[i].getString();
            g.drawString(font, text, sx + (sw - font.width(text)) / 2, y + 6, ink, false);
        }
        if (isFocused() && active) BfTheme.focusRing(g, x, y, width, height);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(
                NarratedElementType.TITLE,
                Component.translatable(
                        "blockfield.workshop.ai.segment_narration", caption, labels[selected]));
        output.add(NarratedElementType.HINT, hints[selected]);
    }
}
