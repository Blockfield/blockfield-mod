package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.gui.BfIcon;
import com.lunares.blockfield.client.gui.BfTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared upper match bar: score/tickets, timer, frag limit and leader, plus the state chips under
 * it (landing protection and other notices). The block owns geometry, style, scale and clipping;
 * modes pass data and a semantic variant only.
 */
public final class HudTopBar {
    private HudTopBar() {}

    public static final int Y = 6;
    public static final int BAR_H = 20;
    static final int EDGE = 10;
    static final int GAP = 15;

    /** Capture-point diamond size and step (square plus gap), drawn by the caller in the center. */
    public static final int POINT = 11;

    public static final int POINT_GAP = 6;

    /** Protection chip height; drawn one gap below the bar. */
    static final int CHIP_H = 14;

    static final int CHIP_GAP = 2;

    public record Layout(int left, int half, int centerX, int bottom) {}

    /**
     * Bar geometry from already-composed cell widths; clamped so the bar never leaves the screen.
     * The center cell keeps at least its padding so the timer is never squeezed out.
     */
    public static Layout layout(int screenWidth, int leftW, int centerW, int rightW) {
        int centerX = screenWidth / 2;
        int half = centerW / 2 + GAP + Math.max(leftW, rightW) + EDGE;
        half = Math.min(half, Math.max(0, centerX - 1));
        half = Math.max(half, centerW / 2 + EDGE);
        return new Layout(centerX - half, half, centerX, Y + BAR_H);
    }

    /** Center width of the capture-point diamond row. */
    public static int pointsWidth(int count) {
        if (count <= 0) return 0;
        return count * POINT + (count - 1) * POINT_GAP;
    }

    /** Room each side cell gets before the bar would touch the screen edge. */
    public static int sideAvail(int screenWidth) {
        return Math.max(24, screenWidth / 2 - 1 - EDGE - GAP - 20);
    }

    /**
     * Draws the bar: background, border, corner ticks, side cells and an optional center cell.
     * Widths must come from {@link #layout} over these same strings.
     */
    public static void render(
            GuiGraphics graphics,
            Font font,
            Layout bar,
            String left,
            int leftColor,
            String center,
            String right,
            int rightColor) {
        int barLeft = bar.left();
        int barRight = bar.centerX() + bar.half();
        graphics.fill(barLeft, Y, barRight, Y + BAR_H, BfTheme.alpha(BfTheme.BG, 0xCC));
        BfTheme.outline(graphics, barLeft, Y, bar.half() * 2, BAR_H, BfTheme.BORDER);
        BfTheme.ticks(graphics, barLeft, Y, bar.half() * 2, BAR_H, 4, BfTheme.AMBER);
        graphics.drawString(font, left, barLeft + EDGE, Y + 6, leftColor, true);
        if (center != null)
            graphics.drawCenteredString(font, center, bar.centerX(), Y + 6, BfTheme.TEXT);
        int rightW = font.width(right);
        graphics.drawString(font, right, barRight - EDGE - rightW, Y + 6, rightColor, true);
    }

    /** Protection chip width; {@link #renderChip} draws it centered on its x. */
    public static int chipWidth(Font font, String text) {
        return font.width(text) + 26;
    }

    /**
     * State chip under the bar: shield icon plus text on the shared plate. Returns its bottom edge
     * so the caller can stack the zone banner and the killfeed below it.
     */
    public static int renderChip(GuiGraphics graphics, Font font, int centerX, int y, String text) {
        int width = chipWidth(font, text);
        int x = centerX - width / 2;
        BfTheme.panel(graphics, x, y, width, CHIP_H, false);
        BfIcon.SHIELD.draw(graphics, x + 6, y + 2, 10, BfTheme.AMBER);
        graphics.drawString(font, text, x + 20, y + 3, BfTheme.TEXT, false);
        return y + CHIP_H;
    }

    /** Top of the protection chip when the bar bottom is known. */
    public static int chipTop(int barBottom) {
        return barBottom + CHIP_GAP;
    }
}
