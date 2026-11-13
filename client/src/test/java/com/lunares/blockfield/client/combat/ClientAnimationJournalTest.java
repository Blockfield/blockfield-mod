package com.lunares.blockfield.client.combat;

import static com.lunares.blockfield.client.combat.ExactFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationResolver;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTiming;
import com.lunares.blockfield.common.combat.AnimationTypes.AnchorClass;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.StartKind;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.ObserverReducer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client journal against the server's replay of the same receipt: activation, causal order,
 * zero-delay application stamping and complete victim rows. Create membership capsules exercise
 * node-creating and no-node applications without clip assets.
 */
class ClientAnimationJournalTest {
    // Server monotonic tick boundaries: tick n starts at n * 50 ms.
    private static final long MS = 1_000_000;
    private final java.util.concurrent.atomic.AtomicBoolean presentable =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final ClientAnimationJournal journal =
            new ClientAnimationJournal(identity -> presentable.get(), event -> true);
    private final TreeMap<Long, SnapshotTransform> frames = new TreeMap<>();
    private final Map<Long, AnimationState> checkpoints = new HashMap<>();
    private long frame;

    @BeforeEach
    void install() {
        ClientAnimationAssets.install(assets());
        for (long tick = 98; tick <= 110; tick++) {
            var checkpoint = checkpoint(identity(), tick, tick * .25);
            checkpoints.put(tick, checkpoint);
            frames.put(
                    tick,
                    SnapshotTransform.exact(
                            tick, identity(), 0, checkpoint.placement(), checkpoint));
        }
    }

    @AfterEach
    void clear() {
        ClientAnimationAssets.clear();
    }

    /** The client clock offset: server ns = client ns - 7 s (never assumed by the journal). */
    private static long client(long serverNs) {
        return serverNs + 7_000 * MS;
    }

    private long probed = 97;

    /** Delivers every probe applied by server time {@code serverNs}, in order. */
    private void until(long serverNs) {
        // Probe n leaves at the boundary of tick n and is applied 4 ms later on the client.
        while ((probed + 1) * 50 * MS + 4 * MS <= serverNs) {
            long tick = ++probed;
            journal.probe(
                    tick, tick * 50 * MS, tick, tick * 50 * MS, client(tick * 50 * MS + 4 * MS));
        }
    }

    /**
     * Receives a seed or fold at server time {@code serverNs}, after the probes applied by then.
     */
    private void receive(AnimationEvent.ObserverSeed seed, long serverNs) {
        until(serverNs);
        journal.seed(seed, client(serverNs));
    }

    private ClientAnimationJournal.Stamp stamp(Time a, long h, long serverNs) {
        until(serverNs);
        frame++;
        var r = Time.max(Time.whole(0), new Time(a.tick() - 2, a.alpha()));
        return new ClientAnimationJournal.Stamp(frame, frame, r, a, h, client(serverNs));
    }

    private void frame(ClientAnimationJournal.Stamp stamp) {
        journal.frame(stamp, id -> id == ENTITY ? frames : new TreeMap<>());
    }

    private static long seq(long tick, int ordinal) {
        return tick * 256 + ordinal;
    }

    @Test
    void activePresentationResumesAfterTemporaryNativeUnpairing() {
        var seed = seed(checkpoints.get(100L), seq(100, 1));
        receive(seed, 4_990 * MS);
        frame(stamp(new Time(100, 0x4000), 101, 5_020 * MS));
        assertNotNull(journal.presentation(ENTITY));
        presentable.set(false);
        frame(stamp(new Time(100, 0x8000), 101, 5_030 * MS));
        assertNull(journal.presentation(ENTITY));
        assertEquals("not_presentable", journal.unavailable(ENTITY));
        presentable.set(true);
        var resumed = stamp(new Time(101, 0x4000), 102, 5_070 * MS);
        frame(resumed);
        assertNotNull(journal.presentation(ENTITY), "pairing recovered without a replacement seed");
        var row = journal.row(record(102), resumed);
        assertNull(row.unavailable(), row.unavailable());
        assertEquals(1L, journal.counters().get("seedActivations"));
        replay(row.victim(), seed, resumed.a(), List.of());
    }

    @Test
    void liveResetActivatesItsSeedAndProducesAnExactRowWithoutReconnect() {
        var buffer = new ClientSnapshotBuffer();
        var deliveries = new java.util.concurrent.atomic.AtomicInteger();
        var tracked =
                new ClientAnimationJournal(
                        buffer::paired,
                        event -> {
                            deliveries.incrementAndGet();
                            return true;
                        });
        buffer.add(ENTITY, 0);
        for (long tick = 100; tick <= 104; tick++) {
            var identity =
                    tick < 102 ? identity() : identity(ENTITY, TARGET + 1, SUBSCRIPTION + 1, 0, 0);
            var checkpoint = checkpoint(identity, tick, tick * .25);
            boolean reset = tick == 100 || tick == 102;
            var record =
                    new com.lunares.blockfield.common.combat.AnimationRecipe.SnapshotRecord(
                            identity,
                            reset ? ClientSnapshotBufferTest.FULL_RESET : 0,
                            checkpoint.placement(),
                            checkpoint);
            assertTrue(
                    ClientSnapshotBufferTest.apply(
                            buffer, ClientSnapshotBufferTest.batch(tick, record)));
            tracked.probe(
                    tick, tick * 50 * MS, tick, tick * 50 * MS, client(tick * 50 * MS + 2 * MS));
            if (reset)
                tracked.seed(seed(checkpoint, seq(tick, 1)), client(tick * 50 * MS + 4 * MS));
            long next = (tick + 1) * 50 * MS;
            tracked.probe(tick + 1, next, tick + 1, next, client(next + 2 * MS));
            var stamp = stamp(new Time(tick, 0), tick, next + 3 * MS);
            tracked.frame(stamp, buffer::frames);
            var row = tracked.row(buffer.frames(ENTITY).lastEntry().getValue(), stamp);
            assertNull(row.unavailable(), "tick " + tick + ": " + row.unavailable());
            assertEquals(identity.targetEpoch(), row.victim().targetEpoch());
            if (tick >= 102)
                assertFalse(
                        tracked.capsule(
                                create(identity(), tick, seq(tick, 2), 0, true, tick * 50 * MS),
                                client(tick * 50 * MS + 6 * MS)),
                        "old-life capsule remains stale");
        }
        assertEquals(0, deliveries.get());
        assertEquals(2L, tracked.counters().get("seedActivations"));
    }

    @Test
    void farRateSkipsKeepTheLifeSteppingOverHeldBoundaries() {
        var buffer = new ClientSnapshotBuffer();
        var tracked = new ClientAnimationJournal(buffer::paired, event -> true);
        buffer.add(ENTITY, 0);
        for (long tick = 100; tick <= 106; tick++) {
            // farEvery=2: odd ticks are true skips, every due tick closes with a DELTA.
            boolean due = tick % 2 == 0;
            var checkpoint = checkpoint(identity(), tick, tick * .25);
            var batch =
                    due
                            ? ClientSnapshotBufferTest.batch(
                                    tick,
                                    new com.lunares.blockfield.common.combat.AnimationRecipe
                                            .SnapshotRecord(
                                            identity(),
                                            tick == 100 ? ClientSnapshotBufferTest.FULL_RESET : 0,
                                            checkpoint.placement(),
                                            checkpoint))
                            : ClientSnapshotBufferTest.empty(tick);
            assertTrue(ClientSnapshotBufferTest.apply(buffer, batch));
            tracked.probe(
                    tick, tick * 50 * MS, tick, tick * 50 * MS, client(tick * 50 * MS + 2 * MS));
            if (tick == 100)
                tracked.seed(seed(checkpoint, seq(tick, 1)), client(tick * 50 * MS + 4 * MS));
            long next = (tick + 1) * 50 * MS;
            tracked.probe(tick + 1, next, tick + 1, next, client(next + 2 * MS));
            if (!due) continue;
            var stamp = stamp(new Time(tick, 0), tick, next + 3 * MS);
            tracked.frame(stamp, buffer::frames);
            var row = tracked.row(buffer.frames(ENTITY).lastEntry().getValue(), stamp);
            assertNull(row.unavailable(), "tick " + tick + ": " + row.unavailable());
        }
    }

    /**
     * A trails H, so a seed is first presented after the next batch and probe arrived. Its sW is
     * its causal receipt under the probe held then; the presenting frame only supplies frame,
     * revision, R, A and H. The server's unchanged frozen seed bound then admits it.
     */
    @Test
    void aSeedActivationKeepsItsReceiptMappingInsideTheFrozenSendBound() {
        var seed = seed(checkpoints.get(100L), seq(100, 1));
        // Issued at 4,999.5 ms, probe 100 leaves at 5,000 ms, the batch at 5,001 ms: both
        // received 4 ms later.
        long issued = 4_999 * MS + MS / 2, sent = 5_001 * MS;
        receive(seed, 5_005 * MS);
        frame(stamp(new Time(99, 0xA000), 100, 5_010 * MS));
        assertNull(journal.presentation(ENTITY), "A below the seed tick presents nothing yet");
        var showing = stamp(new Time(100, 0x2000), 101, 5_060 * MS);
        frame(showing);
        assertNotNull(journal.presentation(ENTITY), "probe 101 has arrived");
        var activation =
                (AnimationStartReport.ObserverApply)
                        journal.drainUnreported().getFirst().activation().proof();
        assertEquals(100, activation.mappingId(), "the probe held at receipt");
        assertEquals(journal.tickTime(5_001 * MS), activation.sW(), "probe send + client elapsed");
        assertEquals(showing.frameId(), activation.applyFrameId());
        assertEquals(showing.a(), activation.applyA());
        assertEquals(showing.h(), activation.applyH());
        // AnimSyncValidation.frozenBound: acceptance = issuance, deadline = D + RTT_hi + jitter,
        // lower raised to the mapping nonce's send (probe n leaves at n * 50 ms here).
        var map =
                new AnimationTiming.Boundaries(
                        java.util.stream.LongStream.rangeClosed(98, 102)
                                .mapToObj(n -> new AnimationTiming.Boundary(n, n * 50 * MS))
                                .toList());
        var deadline =
                AnimationTiming.freeze(
                                sent,
                                100,
                                List.of(new AnimationTiming.Probe(99, 4_950 * MS, 4_958 * MS, 99)))
                        .orElseThrow();
        var bound =
                AnimationTiming.bound(map.at(issued).orElseThrow(), deadline, 5_065 * MS, map, null)
                        .orElseThrow();
        var lower =
                Time.max(
                        bound.acceptance(),
                        map.at(Math.max(issued, activation.mappingId() * 50 * MS)).orElseThrow());
        assertTrue(
                lower.compareTo(activation.sW()) <= 0
                        && activation.sW().compareTo(bound.upper()) <= 0,
                () -> "sW " + activation.sW() + " outside [" + lower + ", " + bound.upper() + "]");
    }

    /**
     * A 70 ms server tick: the 50 ms slope past the last boundary would place the receipt after the
     * seed's few-ms bound. Activation waits for the next boundary and converts the receipt with the
     * server's own map, which the unchanged frozen bound then admits.
     */
    @Test
    void aSeedActivationConvertsItsReceiptWithTheServersBoundaries() {
        var exact = new ClientAnimationJournal(identity -> true, event -> true);
        exact.probe(99, 4_990 * MS, 99, 4_950 * MS, client(4_994 * MS));
        // Tick 100 starts at 5,000 ms and runs 70 ms; issued 5,044.5, probe 5,045, batch 5,046.
        exact.probe(100, 5_045 * MS, 100, 5_000 * MS, client(5_049 * MS));
        long issued = 5_044 * MS + MS / 2, sent = 5_046 * MS;
        exact.seed(seed(checkpoints.get(100L), seq(100, 1)), client(5_050 * MS));
        var early =
                new ClientAnimationJournal.Stamp(
                        1, 1, new Time(98, 0), new Time(100, 0x1000), 101, client(5_060 * MS));
        exact.frame(early, id -> frames);
        assertNotNull(exact.presentation(ENTITY), "A reached the seed: presented at once");
        assertEquals(
                "application_pending",
                exact.row(record(101), early).unavailable(),
                "the boundary after the receipt is not known yet");
        assertTrue(exact.drainUnreported().isEmpty(), "no proof leaves before it is exact");
        exact.probe(101, 5_115 * MS, 101, 5_070 * MS, client(5_119 * MS));
        var showing =
                new ClientAnimationJournal.Stamp(
                        2, 2, new Time(98, 0x8000), new Time(100, 0x9000), 101, client(5_120 * MS));
        exact.frame(showing, id -> frames);
        assertNotNull(exact.presentation(ENTITY));
        var activation =
                (AnimationStartReport.ObserverApply)
                        exact.drainUnreported().getFirst().activation().proof();
        var map =
                new AnimationTiming.Boundaries(
                        List.of(
                                new AnimationTiming.Boundary(99, 4_950 * MS),
                                new AnimationTiming.Boundary(100, 5_000 * MS),
                                new AnimationTiming.Boundary(101, 5_070 * MS)));
        assertEquals(100, activation.mappingId());
        assertEquals(map.at(5_046 * MS).orElseThrow(), activation.sW(), "the server's own map");
        var deadline =
                AnimationTiming.freeze(
                                sent,
                                100,
                                List.of(new AnimationTiming.Probe(98, 4_900 * MS, 4_908 * MS, 98)))
                        .orElseThrow();
        var bound =
                AnimationTiming.bound(map.at(issued).orElseThrow(), deadline, 5_125 * MS, map, null)
                        .orElseThrow();
        var lower =
                Time.max(bound.acceptance(), map.at(Math.max(issued, 5_045 * MS)).orElseThrow());
        assertTrue(
                lower.compareTo(activation.sW()) <= 0
                        && activation.sW().compareTo(bound.upper()) <= 0,
                () -> "sW " + activation.sW() + " outside [" + lower + ", " + bound.upper() + "]");
    }

    /**
     * Review should-fix 1 and p4 A: a 40 ms server tick. The seed presents as soon as A reaches it
     * and an immediate native capsule received before the next boundary is dispatched and installed
     * at once; both proofs are first sent after probe 101 records B101, converted with the server's
     * own boundaries. The 50 ms extrapolation would put the capsule below its acceptance; waiting
     * for B101 before activation would release it past its deadline.
     */
    @Test
    void aNativeCapsuleDuringTheSeedProofWaitInstallsAtOnceAndIsSentExact() {
        var deliveries = new java.util.concurrent.atomic.AtomicInteger();
        var live =
                new ClientAnimationJournal(
                        identity -> true,
                        event -> {
                            deliveries.incrementAndGet();
                            return true;
                        });
        live.probe(99, 4_960 * MS, 99, 4_950 * MS, client(4_964 * MS));
        live.probe(100, 5_010 * MS, 100, 5_000 * MS, client(5_014 * MS));
        var seed = seed(checkpoints.get(100L), seq(100, 1));
        live.seed(seed, client(5_015 * MS)); // issued 5,009.5, sent 5,011
        live.frame(
                new ClientAnimationJournal.Stamp(
                        1, 1, new Time(98, 0), new Time(100, 0), 100, client(5_030 * MS)),
                id -> frames);
        assertNotNull(live.presentation(ENTITY), "activated when A reached the seed tick");
        // Accepted 5,035, sent 5,035.5 by the native flush, received 5,039.5.
        long accepted = 5_035 * MS, sent = accepted + MS / 2;
        var enter = create(identity(), 101, seq(101, 1), 0, true, accepted);
        assertTrue(live.capsule(enter, client(sent + 4 * MS)));
        assertEquals(1, deliveries.get(), "dispatched in its receiving task");
        var installing =
                new ClientAnimationJournal.Stamp(
                        2, 2, new Time(98, 0x8000), new Time(100, 0), 100, client(5_040 * MS));
        live.frame(installing, id -> frames);
        assertNotNull(present(live).create(), "installed in the very frame");
        assertEquals("application_pending", live.row(record(100), installing).unavailable());
        assertTrue(live.drainUnreported().isEmpty(), "no proof leaves before it is exact");
        // B101 = 5,040 ms (tick 100 ran 40 ms); probe 101 leaves at 5,050, applied 5,054.
        live.probe(101, 5_050 * MS, 101, 5_040 * MS, client(5_054 * MS));
        var exact =
                new ClientAnimationJournal.Stamp(
                        3, 3, new Time(99, 0), new Time(100, 0xC000), 101, client(5_056 * MS));
        live.frame(exact, id -> frames);
        var map =
                new AnimationTiming.Boundaries(
                        List.of(
                                new AnimationTiming.Boundary(99, 4_950 * MS),
                                new AnimationTiming.Boundary(100, 5_000 * MS),
                                new AnimationTiming.Boundary(101, 5_040 * MS),
                                new AnimationTiming.Boundary(102, 5_090 * MS)));
        var cohort = List.of(new AnimationTiming.Probe(99, 4_960 * MS, 4_968 * MS, 99));
        var reports = live.drainUnreported();
        assertEquals(1, reports.size(), "activation and application, in application order");
        var activation =
                (AnimationStartReport.ObserverApply) reports.getFirst().activation().proof();
        assertEquals(1, activation.applyFrameId(), "the presenting frame");
        assertInside(
                activation.sW(),
                map,
                5_009 * MS + MS / 2,
                5_010 * MS,
                5_011 * MS,
                cohort,
                5_060 * MS);
        var application = reports.getFirst().applications().getFirst().proof();
        assertEquals(2, application.applyFrameId(), "the installing frame");
        assertEquals(100, application.mappingId(), "the probe held at release");
        assertEquals(map.at(sent).orElseThrow(), application.sW(), "the server's own map");
        assertInside(application.sW(), map, accepted, 5_010 * MS, sent, cohort, 5_060 * MS);
        var row = live.row(record(101), exact);
        assertNull(row.unavailable(), row.unavailable());
        assertEquals(application, row.victim().applications().getFirst().proof());
        replay(row.victim(), seed, exact.a(), List.of(enter));
    }

    /** AnimSyncValidation.frozenBound for one proof: acceptance, mapping nonce and deadline. */
    private static void assertInside(
            Time sW,
            AnimationTiming.Boundaries map,
            long acceptedNs,
            long probeSentNs,
            long sentNs,
            List<AnimationTiming.Probe> cohort,
            long firstReportNs) {
        var deadline =
                AnimationTiming.freeze(sentNs, map.at(sentNs).orElseThrow().tick(), cohort)
                        .orElseThrow();
        var bound =
                AnimationTiming.bound(
                                map.at(acceptedNs).orElseThrow(),
                                deadline,
                                firstReportNs,
                                map,
                                null)
                        .orElseThrow();
        var lower =
                Time.max(
                        bound.acceptance(),
                        map.at(Math.max(acceptedNs, probeSentNs)).orElseThrow());
        assertTrue(
                lower.compareTo(sW) <= 0 && sW.compareTo(bound.upper()) <= 0,
                () -> "sW " + sW + " outside [" + lower + ", " + bound.upper() + "]");
    }

    /**
     * Review nit 3: a probe gap drops the boundary below a pending receipt. Neither the seed's
     * activation nor a pending fold is then sent or adopted with a guessed sW.
     */
    @Test
    void aLostLowerBoundaryNeverBecomesAGuessedProof() {
        var gap = new ClientAnimationJournal(identity -> true, event -> true);
        gap.probe(100, 5_010 * MS, 100, 5_000 * MS, client(5_014 * MS));
        gap.seed(seed(checkpoints.get(100L), seq(100, 1)), client(5_015 * MS));
        gap.frame(
                new ClientAnimationJournal.Stamp(
                        1, 1, new Time(98, 0), new Time(100, 0x1000), 100, client(5_030 * MS)),
                id -> frames);
        gap.probe(102, 5_110 * MS, 102, 5_100 * MS, client(5_114 * MS)); // B101 never arrived
        var after =
                new ClientAnimationJournal.Stamp(
                        2, 2, new Time(99, 0), new Time(101, 0x1000), 102, client(5_116 * MS));
        gap.frame(after, id -> frames);
        assertEquals("mapping_unavailable", gap.row(record(102), after).unavailable());
        assertTrue(gap.drainUnreported().isEmpty(), "the activation is never sent");

        var seed = seed(checkpoints.get(100L), seq(100, 1));
        receive(seed, 4_990 * MS);
        frame(stamp(new Time(100, 0x4000), 101, 5_020 * MS));
        journal.drainUnreported();
        var checkpoint = checkpoints.get(101L);
        var fold =
                new AnimationEvent.ObserverSeed(
                        identity(),
                        new com.lunares.blockfield.common.combat.AnimationResources.SeedRef(
                                0, TARGET, seq(104, 9), ZERO),
                        seed.ref(),
                        checkpoint,
                        checkpoint.graph(),
                        101,
                        checkpoint.eventWatermark(),
                        checkpoint.eventWatermark(),
                        List.of(),
                        List.of(),
                        ObserverReducer.DIGEST);
        receive(fold, 5_060 * MS);
        journal.probe(104, 5_200 * MS, 104, 5_200 * MS, client(5_204 * MS)); // gap after 101
        frame(
                new ClientAnimationJournal.Stamp(
                        ++frame,
                        frame,
                        new Time(101, 0),
                        new Time(103, 0),
                        104,
                        client(5_206 * MS)));
        assertNull(journal.counters().get("foldsAdopted"), "never adopted with a guessed sW");
        assertEquals(1L, journal.counters().get("foldMappingUnavailable"));
    }

    @Test
    void aSeedActivatesWhenAReachesItsTickAndCapsulesInstallInTheirApplicationFrame() {
        var seed = seed(checkpoints.get(100L), seq(100, 1));
        receive(seed, 4_985 * MS);
        frame(stamp(new Time(99, 0x8000), 100, 4_990 * MS));
        assertNull(journal.presentation(ENTITY), "A below the seed tick presents nothing yet");
        frame(stamp(new Time(100, 0x4000), 101, 5_020 * MS));
        assertNotNull(journal.presentation(ENTITY), "activated at the first frame with A >= 100");
        // The enter of tick 101 arrives while A = 100.4 < 101 <= H: installed now, anchor 100.
        var enter = create(identity(), 101, seq(101, 1), 0, true, 5_030 * MS);
        until(5_055 * MS);
        assertTrue(journal.capsule(enter, client(5_055 * MS)));
        var applied = stamp(new Time(100, 0xC000), 101, 5_060 * MS);
        frame(applied);
        var hanging = journal.presentation(ENTITY);
        assertNotNull(hanging.create(), "the membership start exists in the very frame");
        assertEquals(100, hanging.create().phaseAnchor());
        assertEquals(AnchorClass.OBSERVER_APPLY, hanging.create().anchorClass());
        assertEquals(0, hanging.create().u0());
        assertEquals("application_pending", journal.row(record(101), applied).unavailable());
        // Probe 102 records boundary 102: the proof is converted with the server's own pair.
        var exact = stamp(new Time(101, 0x1000), 102, 5_106 * MS);
        frame(exact);
        var row = journal.row(record(102), exact);
        assertNull(row.unavailable(), row.unavailable());
        // sW: probe 101 left at 5,050 ms and was applied at 5,054 ms; the capsule at 5,055 ms.
        var application = row.victim().applications().getFirst().proof();
        assertEquals(
                journal.tickTime(5_051 * MS),
                application.sW(),
                "the lower mapped bound, above the 5,030 ms acceptance");
        assertEquals(applied.frameId(), application.applyFrameId(), "the installing frame");
        replay(row.victim(), seed, exact.a(), List.of(enter));
        // The exit (tick 103) creates no node: still a reported application with its bucket.
        frame(stamp(new Time(102, 0x2000), 103, 5_110 * MS));
        var exit = create(identity(), 103, seq(103, 1), seq(101, 1), false, 5_140 * MS);
        until(5_160 * MS);
        assertTrue(journal.capsule(exit, client(5_160 * MS)));
        frame(stamp(new Time(102, 0x6000), 103, 5_165 * MS));
        assertNull(present(journal).create(), "the exit ends hanging");
        var later = stamp(new Time(102, 0xA000), 104, 5_206 * MS);
        frame(later);
        var exited = journal.row(record(104), later);
        assertNull(exited.unavailable(), exited.unavailable());
        assertTrue(exited.victim().starts().isEmpty(), "no node, no start");
        assertEquals(2, exited.victim().applications().size());
        replay(exited.victim(), seed, later.a(), List.of(enter, exit));
    }

    @Test
    void foldedCapsuleStillDeliversItsRetainedNativeBodyAfterPairing() {
        var present = new java.util.concurrent.atomic.AtomicBoolean();
        var deliveries = new java.util.concurrent.atomic.AtomicInteger();
        var pending =
                new ClientAnimationJournal(
                        id -> present.get(),
                        event -> {
                            deliveries.incrementAndGet();
                            return true;
                        });
        pending.seed(seed(checkpoints.get(100L), seq(100, 1)), client(5_015 * MS));
        pending.capsule(
                create(identity(), 99, seq(99, 1), 0, true, 4_940 * MS), client(5_020 * MS));
        var stamp = stamp(new Time(100, 0x4000), 101, 5_025 * MS);
        pending.frame(stamp, id -> frames);
        assertEquals(0, deliveries.get(), "no mutation before native pairing");
        present.set(true);
        pending.frame(stamp(new Time(100, 0x8000), 101, 5_030 * MS), id -> frames);
        assertEquals(1, deliveries.get(), "seed watermark is not delivery evidence");
        assertNull(pending.presentation(ENTITY).create(), "folded geometry is not restarted");
    }

    @Test
    void heldApplicationKeepsItsReleaseMappingAcrossNewerProbes() {
        var seed = seed(checkpoints.get(100L), seq(100, 1));
        receive(seed, 4_990 * MS);
        frame(stamp(new Time(100, 0x4000), 101, 5_020 * MS));
        var enter = create(identity(), 101, seq(101, 1), 0, true, 5_030 * MS);
        var exit = create(identity(), 102, seq(102, 1), seq(101, 1), false, 5_050 * MS);
        until(5_090 * MS);
        assertTrue(journal.capsule(exit, client(5_090 * MS)));
        until(5_110 * MS); // Probe 102 arrived after B's receipt.
        assertTrue(journal.capsule(enter, client(5_111 * MS)));
        until(5_160 * MS); // Probe 103 arrived after both actual applications.
        var showing = stamp(new Time(100, 0xC000), 103, 5_165 * MS);
        frame(showing);
        var row = journal.row(record(103), showing);
        assertNull(row.unavailable(), row.unavailable());
        var boundaryMap =
                new AnimationTiming.Boundaries(
                        java.util.stream.LongStream.rangeClosed(98, 110)
                                .mapToObj(n -> new AnimationTiming.Boundary(n, n * 50 * MS))
                                .toList());
        var mapping =
                AnimationTiming.Mapping.fromProbe(
                        102, 5_100 * MS, 5_110 * MS, client(5_104 * MS), client(5_104 * MS));
        for (var application : row.victim().applications()) {
            var event = application.eventSequence() == enter.id().eventSequence() ? enter : exit;
            var deadline =
                    AnimationTiming.freeze(
                                    event.broadcastNs(),
                                    102,
                                    List.of(
                                            new AnimationTiming.Probe(
                                                    99, 4_700 * MS, 4_900 * MS, 98)))
                            .orElseThrow();
            var bound =
                    AnimationTiming.bound(
                                    boundaryMap.at(event.acceptedNs()).orElseThrow(),
                                    deadline,
                                    5_165 * MS,
                                    boundaryMap,
                                    null)
                            .orElseThrow();
            var interval =
                    mapping.at(client(5_111 * MS), event.acceptedNs(), 5_165 * MS, boundaryMap)
                            .orElseThrow();
            assertTrue(
                    bound.contains(application.proof().sW(), interval),
                    "the server bound accepts actual release");
            assertEquals(102, application.proof().mappingId());
            assertEquals(journal.tickTime(5_107 * MS), application.proof().sW());
        }
        replay(row.victim(), seed, showing.a(), List.of(enter, exit));
    }

    @Test
    void capsulesWaitForTheirPredecessorAndSeedAndAreAppliedOnce() {
        var enter = create(identity(), 101, seq(101, 1), 0, true, 5_030 * MS);
        var exit = create(identity(), 102, seq(102, 1), seq(101, 1), false, 5_080 * MS);
        // Before the seed: held (bounded), not dropped.
        until(5_090 * MS);
        journal.capsule(exit, client(5_090 * MS));
        var seed = seed(checkpoints.get(100L), seq(100, 1));
        receive(seed, 5_091 * MS);
        frame(stamp(new Time(101, 0), 102, 5_100 * MS));
        assertNull(present(journal).create(), "the exit waits for its predecessor");
        until(5_101 * MS);
        journal.capsule(enter, client(5_101 * MS));
        assertFalse(journal.capsule(enter, client(5_102 * MS)), "a duplicate is ignored");
        var stamp = stamp(new Time(101, 0x1000), 102, 5_106 * MS);
        frame(stamp);
        var row = journal.row(record(102), stamp);
        assertNull(row.unavailable(), row.unavailable());
        assertEquals(
                List.of(seq(101, 1), seq(102, 1)),
                row.victim().applications().stream()
                        .map(AnimationStartReport.Application::eventSequence)
                        .toList(),
                "both applied in causal order in one revision");
        replay(row.victim(), seed, stamp.a(), List.of(enter, exit));
        var report = journal.drainUnreported().getFirst();
        assertEquals(2, report.applications().size());
        assertEquals(StartKind.SEED_CONTINUE, report.activation().startKind(), "with A for folds");
        assertTrue(journal.drainUnreported().isEmpty(), "each application is reported once");
    }

    @Test
    void anUnmappedApplicationOrAnExpiredWindowIsNeverAnExactRow() {
        // No probe has arrived: the capsule still presents now, but its row cannot validate.
        var unmapped = new ClientAnimationJournal();
        unmapped.seed(seed(checkpoints.get(100L), seq(100, 1)), client(4_990 * MS));
        unmapped.frame(bare(new Time(100, 0), 100, 5_000 * MS), id -> frames);
        unmapped.capsule(
                create(identity(), 101, seq(101, 1), 0, true, 5_030 * MS), client(5_050 * MS));
        var stamp = bare(new Time(100, 0x8000), 101, 5_060 * MS);
        unmapped.frame(stamp, id -> frames);
        assertNotNull(present(unmapped).create(), "presentation never waits");
        assertEquals("mapping_unavailable", unmapped.row(record(101), stamp).unavailable());

        var old = new ClientAnimationJournal();
        old.probe(99, 4_950 * MS, 99, 4_950 * MS, client(4_952 * MS));
        old.seed(seed(checkpoints.get(100L), seq(100, 1)), client(4_990 * MS));
        old.probe(100, 5_000 * MS, 100, 5_000 * MS, client(5_002 * MS));
        old.frame(bare(new Time(100, 0), 100, 5_003 * MS), id -> frames);
        var late = bare(new Time(110, 0), 141, 5_500 * MS);
        old.frame(late, id -> frames);
        present(old);
        assertEquals("seed_window", old.row(record(110), late).unavailable());
    }

    private ClientAnimationJournal.Stamp bare(Time a, long h, long serverNs) {
        frame++;
        return new ClientAnimationJournal.Stamp(
                frame, frame, new Time(a.tick() - 2, a.alpha()), a, h, client(serverNs));
    }

    @Test
    void applicationTimesUseTheServerBoundariesAndNeverPrecedeAcceptance() {
        until(5_054 * MS);
        // Between recorded boundaries: the server's own map (ns 5,025 ms = 100 + 1/2).
        assertEquals(
                new AnimationTiming.Boundaries(
                                List.of(
                                        new AnimationTiming.Boundary(100, 5_000 * MS),
                                        new AnimationTiming.Boundary(101, 5_050 * MS)))
                        .at(5_025 * MS)
                        .orElseThrow(),
                journal.tickTime(5_025 * MS));
        // Past the last boundary: the fixed 20 ticks/s slope.
        assertEquals(new Time(102, 0x8000), journal.tickTime(5_125 * MS));
        assertNull(journal.tickTime(4_000 * MS), "before the recorded map");
        // Lower mapped bound: probe 101 sent at 5,050 ms, applied 4 ms later on the client.
        assertEquals(journal.tickTime(5_070 * MS), journal.causal(5_070 * MS, 5_010 * MS));
        var accepted = journal.causal(5_051 * MS, 5_060 * MS);
        assertEquals(journal.tickTime(5_060 * MS), accepted, "never below server acceptance");
    }

    @Test
    void aNewerSeedOfTheLifeHoldsCapsulesUntilItActivates() {
        receive(seed(checkpoints.get(100L), seq(100, 1)), 4_940 * MS);
        frame(stamp(new Time(100, 0), 101, 5_000 * MS));
        var second = seed(checkpoints.get(103L), seq(103, 2));
        receive(second, 5_160 * MS);
        var enter = create(identity(), 104, seq(104, 1), 0, true, 5_190 * MS);
        until(5_205 * MS);
        journal.capsule(enter, client(5_205 * MS));
        frame(stamp(new Time(102, 0x8000), 104, 5_210 * MS));
        assertNull(present(journal).create(), "held behind the pending seed");
        frame(stamp(new Time(103, 0x1000), 104, 5_215 * MS));
        assertNotNull(present(journal).create(), "applied with the new seed");
        var stamp = stamp(new Time(103, 0x9000), 105, 5_256 * MS);
        frame(stamp);
        var row = journal.row(record(105), stamp);
        assertNull(row.unavailable(), row.unavailable());
        assertEquals(second.ref().contentId(), row.victim().seedRef());
        replay(row.victim(), second, stamp.a(), List.of(enter));
    }

    private static ObserverReducer.Result present(ClientAnimationJournal journal) {
        var result = journal.presentation(ENTITY);
        assertNotNull(result, () -> "presentation: " + journal.unavailable(ENTITY));
        return result;
    }

    @Test
    void aServerFoldIsAdoptedWithoutChangingThePresentation() {
        fold(false);
    }

    @Test
    void sameRevisionFoldKeepsTheUnsentApplicationUnderItsOriginalSeed() {
        fold(true);
    }

    private void fold(boolean unsentApplication) {
        var seed = seed(checkpoints.get(100L), seq(100, 1));
        receive(seed, 4_990 * MS);
        frame(stamp(new Time(100, 0x4000), 101, 5_020 * MS));
        var enter = create(identity(), 101, seq(101, 1), 0, true, 5_030 * MS);
        until(5_055 * MS);
        journal.capsule(enter, client(5_055 * MS));
        frame(stamp(new Time(100, 0xC000), 101, 5_060 * MS));
        var applied = stamp(new Time(101, 0x1000), 102, 5_106 * MS);
        frame(applied);
        var row = journal.row(record(102), applied);
        assertNull(row.unavailable(), row.unavailable());
        // The server folds what the observer reported (activation, application, A) at bucket 102.
        var late = stamp(new Time(103, 0x2000), 104, 5_160 * MS);
        frame(late);
        var before = present(journal);
        var activation =
                row.victim().starts().stream()
                        .filter(start -> start.startKind() == StartKind.SEED_CONTINUE)
                        .findFirst()
                        .orElse(null);
        var application = row.victim().applications().getFirst();
        var ordered =
                ObserverReducer.ordered(
                        List.of(
                                new ObserverReducer.Applied(
                                        enter,
                                        ObserverReducer.template(
                                                enter, application.proof(), policy()))));
        var boundaries = new HashMap<Long, AnimationState>();
        for (long n = 101; n <= 102; n++) boundaries.put(n, checkpoints.get(n));
        var reduced =
                ObserverReducer.reduce(
                        seed, activation, 102, boundaries, ordered, null, assets().lr(), policy());
        var fold =
                new AnimationEvent.ObserverSeed(
                        identity(),
                        new com.lunares.blockfield.common.combat.AnimationResources.SeedRef(
                                0, TARGET, seq(104, 9), ZERO),
                        seed.ref(),
                        // As the server folds: the reduced ParCool value travels with its graph.
                        com.lunares.blockfield.common.combat.ParCoolProjection.withFrame(
                                checkpoints.get(102L), reduced.parcoolFrame()),
                        reduced.pa().graph(),
                        102,
                        Math.max(seed.ref().contentId(), enter.id().eventSequence()),
                        checkpoints.get(102L).eventWatermark(),
                        reduced.starts(),
                        List.of(),
                        ObserverReducer.DIGEST);
        journal.drainUnreported();
        // Reproduce UOPT: apply another event in the same revision before adopting the fold,
        // with no intervening AnimationApplied flush.
        var exit =
                create(identity(), 104, seq(104, 1), enter.id().eventSequence(), false, 5_165 * MS);
        if (unsentApplication) journal.capsule(exit, client(5_166 * MS));
        // Received behind probe 103 (5,150 ms, applied 5,154 ms); adopted after probe 104 arrived.
        receive(fold, 5_168 * MS);
        var adopted = stamp(new Time(103, 0x6000), 104, 5_205 * MS);
        frame(adopted);
        var reports = journal.drainUnreported();
        var foldProof = (AnimationStartReport.ObserverApply) reports.getLast().activation().proof();
        assertEquals(103, foldProof.mappingId(), "the fold keeps the mapping held at its receipt");
        assertEquals(journal.tickTime(5_164 * MS), foldProof.sW(), "the fold's causal receipt");
        assertEquals(adopted.a(), foldProof.applyA(), "the adopting frame's clocks");
        assertEquals(unsentApplication ? 2 : 1, reports.size());
        if (unsentApplication) {
            assertEquals(seed.ref().contentId(), reports.getFirst().seedRef());
            assertEquals(
                    List.of(exit.id().eventSequence()),
                    reports.getFirst().applications().stream()
                            .map(AnimationStartReport.Application::eventSequence)
                            .toList());
            var proof = reports.getFirst().applications().getFirst().proof();
            assertEquals(adopted.frameId(), proof.applyFrameId());
            assertEquals(adopted.revision(), proof.applyRevision());
        }
        assertEquals(fold.ref().contentId(), reports.getLast().seedRef());
        assertTrue(reports.getLast().applications().isEmpty());
        assertTrue(journal.drainUnreported().isEmpty(), "each original proof is drained once");
        var after = present(journal);
        assertEquals(before.pa().graph().roots(), after.pa().graph().roots());
        if (unsentApplication) assertNull(after.create(), "the exit remains applied");
        else
            assertEquals(
                    before.create().u0() + (adopted.a().tick() - before.create().phaseAnchor()),
                    after.create().u0() + (adopted.a().tick() - after.create().phaseAnchor()),
                    "the hanging phase continues unchanged");
        var folded = journal.row(record(104), adopted);
        assertNull(folded.unavailable(), folded.unavailable());
        assertEquals(fold.ref().contentId(), folded.victim().seedRef());
        assertEquals(unsentApplication ? 1 : 0, folded.victim().applications().size());
        replay(
                folded.victim(),
                fold,
                adopted.a(),
                unsentApplication ? List.of(exit) : List.of(),
                reports.getLast().activation());
        assertEquals(1, journal.counters().get("foldsAdopted"));
    }

    private SnapshotTransform record(long tick) {
        return frames.get(tick);
    }

    /**
     * The server's reconstruction of one row (AnimSyncValidation): the activation it names, the
     * templates of the delivered capsules its applications report, the shared reducer and both
     * digests. Every value must equal the client's.
     */
    private void replay(
            AnimationStartReport.Victim row,
            AnimationEvent.ObserverSeed seed,
            Time a,
            List<AnimationEvent> delivered) {
        replay(row, seed, a, delivered, null);
    }

    private void replay(
            AnimationStartReport.Victim row,
            AnimationEvent.ObserverSeed seed,
            Time a,
            List<AnimationEvent> delivered,
            AnimationStartReport.Start retainedActivation) {
        AnimationStartReport.validate(new AnimationStartReport(List.of(row)));
        AnimationStartReport.Start activation = null;
        for (var start : row.starts())
            if (start.startKind() == StartKind.SEED_CONTINUE
                    && (activation == null || start.nodeId() < activation.nodeId()))
                activation = start;
        // An expired seed actor no longer contributes a start; its admitted activation remains
        // necessary for reconstruction, exactly as in the server's retained application history.
        if (activation == null) activation = retainedActivation;
        var events = new HashMap<Long, AnimationEvent>();
        for (var event : delivered) events.put(event.id().eventSequence(), event);
        var applied = new ArrayList<ObserverReducer.Applied>();
        for (var application : row.applications()) {
            var event = events.get(application.eventSequence());
            applied.add(
                    new ObserverReducer.Applied(
                            event, ObserverReducer.template(event, application.proof(), policy())));
        }
        var ordered = ObserverReducer.ordered(applied);
        var boundaries = new HashMap<Long, AnimationState>();
        for (long n = seed.checkpoint().serverTick() + 1; n <= a.tick(); n++)
            boundaries.put(n, checkpoints.get(n));
        var reduced =
                ObserverReducer.reduce(
                        seed,
                        activation,
                        a.tick(),
                        boundaries,
                        ordered,
                        null,
                        assets().lr(),
                        policy());
        assertEquals(reduced.starts(), row.starts(), "start_mismatch");
        assertEquals(
                ObserverReducer.prefixDigest(row.seedRef(), ordered),
                row.eventPrefixDigest(),
                "event_prefix");
        assertEquals(
                AnimationResolver.presentationDigest(seed, reduced.pa().graph(), row),
                row.presentationDigest(),
                "presentation_digest");
        assertTrue(row.appliedEventSequence() >= seed.eventPrefix());
        for (var start : row.starts())
            if (start.layerTag() == LayerTag.CREATE_HANGING)
                assertEquals(AnchorClass.OBSERVER_APPLY, start.anchorClass());
    }
}
