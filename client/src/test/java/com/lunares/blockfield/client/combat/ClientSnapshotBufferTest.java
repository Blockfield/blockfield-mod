package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationRecipe.SnapshotRecord;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exact-stream port of the r10 buffer gate. Dropped r10-only cases (subject deleted with {@code
 * PlayerSnapshotS2CPacket.Record}): angleWrap (u16/i16 quantization via {@code
 * SnapshotWireMath.applyAngleDelta} in the old buffer), decodedMillimetreOverflow ({@code
 * Math.addExact} mm path, old {@code Reason.ARITHMETIC}), walkDistance ({@code
 * SnapshotResolver.resolve(double)} chord + {@code Sample.tickDistance}).
 */
class ClientSnapshotBufferTest {
    static final long STREAM = 17, CONNECTION = 3, SCENE = 1, SUB = 5;
    static final int FULL_RESET = SnapshotRecord.FLAG_FULL | SnapshotRecord.FLAG_RESET;

    static AnimationState.Identity id(
            int entity, int kind, long sub, long epoch, long seat, long teleport) {
        return new AnimationState.Identity(
                CONNECTION,
                STREAM,
                SCENE,
                entity,
                kind,
                sub,
                epoch,
                seat,
                teleport,
                0,
                0,
                AnimationSchema.layoutDigest(),
                new AnimationResources.Digest(0, 0, 0, 0));
    }

    static SnapshotRecord full(
            int entity,
            int kind,
            int flags,
            double x,
            long epoch,
            long seat,
            long teleport,
            long tick) {
        return fullSub(entity, kind, flags, x, SUB, epoch, seat, teleport, tick);
    }

    static SnapshotRecord fullSub(
            int entity,
            int kind,
            int flags,
            double x,
            long sub,
            long epoch,
            long seat,
            long teleport,
            long tick) {
        var identity = id(entity, kind, sub, epoch, seat, teleport);
        var checkpoint = ExactFixtures.checkpoint(identity, tick, x);
        return new SnapshotRecord(identity, flags, checkpoint.placement(), checkpoint);
    }

    static SnapshotRecord cont(int entity, double x, long tick) {
        return full(entity, 0, 0, x, 7, 0, 0, tick);
    }

    static SnapshotRecord contE(int entity, double x, long epoch, long tick) {
        return full(entity, 0, 0, x, epoch, 0, 0, tick);
    }

    static SnapshotRecord cartCont(int entity, double x, long tick) {
        return full(entity, 1, 0, x, 2, 0, 0, tick);
    }

    static PlayerSnapshotS2CPacket batch(long tick, SnapshotRecord... records) {
        var sorted = new ArrayList<>(List.of(records));
        sorted.sort(Comparator.comparingInt(r -> r.identity().entityId()));
        return PlayerSnapshotS2CPacket.animation(
                STREAM,
                0,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(
                        CONNECTION, SCENE, List.copyOf(sorted), List.of(), List.of()));
    }

    static PlayerSnapshotS2CPacket empty(long tick) {
        return batch(tick);
    }

    /** No native entity outside explicit add/remove calls. */
    static boolean apply(ClientSnapshotBuffer b, PlayerSnapshotS2CPacket batch) {
        return apply(b, batch, id -> false);
    }

    static boolean apply(
            ClientSnapshotBuffer b,
            PlayerSnapshotS2CPacket batch,
            java.util.function.IntPredicate nativePresent) {
        var sent =
                PlayerSnapshotS2CPacket.animation(
                        STREAM,
                        b.high() + 1,
                        batch.serverTick(),
                        batch.interpMin(),
                        batch.interpMax(),
                        batch.dilationPercent(),
                        batch.holdToSnapMs(),
                        batch.animation());
        return b.apply(sent, nativePresent);
    }

    static SnapshotResolver.Sample at(ClientSnapshotBuffer b, int entity, long tick) {
        return SnapshotResolver.exact(b.frames(entity), Time.whole(tick));
    }

    @Test
    void omittedSenderBatchUsesActualEndpointsAndInheritedQuantizedBaseline() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        assertTrue(apply(b, batch(1020, full(1, 0, FULL_RESET, 0, 7, 0, 0, 1020))));
        // Sender omits 1021 without advancing the baseline; 1022 carries the run endpoint.
        assertTrue(apply(b, batch(1022, cont(1, 0.56, 1022))));
        var mid = SnapshotResolver.exact(b.frames(1), new Time(1021, 0x4000));
        assertEquals("lerp", mid.mode());
        assertEquals(0.25, mid.fraction(), 1e-6);
        assertEquals(0.3725, mid.x(), 1e-12);
        assertEquals(1022, mid.before().tick());
        assertEquals(7, mid.after().epoch());
    }

    @Test
    void orderedStallKeepsBaselineAndHoldPinsFeet() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        apply(b, batch(1000, full(1, 0, FULL_RESET, 0.123, 7, 0, 0, 1000)));
        for (int i = 1; i <= 12; i++)
            assertTrue(apply(b, batch(1000 + i, cont(1, 0.123 + 0.28 * i, 1000 + i))));
        var hold = at(b, 1, 1015);
        assertEquals("hold", hold.mode());
        assertEquals(3.483, hold.x(), 1e-12);
    }

    @Test
    void lateFullClampsOnlyItsOwnVictim() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        b.add(2, 0);
        apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100)));
        apply(b, batch(101, cont(1, 0.28, 101), full(2, 0, FULL_RESET, 9, 8, 0, 0, 101)));
        assertEquals(List.of(100L, 101L), List.copyOf(b.frames(1).keySet()));
        assertEquals(List.of(101L), List.copyOf(b.frames(2).keySet()));
        assertEquals(9, at(b, 2, 100).x(), 0);
        assertEquals("snap", at(b, 2, 100).mode());
        assertEquals(0.28, at(b, 1, 101).x(), 1e-12);
    }

    @Test
    void fullBeforeAddWaitsAndChecksNativeKind() {
        var b = new ClientSnapshotBuffer();
        apply(
                b,
                batch(
                        100,
                        full(1, 0, FULL_RESET, 4, 7, 0, 0, 100),
                        full(2, 1, FULL_RESET, 5, 8, 0, 0, 100)));
        assertFalse(b.hasLiveSamples(1));
        b.add(1, 0);
        assertEquals(4, at(b, 1, 100).x(), 0);
        b.add(2, 0);
        assertFalse(b.hasLiveSamples(2));
        apply(b, batch(101, full(2, 0, FULL_RESET, 6, 9, 0, 0, 101), cont(1, 4.28, 101)));
        assertEquals(6, at(b, 2, 101).x(), 0);
        assertEquals(0, b.kindMismatches());
    }

    @Test
    void removeAndIdReuseCannotInheritTrailOrAffectOthers() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        b.add(2, 0);
        apply(
                b,
                batch(
                        100,
                        full(1, 0, FULL_RESET, 0.001, 7, 0, 0, 100),
                        full(2, 0, FULL_RESET, 2, 8, 0, 0, 100)));
        apply(b, batch(101, cont(1, 0.281, 101), full(2, 0, 0, 2.28, 8, 0, 0, 101)));
        b.remove(1);
        assertFalse(b.hasLiveSamples(1));
        assertEquals(2.28, at(b, 2, 101).x(), 1e-12);
        assertEquals(101, b.high());
        b.add(1, 0);
        assertFalse(b.hasLiveSamples(1));
        apply(b, batch(102, full(1, 0, FULL_RESET, 9, 9, 0, 0, 102)));
        assertEquals(9, at(b, 1, 100).x(), 0);
        assertEquals("snap", at(b, 1, 100).mode());
        assertEquals(9, at(b, 1, 102).before().epoch());
    }

    @Test
    void fullCorrectionOrderingDoesNotDependOnNativeTeleport() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100)));
        apply(b, batch(101, cont(1, 0.28, 101)));
        apply(
                b,
                batch(
                        102,
                        full(
                                1,
                                0,
                                SnapshotRecord.FLAG_FULL | SnapshotRecord.FLAG_SNAP,
                                5.28,
                                7,
                                0,
                                1,
                                102)));
        assertEquals(List.of(102L), List.copyOf(b.frames(1).keySet()));
        assertEquals(5.28, at(b, 1, 100).x(), 0);
        assertEquals("snap", at(b, 1, 100).mode());
    }

    @Test
    void seatFullAndNativeLinkageWorkInBothOrders() {
        for (boolean fullFirst : List.of(false, true)) {
            var b = new ClientSnapshotBuffer();
            b.add(1, 0);
            apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100)));
            if (!fullFirst) {
                b.seatChanged(1);
                assertFalse(b.hasLiveSamples(1));
            }
            apply(
                    b,
                    batch(
                            101,
                            full(
                                    1,
                                    0,
                                    SnapshotRecord.FLAG_FULL | SnapshotRecord.FLAG_SEAT,
                                    3,
                                    7,
                                    1,
                                    0,
                                    101)));
            if (fullFirst) b.seatChanged(1);
            assertEquals(3, at(b, 1, 100).x(), 0);
            assertEquals(1, b.frames(1).get(101L).seat());
        }
    }

    @Test
    void protocolFailureIsAtomicUntilExplicitRestart() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100)));
        assertFalse(apply(b, batch(101, cont(1, 0.28, 101), cont(2, 0.05, 101))));
        assertEquals(100, b.high());
        assertTrue(b.broken());
        assertEquals(1, b.defects());
        assertFalse(apply(b, batch(102, full(1, 0, FULL_RESET, 5, 7, 0, 0, 102))));
        assertFalse(b.hasLiveSamples(1));
    }

    @Test
    void pendingExpiresAfterFortyTicksAndNeverDrives() {
        var b = new ClientSnapshotBuffer();
        apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100)));
        apply(b, empty(140));
        assertEquals(0, b.size());
        assertEquals(1, b.expiredPending());
    }

    @Test
    void deltaOnlyGapRetainsCodecBaseAndNewSampleCanRewarm() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        apply(b, batch(1000, full(1, 0, FULL_RESET, 0, 7, 0, 0, 1000)));
        // No sender advancement on omitted 1001..1005: 1006 carries the run endpoint.
        assertTrue(apply(b, batch(1006, cont(1, 1.68, 1006))));
        assertEquals("hold", SnapshotResolver.exact(b.frames(1), Time.whole(1003)).mode());
        assertEquals(1.68, at(b, 1, 1006).x(), 1e-12);
        assertEquals(7, b.frames(1).get(1006L).epoch());
        assertTrue(apply(b, batch(1007, cont(1, 1.96, 1007))));
        assertEquals(1.96, at(b, 1, 1007).x(), 1e-12);
    }

    @Test
    void liveFullResetsRemainPresentableWithoutNativeRemoveAdd() {
        for (var reset :
                List.of(
                        full(7, 0, FULL_RESET, 2, 3, 0, 0, 102),
                        fullSub(7, 0, FULL_RESET, 2, SUB + 1, 4, 0, 0, 102),
                        full(
                                7,
                                0,
                                SnapshotRecord.FLAG_FULL | SnapshotRecord.FLAG_SNAP,
                                2,
                                3,
                                0,
                                1,
                                102))) {
            var b = new ClientSnapshotBuffer();
            b.add(7, 0);
            var initial = full(7, 0, FULL_RESET, 0, 3, 0, 0, 100);
            assertTrue(apply(b, batch(100, initial)));
            assertTrue(b.paired(initial.identity()));
            assertTrue(apply(b, batch(101, contE(7, 0.28, 3, 101))));
            assertTrue(apply(b, batch(102, reset)));
            for (long tick = 103; tick <= 150; tick++) {
                var next =
                        new SnapshotRecord(
                                reset.identity(),
                                0,
                                ExactFixtures.checkpoint(reset.identity(), tick, 2).placement(),
                                ExactFixtures.checkpoint(reset.identity(), tick, 2));
                assertTrue(apply(b, batch(tick, next)));
            }
            assertTrue(
                    b.paired(reset.identity()), "live reset must not wait forever for native Add");
            if (!initial.identity().sameGeneration(reset.identity()))
                assertFalse(b.paired(initial.identity()), "old identity stays unavailable");
            assertFalse(b.paired(id(7, 1, SUB, 3, 0, 0)), "wrong native kind");
            b.remove(7);
            assertFalse(b.paired(reset.identity()), "absent native entity cannot present");
            assertFalse(b.hasLiveSamples(7));
            b.add(7, 0);
            if ((reset.flags() & SnapshotRecord.FLAG_RESET) != 0)
                assertTrue(b.paired(reset.identity()), "late Remove/Add retains the new run");
        }
    }

    @Test
    void lateRemoveAfterNewerPairingFullKeepsThatSubscriptionForItsAdd() {
        var b = new ClientSnapshotBuffer();
        b.add(7, 0);
        apply(b, batch(100, full(7, 0, FULL_RESET, 0, 3, 0, 0, 100)));
        apply(b, batch(101, contE(7, 0.28, 3, 101)));
        apply(b, empty(102));
        apply(b, empty(103));
        apply(b, batch(104, full(7, 0, FULL_RESET, 1, 3, 0, 0, 104)));
        apply(b, batch(105, full(7, 0, 0, 1.28, 3, 0, 0, 105)));
        b.remove(7);
        b.add(7, 0);
        assertTrue(apply(b, batch(106, full(7, 0, 0, 1.56, 3, 0, 0, 106))));
        assertFalse(b.broken());
        assertEquals("hold", at(b, 7, 106).mode());
        assertEquals(1.56, at(b, 7, 106).x(), 1e-12);
        // The removed pairing's 100..101 trail is gone: sampling there snaps to the adopted full.
        assertEquals("snap", at(b, 7, 100).mode());
        assertEquals(1, at(b, 7, 100).x(), 0);
    }

    @Test
    void lateRemoveAfterSameTickRePairFullKeepsItsBaseline() {
        var b = new ClientSnapshotBuffer();
        b.add(7, 0);
        apply(b, batch(100, full(7, 0, FULL_RESET, 0, 3, 0, 0, 100)));
        apply(b, batch(101, contE(7, 0.28, 3, 101)));
        apply(b, batch(102, full(7, 0, FULL_RESET, 2, 3, 0, 0, 102)));
        b.remove(7);
        b.add(7, 0);
        assertTrue(apply(b, batch(103, full(7, 0, 0, 2.28, 3, 0, 0, 103))));
        assertEquals(2.28, at(b, 7, 103).x(), 1e-12);
        assertEquals("snap", at(b, 7, 101).mode());
    }

    @Test
    void removeBeforeDelayedOldRecordsDecodesButNeverAdoptsThem() {
        var b = new ClientSnapshotBuffer();
        b.add(7, 0);
        apply(b, batch(100, full(7, 0, FULL_RESET, 0, 3, 0, 0, 100)));
        apply(b, batch(101, contE(7, 0.28, 3, 101)));
        b.remove(7);
        b.add(7, 0);
        assertTrue(apply(b, batch(102, full(7, 0, 0, 0.56, 3, 0, 0, 102))));
        assertFalse(b.hasLiveSamples(7));
        apply(b, empty(103));
        assertTrue(apply(b, batch(104, full(7, 0, FULL_RESET, 5, 3, 0, 0, 104))));
        assertEquals(5, at(b, 7, 104).x(), 0);
        assertEquals(104, at(b, 7, 103).before().tick());
        assertFalse(b.broken());
    }

    @Test
    void removeOfAnIdWithoutLiveEntityKeepsPendingFullForTheFollowingAdd() {
        var b = new ClientSnapshotBuffer();
        apply(b, batch(100, full(7, 0, FULL_RESET, 4, 3, 0, 0, 100)));
        b.remove(7);
        b.add(7, 0);
        assertEquals(4, at(b, 7, 100).x(), 0);
    }

    @Test
    void farRateSkipClosedByDeltaHoldsTheLastSentBoundary() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100)));
        apply(b, batch(101, cont(1, 0.28, 101)));
        apply(b, empty(102));
        assertTrue(b.hasLiveSamples(1), "a skip may still be certified by the next DELTA");
        assertTrue(apply(b, batch(103, cont(1, 0.84, 103))));
        var frames = b.frames(1);
        assertEquals(List.of(100L, 101L, 102L, 103L), List.copyOf(frames.keySet()));
        var held = frames.get(102L);
        assertEquals(frames.get(101L).placement(), held.placement());
        assertEquals(102, held.checkpoint().serverTick());
        assertFalse(held.checkpoint().decisions().isEmpty());
        for (var decision : held.checkpoint().decisions()) {
            assertEquals(102, decision.id().serverTick());
            assertEquals(102, decision.input().contentId());
        }
        assertEquals(0.28, at(b, 1, 102).x(), 1e-12);
    }

    @Test
    void fullAfterAShortAbsenceStillStartsAFreshRun() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100)));
        apply(b, batch(101, cont(1, 0.28, 101)));
        apply(b, empty(102));
        assertTrue(apply(b, batch(103, full(1, 0, SnapshotRecord.FLAG_FULL, 0.84, 7, 0, 0, 103))));
        assertEquals(List.of(103L), List.copyOf(b.frames(1).keySet()));
    }

    @Test
    void absenceStopsDrivingAndResumedDeltaStartsAFreshRunOnTheKeptBaseline() {
        var b = new ClientSnapshotBuffer();
        b.add(5, 1);
        apply(b, batch(100, full(5, 1, FULL_RESET, 0, 2, 0, 0, 100)));
        apply(b, batch(101, cartCont(5, 0.28, 101)));
        for (int t = 102; t <= 104; t++) apply(b, empty(t));
        assertTrue(b.hasLiveSamples(5), "shorter than the longest far-rate skip");
        apply(b, empty(105));
        assertFalse(b.hasLiveSamples(5));
        for (int t = 106; t <= 151; t++) apply(b, empty(t));
        assertTrue(apply(b, batch(152, full(5, 1, 0, 1.28, 2, 0, 0, 152))));
        var s = SnapshotResolver.exact(b.frames(5), Time.whole(151));
        assertEquals("snap", s.mode());
        assertEquals(1.28, s.x(), 1e-12);
    }

    @Test
    void streamedEntityWithoutDriverKeepsBaselineWhileNativelyPresent() {
        var b = new ClientSnapshotBuffer();
        assertTrue(apply(b, batch(100, full(5, 1, FULL_RESET, 0, 2, 0, 0, 100)), id -> id == 5));
        for (int t = 101; t <= 160; t++)
            assertTrue(apply(b, batch(t, cartCont(5, 0.28 * (t - 100), t)), id -> id == 5));
        assertTrue(apply(b, batch(161, cartCont(5, 0.28 * 61, 161)), id -> id == 5));
        assertFalse(b.broken());
        for (int t = 162; t <= 202; t++) apply(b, empty(t), id -> false);
        assertEquals(0, b.size());
        assertFalse(b.broken());
    }

    @Test
    void liveKindMismatchWaitsForMatchingFullWithoutDisablingTheStream() {
        var b = new ClientSnapshotBuffer();
        b.add(3, 0);
        b.add(4, 0);
        assertTrue(
                apply(
                        b,
                        batch(
                                100,
                                full(3, 1, FULL_RESET, 0, 2, 0, 0, 100),
                                full(4, 0, FULL_RESET, 0, 2, 0, 0, 100))));
        assertFalse(b.hasLiveSamples(3));
        assertEquals(1, b.kindMismatches());
        assertEquals(0, at(b, 4, 100).x(), 0);
        assertTrue(
                apply(
                        b,
                        batch(
                                101,
                                full(3, 0, FULL_RESET, 7, 2, 0, 0, 101),
                                full(4, 0, 0, 0.28, 2, 0, 0, 101))));
        assertEquals(7, at(b, 3, 101).x(), 0);
    }

    @Test
    void sceneResetEmptiesPresentationAndKeepsDecodeForThePairingFull() {
        var b = new ClientSnapshotBuffer();
        b.add(7, 0);
        apply(b, batch(100, full(7, 0, FULL_RESET, 0, 3, 0, 0, 100)));
        b.sceneReset();
        assertFalse(b.hasLiveSamples(7));
        assertTrue(apply(b, batch(101, full(7, 0, 0, 0.28, 3, 0, 0, 101))));
        b.add(7, 0);
        assertFalse(b.hasLiveSamples(7));
        apply(b, batch(102, full(7, 0, FULL_RESET, 3, 4, 0, 0, 102)));
        assertEquals(3, at(b, 7, 102).x(), 0);
    }

    @Test
    void mountedInitialFullBeforeAddCanPayForItsLateSeatLinkage() {
        var b = new ClientSnapshotBuffer();
        apply(b, batch(100, full(7, 0, FULL_RESET, 4, 3, 1, 0, 100)));
        b.remove(7);
        b.add(7, 0);
        b.seatChanged(7);
        assertEquals(4, at(b, 7, 100).x(), 0);
        assertTrue(apply(b, batch(101, full(7, 0, 0, 4.28, 3, 1, 0, 101))));
        assertEquals(4.28, at(b, 7, 101).x(), 1e-12);
    }

    @Test
    void newPairingFullWithoutResetIsAProtocolDefect() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        // A bare FULL opens no subscription: the server always sends FULL|RESET first.
        assertFalse(apply(b, batch(100, full(1, 0, SnapshotRecord.FLAG_FULL, 0, 7, 0, 0, 100))));
        assertTrue(b.broken());
        assertEquals(1, b.defects());
    }

    @Test
    void newPairingFullResetStartsTheRun() {
        var b = new ClientSnapshotBuffer();
        b.add(1, 0);
        assertTrue(apply(b, batch(100, full(1, 0, FULL_RESET, 0, 7, 0, 0, 100))));
        assertFalse(b.broken());
        assertEquals("hold", at(b, 1, 100).mode());
        assertEquals(7, b.frames(1).get(100L).epoch());
    }
}
