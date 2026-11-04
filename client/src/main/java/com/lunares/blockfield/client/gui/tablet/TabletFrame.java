package com.lunares.blockfield.client.gui.tablet;

/**
 * Геометрия полноэкранного планшета в GUI-пикселях: полоса вкладок сверху и область содержимого под
 * ней, во весь экран за вычетом отступов. Открытие/закрытие теперь анимирует не размер кадра (это и
 * давало "ступенчатый" вид при тиковой анимации), а масштаб всего кадра через PoseStack в {@code
 * TabletScreen.render} -- сама геометрия здесь статична для данного размера окна.
 *
 * <p>Без классов Minecraft -- проверяется юнит-тестом без игры.
 */
public record TabletFrame(
        int contentX, int contentY, int contentW, int contentH, int tabsY, int tabsH) {

    public static final int MARGIN = 22;
    public static final int TABS_H = 26;
    private static final int TABS_GAP = 6;

    public static TabletFrame of(int guiWidth, int guiHeight) {
        int contentX = MARGIN;
        int tabsY = MARGIN;
        int contentY = tabsY + TABS_H + TABS_GAP;
        int contentW = Math.max(60, guiWidth - 2 * MARGIN);
        int contentH = Math.max(60, guiHeight - MARGIN - contentY);
        return new TabletFrame(contentX, contentY, contentW, contentH, tabsY, TABS_H);
    }

    /** Tabs and content filling a `width` x `height` surface with `margin` on every side. */
    public static TabletFrame inset(int width, int height, int margin) {
        int contentY = margin + TABS_H + TABS_GAP;
        return new TabletFrame(
                margin,
                contentY,
                Math.max(60, width - 2 * margin),
                Math.max(60, height - margin - contentY),
                margin,
                TABS_H);
    }

    public boolean inContent(double mouseX, double mouseY) {
        return mouseX >= contentX
                && mouseX < contentX + contentW
                && mouseY >= contentY
                && mouseY < contentY + contentH;
    }
}
