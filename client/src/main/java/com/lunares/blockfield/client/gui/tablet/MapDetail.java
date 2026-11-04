package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.network.packet.MapTerrainS2CPacket;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.resources.ResourceLocation;

/**
 * Close zoom draws every visible block with its top texture over the flat snapshot. Tiles average
 * to the flat map color ({@link MapColors#detail}), so fading them in only adds texture.
 */
final class MapDetail {
    private static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath("blockfield", "map_detail");
    // Tiles are 16 texels; four mip levels end at one texel per tile, so tiles never bleed.
    private static final int MIPS = 4;
    private static MapPalette palette;
    private static int paletteSize, columns, side;
    // Tile per palette entry; 0 is plain white, tinted with the lit flat color (water, the void
    // and blocks without a readable texture), so they stay crisp instead of the soft upscale.
    private static int[] tileOf = new int[0];

    private MapDetail() {}

    static void clear() {
        palette = null;
        Minecraft.getInstance().getTextureManager().release(ATLAS);
    }

    /** Detail opacity for a block drawn this many screen pixels wide. */
    static float alpha(float pixelsPerBlock) {
        return Math.max(0f, Math.min(1f, (pixelsPerBlock - 8f) / 6f));
    }

    /**
     * Draw image pixels [x0, x1) × [z0, z1) inside a pose where the whole image spans [-128, 128]²,
     * the space of the flat snapshot blit.
     */
    static void render(
            GuiGraphics g,
            MapTileAssembly.MapImage image,
            int x0,
            int x1,
            int z0,
            int z1,
            float alpha) {
        if (!atlas(image.palette())) return;
        int w = image.header().width(), h = image.header().height(), a = Math.round(alpha * 255);
        var matrix = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, ATLAS);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        var buffer =
                Tesselator.getInstance()
                        .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float tile = 1f / (side / MapColors.TILE);
        for (int z = Math.max(0, z0); z < Math.min(h, z1); z++)
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                int p = z * w + x, entry = image.entries()[p], index = tileOf[entry];
                int light = image.light()[p] & 255;
                // Vertex color only darkens: lit slopes above 1 keep the flat texture brightness.
                int l = Math.min(255, light * 255 / MapTerrainS2CPacket.UNLIT);
                int color =
                        index == 0
                                ? MapTerrainS2CPacket.lit(
                                        image.palette().flat(entry, MapColors::color), light)
                                : l << 16 | l << 8 | l;
                int r = color >> 16 & 255, gr = color >> 8 & 255, b = color & 255;
                float left = -128 + 256f * x / w, right = -128 + 256f * (x + 1) / w;
                float top = -128 + 256f * z / h, bottom = -128 + 256f * (z + 1) / h;
                float u = index % columns * tile, v = index / columns * tile;
                buffer.addVertex(matrix, left, top, 0).setUv(u, v).setColor(r, gr, b, a);
                buffer.addVertex(matrix, left, bottom, 0).setUv(u, v + tile).setColor(r, gr, b, a);
                buffer.addVertex(matrix, right, bottom, 0)
                        .setUv(u + tile, v + tile)
                        .setColor(r, gr, b, a);
                buffer.addVertex(matrix, right, top, 0).setUv(u + tile, v).setColor(r, gr, b, a);
            }
        var mesh = buffer.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.disableBlend();
    }

    /** Tiles for every block state of the palette, rebuilt when patches add surfaces. */
    private static boolean atlas(MapPalette current) {
        if (current == palette && current.size() == paletteSize) return side > 0;
        palette = current;
        paletteSize = current.size();
        tileOf = new int[paletteSize];
        Map<Integer, Integer> byState = new HashMap<>();
        Map<Integer, int[]> tiles = new HashMap<>();
        int[] white = new int[MapColors.TILE * MapColors.TILE];
        Arrays.fill(white, -1);
        tiles.put(0, white);
        for (int entry = 0; entry < paletteSize; entry++) {
            int state = current.state(entry);
            if (state < 0) continue;
            Integer known = byState.get(state);
            if (known == null) {
                int[] pixels = MapColors.tile(state, current.flat(entry, MapColors::color));
                known = pixels == null ? 0 : tiles.size();
                if (pixels != null) tiles.put(known, pixels);
                byState.put(state, known);
            }
            tileOf[entry] = known;
        }
        columns = Math.max(1, (int) Math.ceil(Math.sqrt(tiles.size())));
        side = Integer.highestOneBit(columns * MapColors.TILE - 1) << 1;
        if (side == 0 || side > 4096) {
            side = 0;
            return false;
        }
        NativeImage image = new NativeImage(side, side, true);
        for (var tile : tiles.entrySet()) {
            int ox = tile.getKey() % columns * MapColors.TILE;
            int oy = tile.getKey() / columns * MapColors.TILE;
            for (int y = 0; y < MapColors.TILE; y++)
                for (int x = 0; x < MapColors.TILE; x++)
                    image.setPixelRGBA(ox + x, oy + y, tile.getValue()[y * MapColors.TILE + x]);
        }
        MapSnapshots.register(
                ATLAS,
                MipmapGenerator.generateMipLevels(new NativeImage[] {image}, MIPS),
                false,
                false);
        return true;
    }
}
