package com.lunares.blockfield.client.gui.tablet;

/**
 * Вид карты планшета: зона боя, вписанная в рамку, с приближением вокруг точки под курсором. Набок
 * карту не кладём; если база игрока в северной половине, разворачиваем на 180°, чтобы своя база
 * была снизу. Без классов Minecraft — проверяется юнит-тестом без игры.
 *
 * @param x левый верхний угол и размер карты на экране; при zoom &gt; 1 карта шире рамки
 */
public record MapView(
        double minX,
        double minZ,
        double maxX,
        double maxZ,
        boolean flipped,
        float x,
        float y,
        float w,
        float h) {

    /**
     * @param anchorU доля карты (0..1), которая должна остаться под точкой экрана anchorX/anchorY
     *     -- так приближение идёт к курсору, а не к центру
     * @param baseZ Z базы игрока; NaN -- базы нет, север сверху
     */
    public static MapView of(
            double minX,
            double minZ,
            double maxX,
            double maxZ,
            double baseZ,
            int frameX,
            int frameY,
            int frameW,
            int frameH,
            float zoom,
            float anchorX,
            float anchorY,
            float anchorU,
            float anchorV) {
        double worldW = Math.max(1e-6, maxX - minX);
        double worldH = Math.max(1e-6, maxZ - minZ);
        boolean flipped = baseZ < (minZ + maxZ) / 2;
        double scale = Math.min(frameW / worldW, frameH / worldH) * zoom;
        float w = (float) (worldW * scale);
        float h = (float) (worldH * scale);
        return new MapView(
                minX,
                minZ,
                maxX,
                maxZ,
                flipped,
                place(anchorX - anchorU * w, w, frameX, frameW),
                place(anchorY - anchorV * h, h, frameY, frameH),
                w,
                h);
    }

    public MapView pan(float dx, float dy, int frameX, int frameY, int frameW, int frameH) {
        return new MapView(
                minX,
                minZ,
                maxX,
                maxZ,
                flipped,
                place(x + dx, w, frameX, frameW),
                place(y + dy, h, frameY, frameH),
                w,
                h);
    }

    /** Карта уже рамки стоит по центру, шире -- не отрывается от её краёв. */
    private static float place(float start, float size, int frameStart, int frameSize) {
        if (size <= frameSize) return frameStart + (frameSize - size) / 2f;
        return Math.min(frameStart, Math.max(frameStart + frameSize - size, start));
    }

    public float[] screen(double worldX, double worldZ) {
        double u = (worldX - minX) / (maxX - minX);
        double v = (worldZ - minZ) / (maxZ - minZ);
        if (flipped) {
            u = 1 - u;
            v = 1 - v;
        }
        return new float[] {(float) (x + u * w), (float) (y + v * h)};
    }

    /** Мировые координаты точки, прижатые к зоне: маркер игрока у кромки не уезжает под рамку. */
    public float[] clampedScreen(double worldX, double worldZ) {
        return screen(
                Math.min(maxX, Math.max(minX, worldX)), Math.min(maxZ, Math.max(minZ, worldZ)));
    }

    /** Обратное преобразование: точка на карте -> мировые X/Z (для метки с карты). */
    public double[] world(double screenX, double screenY) {
        double u = (screenX - x) / w;
        double v = (screenY - y) / h;
        if (flipped) {
            u = 1 - u;
            v = 1 - v;
        }
        return new double[] {minX + u * (maxX - minX), minZ + v * (maxZ - minZ)};
    }

    public boolean onMap(double screenX, double screenY) {
        return screenX >= x && screenX <= x + w && screenY >= y && screenY <= y + h;
    }

    public boolean contains(double worldX, double worldZ) {
        return worldX >= minX && worldX <= maxX && worldZ >= minZ && worldZ <= maxZ;
    }
}
