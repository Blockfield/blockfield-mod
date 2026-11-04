package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CombatSceneClockTest {
    static PlayerSnapshotS2CPacket batch(long tick) {
        return PlayerSnapshotS2CPacket.animation(
                17,
                tick,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(0, 0, List.of(), List.of(), List.of()));
    }

    static CombatRenderReceipt receipt(long high, long tick, int alpha) {
        return new CombatRenderReceipt(
                17,
                high,
                tick,
                alpha,
                Map.of(),
                new CombatRenderReceipt.Exact(
                        3,
                        1,
                        0,
                        0,
                        new Time(tick, alpha),
                        0,
                        0,
                        new AnimationResources.Digest(0, 0, 0, 0),
                        new AnimationStartReport(List.of()),
                        null));
    }

    @Test
    void sameArrivalsAndFramesIgnoreClientTickPhase() {
        var a = new CombatSceneClock();
        var b = new CombatSceneClock();
        for (int n = 0; n < 4; n++) {
            a.arrival(batch(1000 + n), n * 50);
            b.arrival(batch(1000 + n), n * 50);
            assertEquals(n == 3, a.frame(n * 50));
            assertEquals(n == 3, b.frame(n * 50));
        }
        // Native client tick phase 0 vs 23ms has no input to this scene API.
        // At warm-up H=1003, delay=3 => R=1000. Next renders precede the 175ms servo
        // update: each adds 10/50=.2 tick, quantized downward by 1/65536.
        assertEquals(1000, a.time());
        assertEquals(1000, b.time());
        double[] expected = {1000.1999969482422, 1000.3999938964844};
        for (int i = 0; i < expected.length; i++) {
            assertTrue(a.frame(160 + i * 10));
            assertTrue(b.frame(160 + i * 10));
            assertEquals(expected[i], a.time());
            assertEquals(expected[i], b.time());
        }
    }

    @Test
    void delayTwoAndSixHaveTheirOwnHorizonTarget() {
        assertEquals(1, CombatSceneClock.servo(2.5, 2, .1));
        assertEquals(1, CombatSceneClock.servo(6.5, 6, .1));
        assertEquals(.925, CombatSceneClock.servo(5, 6, .1), 1e-12);
        assertEquals(1.075, CombatSceneClock.servo(8, 6, .1), 1e-12);
        assertEquals(1.1, CombatSceneClock.servo(20, 6, .1), 1e-12);
        assertEquals(.9, CombatSceneClock.servo(0, 6, .1), 1e-12);
    }

    @Test
    void holdClampsAtHAndTimeoutIsStrictGreater() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        assertTrue(c.frame(950));
        assertEquals(1009, c.time());
        assertFalse(c.timedOut(950));
        assertFalse(c.frame(950.001));
        assertTrue(c.timedOut(950.001));
    }

    @Test
    void arrivalsAtDeadlineCatchUpWithoutSnap() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        c.arrival(batch(1019), 950);
        assertTrue(c.frame(950));
        assertEquals(0, c.recoverySnaps());
        assertEquals(1019, c.time(), 1e-12, "catch-up runs R to the live edge, no snap");
        for (int n = 20; n <= 59; n++) {
            c.arrival(batch(1000 + n), 950 + (n - 19) * 50);
            assertTrue(c.frame(950 + (n - 19) * 50));
        }
        assertEquals(0, c.recoverySnaps());
        assertTrue(c.occupancy() <= c.delay() + 1, "catch-up returns lag toward the servo band");
    }

    @Test
    void orderedOldBacklogCatchesUpWithoutSnap() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        double held = c.time();
        for (int n = 10; n <= 20; n++) c.arrival(batch(1000 + n), 1000);
        assertTrue(c.frame(1000));
        assertEquals(0, c.recoverySnaps());
        assertEquals(1020, c.time(), 1e-12, "catch-up runs R to the live edge, no snap");
        assertTrue(c.time() > held);
        for (int n = 21; n <= 60; n++) {
            c.arrival(batch(1000 + n), 1000 + (n - 20) * 50);
            assertTrue(c.frame(1000 + (n - 20) * 50));
        }
        assertEquals(0, c.recoverySnaps());
        assertTrue(c.rate() <= 1.1);
        assertTrue(c.occupancy() <= c.delay() + 1, "catch-up returns lag toward the servo band");
    }

    @Test
    void catchUpRateLaw() {
        assertEquals(1, CombatSceneClock.catchUpRate(3, 3, .1), 1e-12);
        assertEquals(2, CombatSceneClock.catchUpRate(5, 3, .1), 1e-12);
        assertEquals(3, CombatSceneClock.catchUpRate(20, 3, .1), 1e-12);
        assertEquals(3, CombatSceneClock.catchUpRate(60, 3, .1), 1e-12);
        assertEquals(.9, CombatSceneClock.catchUpRate(0, 3, .1), 1e-12);
    }

    /**
     * Synthetic server stalls of 0.25/1/3 s, as a catch-up burst (ticks replayed back to back) or a
     * gap (ticks never sent). Under the lag bound R stays continuous and converges below the age
     * bound with no snap; beyond it R snaps once. Rendered stays pose(R) throughout, so hit tickets
     * stay exact.
     */
    @Test
    void stallCatchUpBurstOrGap() {
        stallTrace(250, true, 0);
        stallTrace(1000, true, 0);
        stallTrace(3000, true, 1);
        stallTrace(250, false, 0);
        stallTrace(1000, false, 0);
        stallTrace(3000, false, 1);
    }

    /**
     * A spread burst: the replayed ticks arrive 2 ms apart (as the real server replay does), not
     * all at once. The catch-up budget is per stall episode, so a 1500 ms stall still snaps once
     * instead of gliding forever.
     */
    @Test
    void spreadBurstBudgetIsPerEpisode() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            assertEquals(n >= 3, c.frame(n * 50), "warm-up frame " + n);
        }
        double burstAt = 450 + 1500;
        double lastNow = 450;
        for (double t = 466; t < burstAt; t += 16) {
            c.frame(t);
            lastNow = t;
        }
        double previous = c.time();
        long snaps = c.recoverySnaps();
        // The old law froze R through the whole burst; catch-up advances every frame.
        int advancing = 0;
        for (long k = 1010; k < 1040; k++) {
            double now = burstAt + (k - 1010) * 2;
            c.arrival(batch(k), now);
            assertTrue(c.frame(now), "spread tick " + k);
            assertTrue(c.time() >= previous, "R never decreases");
            if (c.time() > previous) advancing++;
            if (c.recoverySnaps() == snaps) {
                assertTrue(
                        c.time() - previous <= 3 * (now - lastNow) / 50 + 1e-4,
                        "no R discontinuity at spread tick " + k);
            } else {
                snaps = c.recoverySnaps();
            }
            previous = c.time();
            lastNow = now;
        }
        assertEquals(30, advancing, "catch-up advances R during the spread burst, no freeze");
        double t = lastNow;
        double end = burstAt + 2000;
        double nextArrival = burstAt + 60 + 50;
        long lastTick = 1040;
        boolean belowAgeBound = false;
        boolean converged = false;
        while (t < end) {
            t += 16;
            if (t >= nextArrival) {
                c.arrival(batch(++lastTick), nextArrival);
                nextArrival += 50;
            }
            assertTrue(c.frame(t), "spread tail at " + t);
            assertTrue(c.time() >= previous, "R never decreases at " + t);
            if (c.recoverySnaps() == snaps) {
                assertTrue(
                        c.time() - previous <= 3 * (t - lastNow) / 50 + 1e-4,
                        "no R discontinuity at " + t);
                if (c.occupancy() <= c.ageBound()) {
                    if (t - burstAt <= 900) belowAgeBound = true;
                    if (c.occupancy() <= c.delay() + 1
                            && c.occupancy() >= c.delay() - 1
                            && c.rate() <= 1.1) {
                        converged = true;
                        break;
                    }
                }
            } else {
                snaps = c.recoverySnaps();
            }
            previous = c.time();
            lastNow = t;
        }
        assertEquals(1, c.recoverySnaps(), "spread burst snaps once the episode budget ends");
        assertTrue(belowAgeBound, "converges below the age bound");
        assertTrue(converged, "converges back to servo regulation");
    }

    /**
     * A real server skip: skipped ticks are not numbered, the counter continues and whole batches
     * arrive late. No records are missing, so R only catches up and never snaps.
     */
    @Test
    void lateContinuousStreamNeverSnaps() {
        for (long stallMs : new long[] {250, 1000, 3000}) {
            String what = stallMs + "ms late stream";
            var c = new CombatSceneClock();
            for (int n = 0; n < 10; n++) {
                c.arrival(batch(1000 + n), n * 50);
                assertEquals(n >= 3, c.frame(n * 50), what);
            }
            double previous = c.time();
            for (int n = 10; n < 50; n++) {
                double now = n * 50 + stallMs;
                if (n == 10) {
                    for (double t = 466; t < now; t += 16) c.frame(t);
                }
                c.arrival(batch(1000 + n), now);
                assertTrue(c.frame(now), what + " tick " + n);
                assertTrue(c.time() >= previous, what + " R never decreases");
                previous = c.time();
            }
            assertEquals(0, c.recoverySnaps(), what);
            assertTrue(
                    c.occupancy() <= c.delay() + 1,
                    what + " lag returns toward the servo band, occupancy " + c.occupancy());
        }
    }

    static void stallTrace(long stallMs, boolean burst, int expectedSnaps) {
        String what = stallMs + "ms " + (burst ? "burst" : "gap");
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            assertEquals(n >= 3, c.frame(n * 50), what + " warm-up frame " + n);
        }
        // Frames through the stall at 60 fps; past the hold window they stop driving.
        double burstAt = 450 + stallMs;
        double lastNow = 450;
        for (double t = 466; t < burstAt; t += 16) {
            c.frame(t);
            lastNow = t;
        }
        long missing = stallMs / 50;
        double previous = c.time();
        long snaps = c.recoverySnaps();
        if (burst) {
            for (long k = 1010; k < 1010 + missing; k++) {
                double now = burstAt + (k - 1010) * 2;
                c.arrival(batch(k), burstAt);
                assertTrue(c.frame(now), what + " tick " + k);
                assertTrue(c.time() >= previous, what + " R never decreases");
                if (c.recoverySnaps() == snaps) {
                    // Epsilon covers the Q16 quantum of time(), far below a tick.
                    assertTrue(
                            c.time() - previous <= 3 * (now - lastNow) / 50 + 1e-4,
                            what + " no R discontinuity at tick " + k);
                } else {
                    snaps = c.recoverySnaps();
                }
                previous = c.time();
                lastNow = now;
            }
        } else {
            c.arrival(batch(1010 + missing), burstAt);
            assertTrue(c.frame(burstAt), what);
            previous = c.time();
            lastNow = burstAt;
        }
        // Tail: arrivals every 50 ms, frames every 16 ms, until convergence.
        double t = lastNow;
        double end = burstAt + 2000;
        double nextArrival = burstAt + 50;
        long lastTick = burst ? 1009 + missing : 1010 + missing;
        boolean belowAgeBound = false;
        boolean converged = false;
        while (t < end) {
            t += 16;
            if (t >= nextArrival) {
                c.arrival(batch(++lastTick), nextArrival);
                nextArrival += 50;
            }
            assertTrue(c.frame(t), what);
            assertTrue(c.time() >= previous, what + " R never decreases at " + t);
            if (c.recoverySnaps() == snaps) {
                assertTrue(
                        c.time() - previous <= 3 * (t - lastNow) / 50 + 1e-4,
                        what + " no R discontinuity at " + t);
                if (c.occupancy() <= c.ageBound()) {
                    if (t - burstAt <= 600) belowAgeBound = true;
                    if (c.occupancy() <= c.delay() + 1
                            && c.occupancy() >= c.delay() - 1
                            && c.rate() <= 1.1) {
                        converged = true;
                        break;
                    }
                }
            } else {
                snaps = c.recoverySnaps();
            }
            previous = c.time();
            lastNow = t;
        }
        assertEquals(expectedSnaps, c.recoverySnaps(), what + " snaps");
        if (expectedSnaps == 0) {
            assertTrue(belowAgeBound, what + " converges below the age bound");
            assertTrue(converged, what + " converges back to servo regulation");
        }
    }

    /**
     * Remote-snap U2: a 5.6 m single-tick server step (1 s of queued sprint applied in one tick) is
     * traversed at most cap metres per R-tick instead of drawn as a jump, at every frame rate from
     * 30 to 250 fps. No per-frame drawn step above cap·frame-time, R stays monotonic, no recovery
     * snap, and lag still converges below the age bound. The traversal debt keeps tickets stale
     * longer than the uncapped U1 glide: measured in stale time below. The first post-burst frame
     * is grid-aligned with R held at the integer pre-stall high, which is the alignment that
     * bypassed the cap before the ceil fix.
     */
    @Test
    void largeSingleTickStepTraversesAtCappedSpeed() {
        for (double frameMs : new double[] {4, 6.9, 8, 16, 16.67, 33}) {
            cappedStepTrace(frameMs);
        }
    }

    static void cappedStepTrace(double frameMs) {
        String what = frameMs + "ms frames";
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            assertEquals(n >= 3, c.frame(n * 50), what + " warm-up frame " + n);
        }
        long burst = 1010;
        double lastNow = 450;
        for (double t = 466; t < 450 + 1000; t += frameMs) {
            c.frame(t);
            lastNow = t;
        }
        double burstAt = lastNow + frameMs;
        c.noteSteps(tick -> tick == burst ? 5.6 : 0.28);
        for (long k = burst; k < burst + 20; k++) c.arrival(batch(k), burstAt);
        double previous = c.time();
        double drawn = poseAt(previous, burst);
        long snaps = c.recoverySnaps();
        double maxFrameDrawn = 0;
        int crossFrames = 0;
        int staleFrames = 0;
        boolean belowAgeBound = false;
        boolean converged = false;
        double t = burstAt;
        double end = burstAt + 3000;
        double nextArrival = burstAt + 50;
        long lastTick = burst + 19;
        while (t < end) {
            if (t >= nextArrival) {
                c.arrival(batch(++lastTick), nextArrival);
                nextArrival += 50;
            }
            assertTrue(c.frame(t), what + " frame at " + t);
            assertTrue(c.time() >= previous, what + " R never decreases at " + t);
            assertEquals(snaps, c.recoverySnaps(), what + " no recovery snap at " + t);
            double now = poseAt(c.time(), burst);
            double step = now - drawn;
            assertTrue(step >= -1e-9, what + " drawn never moves back at " + t);
            assertTrue(
                    step <= CombatSceneClock.STEP_CAP_M_PER_TICK * (t - lastNow) / 50 + 1e-3,
                    what + " drawn step " + step + " above cap at " + t);
            maxFrameDrawn = Math.max(maxFrameDrawn, step);
            if (c.time() > burst - 1 && c.time() < burst) crossFrames++;
            if (c.occupancy() > c.ageBound()) staleFrames++;
            else if (t - burstAt <= 1200) belowAgeBound = true;
            if (c.occupancy() <= c.delay() + 1
                    && c.occupancy() >= c.delay() - 1
                    && c.rate() <= 1.1) {
                converged = true;
                break;
            }
            previous = c.time();
            drawn = now;
            lastNow = t;
            t += frameMs;
        }
        assertTrue(c.time() >= burst, what + " R traverses the large step");
        assertTrue(
                crossFrames >= 6, what + " step crawls over frames, not one jump: " + crossFrames);
        assertTrue(maxFrameDrawn < 1.0, what + " no metre-scale frame jump: " + maxFrameDrawn);
        double staleMs = staleFrames * frameMs;
        assertTrue(staleMs >= 200, what + " traversal debt keeps tickets stale: " + staleMs + "ms");
        assertTrue(staleMs <= 1500, what + " staleness stays bounded: " + staleMs + "ms");
        assertTrue(belowAgeBound, what + " lag returns below the age bound");
        assertTrue(converged, what + " lag converges back to servo regulation");
    }

    /**
     * S1: the frozen episode budget is bounded. Three consecutive 2 m steps (a pathological episode
     * the sustained-run exemption would also release in production) need ~300 ms of frozen
     * traversal against a ~200 ms allowance, so the U1 budget still snaps instead of gliding
     * forever. The snap must fire on the budget (lag below the 40-tick hard bound).
     */
    @Test
    void repeatedLargeStepsExhaustFrozenBudgetAndSnap() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            assertEquals(n >= 3, c.frame(n * 50), "warm-up frame " + n);
        }
        long burst = 1010;
        double burstAt = 450 + 1000;
        for (double t = 466; t < burstAt; t += 16) c.frame(t);
        c.noteSteps(tick -> tick >= burst && tick < burst + 3 ? 2.0 : 0.28);
        for (long k = burst; k < burst + 20; k++) c.arrival(batch(k), burstAt);
        double previous = c.time();
        double t = burstAt;
        double end = burstAt + 3000;
        double nextArrival = burstAt + 50;
        long lastTick = burst + 19;
        long snaps = c.recoverySnaps();
        double lagBeforeSnap = Double.NaN;
        while (t < end) {
            t += 16;
            if (t >= nextArrival) {
                c.arrival(batch(++lastTick), nextArrival);
                nextArrival += 50;
            }
            assertTrue(c.frame(t), "frame at " + t);
            assertTrue(c.time() >= previous, "R never decreases at " + t);
            if (c.recoverySnaps() > snaps) {
                snaps = c.recoverySnaps();
                break;
            }
            previous = c.time();
            lagBeforeSnap = c.occupancy();
        }
        assertTrue(c.recoverySnaps() > 0, "pathological episode still snaps on the budget");
        assertTrue(lagBeforeSnap < 35, "budget snap fires below the 40-tick hard bound");
    }

    /**
     * N3′: the cap derivation tripwire reads the pinned ParCool jar, not hard-coded constants.
     * Dodge sets 0.9 × min(DodgeSpeedModifier, MaxDodgeSpeedModifier) velocity on a unit input
     * vector; FastRun adds its modifier/100 flat speed (ADD_VALUE) on the 0.1 base, so it is (0.1 +
     * m/100)/0.1 of the stand-measured 5.6 m/s sprint. Raising a pinned modifier past the cap fails
     * here first.
     */
    @Test
    void stepCapClearsFastestParCoolMove() throws Exception {
        String jar = System.getProperty("blockfield.test.parcoolJar");
        assertNotNull(jar, "pinned ParCool jar path comes from the build");
        double dodgeClient = parcoolDefault(jar, "Client$Doubles", "DodgeSpeedModifier");
        double dodgeServer = parcoolDefault(jar, "Server$Doubles", "MaxDodgeSpeedModifier");
        double fastClient = parcoolDefault(jar, "Client$Doubles", "FastRunSpeedModifier");
        double fastServer = parcoolDefault(jar, "Server$Doubles", "MaxFastRunSpeedModifier");
        assertTrue(dodgeClient > 0 && dodgeServer > 0 && fastClient > 0 && fastServer > 0);
        double dodgeImpulse = 0.9 * Math.min(dodgeClient, dodgeServer);
        double fastRun = (0.1 + Math.min(fastClient, fastServer) / 100) / 0.1 * (5.6 / 50);
        assertTrue(dodgeImpulse < CombatSceneClock.STEP_CAP_M_PER_TICK, "Dodge impulse under cap");
        assertTrue(fastRun < CombatSceneClock.STEP_CAP_M_PER_TICK, "FastRun under cap");
        assertTrue(5.6 / 50 < CombatSceneClock.STEP_CAP_M_PER_TICK, "sprint under cap");
    }

    /** DefaultValue of a ParCoolConfig enum entry, loaded from the pinned jar in isolation. */
    static double parcoolDefault(String jar, String group, String entry) throws Exception {
        var url = new java.io.File(jar).toURI().toURL();
        try (var loader =
                new java.net.URLClassLoader(
                        new java.net.URL[] {url}, CombatSceneClockTest.class.getClassLoader())) {
            Class<?> config =
                    Class.forName("com.alrex.parcool.config.ParCoolConfig$" + group, true, loader);
            Object constant = Enum.valueOf(config.asSubclass(Enum.class), entry);
            return config.getField("DefaultValue").getDouble(constant);
        }
    }

    /**
     * FLAG_SNAP control: the same 5.6 m displacement on a teleport tick (no capped traversal)
     * crosses at the full catch-up rate, drawing a metre-scale frame step. Real teleports
     * additionally re-init R through sceneReset (see teleportReinitThroughSceneResetIsUnchanged);
     * this pins that the cap never slows them.
     */
    @Test
    void snapTickIgnoresStepCap() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            assertEquals(n >= 3, c.frame(n * 50), "warm-up frame " + n);
        }
        long burst = 1010;
        double burstAt = 450 + 1000;
        for (double t = 466; t < burstAt; t += 16) c.frame(t);
        c.noteSteps(tick -> tick == burst ? Double.NaN : 0.28);
        for (long k = burst; k < burst + 20; k++) c.arrival(batch(k), burstAt);
        double previous = c.time();
        double drawn = poseAt(previous, burst);
        long snaps = c.recoverySnaps();
        double lastNow = burstAt;
        double maxFrameDrawn = 0;
        int crossFrames = 0;
        double t = burstAt;
        double end = burstAt + 2500;
        double nextArrival = burstAt + 50;
        long lastTick = burst + 19;
        boolean crossed = false;
        while (t < end) {
            t += 16;
            if (t >= nextArrival) {
                c.arrival(batch(++lastTick), nextArrival);
                nextArrival += 50;
            }
            assertTrue(c.frame(t), "frame at " + t);
            assertTrue(c.time() >= previous, "R never decreases at " + t);
            double now = poseAt(c.time(), burst);
            maxFrameDrawn = Math.max(maxFrameDrawn, now - drawn);
            if (c.time() > burst - 1 && c.time() < burst) crossFrames++;
            if (c.time() >= burst) {
                crossed = true;
                break;
            }
            previous = c.time();
            drawn = now;
            lastNow = t;
        }
        assertTrue(crossed, "R crosses the teleport tick");
        assertTrue(crossFrames <= 3, "teleport crosses fast: " + crossFrames);
        assertTrue(maxFrameDrawn >= 1.0, "teleport still draws a jump: " + maxFrameDrawn);
        assertEquals(snaps, c.recoverySnaps(), "no recovery snap either way");
    }

    /**
     * B2′: fluctuating motion around the cap must not pile lag without limit in the servo branch,
     * where no streak/window count can catch it and no U1 budget runs. A repeating 1.1/1.1/0.9
     * pattern, the review's periodic patterns and seeded elytra-like noise all stay at the servo
     * backstop instead of growing toward 40+ (past 39 ticks R points at evicted records, so the
     * resolver holds and every ticket goes stale).
     */
    @Test
    void fluctuatingFastMotionStaysAtServoBackstop() {
        fluctuatingTrace(new double[] {1.1, 1.1, 0.9});
        fluctuatingTrace(new double[] {1.5, 1.5, 0.5});
        fluctuatingTrace(new double[] {1.2, 0.9});
        var random = new java.util.Random(20261027);
        double[] noise = new double[64];
        for (int i = 0; i < noise.length; i++) noise[i] = 1.1 + (random.nextDouble() * 2 - 1) * 0.3;
        fluctuatingTrace(noise);
    }

    static void fluctuatingTrace(double[] pattern) {
        String what = "pattern[" + pattern.length + "]";
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            assertEquals(n >= 3, c.frame(n * 50), what + " warm-up frame " + n);
        }
        // Steady arrivals, no stall: every tick carries its pattern step.
        c.noteSteps(
                tick ->
                        pattern[
                                (int)
                                        (((tick - 1010) % pattern.length + pattern.length)
                                                % pattern.length)]);
        double t = 450;
        double end = 450 + 60000;
        double nextArrival = 500;
        long lastTick = 1009;
        double maxOcc = 0;
        while (t < end) {
            t += 16;
            if (t >= nextArrival) {
                c.arrival(batch(++lastTick), nextArrival);
                nextArrival += 50;
            }
            assertTrue(c.frame(t), what + " frame at " + t);
            maxOcc = Math.max(maxOcc, c.occupancy());
            assertTrue(c.occupancy() <= 15, what + " occupancy bounded: " + c.occupancy());
            assertEquals(0, c.recoverySnaps(), what + " never snaps");
        }
        assertTrue(maxOcc > 3, what + " backstop actually engages: " + maxOcc);
    }

    /** Origin of server tick t when tick {@code burst} carries one 5.6 m stall step. */
    static double originAt(long t, long burst) {
        if (t < burst) return t * 0.28;
        return (burst - 1) * 0.28 + 5.6 + (t - burst) * 0.28;
    }

    /** Drawn feet mirror: pose(R) lerps the within-tick segment, as SnapshotResolver.exact does. */
    static double poseAt(double r, long burst) {
        long t = (long) Math.floor(r);
        return originAt(t, burst) + (r - t) * (originAt(t + 1, burst) - originAt(t, burst));
    }

    static SnapshotTransform record(
            long tick,
            AnimationState.Identity id,
            int flags,
            double prevX,
            double x,
            double prevY,
            double y) {
        return SnapshotTransform.exact(
                tick,
                id,
                flags,
                new AnimationState.Placement(
                        new AnimationState.Vec3(prevX, prevY, 0),
                        new AnimationState.Vec3(x, y, 0),
                        new AnimationState.Angles(0, 0, 0, 0, 0, 0, 0, 0),
                        new AnimationState.Walk(0, 0, 0),
                        new AnimationState.Vec3(0, 0, 0),
                        new AnimationState.Vec3(0, 0, 0),
                        new AnimationState.VehicleAngles(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        List.of()),
                null);
    }

    @Test
    void traverseStepResolvesEligibility() {
        var id = ExactFixtures.identity(42, 9, 5, 0, 0);
        var frames = new java.util.TreeMap<Long, SnapshotTransform>();
        frames.put(1000L, record(1000L, id, 0, 0, 0, 64, 64));
        frames.put(1001L, record(1001L, id, 0, 0, 5.6, 64, 64));
        assertEquals(5.6, SnapshotResolver.traverseStep(frames, 1001), 1e-12);
        // A previousOrigin that is not the previous origin is a discontinuity, not a step.
        var broken = new java.util.TreeMap<Long, SnapshotTransform>();
        broken.put(1000L, record(1000L, id, 0, 0, 0, 64, 64));
        broken.put(1001L, record(1001L, id, 0, 3.0, 5.6, 64, 64));
        assertTrue(Double.isNaN(SnapshotResolver.traverseStep(broken, 1001)));
        // Vertical-only motion is continuous yet never capped.
        var fall = new java.util.TreeMap<Long, SnapshotTransform>();
        fall.put(1000L, record(1000L, id, 0, 0, 0, 64, 64));
        fall.put(1001L, record(1001L, id, 0, 0, 0, 64, 60.1));
        assertEquals(0, SnapshotResolver.traverseStep(fall, 1001), 1e-12);
        // FLAG_SNAP stays a hard snap.
        var snap = new java.util.TreeMap<Long, SnapshotTransform>();
        snap.put(1000L, record(1000L, id, 0, 0, 0, 64, 64));
        snap.put(
                1001L, record(1001L, id, AnimationRecipe.SnapshotRecord.FLAG_SNAP, 0, 5.6, 64, 64));
        assertTrue(Double.isNaN(SnapshotResolver.traverseStep(snap, 1001)));
        // New teleport, seat or epoch generation breaks the run.
        var teleported = ExactFixtures.identity(42, 9, 5, 0, 1);
        var tele = new java.util.TreeMap<Long, SnapshotTransform>();
        tele.put(1000L, record(1000L, id, 0, 0, 0, 64, 64));
        tele.put(1001L, record(1001L, teleported, 0, 0, 5.6, 64, 64));
        assertTrue(Double.isNaN(SnapshotResolver.traverseStep(tele, 1001)));
        var reseated = ExactFixtures.identity(42, 9, 5, 1, 0);
        var seat = new java.util.TreeMap<Long, SnapshotTransform>();
        seat.put(1000L, record(1000L, id, 0, 0, 0, 64, 64));
        seat.put(1001L, record(1001L, reseated, 0, 0, 5.6, 64, 64));
        assertTrue(Double.isNaN(SnapshotResolver.traverseStep(seat, 1001)));
        var newEpoch = ExactFixtures.identity(42, 10, 5, 0, 0);
        var epoch = new java.util.TreeMap<Long, SnapshotTransform>();
        epoch.put(1000L, record(1000L, id, 0, 0, 0, 64, 64));
        epoch.put(1001L, record(1001L, newEpoch, 0, 0, 5.6, 64, 64));
        assertTrue(Double.isNaN(SnapshotResolver.traverseStep(epoch, 1001)));
        // Vehicles never count, and a missing previous tick is not a step.
        var kindOne =
                new AnimationState.Identity(
                        ExactFixtures.CONNECTION,
                        ExactFixtures.STREAM,
                        ExactFixtures.SCENE,
                        42,
                        1,
                        5,
                        9,
                        0,
                        0,
                        0,
                        0,
                        AnimationSchema.layoutDigest(),
                        ExactFixtures.ZERO);
        var veh = new java.util.TreeMap<Long, SnapshotTransform>();
        veh.put(1000L, record(1000L, kindOne, 0, 0, 0, 64, 64));
        veh.put(1001L, record(1001L, kindOne, 0, 0, 5.6, 64, 64));
        assertTrue(Double.isNaN(SnapshotResolver.traverseStep(veh, 1001)));
        assertTrue(Double.isNaN(SnapshotResolver.traverseStep(new java.util.TreeMap<>(), 1001)));
    }

    @Test
    void lastingLatencyStepCatchesUpWithoutSnap() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 30; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        // Every later batch arrives 200 ms older than before: a route change, not a stall burst.
        double stepMs = 30 * 50 + 200;
        double frozen = c.time();
        for (int n = 30; n < 100; n++) {
            double now = n * 50 + 200;
            c.arrival(batch(1000 + n), now);
            assertTrue(c.frame(now));
            if (now >= stepMs + 500 + 50) assertTrue(c.time() > frozen, "frozen at " + now);
        }
        assertEquals(0, c.recoverySnaps());
        assertTrue(c.occupancy() <= c.delay() + 1, "catch-up returns lag toward the servo band");
    }

    @Test
    void staleBacklogDoesNotChangeEstimatorsAndCatchesUpWithoutSnap() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        double held = c.time();
        double prev = held;
        double lastNow = 450;
        for (int n = 10; n < 20; n++) {
            c.arrival(batch(1000 + n), 1000);
            assertTrue(c.frame(1000));
            assertTrue(c.time() >= prev, "catch-up never moves R backwards");
            assertTrue(c.time() - prev <= 3 * (1000 - lastNow) / 50 + 1e-9);
            prev = c.time();
            lastNow = 1000;
            assertEquals(0, c.jitter());
            assertEquals(3, c.delay());
            assertEquals(0, c.recoverySnaps());
        }
        c.arrival(batch(1020), 1000);
        assertTrue(c.frame(1000));
        assertEquals(0, c.recoverySnaps());
        assertEquals(1010, c.time(), 1e-12);
        assertEquals(0, c.jitter());
        // The catch-up resumes on the next wall-clock frames instead of snapping.
        c.frame(1020);
        assertTrue(c.time() > held);
        c.frame(1025);
        assertTrue(c.time() > held);
        assertTrue(c.rate() <= 3);
        assertEquals(0, c.recoverySnaps());
    }

    @Test
    void fortyMsJitterStepAdaptsWithinThreeSecondsAndStaysStable() {
        var c = new CombatSceneClock();
        int n = 0;
        double next = 0;
        long changesAtStable = 0;
        int stableDelay = -1;
        for (int ms = 0; ms < 18000; ms += 5) {
            while (ms >= next) {
                c.arrival(batch(1000 + n), next);
                n++;
                // Reliable ordered delivery holds early successors behind a delayed batch.
                next = Math.max(next, n * 50 + (n < 100 ? 0 : (n % 2 == 0 ? 40 : -40)));
            }
            c.frame(ms);
            assertTrue(c.rate() >= .9 && c.rate() <= 1.1);
            if (ms == 4950) assertEquals(2, c.delay());
            if (ms == 8000) {
                stableDelay = c.delay();
                assertEquals(3, stableDelay);
                changesAtStable = c.delayChanges();
                assertTrue(c.jitter() > 0);
            }
            if (ms >= 8000 && ms < 13000) assertEquals(stableDelay, c.delay());
        }
        assertTrue(c.delayChanges() - changesAtStable <= 2);
        assertEquals(0, c.recoverySnaps());
    }

    @Test
    void configuredTwoSecondTimeoutMeansFortyTicks() {
        var c = new CombatSceneClock();
        var empty = new AnimationRecipe.Batch(0, 0, List.of(), List.of(), List.of());
        for (int n = 0; n < 10; n++) {
            c.arrival(
                    PlayerSnapshotS2CPacket.animation(
                            17, n == 0 ? 0 : 1000 + n, 1000 + n, 2, 6, 10, 2000, empty),
                    n * 50);
            c.frame(n * 50);
        }
        assertTrue(c.frame(2450));
        assertFalse(c.timedOut(2450));
        assertEquals(1009, c.time());
        assertFalse(c.frame(2450.001));
    }

    @Test
    void deltaOnlyGapCatchesUpWithoutSnap() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 6; n++) {
            c.arrival(batch(995 + n), n * 50);
            c.frame(n * 50);
        }
        double held = c.time();
        c.arrival(batch(1006), 550);
        assertTrue(c.frame(550));
        assertEquals(0, c.recoverySnaps());
        assertTrue(c.time() >= held);
        for (int n = 7; n < 47; n++) {
            c.arrival(batch(1000 + n), 550 + (n - 6) * 50);
            assertTrue(c.frame(550 + (n - 6) * 50));
        }
        assertEquals(0, c.recoverySnaps());
        assertTrue(c.occupancy() <= c.delay() + 1, "catch-up returns lag toward the servo band");
    }

    @Test
    void teleportReinitThroughSceneResetIsUnchanged() {
        // Real teleports (FLAG_SNAP/RESET/SEAT) clear the buffer's frames and restart the
        // scene; the tick counter continues, so R re-inits at high - delay with no recovery
        // snap. That path is untouched.
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        c.sceneReset();
        for (int n = 10; n < 14; n++) {
            c.arrival(batch(1000 + n), n * 50);
            assertEquals(n == 13, c.frame(n * 50));
        }
        assertEquals(1013 - c.delay(), c.time(), 1e-12);
        assertEquals(0, c.recoverySnaps());
    }

    @Test
    void receiptDoesNotFollowNewArrivalsAndEqualRHasFreshSequence() {
        var r = receipt(1020, 1017, 32768);
        var c = new CombatSceneClock();
        c.arrival(batch(1021), 0);
        assertEquals(1020, r.ticket(1).receivedSnapshotTick());
        assertEquals(1017.5, r.ticket(1).renderTime());
        assertEquals(1, r.ticket(1).sequence());
        assertEquals(2, r.ticket(2).sequence());
    }

    @Test
    void shadowPassReusesReceiptWithoutAdvancingClock() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        var r = receipt(1009, (long) c.time(), 0);
        double before = c.time();
        for (int shadow = 0; shadow < 4; shadow++) assertEquals(r.time(), r.ticket(1).renderTime());
        assertEquals(before, c.time());
    }

    /**
     * Servo starvation below ~20 fps: a frame slower than half a tick misses its slot, and the next
     * arrival re-arms it unfired, so the last sampled rate holds. After a transient that leaves the
     * rate below 1 (R ahead of the band), 18.5 fps frames with arrivals drained at frame start must
     * still keep H-R within delay + 2 over 30 s. Stock holds the stale rate and H-R walks away
     * without bound.
     */
    @Test
    void slowFramesKeepSamplingTheServo() {
        var c = new CombatSceneClock();
        for (int n = 0; n < 10; n++) {
            c.arrival(batch(1000 + n), n * 50);
            c.frame(n * 50);
        }
        // A +100 ms phase step with no gap: R outruns H while frames keep hitting slots,
        // so the last samples see R ahead of the band and the rate drops below 1.
        // Wall clock is (tick - 1000) * 50 + phase throughout; warm-up ran at phase 0.
        double t = 466;
        int tick = 1010;
        while (t <= 1150) {
            while ((tick - 1000) * 50 + 100 <= t) {
                c.arrival(batch(tick), (tick - 1000) * 50 + 100);
                tick++;
            }
            assertTrue(c.frame(t), "bias frame at " + t);
            t += 16;
        }
        assertTrue(c.rate() < 1, "bias leaves the rate below 1: " + c.rate());
        assertTrue(c.occupancy() < c.delay(), "bias leaves R ahead of the band");
        // 18.5 fps: arrivals drained at frame start, slots never land on a frame.
        double maxOcc = 0;
        double end = t + 30_000;
        while (t < end) {
            t += 54;
            while ((tick - 1000) * 50 + 100 <= t) {
                c.arrival(batch(tick), t);
                tick++;
            }
            assertTrue(c.frame(t), "starved frame at " + t);
            maxOcc = Math.max(maxOcc, c.occupancy());
        }
        assertTrue(
                maxOcc <= c.delay() + 2, "H-R stays within delay + 2 at 18.5 fps, max " + maxOcc);
    }

    @Test
    void fifteenMsDitherStaysAtUnityForAtLeastNinetyFivePercentAndSlewsAtMostTwice() {
        var c = new CombatSceneClock();
        var random = new java.util.Random(74);
        int n = 0;
        double next = 0;
        int unity = 0, total = 0;
        long startChanges = 0;
        for (int frame = 0; frame < 2400; frame++) {
            double now = frame * 5;
            if (now >= next) {
                c.arrival(batch(1000 + n), next);
                n++;
                next = n * 50 + random.nextDouble(-15, 15);
            }
            if (c.frame(now) && now >= 7000) {
                if (now == 7000) startChanges = c.delayChanges();
                total++;
                if (c.rate() == 1) unity++;
            }
            assertTrue(c.rate() >= .9 && c.rate() <= 1.1);
        }
        assertTrue(unity >= total * .95, "unity " + unity + "/" + total);
        assertTrue(c.delayChanges() - startChanges <= 2);
    }

    @Test
    void nonTickAlignedTimeoutRoundsUpToWholeElapsedTicks() {
        var c = new CombatSceneClock();
        c.arrival(
                PlayerSnapshotS2CPacket.animation(
                        17,
                        0,
                        1000,
                        2,
                        6,
                        10,
                        201,
                        new AnimationRecipe.Batch(0, 0, List.of(), List.of(), List.of())),
                0);
        assertFalse(c.timedOut(250));
        assertTrue(c.timedOut(250.001));
    }
}
