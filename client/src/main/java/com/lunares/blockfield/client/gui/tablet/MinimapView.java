package com.lunares.blockfield.client.gui.tablet;

/**
 * Окно миникарты: кусок боевой зоны вокруг игрока в квадрате виджета. Зона проецируется тем же
 * {@link ZoneMap}, только растянутым так, что по ширине виджета укладывается {@code span} блоков, а
 * игрок стоит в центре. Всё, что за зоной, просто выходит за рамку — обрезает её ножницами. Без
 * классов Minecraft — проверяется юнит-тестом без игры.
 *
 * @param rotation поворот окна в градусах вокруг центра; 0 — север сверху.
 */
public record MinimapView(
        ZoneMap zone,
        float offsetX,
        float offsetY,
        float centerX,
        float centerY,
        float rotation,
        int x,
        int y,
        int w,
        int h) {

    /** Клиент не сканирует чанки: миникарта появляется после получения готового снимка. */
    public static boolean shouldRender(
            boolean enabled,
            boolean deployed,
            boolean hudFree,
            boolean hasZone,
            boolean hasSnapshot) {
        return enabled && deployed && hudFree && hasZone && hasSnapshot;
    }

    public static MinimapView of(
            double minX,
            double minZ,
            double maxX,
            double maxZ,
            double playerX,
            double playerZ,
            float yaw,
            boolean rotate,
            double span,
            int x,
            int y,
            int w,
            int h) {
        double perBlock = w / Math.max(1e-6, span);
        int zw = Math.max(1, (int) Math.round((maxX - minX) * perBlock));
        int zh = Math.max(1, (int) Math.round((maxZ - minZ) * perBlock));
        float centerX = x + w / 2f;
        float centerY = y + h / 2f;
        ZoneMap zone = new ZoneMap(minX, minZ, maxX, maxZ, 0, 0, zw, zh);
        // Keep subpixel motion: rounding here makes a walking player scroll only a few times per
        // second.
        float offsetX = centerX - zone.screenX(playerX);
        float offsetY = centerY - zone.screenZ(playerZ);
        // yaw MC: 0 = юг (+Z, вниз по экрану). Поворот 180-yaw кладёт направление взгляда вверх.
        return new MinimapView(
                zone, offsetX, offsetY, centerX, centerY, rotate ? 180f - yaw : 0f, x, y, w, h);
    }

    /** Мировая точка -> пиксель виджета с учётом поворота вокруг центра. */
    public float[] project(double worldX, double worldZ) {
        float px = zone.screenX(worldX) + offsetX;
        float py = zone.screenZ(worldZ) + offsetY;
        if (rotation == 0f) return new float[] {px, py};
        double a = Math.toRadians(rotation);
        double dx = px - centerX;
        double dy = py - centerY;
        return new float[] {
            (float) (centerX + dx * Math.cos(a) - dy * Math.sin(a)),
            (float) (centerY + dx * Math.sin(a) + dy * Math.cos(a))
        };
    }

    public boolean inFrame(float px, float py, float inset) {
        return px >= x + inset && px <= x + w - inset && py >= y + inset && py <= y + h - inset;
    }

    /** Маркер за окном показываем указателем направления, прижатым к рамке. */
    public float[] clamp(float px, float py, float inset) {
        return new float[] {
            Math.min(x + w - inset, Math.max(x + inset, px)),
            Math.min(y + h - inset, Math.max(y + inset, py))
        };
    }

    /** Место у рамки уже занято другим указателем: второй поверх него не читается. */
    public static boolean crowded(float[] at, java.util.List<float[]> placed, float gap) {
        for (float[] other : placed) {
            if (Math.abs(other[0] - at[0]) < gap && Math.abs(other[1] - at[1]) < gap) return true;
        }
        return false;
    }
}
