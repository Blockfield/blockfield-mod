package com.lunares.blockfield.client.gui.tablet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MinimapTerrainTest {
    @Test
    void smoothsNoiseWithoutErasingNarrowRoadsOrChangingSource() {
        byte[] source = new byte[9 * 7 * 3];
        Arrays.fill(source, (byte) 100);
        for (int z = 0; z < 7; z++)
            for (int c = 0; c < 3; c++) source[(z * 9 + 4) * 3 + c] = (byte) 190;
        source[(3 * 9 + 2) * 3] = 112;
        byte[] before = source.clone(), mini = MinimapTerrain.filter(source, 9);
        assertArrayEquals(before, source);
        assertTrue((mini[(3 * 9 + 2) * 3] & 255) < 112);
        for (int z = 0; z < 7; z++) assertEquals(190, mini[(z * 9 + 4) * 3] & 255);
        assertArrayEquals(
                new byte[] {20, 30, 40}, MinimapTerrain.filter(new byte[] {20, 30, 40}, 1));
    }

    @Test
    void minimapUpscaleStaysWithinItsTextureBudget() {
        assertEquals(4, MinimapTerrain.scale(256, 256));
        assertEquals(2, MinimapTerrain.scale(703, 703));
        assertEquals(1, MinimapTerrain.scale(2048, 2048));
        assertTrue(381 * 797 * Math.pow(MinimapTerrain.scale(381, 797), 2) <= 1 << 22);
    }

    @Test
    void textureColorsWeighAlphaAndApplyTint() {
        // NativeImage pixels are ABGR: one opaque red, one opaque blue, one transparent white.
        assertEquals(0x7F007F, MapColors.average(new int[] {0xFF0000FF, 0xFFFF0000, 0x00FFFFFF}));
        assertEquals(-1, MapColors.average(new int[] {0x00FFFFFF}));
        assertEquals(0x808080, MapColors.multiply(0x808080, -1));
        assertEquals(0x400080, MapColors.multiply(0x808080, 0x8000FF));
    }

    @Test
    void detailTilesAverageToTheFlatColorAndFillCutoutHoles() {
        int[] texture = new int[MapColors.TILE * MapColors.TILE];
        for (int p = 0; p < texture.length; p++) texture[p] = p % 2 == 0 ? 0xFF404040 : 0xFF808080;
        texture[3] = 0x00FFFFFF;
        int[] tile = MapColors.detail(texture, 0x606060);
        long sum = 0;
        for (int pixel : tile) {
            assertEquals(0xFF, pixel >>> 24, "Tiles are opaque");
            sum += pixel & 255;
        }
        assertEquals(0x60, sum / tile.length, 1.0);
        assertEquals(0xFF606060, tile[3], "A transparent texel shows the flat color");
        assertTrue((tile[0] & 255) < 0x60 && (tile[1] & 255) > 0x60, "Texture contrast is kept");
        assertNull(MapColors.detail(new int[] {0}, 0x606060));
        assertEquals(0f, MapDetail.alpha(8f));
        assertEquals(1f, MapDetail.alpha(20f));
    }
}
