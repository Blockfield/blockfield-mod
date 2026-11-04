package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.network.packet.MapTerrainS2CPacket;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Top-face colors and textures from the client's own block models and resource packs, so modded
 * blocks no longer fall back to the server's coarse map color. Render thread only.
 */
final class MapColors {
    static final int TILE = 16;

    /** Flat color, or -1; the tinted first top texture as TILE² ABGR, or null. */
    private record Look(int color, int[] texture) {}

    private static final Look NONE = new Look(-1, null);
    private static final int[] MISSING = new int[0];
    private static final Map<Integer, Look> STATES = new HashMap<>();
    private static final Map<ResourceLocation, int[]> SPRITES = new HashMap<>();

    private MapColors() {}

    /** Per connection and after every resource reload. */
    static void clear() {
        STATES.clear();
        SPRITES.clear();
    }

    static int color(int state, int fallback) {
        int color = look(state).color;
        return color == -1 ? fallback : color;
    }

    /** TILE² opaque ABGR texture whose average is {@code flat}, or null without a texture. */
    static int[] tile(int state, int flat) {
        int[] texture = look(state).texture;
        return texture == null ? null : detail(texture, flat);
    }

    private static Look look(int state) {
        return state < 0 ? NONE : STATES.computeIfAbsent(state, MapColors::resolve);
    }

    private static Look resolve(int id) {
        BlockState state = Block.stateById(id);
        if (state.isAir()) return NONE;
        Minecraft mc = Minecraft.getInstance();
        try {
            var model = mc.getBlockRenderer().getBlockModel(state);
            List<BakedQuad> quads =
                    new ArrayList<>(model.getQuads(state, Direction.UP, RandomSource.create(42)));
            if (quads.isEmpty())
                for (BakedQuad quad : model.getQuads(state, null, RandomSource.create(42)))
                    if (quad.getDirection() == Direction.UP) quads.add(quad);
            long r = 0, g = 0, b = 0, n = 0;
            int[] texture = null;
            for (BakedQuad quad : quads) {
                int[] pixels = sprite(quad.getSprite());
                int color = average(pixels);
                if (color == -1) continue;
                // Without a level vanilla providers return their default grass and foliage tint.
                int tint =
                        quad.isTinted()
                                ? mc.getBlockColors()
                                        .getColor(state, null, null, quad.getTintIndex())
                                : -1;
                color = multiply(color, tint);
                if (texture == null) texture = tinted(pixels, tint);
                r += color >> 16 & 255;
                g += color >> 8 & 255;
                b += color & 255;
                n++;
            }
            if (n == 0) {
                int[] particle = sprite(model.getParticleIcon());
                int color = average(particle);
                return color == -1 ? NONE : new Look(MapTerrainS2CPacket.tone(color), particle);
            }
            return new Look(
                    MapTerrainS2CPacket.tone(
                            (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n)),
                    texture);
        } catch (RuntimeException error) {
            return NONE;
        }
    }

    private static int[] sprite(TextureAtlasSprite sprite) {
        return SPRITES.computeIfAbsent(sprite.contents().name(), MapColors::load);
    }

    /** The first animation frame, resampled to TILE² ABGR; empty when unreadable. */
    private static int[] load(ResourceLocation sprite) {
        ResourceLocation file = sprite.withPath(path -> "textures/" + path + ".png");
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(file);
                NativeImage image = NativeImage.read(NativeImage.Format.RGBA, in)) {
            int side = Math.min(image.getWidth(), image.getHeight());
            int[] abgr = new int[TILE * TILE];
            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++)
                    abgr[y * TILE + x] = image.getPixelRGBA(x * side / TILE, y * side / TILE);
            return abgr;
        } catch (IOException | RuntimeException error) {
            return MISSING;
        }
    }

    /** Alpha-weighted RGB of NativeImage ABGR pixels; -1 when fully transparent. */
    static int average(int[] abgr) {
        long r = 0, g = 0, b = 0, weight = 0;
        for (int pixel : abgr) {
            int alpha = pixel >>> 24;
            r += (pixel & 255) * alpha;
            g += (pixel >> 8 & 255) * alpha;
            b += (pixel >> 16 & 255) * alpha;
            weight += alpha;
        }
        if (weight == 0) return -1;
        return (int) (r / weight) << 16 | (int) (g / weight) << 8 | (int) (b / weight);
    }

    static int multiply(int color, int tint) {
        if (tint == -1) return color;
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8)
            result |= ((color >> shift & 255) * (tint >> shift & 255) / 255) << shift;
        return result;
    }

    private static int[] tinted(int[] abgr, int tint) {
        if (tint == -1) return abgr;
        int[] result = new int[abgr.length];
        // NativeImage keeps red in the low byte, tint colors keep it in the high one.
        int abgrTint = (tint & 255) << 16 | (tint & 0xFF00) | (tint >> 16 & 255);
        for (int p = 0; p < abgr.length; p++)
            result[p] = abgr[p] & 0xFF000000 | multiply(abgr[p] & 0xFFFFFF, abgrTint);
        return result;
    }

    /**
     * Texture detail on top of the flat map color: each texel keeps its offset from the texture
     * average, so a zoomed-in block averages to the same color as the flat map around it. Holes in
     * cutout textures (leaves) show the flat color.
     */
    static int[] detail(int[] abgr, int flat) {
        int average = average(abgr);
        if (average == -1) return null;
        int[] tile = new int[abgr.length];
        for (int p = 0; p < abgr.length; p++) {
            int pixel = abgr[p], color = 0xFF000000;
            for (int c = 0; c < 3; c++) {
                int base = flat >> (16 - 8 * c) & 255;
                int offset =
                        pixel >>> 24 < 128
                                ? 0
                                : (pixel >> 8 * c & 255) - (average >> (16 - 8 * c) & 255);
                color |= Math.max(0, Math.min(255, base + offset * 4 / 5)) << 8 * c;
            }
            tile[p] = color;
        }
        return tile;
    }
}
