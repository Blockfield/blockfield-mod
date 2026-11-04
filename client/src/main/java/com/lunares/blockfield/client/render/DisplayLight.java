package com.lunares.blockfield.client.render;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where a display samples its light (DisplayLightProbeMixin).
 *
 * <p>Vanilla lights a whole display from the block at its origin, but maps put origins far from
 * what they draw. On Overpass ~560 origins sit in dirt or grass (canal walls, the container by
 * JBNL) and render black, and the 378-piece railway viaduct shares one origin under a ledge at sky
 * light 9 while it stands in open sky. So a display takes the brightest open block among its origin
 * and the blocks it draws through, else the open blocks around those.
 */
public final class DisplayLight {
    // Samples per axis of the drawn box: a long piece is lit from a few points along it.
    private static final int SAMPLES = 4;
    private static final Vector3f[] UNIT_CORNERS = new Vector3f[8];
    // Static map geometry never changes its render state or position, so the chosen block holds;
    // the batcher asks every display for its light twice a second.
    private static final Map<Display, Probe> CACHE = new WeakHashMap<>();

    static {
        for (int i = 0; i < 8; i++) UNIT_CORNERS[i] = new Vector3f(i & 1, i >> 1 & 1, i >> 2 & 1);
    }

    private record Probe(Display.RenderState state, double x, double y, double z, BlockPos pos) {}

    private DisplayLight() {}

    public static void invalidate(Display display) {
        CACHE.remove(display);
    }

    static Matrix4f transformation(Display display) {
        Display.RenderState state = display.renderState();
        return state == null
                ? Display.createTransformation(display.getEntityData()).getMatrix()
                : state.transformation().get(1.0f).getMatrix();
    }

    public static BlockPos probe(Display display, BlockPos origin) {
        Display.RenderState state = display.renderState();
        Probe cached = CACHE.get(display);
        if (cached != null
                && cached.state() == state
                && cached.x() == display.getX()
                && cached.y() == display.getY()
                && cached.z() == display.getZ()) return cached.pos();
        Level level = display.level();
        Matrix4f transformation = transformation(display);
        boolean block = display instanceof Display.BlockDisplay;
        var min = new Vector3f(Float.POSITIVE_INFINITY);
        var max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (Vector3f corner : block ? UNIT_CORNERS : new Vector3f[] {new Vector3f()}) {
            Vector3f drawn =
                    drawn(transformation, corner, display.getYRot(), display.getXRot())
                            .add(
                                    (float) display.getX(),
                                    (float) display.getY(),
                                    (float) display.getZ());
            min.min(drawn);
            max.max(drawn);
        }
        ToIntFunction<BlockPos> light =
                at ->
                        level.getBlockState(at).isSolidRender(level, at)
                                ? -1
                                : level.getBrightness(LightLayer.SKY, at)
                                        + level.getBrightness(LightLayer.BLOCK, at);
        BlockPos pos = brightest(origin, min, max, light);
        // A display can arrive before its blocks or their light: an unloaded neighbour reads as
        // dark air, and caching that pick left canal walls black for good. Ask again until real.
        if (level.hasChunksAt(
                        Mth.floor(min.x) - 1,
                        Mth.floor(min.z) - 1,
                        Mth.floor(max.x) + 1,
                        Mth.floor(max.z) + 1)
                && light.applyAsInt(pos) > 0)
            CACHE.put(
                    display, new Probe(state, display.getX(), display.getY(), display.getZ(), pos));
        return pos;
    }

    /**
     * The brightest open block among the origin and the drawn box, else among the blocks around the
     * box; the origin when everything is solid. {@code light} is -1 for a solid block.
     */
    static BlockPos brightest(
            BlockPos origin, Vector3f min, Vector3f max, ToIntFunction<BlockPos> light) {
        BlockPos best = origin;
        int bestLight = light.applyAsInt(origin);
        // The ring around the box is searched only when the origin and the box are all solid.
        for (int grow = 0; grow == 0 || grow == 1 && bestLight < 0; grow++) {
            int x0 = Mth.floor(min.x) - grow, x1 = Mth.floor(max.x - 1e-4f) + grow;
            int y0 = Mth.floor(min.y) - grow, y1 = Mth.floor(max.y - 1e-4f) + grow;
            int z0 = Mth.floor(min.z) - grow, z1 = Mth.floor(max.z - 1e-4f) + grow;
            for (int x : samples(x0, x1))
                for (int y : samples(y0, y1))
                    for (int z : samples(z0, z1)) {
                        BlockPos at = new BlockPos(x, y, z);
                        int value = light.applyAsInt(at);
                        if (value > bestLight) {
                            best = at;
                            bestLight = value;
                        }
                    }
        }
        return best;
    }

    /** At most SAMPLES evenly spread values from {@code from} to {@code to}, both included. */
    static int[] samples(int from, int to) {
        int count = Math.min(SAMPLES, to - from + 1);
        int[] values = new int[count];
        for (int i = 0; i < count; i++)
            values[i] = count == 1 ? from : from + Math.round(i * (to - from) / (count - 1f));
        return values;
    }

    /** Offset from the entity position to the centre of what it draws (see {@link #drawn}). */
    static Vector3f centre(Matrix4f transformation, boolean block, float yRot, float xRot) {
        return drawn(transformation, block ? new Vector3f(0.5f) : new Vector3f(), yRot, xRot);
    }

    /**
     * Offset from the entity position to a point of the display's local model space (the unit block
     * of a block_display, the anchor of text and items), through its transformation and the entity
     * rotation DisplayRenderer applies for a FIXED billboard.
     */
    static Vector3f drawn(Matrix4f transformation, Vector3f point, float yRot, float xRot) {
        Vector3f result = new Vector3f(point);
        if (transformation != null) transformation.transformPosition(result);
        return new Quaternionf()
                .rotationYXZ(-Mth.DEG_TO_RAD * yRot, Mth.DEG_TO_RAD * xRot, 0.0f)
                .transform(result);
    }
}
