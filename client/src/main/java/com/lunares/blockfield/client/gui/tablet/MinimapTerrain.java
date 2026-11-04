package com.lunares.blockfield.client.gui.tablet;

final class MinimapTerrain {
    private MinimapTerrain() {}

    /**
     * Integer upscale for the rotating minimap. Linear filtering of a nearest-upscaled texture
     * keeps block edges sharp but stops them from stair-stepping and crawling while turning.
     * Bounded to 4 Mpx (16 MiB before mips).
     */
    static int scale(int width, int height) {
        return (int) Math.max(1, Math.min(4, Math.sqrt((1 << 22) / ((double) width * height))));
    }

    /** Suppress small tonal noise within a material, preserving roads and building edges. */
    static byte[] filter(byte[] rgb, int width) {
        byte[] result = new byte[rgb.length];
        int height = rgb.length / (width * 3);
        for (int z = 0; z < height; z++)
            for (int x = 0; x < width; x++) {
                int p = (z * width + x) * 3;
                int r = (rgb[p] & 255) * 4,
                        g = (rgb[p + 1] & 255) * 4,
                        b = (rgb[p + 2] & 255) * 4,
                        weight = 4;
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int sx = x + dx, sz = z + dz;
                        if (sx < 0 || sx >= width || sz < 0 || sz >= height) continue;
                        int q = (sz * width + sx) * 3;
                        int difference = 0;
                        for (int c = 0; c < 3; c++)
                            difference =
                                    Math.max(
                                            difference,
                                            Math.abs((rgb[p + c] & 255) - (rgb[q + c] & 255)));
                        if (difference > 20) continue;
                        int w = dx == 0 || dz == 0 ? 2 : 1;
                        r += (rgb[q] & 255) * w;
                        g += (rgb[q + 1] & 255) * w;
                        b += (rgb[q + 2] & 255) * w;
                        weight += w;
                    }
                result[p] = (byte) Math.round(r / (float) weight);
                result[p + 1] = (byte) Math.round(g / (float) weight);
                result[p + 2] = (byte) Math.round(b / (float) weight);
            }
        return result;
    }
}
