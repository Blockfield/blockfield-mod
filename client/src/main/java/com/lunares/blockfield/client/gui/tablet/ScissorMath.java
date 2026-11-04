package com.lunares.blockfield.client.gui.tablet;

/**
 * Пересчёт прямоугольника scissor под масштаб виртуального холста планшета (см. {@link
 * TabletCanvas}). Правую/нижнюю грань считаем от абсолютных координат ({@code x + w}, {@code y +
 * h}), а не складываем округлённые по отдельности позицию и размер -- на нецелом масштабе (обычное
 * дело: масштаб холста почти никогда не целое число) независимое округление ширины иногда даёт на
 * 1px меньше, чем нужно, и последняя колонка/строка контента (тонкая рамка карточки, конец
 * подсказки у правого края) обрезается scissor'ом. Это и был баг с «Случайно» без правой рамки и
 * обрезанной подсказкой.
 *
 * <p>Без классов Minecraft -- проверяется юнит-тестом без игры.
 */
public final class ScissorMath {
    private ScissorMath() {}

    /**
     * @return {left, top, width, height} прямоугольника после сдвига (dx,dy) и масштаба scale.
     */
    public static int[] scale(int x, int y, int w, int h, float scale, int dx, int dy) {
        int left = dx + Math.round(x * scale);
        int top = dy + Math.round(y * scale);
        int right = dx + Math.round((x + w) * scale);
        int bottom = dy + Math.round((y + h) * scale);
        return new int[] {left, top, right - left, bottom - top};
    }
}
