package com.lunares.blockfield.client.workshop;

/** Live vanilla chat reservation, refreshed by ChatHudMixin before the tool HUD renders. */
public final class WorkshopChatArea {
    private static int[] rect;

    private WorkshopChatArea() {}

    public static int[] rect() {
        return rect;
    }

    public static void capture(
            int width,
            int height,
            double scale,
            int lineHeight,
            int bottomInset,
            int maxLines,
            int[] ages,
            boolean open,
            boolean hidden) {
        rect = bounds(width, height, scale, lineHeight, bottomInset, maxLines, ages, open, hidden);
    }

    static int[] bounds(
            int width,
            int height,
            double scale,
            int lineHeight,
            int bottomInset,
            int maxLines,
            int[] ages,
            boolean open,
            boolean hidden) {
        if (hidden || scale <= 0) return null;
        int rows = 0;
        for (int i = 0; i < Math.min(maxLines, ages.length); i++)
            if (open || ages[i] < 200) rows = i + 1;
        if (rows == 0) return null;
        int bottom = (int) Math.floor((height - bottomInset) / scale);
        int top = (int) Math.floor((bottom - rows * lineHeight) * scale) - 2;
        // Includes the vanilla background, margin, message tags and glyph rounding.
        return new int[] {
            0,
            Math.max(0, top),
            (int) Math.ceil(width + 16 * scale),
            Math.max(0, (int) Math.ceil(bottom * scale) + 2 - Math.max(0, top))
        };
    }
}
