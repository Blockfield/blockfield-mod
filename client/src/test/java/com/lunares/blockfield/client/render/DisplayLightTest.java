package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.math.Transformation;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DisplayLightTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static void assertNear(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-3);
        assertEquals(expected.y, actual.y, 1e-3);
        assertEquals(expected.z, actual.z, 1e-3);
    }

    @Test
    void blockCentreTurnsWithTheEntity() {
        assertNear(new Vector3f(0.5f), DisplayLight.centre(null, true, 0, 0));
        assertNear(new Vector3f(-0.5f, 0.5f, 0.5f), DisplayLight.centre(null, true, 90, 0));
        assertNear(new Vector3f(), DisplayLight.centre(null, false, 90, 0));
    }

    @Test
    void arrivalSamplesTheSyncedTransformationBeforeItsFirstTick() {
        var display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, null);
        display.setTransformation(
                new Transformation(
                        new Vector3f(20, 5, -3),
                        new Quaternionf(),
                        new Vector3f(2, 3, 4),
                        new Quaternionf()));
        assertNull(display.renderState());
        assertNear(
                new Vector3f(21, 6.5f, -1),
                DisplayLight.centre(DisplayLight.transformation(display), true, 0, 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataInvalidationDropsTheProbeCachedBeforeTheFirstTick() throws Exception {
        var display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, null);
        var cachedPos = new BlockPos(1, 2, 3);
        var probeType = Class.forName(DisplayLight.class.getName() + "$Probe");
        var constructor =
                probeType.getDeclaredConstructor(
                        Display.RenderState.class,
                        double.class,
                        double.class,
                        double.class,
                        BlockPos.class);
        constructor.setAccessible(true);
        Object probe = constructor.newInstance(null, 0, 0, 0, cachedPos);
        var cacheField = DisplayLight.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        var cache = (Map<Display, Object>) cacheField.get(null);
        cache.put(display, probe);
        try {
            assertEquals(cachedPos, DisplayLight.probe(display, BlockPos.ZERO));
            display.setTransformation(new Transformation(new Matrix4f().translation(20, 5, -3)));
            assertNull(display.renderState());
            DisplayLight.invalidate(display);
            assertFalse(cache.containsKey(display));
        } finally {
            DisplayLight.invalidate(display);
        }
    }

    @Test
    void containerWallByJbnlIsLitAboveItsBuriedOrigin() {
        // Overpass world-seed v10: a wall of the container at (-27.5, 90.5, 7.75), origin in grass.
        Matrix4f transformation =
                new Matrix4f()
                        .translation(-0.5f, 1.5f, -0.5f)
                        .rotate(new Quaternionf(0.706433f, -0.706433f, 0.030859f, -0.030859f))
                        .scale(2.0f, 1.0f, 1.0f);
        assertNear(
                new Vector3f(-0.9545f, 0.5f, -1.0417f),
                DisplayLight.centre(transformation, true, 0, 0));
    }

    private static ToIntFunction<BlockPos> world(Map<BlockPos, Integer> open) {
        return at -> open.getOrDefault(at, -1);
    }

    @Test
    void viaductTakesTheSkyItIsDrawnInNotTheLedgeOverItsOrigin() {
        // Overpass world-seed v12: the viaduct's shared origin (-54.5, 95.56, -3.88) is open but
        // under a ledge (sky 9, block 1); a deck slab is drawn in open sky.
        var origin = new BlockPos(-55, 95, -4);
        var sky = new BlockPos(-30, 96, -5);
        assertEquals(
                sky,
                DisplayLight.brightest(
                        origin,
                        new Vector3f(-31f, 96.3f, -6f),
                        new Vector3f(-29f, 97.1f, -4f),
                        world(Map.of(origin, 10, sky, 15))));
    }

    @Test
    void canalWallBandIsLitFromTheBarrierOverTheGrass() {
        // A smooth_stone wall piece x -40.58..-39.02, y 90.78..92.74 with its origin and centre
        // in grass; only the barrier row (-41, 92, z) above the grass is open, at light 15.
        var origin = new BlockPos(-41, 89, -7);
        var barriers = new java.util.HashMap<BlockPos, Integer>();
        for (int z = -7; z <= -3; z++) barriers.put(new BlockPos(-41, 92, z), 15);
        BlockPos lit =
                DisplayLight.brightest(
                        origin,
                        new Vector3f(-40.58f, 90.78f, -7f),
                        new Vector3f(-39.02f, 92.74f, -2f),
                        world(barriers));
        assertEquals(15, barriers.get(lit));
    }

    @Test
    void enclosedPieceSearchesAroundItsBoxAndPrefersTheLitSide() {
        var origin = new BlockPos(0, 0, 0);
        var pocket = new BlockPos(-1, 1, 1);
        var open = new BlockPos(1, 2, 1);
        assertEquals(
                open,
                DisplayLight.brightest(
                        origin,
                        new Vector3f(0f, 1f, 0f),
                        new Vector3f(1f, 2f, 2f),
                        world(Map.of(pocket, 0, open, 15))));
        assertEquals(
                origin,
                DisplayLight.brightest(
                        origin, new Vector3f(0f), new Vector3f(1f), world(Map.of())));
    }

    @Test
    void samplesSpreadAcrossLongSpansAndKeepBothEnds() {
        assertArrayEquals(new int[] {3}, DisplayLight.samples(3, 3));
        assertArrayEquals(new int[] {0, 1}, DisplayLight.samples(0, 1));
        assertArrayEquals(new int[] {-56, -42, -28, -14}, DisplayLight.samples(-56, -14));
    }
}
