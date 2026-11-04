package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.lunares.blockfield.oracle.OracleTransformAudit;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * U6b Create hanging-pose execution fixture (ADR4 §§4.5/5.3, §10). Closes the gap U6 left in {@code
 * CreateSbwServerOracleFixture.hangingPoseBytecodePin} ("Catnip's clock is absent" — that was the
 * SERVER runner). Catnip ships inside Create bf4 (nested {@code
 * META-INF/jars/Ponder-Fabric-1.21.1-1.0.69.jar} contains {@code net/createmod/catnip/**}), and the
 * CLIENT transformed runner (full pack, 188 mods) loads it, so this fixture executes the real
 * native hanging-pose code — private {@code PlayerSkyhookRenderer.setHangingPose} including its
 * observer Catnip clock read — on a remote player model with production mixins applied.
 *
 * <p>The observer clock singleton is driven with exact times ({@code reset} + {@code tick} for
 * whole ticks, a stubbed {@code DeltaTracker} partial for fractions) instead of mocking the pose
 * code. Every sample runs on a freshly baked vanilla {@link PlayerModel} (wide and slim), so no
 * retained model state leaks between samples.
 *
 * <p>Two fragments: the raw native trajectory at 30/60/144/240 FPS with seeded random frame phases
 * next to the deterministic replacement trajectory (accepted-membership phase, ADR4 §4.5), and the
 * bitwise equality case the replacement must satisfy. Expected values come from {@link
 * #expectedBits}, a line-for-line mirror of server {@code CreateBodyPose.apply} (same base
 * revision, including the exact double-pi cycle division and Catnip {@code AngleHelper.rad}) fed
 * with the baked vanilla pivots — never the port's hardcoded mesh table, so pivot drift fails
 * loudly. A fallback/unavailable result is never an equality pass.
 */
class CreateHangingOracleFixture {
    private static final String RENDERER =
            "com.simibubi.create.foundation.render.PlayerSkyhookRenderer";
    private static final String CLOCK = "net.createmod.catnip.animation.AnimationTickHolder";

    private static final int[] FPS = {30, 60, 144, 240};
    private static final int WINDOW_TICKS = 8;
    private static final long PHASE_SEED = 0xC0E4E6L;

    /** Part order shared with the server pose evaluators: head, body, arms, legs. */
    private static final String[] PARTS = {
        "head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg"
    };

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void hangingPoseNativeTrajectory() {
        AnimSyncOracleRunner.run(
                "create",
                "hanging-pose-native-trajectory",
                () -> {
                    var clock = clock();
                    try (var statics = mockStatic(Minecraft.class)) {
                        var minecraft = mock(Minecraft.class);
                        statics.when(Minecraft::getInstance).thenReturn(minecraft);
                        when(minecraft.isPaused()).thenReturn(false);
                        var tracker = mock(DeltaTracker.class);
                        when(minecraft.getTimer()).thenReturn(tracker);
                        var trajectories = new LinkedHashMap<String, Object>();
                        for (boolean slim : List.of(false, true)) {
                            var sweeps = new ArrayList<Object>();
                            for (int fps : FPS) sweeps.add(sweep(clock, tracker, slim, fps));
                            trajectories.put(slim ? "slim" : "wide", sweeps);
                        }
                        var result = new LinkedHashMap<String, Object>();
                        result.put("fps", Arrays.toString(FPS));
                        result.put("windowTicks", WINDOW_TICKS);
                        result.put("phaseSeed", PHASE_SEED);
                        result.put("trajectories", trajectories);
                        OracleTransformAudit.requireConfig("create.mixins.json");
                        return result;
                    } finally {
                        clock.resetAll();
                    }
                });
    }

    @Test
    void hangingPoseDeterministicEquality() {
        AnimSyncOracleRunner.run(
                "create",
                "hanging-pose-deterministic-equality",
                () -> {
                    var clock = clock();
                    try (var statics = mockStatic(Minecraft.class)) {
                        var minecraft = mock(Minecraft.class);
                        statics.when(Minecraft::getInstance).thenReturn(minecraft);
                        when(minecraft.isPaused()).thenReturn(false);
                        var tracker = mock(DeltaTracker.class);
                        when(minecraft.getTimer()).thenReturn(tracker);
                        var result = new LinkedHashMap<String, Object>();
                        int checked = 0;
                        var mismatches = new ArrayList<String>();
                        for (boolean slim : List.of(false, true)) {
                            clock.resetAll();
                            var rows = new ArrayList<Object>();
                            for (var sample : equalitySamples()) {
                                int tick = sample[0];
                                float partial = Float.intBitsToFloat(sample[1]);
                                clock.driveTo(tick);
                                when(tracker.getGameTimeDeltaPartialTick(false))
                                        .thenReturn(partial);
                                int ticks = (int) clock.getTicks.invoke(null, true);
                                assertEquals(tick, ticks, "clock must hold the driven tick");
                                float nativeTime =
                                        (float) ticks + (float) clock.getPartial.invoke(null);
                                // Accepted-membership phase construction with anchor 0/u0 0 must
                                // reproduce the observer clock float bitwise (ADR4 §4.5).
                                float base = (float) (0 + (tick - 0));
                                float phase = base + partial;
                                assertEquals(
                                        Float.floatToRawIntBits(nativeTime),
                                        Float.floatToRawIntBits(phase),
                                        "phase construction must equal the native clock");
                                var model = baked(slim);
                                float[] pivots = initialPivots(model, slim);
                                clock.pose.invoke(null, model);
                                int[] nativeBits = snapshot(model);
                                int[] expected = expectedBits(pivots, phase);
                                compareBits(nativeBits, expected, tick, partial, slim, mismatches);
                                checked++;
                                var row = new LinkedHashMap<String, Object>();
                                row.put("tick", tick);
                                row.put("partialBits", sample[1]);
                                row.put("native", nativeBits);
                                row.put("expected", expected);
                                rows.add(row);
                            }
                            result.put(slim ? "slim" : "wide", rows);
                        }
                        assertTrue(
                                mismatches.isEmpty(),
                                "native-vs-replacement bitwise mismatches: " + mismatches);
                        result.put("samplesChecked", checked);
                        OracleTransformAudit.requireConfig("create.mixins.json");
                        return result;
                    } finally {
                        clock.resetAll();
                    }
                });
    }

    // --- native trajectory: exact FPS sampling with random frame phases ---

    /** One FPS sweep: native bits plus the deterministic replacement at the same samples. */
    private static LinkedHashMap<String, Object> sweep(
            Clock clock, DeltaTracker tracker, boolean slim, int fps) throws Exception {
        clock.resetAll();
        float frameMs = 1000f / fps;
        var rand = new Random(PHASE_SEED + fps);
        var plan = new ArrayList<int[]>();
        int frames = (int) Math.ceil(WINDOW_TICKS * 50f / frameMs);
        for (int f = 0; f < frames; f++) {
            float ms =
                    Math.min(f * frameMs + rand.nextFloat() * frameMs, WINDOW_TICKS * 50f - 0.001f);
            float total = 1 + ms / 50f;
            int tick = (int) total;
            plan.add(new int[] {tick, Float.floatToRawIntBits(total - tick)});
        }
        plan.sort(
                (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        var rows = new ArrayList<Object>();
        float maxDeviation = 0;
        for (var sample : plan) {
            int tick = sample[0];
            float partial = Float.intBitsToFloat(sample[1]);
            clock.driveTo(tick);
            when(tracker.getGameTimeDeltaPartialTick(false)).thenReturn(partial);
            var model = baked(slim);
            float[] pivots = initialPivots(model, slim);
            clock.pose.invoke(null, model);
            int[] nativeBits = snapshot(model);
            // Deterministic replacement since presentation enter at tick 1 (ADR4 §4.5):
            // phase zero is the constructor rule, never claimed as native.
            float deterministicPhase = (float) (0 + (tick - 1)) + partial;
            int[] deterministic = expectedBits(pivots, deterministicPhase);
            maxDeviation = Math.max(maxDeviation, maxAbsDeviation(nativeBits, deterministic));
            var row = new LinkedHashMap<String, Object>();
            row.put("tick", tick);
            row.put("partialBits", sample[1]);
            row.put("native", nativeBits);
            row.put("deterministic", deterministic);
            rows.add(row);
        }
        // Same final time after other samples must pose identically: the native code reads only
        // the observer clock, never render history.
        var first = plan.get(0);
        clock.resetAll();
        clock.driveTo(first[0]);
        when(tracker.getGameTimeDeltaPartialTick(false)).thenReturn(Float.intBitsToFloat(first[1]));
        var firstModel = baked(slim);
        // Re-sample on a fresh model at the same driven time for the comparison.
        clock.pose.invoke(null, firstModel);
        assertArrayEquals(
                (int[]) ((LinkedHashMap<String, Object>) rows.get(0)).get("native"),
                snapshot(firstModel),
                fps + " FPS: repeated sample must be bit-identical");
        // Sensitivity: the clock must actually drive the pose, else nothing was executed.
        boolean varied = false;
        var firstBits = (int[]) ((LinkedHashMap<String, Object>) rows.get(0)).get("native");
        for (Object row : rows)
            if (!Arrays.equals(
                    firstBits, (int[]) ((LinkedHashMap<String, Object>) row).get("native"))) {
                varied = true;
                break;
            }
        assertTrue(varied, fps + " FPS: hanging pose must move with the clock");
        var sweep = new LinkedHashMap<String, Object>();
        sweep.put("fps", fps);
        sweep.put("samples", rows);
        sweep.put("maxNativeVsDeterministicDeviation", maxDeviation);
        return sweep;
    }

    // --- shared native plumbing ---

    private static final class Clock {
        final Method reset;
        final Method tick;
        final Method getTicks;
        final Method getPartial;
        final Method pose;
        int driven;

        Clock(Method reset, Method tick, Method getTicks, Method getPartial, Method pose) {
            this.reset = reset;
            this.tick = tick;
            this.getTicks = getTicks;
            this.getPartial = getPartial;
            this.pose = pose;
        }

        void driveTo(int target) throws Exception {
            while (driven < target) {
                tick.invoke(null);
                driven++;
            }
        }

        void resetAll() throws Exception {
            reset.invoke(null);
            driven = 0;
        }
    }

    private static Clock clock() throws Exception {
        Class<?> holder = Class.forName(CLOCK);
        Class<?> renderer = Class.forName(RENDERER);
        var pose = renderer.getDeclaredMethod("setHangingPose", HumanoidModel.class);
        pose.setAccessible(true);
        return new Clock(
                holder.getMethod("reset"),
                holder.getMethod("tick"),
                holder.getMethod("getTicks", boolean.class),
                holder.getMethod("getPartialTicks"),
                pose);
    }

    private static PlayerModel<LivingEntity> baked(boolean slim) {
        return new PlayerModel<>(
                LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64)
                        .bakeRoot(),
                slim);
    }

    /**
     * Baked vanilla pivots (x, y, z per part) with zero rotations. They must equal the server
     * port's mesh table, including every baked z 0.0: setHangingPose never writes z.
     */
    private static float[] initialPivots(PlayerModel<LivingEntity> model, boolean slim) {
        var parts = ordered(model);
        float armY = slim ? 2.5f : 2f;
        float[] wantX = {0, 0, 5, -5, 1.9f, -1.9f};
        float[] wantY = {0, 0, armY, armY, 12, 12};
        var pivots = new float[18];
        for (int i = 0; i < 6; i++) {
            var initial = parts[i].getInitialPose();
            assertEquals(wantX[i], parts[i].x, "baked pivot x for " + PARTS[i]);
            assertEquals(wantX[i], initial.x, "initial pivot x for " + PARTS[i]);
            assertEquals(wantY[i], parts[i].y, "baked pivot y for " + PARTS[i]);
            assertEquals(wantY[i], initial.y, "initial pivot y for " + PARTS[i]);
            assertEquals(0f, parts[i].z, "baked pivot z for " + PARTS[i]);
            assertEquals(0f, initial.z, "initial pivot z for " + PARTS[i]);
            assertEquals(0f, initial.xRot, "initial xRot for " + PARTS[i]);
            assertEquals(0f, initial.yRot, "initial yRot for " + PARTS[i]);
            assertEquals(0f, initial.zRot, "initial zRot for " + PARTS[i]);
            pivots[i * 3] = initial.x;
            pivots[i * 3 + 1] = initial.y;
            pivots[i * 3 + 2] = initial.z;
        }
        return pivots;
    }

    private static ModelPart[] ordered(PlayerModel<LivingEntity> model) {
        return new ModelPart[] {
            model.head, model.body, model.leftArm, model.rightArm, model.leftLeg, model.rightLeg
        };
    }

    private static int[] snapshot(HumanoidModel<LivingEntity> model) {
        var parts =
                new ModelPart[] {
                    model.head,
                    model.body,
                    model.leftArm,
                    model.rightArm,
                    model.leftLeg,
                    model.rightLeg
                };
        var bits = new int[parts.length * 6];
        int i = 0;
        for (var part : parts) {
            bits[i++] = Float.floatToRawIntBits(part.x);
            bits[i++] = Float.floatToRawIntBits(part.y);
            bits[i++] = Float.floatToRawIntBits(part.z);
            bits[i++] = Float.floatToRawIntBits(part.xRot);
            bits[i++] = Float.floatToRawIntBits(part.yRot);
            bits[i++] = Float.floatToRawIntBits(part.zRot);
        }
        return bits;
    }

    /** Equality samples in clock order: whole ticks plus fractional phases inside one tick. */
    private static List<int[]> equalitySamples() {
        var samples = new ArrayList<int[]>();
        for (int tick = 1; tick <= 24; tick++)
            samples.add(new int[] {tick, Float.floatToRawIntBits(0f)});
        for (float partial : new float[] {0.13f, 0.3f, 0.5f, 0.77f, 0.99999f})
            samples.add(new int[] {7, Float.floatToRawIntBits(partial)});
        samples.add(new int[] {3, Float.floatToRawIntBits(0.99999f)});
        samples.sort(
                (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        return samples;
    }

    /**
     * Compares native bits against the replacement mirror: every value must match bitwise. The
     * mirror is fed the baked pivots; {@link #initialPivots} pins them to the port's mesh table.
     */
    private static void compareBits(
            int[] nativeBits,
            int[] expected,
            int tick,
            float partial,
            boolean slim,
            List<String> mismatches) {
        String mesh = slim ? "slim" : "wide";
        for (int i = 0; i < 36; i++) {
            if (nativeBits[i] != expected[i])
                mismatches.add(
                        mesh
                                + " tick "
                                + tick
                                + " partial "
                                + partial
                                + " index "
                                + i
                                + " ("
                                + PARTS[i / 6]
                                + "."
                                + ("xyz".charAt((i % 6) % 3))
                                + (i % 6 < 3 ? "" : "Rot")
                                + "): native "
                                + Integer.toHexString(nativeBits[i])
                                + " expected "
                                + Integer.toHexString(expected[i]));
        }
    }

    private static float maxAbsDeviation(int[] a, int[] b) {
        float max = 0;
        for (int i = 0; i < a.length; i++)
            max = Math.max(max, Math.abs(Float.intBitsToFloat(a[i]) - Float.intBitsToFloat(b[i])));
        return max;
    }

    // --- deterministic replacement mirror ---
    //
    // Line-for-line mirror of server CreateBodyPose.apply at the base revision (including the
    // exact double-pi cycle division and Catnip AngleHelper.rad), except pivots come from the
    // baked model above instead of the hardcoded mesh() table. If apply() changes, this mirror
    // must change with it: the equality test fails otherwise.

    private static final class Cell {
        float x;
        float y;
        float z;
        float xRot;
        float yRot;
        float zRot;
    }

    /** Exact mirror of Catnip AngleHelper.rad(double) (Ponder 1.0.69 bytecode). */
    private static float rad(double degrees) {
        if (degrees == 0) return 0;
        return (float) (degrees / 180 * Math.PI);
    }

    static int[] expectedBits(float[] pivots, float time) {
        var p = new Cell[6];
        for (int i = 0; i < 6; i++) {
            p[i] = new Cell();
            p[i].x = pivots[i * 3];
            p[i].y = pivots[i * 3 + 1];
            p[i].z = pivots[i * 3 + 2];
            p[i].xRot = p[i].yRot = p[i].zRot = 0;
        }
        float mainCycle = Mth.sin((float) (((time + 10) * 0.3f) / Math.PI));
        float limbCycle = Mth.sin((float) ((time * 0.3f) / Math.PI));
        float bodySwing = rad(15 + mainCycle * 10);
        float limbSwing = rad(limbCycle * 15);

        p[1].zRot = bodySwing;
        p[0].zRot = bodySwing;

        p[3].y -= 3;

        float armPivotX = p[3].x * Mth.cos(bodySwing) - p[3].y * Mth.sin(bodySwing) + 4.5f;
        float armPivotY = p[3].x * Mth.sin(bodySwing) + p[3].y * Mth.cos(bodySwing) + 2;
        p[3].xRot = -rad(150.0);
        p[3].zRot = rad(15.0);

        float leftX = p[2].x * Mth.cos(bodySwing) - p[2].y * Mth.sin(bodySwing);
        float leftY = p[2].x * Mth.sin(bodySwing) + p[2].y * Mth.cos(bodySwing);
        p[2].x = leftX;
        p[2].y = leftY;
        p[2].zRot = -rad(20.0) + 0.5f * bodySwing + limbSwing;

        p[5].y -= 0.2f;
        float rightLegX = p[5].x * Mth.cos(bodySwing) - p[5].y * Mth.sin(bodySwing);
        float rightLegY = p[5].x * Mth.sin(bodySwing) + p[5].y * Mth.cos(bodySwing);
        p[5].x = rightLegX;
        p[5].y = rightLegY;
        p[5].xRot = -rad(25.0);
        p[5].zRot = rad(10.0) + 0.5f * bodySwing + limbSwing;

        p[4].y -= 0.8f;
        float leftLegX = p[4].x * Mth.cos(bodySwing) - p[4].y * Mth.sin(bodySwing);
        float leftLegY = p[4].x * Mth.sin(bodySwing) + p[4].y * Mth.cos(bodySwing);
        p[4].x = leftLegX;
        p[4].y = leftLegY;
        p[4].xRot = rad(10.0);
        p[4].zRot = -rad(10.0) + 0.5f * bodySwing + limbSwing;

        for (int i : new int[] {0, 1, 2, 4, 5}) p[i].x -= armPivotX;
        for (int i : new int[] {0, 1, 2, 4, 5}) p[i].y -= armPivotY;

        var bits = new int[36];
        for (int i = 0; i < 6; i++) {
            bits[i * 6] = Float.floatToRawIntBits(p[i].x);
            bits[i * 6 + 1] = Float.floatToRawIntBits(p[i].y);
            bits[i * 6 + 2] = Float.floatToRawIntBits(p[i].z);
            bits[i * 6 + 3] = Float.floatToRawIntBits(p[i].xRot);
            bits[i * 6 + 4] = Float.floatToRawIntBits(p[i].yRot);
            bits[i * 6 + 5] = Float.floatToRawIntBits(p[i].zRot);
        }
        return bits;
    }
}
