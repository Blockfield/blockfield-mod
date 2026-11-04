package com.lunares.blockfield.client.gui.tablet;

/**
 * Проекция боевой зоны карты (мировые X/Z) на прямоугольник экрана с сохранением пропорций. Всё,
 * что вне зоны, за кадром по построению: планшет показывает только зону боя. Без классов Minecraft
 * — проверяется юнит-тестом без игры.
 */
public record ZoneMap(
        double minX, double minZ, double maxX, double maxZ, int x, int y, int w, int h) {

    /**
     * Впечатывает зону в рамку (frameX, frameY, frameW, frameH) по центру, не искажая пропорции.
     */
    public static ZoneMap fit(
            double minX,
            double minZ,
            double maxX,
            double maxZ,
            int frameX,
            int frameY,
            int frameW,
            int frameH) {
        double worldW = Math.max(1e-6, maxX - minX);
        double worldH = Math.max(1e-6, maxZ - minZ);
        int w = frameW;
        int h = (int) Math.round(frameW * worldH / worldW);
        if (h > frameH) {
            h = frameH;
            w = (int) Math.round(frameH * worldW / worldH);
        }
        w = Math.max(1, Math.min(frameW, w));
        h = Math.max(1, Math.min(frameH, h));
        return new ZoneMap(
                minX, minZ, maxX, maxZ, frameX + (frameW - w) / 2, frameY + (frameH - h) / 2, w, h);
    }

    public float screenX(double worldX) {
        return (float) (x + (worldX - minX) / (maxX - minX) * w);
    }

    public float screenZ(double worldZ) {
        return (float) (y + (worldZ - minZ) / (maxZ - minZ) * h);
    }

    /** Обратное преобразование: точка на карте -> мировая координата (для метки с карты). */
    public double worldX(double screenX) {
        return minX + (screenX - x) / w * (maxX - minX);
    }

    public double worldZ(double screenY) {
        return minZ + (screenY - y) / h * (maxZ - minZ);
    }

    public boolean onMap(double screenX, double screenY) {
        return screenX >= x && screenX <= x + w && screenY >= y && screenY <= y + h;
    }

    public boolean contains(double worldX, double worldZ) {
        return worldX >= minX && worldX <= maxX && worldZ >= minZ && worldZ <= maxZ;
    }

    /** Мировые координаты точки, прижатые к зоне: маркер игрока у кромки не уезжает под рамку. */
    public float clampedScreenX(double worldX) {
        return screenX(Math.min(maxX, Math.max(minX, worldX)));
    }

    public float clampedScreenZ(double worldZ) {
        return screenZ(Math.min(maxZ, Math.max(minZ, worldZ)));
    }
}
