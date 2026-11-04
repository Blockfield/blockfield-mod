package com.lunares.blockfield.client.workshop;

/**
 * Right-docked Workshop sheet: the prompt field P is the only flexible row (4–8 lines), the rest is
 * a fixed 176 px stack. Vanilla never offers a GUI smaller than 320×240, so the sheet scales down
 * only for non-standard windows.
 */
public record WorkshopAiLayout(int x, int y, int width, int height, int promptHeight, float scale) {
    static final int MARGIN = 12;
    static final int GAP = 4;
    static final int FIXED = 176;

    public static WorkshopAiLayout fit(int screenWidth, int screenHeight) {
        int width = Math.clamp(Math.round(screenWidth * 0.42f), 300, 340);
        int prompt = Math.clamp(Math.min(screenHeight - 16, FIXED + 88) - FIXED, 36, 88);
        int height = FIXED + prompt;
        float scale =
                Math.max(
                        0.01f,
                        Math.min(
                                1f,
                                Math.min(
                                        (screenWidth - 16f) / width,
                                        (screenHeight - 16f) / height)));
        return new WorkshopAiLayout(
                Math.max(0, screenWidth - 8 - Math.round(width * scale)),
                Math.max(0, (screenHeight - Math.round(height * scale)) / 2),
                width,
                height,
                prompt,
                scale);
    }

    public int inner() {
        return width - 2 * MARGIN;
    }

    /** Extra width over the 300 px minimum; it goes to the prompt, the rows and primary. */
    int extra() {
        return width - 300;
    }

    int promptY() {
        return 28;
    }

    int targetY() {
        return promptHeight + 34;
    }

    int speedY() {
        return promptHeight + 58;
    }

    int speedWidth(boolean voice) {
        return voice ? 180 + extra() : inner();
    }

    int voiceX() {
        return MARGIN + speedWidth(true) + GAP;
    }

    int voiceWidth() {
        return inner() - speedWidth(true) - GAP;
    }

    int statusY() {
        return promptHeight + 86;
    }

    int actionsY() {
        return promptHeight + 144;
    }

    int primaryWidth() {
        return 132 + extra();
    }

    int secondaryX() {
        return MARGIN + primaryWidth() + GAP;
    }

    int tertiaryX() {
        return secondaryX() + 80 + GAP;
    }

    public double localX(double mouseX) {
        return (mouseX - x) / scale;
    }

    public double localY(double mouseY) {
        return (mouseY - y) / scale;
    }
}
