package com.lunares.blockfield.client.combat;

import static com.lunares.blockfield.client.combat.ExactFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationRecipe;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import java.util.List;
import java.util.TreeMap;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

/** ADR4 exact stream intake: lossless decode, pairing, connection identity, R and A. */
class ClientExactStreamTest {
    private static final int FULL_RESET =
            AnimationRecipe.SnapshotRecord.FLAG_FULL | AnimationRecipe.SnapshotRecord.FLAG_RESET;

    private static PlayerSnapshotS2CPacket exact(
            long previous, long tick, long connection, long scene, int flags) {
        var identity =
                new com.lunares.blockfield.common.combat.AnimationState.Identity(
                        connection,
                        STREAM,
                        scene,
                        ENTITY,
                        0,
                        SUBSCRIPTION,
                        TARGET,
                        0,
                        0,
                        0,
                        0,
                        com.lunares.blockfield.common.combat.AnimationSchema.layoutDigest(),
                        ZERO);
        var checkpoint = checkpoint(identity, tick, tick * .25);
        var record =
                new AnimationRecipe.SnapshotRecord(
                        identity, flags, checkpoint.placement(), checkpoint);
        var seeds =
                (flags & AnimationRecipe.SnapshotRecord.FLAG_FULL) != 0
                        ? List.of(seed(checkpoint, tick * 256 + 1))
                        : List
                                .<com.lunares.blockfield.common.combat.AnimationEvent.ObserverSeed>
                                        of();
        return PlayerSnapshotS2CPacket.animation(
                STREAM,
                previous,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(connection, scene, List.of(record), List.of(), seeds));
    }

    @Test
    void exactRecordsKeepLosslessPlacementAndCheckpointThroughThePairing() {
        var buffer = new ClientSnapshotBuffer();
        buffer.add(ENTITY, 0);
        assertFalse(buffer.apply(exact(0, 100, CONNECTION, SCENE, 0), id -> false));
        assertTrue(buffer.broken(), "a new pairing must be FULL|RESET");

        buffer = new ClientSnapshotBuffer();
        buffer.add(ENTITY, 0);
        assertTrue(buffer.apply(exact(0, 100, CONNECTION, SCENE, FULL_RESET), id -> false));
        assertTrue(buffer.apply(exact(101, 101, CONNECTION, SCENE, 0), id -> false));
        assertEquals(CONNECTION, buffer.connectionId());
        var frames = buffer.frames(ENTITY);
        assertEquals(List.of(100L, 101L), List.copyOf(frames.keySet()));
        var record = frames.get(101L);
        assertTrue(record.exact());
        assertEquals(101, record.checkpoint().serverTick());
        assertEquals(
                Double.doubleToRawLongBits(101 * .25),
                Double.doubleToRawLongBits(record.placement().origin().x()),
                "raw f64 origin, never millimetres");
        // A changed connection/scene without a restart is a protocol defect.
        assertFalse(buffer.apply(exact(102, 102, CONNECTION + 1, SCENE, 0), id -> false));
        assertTrue(buffer.broken());
    }

    @Test
    void placementAtRSamplesTheCeilingRecordWithNativeGetterOrder() {
        var frames = new TreeMap<Long, SnapshotTransform>();
        for (long tick : new long[] {100, 101, 103}) {
            var checkpoint = checkpoint(identity(), tick, tick);
            frames.put(
                    tick,
                    SnapshotTransform.exact(
                            tick, identity(), 0, checkpoint.placement(), checkpoint));
        }
        var whole = SnapshotResolver.exact(frames, Time.whole(101));
        assertEquals(101, whole.before().tick(), "exact integer R selects (R, 1)");
        assertEquals(1, whole.fraction());
        assertEquals(101, whole.x(), 0);
        var mid = SnapshotResolver.exact(frames, new Time(100, 0x4000));
        assertEquals(101, mid.before().tick(), "R = 100.25 samples S[101] at p = .25");
        assertEquals(.25, mid.fraction(), 0);
        var placement = frames.get(101L).placement();
        var a = placement.angles();
        assertEquals(
                Mth.lerp(.25f, placement.previousOrigin().x(), placement.origin().x()), mid.x());
        assertEquals(Mth.lerp(.25f, a.yRotO(), a.yRot()), mid.angles().get(0).floatValue());
        assertEquals(Mth.lerp(.25f, a.xRotO(), a.xRot()), mid.angles().get(1).floatValue());
        assertEquals(
                Mth.rotLerp(.25f, a.yBodyRotO(), a.yBodyRot()), mid.angles().get(2).floatValue());
        assertEquals(
                Mth.rotLerp(.25f, a.yHeadRotO(), a.yHeadRot()), mid.angles().get(3).floatValue());
        var gap = SnapshotResolver.exact(frames, new Time(101, 0x8000));
        assertEquals("hold", gap.mode(), "S[102] is missing: hold, never stretch");
        assertEquals(101, gap.before().tick());
        assertEquals(101, gap.x(), 0);
        assertEquals("snap", SnapshotResolver.exact(frames, new Time(98, 0)).mode());
    }

    @Test
    void clockAStaysBetweenRAndHAndNeverDecreasesAcrossARestart() {
        var clock = new CombatSceneClock();
        long ns = 1_000_000_000_000L;
        for (int i = 0; i < 8; i++)
            clock.arrival(
                    exact(
                            i == 0 ? 0 : 100 + i,
                            100 + i,
                            CONNECTION,
                            SCENE,
                            i == 0 ? FULL_RESET : 0),
                    (ns + i * 50_000_000L) / 1e6);
        Time previous = null;
        for (int f = 0; f < 30; f++) {
            assertTrue(clock.frameNs(ns + 350_000_000L + f * 16_000_000L));
            var r = clock.r();
            var a = clock.a();
            assertTrue(clock.animationReady());
            assertTrue(r.compareTo(a) <= 0, "R <= A");
            assertTrue(a.compareTo(Time.whole(107)) <= 0, "A <= H");
            if (previous != null) assertTrue(previous.compareTo(a) <= 0, "A never decreases");
            previous = a;
        }
        var restarted = clock.restarted();
        assertFalse(restarted.animationReady(), "a restarted stream rewarms A");
        assertEquals(previous, restarted.a(), "and keeps the connection high-water");
    }
}
