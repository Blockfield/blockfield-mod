package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static com.lunares.blockfield.common.combat.AnimationTiming.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AnimationTimingTest {
    @Test
    void snapshotEndpointAndActorConventionsAtEveryIntegerNeighbor() {
        long[] ticks = {1, 100, (1L << 40), Long.MAX_VALUE - 1};
        for (long n : ticks) {
            assertEquals(new Endpoint(n, 1), endpoint(Time.whole(n)));
            assertEquals(new Endpoint(n, 65535 / 65536f), endpoint(new Time(n - 1, 65535)));
            assertEquals(new Endpoint(n + 1, 1 / 65536f), endpoint(new Time(n, 1)));
            var start = observer(1, 1, LayerTag.PA_94, PhaseDomain.A, 100, 0);
            if (n >= 100)
                assertEquals(
                        new Time(n - 100, 0),
                        AnimationStartReport.age(start, Time.whole(98), Time.whole(n), policy()));
        }
    }

    @Test
    void eventAboveAAndHInstallsInCurrentBucketWithSharedFraction() {
        var start = observer(103, 1, LayerTag.PA_94, PhaseDomain.A, 100, 0);
        assertEquals(
                new Time(0, 19660),
                AnimationStartReport.age(
                        start, new Time(98, 19660), new Time(100, 19660), policy()));
        var later = observer(104, 2, LayerTag.PA_94, PhaseDomain.A, 100, 0);
        assertEquals(
                AnimationStartReport.age(start, Time.whole(98), new Time(100, 55705), policy()),
                AnimationStartReport.age(later, Time.whole(98), new Time(100, 55705), policy()));
        var continued = observer(1, 3, LayerTag.PA_94, PhaseDomain.A, 100, 17);
        assertEquals(
                new Time(19, 1),
                AnimationStartReport.age(continued, Time.whole(98), new Time(102, 1), policy()));
    }

    @Test
    void canonicalAndProjectedAnchorsHaveNoFabricatedApplicationProof() {
        var canonical =
                new AnimationStartReport.Start(
                        1,
                        1,
                        LayerTag.PARCOOL_ACTIVE,
                        StartKind.NEW,
                        AnchorClass.CANONICAL_TICK,
                        PhaseDomain.A,
                        101,
                        0,
                        0,
                        new AnimationStartReport.CanonicalTick(101, NativeStage.NATIVE_TICK));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnimationStartReport.age(
                                canonical, Time.whole(98), new Time(100, 65535), policy()));
        assertEquals(
                Time.whole(0),
                AnimationStartReport.age(canonical, Time.whole(98), Time.whole(101), policy()));
        var projected =
                new AnimationStartReport.Start(
                        2,
                        2,
                        LayerTag.PARCOOL_ACTIVE,
                        StartKind.REPLACE,
                        AnchorClass.PROJECTED_SUCCESSOR,
                        PhaseDomain.A,
                        102,
                        0,
                        1,
                        new AnimationStartReport.ProjectedSuccessor(
                                102, NativeStage.NATIVE_TICK, 7, 3));
        assertEquals(
                new Time(0, 1),
                AnimationStartReport.age(projected, Time.whole(98), new Time(102, 1), policy()));
        var rPolicy =
                new AnimationPolicy(
                        0, PhaseDomain.R, true, true, true, true, AnimationPolicy.registered());
        var r = observer(1, 1, LayerTag.PA_41, PhaseDomain.R, 98, 0);
        assertEquals(
                new Time(0, 19660),
                AnimationStartReport.age(r, new Time(98, 19660), new Time(100, 55705), rPolicy));
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationStartReport.age(r, Time.whole(98), Time.whole(100), policy()));
        assertThrows(
                ArithmeticException.class,
                () ->
                        AnimationStartReport.age(
                                observer(
                                        1,
                                        1,
                                        LayerTag.PA_94,
                                        PhaseDomain.A,
                                        100,
                                        Integer.MAX_VALUE),
                                Time.whole(98),
                                Time.whole(Long.MAX_VALUE),
                                policy()));
    }

    private static Frame frame(
            long h,
            Time r,
            long ns,
            boolean slot,
            boolean silence,
            boolean gap,
            boolean backlog,
            boolean timeout) {
        return new Frame(r, h, ns, 25, 4, .1, h, slot, silence, gap, backlog, timeout, 9);
    }

    @Test
    void clockLawTraceCoversWarmAdvanceServoHoldSnapTimeoutAndRestartHighwater() {
        assertEquals(.5, offset(0, 2));
        assertEquals(2, offset(25, 4));
        assertEquals(1, rate(2.25, 2, .1));
        assertEquals(1.1, rate(5, 2, .1));
        assertEquals(.9, rate(0, 2, .1));
        var warm =
                advance(
                        ClockState.cold(),
                        frame(103, Time.whole(98), 100_000_000, false, false, false, false, false));
        assertEquals(Time.whole(101), warm.state().a());
        assertTrue(warm.ticketsEnabled());
        var next =
                advance(
                        warm.state(),
                        frame(103, Time.whole(98), 125_000_000, true, false, false, false, false));
        assertEquals(new Time(101, 32768), next.state().a());
        var again =
                advance(
                        next.state(),
                        frame(103, Time.whole(98), 125_000_000, true, false, false, false, false));
        assertEquals(next.state().a(), again.state().a());
        assertEquals(next.state().rate(), again.state().rate());
        var held =
                advance(
                        next.state(),
                        frame(103, Time.whole(98), 500_000_000, false, false, false, false, false));
        assertEquals(Time.whole(103), held.state().a());
        assertEquals(
                ClockReason.HOLD,
                advance(
                                held.state(),
                                frame(
                                        103,
                                        Time.whole(98),
                                        600_000_000,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false))
                        .reason());
        var snap =
                advance(
                        held.state(),
                        frame(110, Time.whole(105), 650_000_000, true, false, false, true, false));
        assertEquals(ClockReason.BACKLOG_SNAP, snap.reason());
        assertEquals(Time.whole(108), snap.state().a());
        assertEquals(
                ClockReason.SILENCE_SNAP,
                advance(
                                snap.state(),
                                frame(
                                        110,
                                        Time.whole(105),
                                        700_000_000,
                                        false,
                                        true,
                                        false,
                                        false,
                                        false))
                        .reason());
        assertEquals(
                ClockReason.GAP_SNAP,
                advance(
                                snap.state(),
                                frame(
                                        110,
                                        Time.whole(105),
                                        700_000_000,
                                        false,
                                        false,
                                        true,
                                        false,
                                        false))
                        .reason());
        var timeout =
                advance(
                        snap.state(),
                        frame(110, Time.whole(105), 700_000_000, false, false, false, false, true));
        assertFalse(timeout.ticketsEnabled());
        assertEquals(snap.state().a(), timeout.state().a());
        var restart =
                advance(
                        timeout.state(),
                        frame(100, Time.whole(98), 750_000_000, true, false, false, false, false));
        assertEquals(ClockReason.REWARM, restart.reason());
        assertEquals(Time.whole(108), restart.state().highWater());
        assertTrue(
                advance(
                                restart.state(),
                                frame(
                                        110,
                                        Time.whole(105),
                                        800_000_000,
                                        true,
                                        false,
                                        false,
                                        false,
                                        false))
                        .ticketsEnabled());
        assertEquals(9, snap.state().incidentId());
        System.out.println(
                "ANIM_CLOCK_TRACE="
                        + new com.google.gson.Gson()
                                .toJson(List.of(warm, next, held, snap, timeout, restart)));
    }

    @Test
    void clockHandlesLongTicksWithoutShiftsAndNeverAdvancesOnNegativeElapsed() {
        var warm =
                advance(
                        ClockState.cold(),
                        frame(
                                Long.MAX_VALUE,
                                Time.whole(Long.MAX_VALUE - 4),
                                100,
                                false,
                                false,
                                false,
                                false,
                                false));
        assertEquals(Time.whole(Long.MAX_VALUE - 2), warm.state().a());
        var backwards =
                advance(
                        warm.state(),
                        frame(
                                Long.MAX_VALUE,
                                Time.whole(Long.MAX_VALUE - 4),
                                99,
                                false,
                                false,
                                false,
                                false,
                                false));
        assertEquals(warm.state().a(), backwards.state().a());
        assertEquals(
                Time.whole(Long.MAX_VALUE),
                advance(
                                warm.state(),
                                frame(
                                        Long.MAX_VALUE,
                                        Time.whole(Long.MAX_VALUE - 4),
                                        500_000_100,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false))
                        .state()
                        .a());
    }

    @Test
    void frozenProbeDeadlineUsesFreshLast32AndLaterReportsOnlyTighten() {
        var probes = new ArrayList<Probe>();
        for (int i = 0; i < 40; i++)
            probes.add(
                    new Probe(
                            i,
                            i * 1_000_000L,
                            i * 1_000_000L + (i % 2 == 0 ? 10_000_000 : 20_000_000),
                            90));
        probes.add(new Probe(100, -100, 0, 1));
        var frozen = freeze(200_000_000, 103, probes).orElseThrow();
        assertEquals(32, frozen.probes().size());
        assertEquals(20_000_000, frozen.rttHi());
        assertEquals(10_000_000, frozen.jitterHi());
        assertEquals(230_000_000, frozen.networkDeadlineNs());
        var map =
                new Boundaries(
                        List.of(
                                new Boundary(100, 0),
                                new Boundary(101, 50_000_000),
                                new Boundary(102, 100_000_000),
                                new Boundary(103, 150_000_000),
                                new Boundary(104, 200_000_000),
                                new Boundary(105, 250_000_000),
                                new Boundary(106, 300_000_000)));
        var first = bound(Time.whole(103), frozen, 210_000_000, map, null).orElseThrow();
        assertEquals(new Time(104, 13107), first.upper());
        var delayed = bound(Time.whole(103), frozen, 300_000_000, map, first).orElseThrow();
        assertEquals(first, delayed);
        System.out.println(
                "ANIM_BOUND_TRACE=" + new com.google.gson.Gson().toJson(List.of(first, delayed)));
        var loose = bound(Time.whole(103), frozen, 300_000_000, map, null).orElseThrow();
        assertEquals(new Time(104, 39321), loose.upper());
        assertFalse(
                first.contains(
                        new Time(104, 13108), new Interval(Time.whole(103), Time.whole(106))));
        assertFalse(
                first.contains(
                        new Time(102, 65535), new Interval(Time.whole(100), Time.whole(106))));
        assertTrue(freeze(200_000_000, 103, List.of()).isEmpty());
        assertTrue(freeze(200_000_000, 103, List.of(new Probe(1, 0, 1, 62))).isEmpty());
        assertEquals(0, freeze(100, 2, List.of(new Probe(1, 0, 10, 1))).orElseThrow().jitterHi());
        assertThrows(
                ArithmeticException.class,
                () -> freeze(Long.MAX_VALUE, 1, List.of(new Probe(1, 0, Long.MAX_VALUE - 1, 1))));
    }

    @Test
    void mappingIntersectsCausalityAndUsesActualStalledTickBoundaries() {
        var map =
                new Boundaries(
                        List.of(
                                new Boundary(100, 0),
                                new Boundary(101, 50),
                                new Boundary(102, 350),
                                new Boundary(103, 400)));
        assertEquals(new Time(101, 32768), map.at(200).orElseThrow());
        assertTrue(map.at(401).isEmpty());
        var mapping = Mapping.fromProbe(7, 50, 150, 1000, 1000);
        var interval = mapping.at(1050, 120, 300, map).orElseThrow();
        assertEquals(map.at(120).orElseThrow(), interval.lower());
        assertEquals(map.at(200).orElseThrow(), interval.upper());
        assertEquals(interval.lower(), interval.choose(Time.whole(101)).orElseThrow());
        assertTrue(mapping.at(1050, 250, 300, map).isEmpty());
        assertEquals(7, mapping.id());
    }

    @Test
    void replaySelectsNewestCompleteCheckpointRegardlessOfMapOrder() {
        class Keeper implements AnimationResolver.Reducer<Integer> {
            int value;

            public void restore(Integer state) {
                value = state;
            }

            public void stage(long t, NativeStage stage, List<AnimationEvent.Decision> decisions) {
                if (stage == NativeStage.NATIVE_TICK) value++;
            }

            public Integer freeze(long tick) {
                return value;
            }
        }
        var journal = new HashMap<Long, List<AnimationEvent.Decision>>();
        for (long t = 99; t <= 100; t++) journal.put(t, List.of());
        var forward = new LinkedHashMap<Long, Integer>();
        forward.put(95L, 95);
        forward.put(98L, 98);
        var backward = new LinkedHashMap<Long, Integer>();
        backward.put(98L, 98);
        backward.put(95L, 95);
        // Base 98 plus two native ticks in both insertion orders; an order-dependent
        // implementation restores 95 on the second map and fails on the missing journal steps.
        assertEquals(100, AnimationResolver.replay(90, 0, 100, forward, journal, new Keeper()));
        assertEquals(100, AnimationResolver.replay(90, 0, 100, backward, journal, new Keeper()));
    }

    @Test
    void restoreReplacesAndOnlyLaterTicksAdvanceOnceInStageOrder() {
        class Reducer implements AnimationResolver.Reducer<Integer> {
            int value, ticks;
            List<NativeStage> stages = new ArrayList<>();

            public void restore(Integer state) {
                value = state;
            }

            public void stage(long t, NativeStage stage, List<AnimationEvent.Decision> decisions) {
                stages.add(stage);
                if (stage == NativeStage.NATIVE_TICK) {
                    value++;
                    ticks++;
                }
            }

            public Integer freeze(long tick) {
                return value;
            }
        }
        var reducer = new Reducer();
        assertEquals(10, AnimationResolver.replay(10, 10, 10, Map.of(), Map.of(), reducer));
        assertEquals(0, reducer.ticks);
        var journal = new HashMap<Long, List<AnimationEvent.Decision>>();
        for (long t = 11; t <= 18; t++) journal.put(t, List.of());
        assertEquals(18, AnimationResolver.replay(10, 10, 18, Map.of(), journal, reducer));
        assertEquals(8, reducer.ticks);
        assertEquals(List.of(NativeStage.values()), reducer.stages.subList(0, 5));
        assertEquals(50, AnimationResolver.replay(10, 10, 18, Map.of(18L, 50), Map.of(), reducer));
        assertEquals(8, reducer.ticks);
        assertEquals(
                100, AnimationResolver.replay(0, 0, 100, Map.of(100L, 100), Map.of(), reducer));
        assertEquals(8, reducer.ticks);
        assertEquals(
                100,
                AnimationResolver.replay(
                        0, 0, 100, Map.of(99L, 99), Map.of(100L, List.of()), reducer));
        assertEquals(9, reducer.ticks);
        assertThrows(
                IllegalArgumentException.class,
                () -> AnimationResolver.replay(10, 10, 11, Map.of(), Map.of(), reducer));
    }
}
