package com.lunares.blockfield.client.gui.tablet;

/**
 * Виртуальный холст меню на экране планшета: меню рисуется в своём «проектном» размере и ужимается
 * матрицей в прямоугольник планшета. Иначе при крупном GUI-масштабе экран планшета получается в 300
 * GUI-пикселей, меню сворачивается в одну колонку и появляется прокрутка — а прокрутки в планшете
 * быть не должно.
 *
 * <p>Без классов Minecraft — проверяется юнит-тестом без игры.
 */
public record TabletCanvas(int width, int height, float scale) {

    /** Высота, под которую нарисованы меню (две колонки карточек, таймер сбоку). */
    public static final int DESIGN_HEIGHT = 360;

    /** Уже этого меню само сваливается в одну колонку — незачем. */
    public static final int MIN_WIDTH = 560;

    /** Дальше ужимать бессмысленно: текст станет нечитаемым. */
    public static final int MAX_HEIGHT = 900;

    /** Холст под прямоугольник (innerW x innerH) с сохранением пропорций. */
    public static TabletCanvas forInner(int innerW, int innerH) {
        // A tablet screen lower than the design height starts at 1:1: text is only shrunk (and
        // blurred) once the menu
        // really overflows (grownBy), not up front. Taller screens keep the design height and scale
        // up as before.
        return of(innerW, innerH, Math.min(DESIGN_HEIGHT, Math.max(1, innerH)));
    }

    /**
     * Холст выше на overflow пикселей содержимого: столько не влезло при прошлом размере, значит
     * ровно на столько нужно «отъехать», чтобы поместилось всё.
     */
    public TabletCanvas grownBy(int overflow, int innerW, int innerH) {
        return of(innerW, innerH, Math.min(MAX_HEIGHT, height + Math.max(1, overflow)));
    }

    private static TabletCanvas of(int innerW, int innerH, int height) {
        int h = Math.max(1, Math.min(MAX_HEIGHT, height));
        int w =
                Math.max(
                        MIN_WIDTH,
                        Math.round(h * (float) Math.max(1, innerW) / Math.max(1, innerH)));
        // Ширину могли поднять до MIN_WIDTH -- тогда высота растёт вместе с ней, пропорции
        // сохраняются.
        h = Math.max(h, Math.round(w * (float) Math.max(1, innerH) / Math.max(1, innerW)));
        return new TabletCanvas(w, h, innerW / (float) w);
    }

    /** Экранная координата -> координата внутри холста меню. */
    public double toCanvasX(double screenX, int contentX) {
        return (screenX - contentX) / scale;
    }

    public double toCanvasY(double screenY, int contentY) {
        return (screenY - contentY) / scale;
    }
}
