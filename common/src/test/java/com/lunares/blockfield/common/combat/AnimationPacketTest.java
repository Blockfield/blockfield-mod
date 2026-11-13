package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class AnimationPacketTest {
    private static <T> byte[] encode(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buf, value);
            return bytes(buf);
        } finally {
            buf.release();
        }
    }

    private static <T> T decode(StreamCodec<FriendlyByteBuf, T> codec, byte[] bytes) {
        var buf = buffer(bytes);
        try {
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }

    @Test
    void exactTicketHasHandDerivedHeaderLayoutAndNeutralVictimRows() {
        var view = receipt(List.of());
        byte[] bytes = encode(CombatViewC2SPacket.ANIMATION_CODEC, new CombatViewC2SPacket(view));
        // Protocol 9: one presence byte before the victim report.
        assertEquals(50, bytes.length);
        assertArrayEquals(
                new byte[] {
                    1, 7, 2, 4, 3, 8, 2, 103, 98, 0x4c, (byte) 0xcc, 100, 0x4c, (byte) 0xcc, 0, 0
                },
                Arrays.copyOf(bytes, 16));
        assertEquals(view, decode(CombatViewC2SPacket.ANIMATION_CODEC, bytes).view());
        var neutral = receipt(List.of(victim(1, List.of())));
        // Schema manifest 4: one more uvarint per row, the (empty) application count.
        assertEquals(
                123,
                encode(CombatViewC2SPacket.ANIMATION_CODEC, new CombatViewC2SPacket(neutral))
                        .length);
        var active =
                receipt(
                        List.of(
                                victim(
                                        1,
                                        List.of(
                                                observer(
                                                        1,
                                                        1,
                                                        LayerTag.PA_94,
                                                        PhaseDomain.A,
                                                        100,
                                                        0)))));
        assertEquals(
                146,
                encode(CombatViewC2SPacket.ANIMATION_CODEC, new CombatViewC2SPacket(active))
                        .length);
        assertEquals(
                active,
                decode(
                                CombatViewC2SPacket.ANIMATION_CODEC,
                                encode(
                                        CombatViewC2SPacket.ANIMATION_CODEC,
                                        new CombatViewC2SPacket(active)))
                        .view());
    }

    @Test
    void applicationRowsHaveAHandDerivedLayoutAndStrictOrder() {
        var apply =
                new AnimationStartReport.ObserverApply(
                        8,
                        2,
                        3,
                        new Time(103, 0),
                        new Time(98, 19660),
                        new Time(100, 19660),
                        103,
                        5);
        var row =
                new AnimationStartReport.Victim(
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        100,
                        DIGEST,
                        List.of(),
                        List.of(new AnimationStartReport.Application(5, apply)),
                        DIGEST);
        var view = receipt(List.of(row));
        byte[] bytes = encode(CombatViewC2SPacket.ANIMATION_CODEC, new CombatViewC2SPacket(view));
        // Row: id, six uvarlongs, prefix digest, start count 0, application count 1, then
        // sequence 5, frame 8, revision 2, mapping 3, sW 103/0, applyR 98/19660,
        // applyA 100/19660, applyH 103, prefix 5, then the presentation digest.
        byte[] application = {
            1, 5, 8, 2, 3, 103, 0, 0, 98, 0x4c, (byte) 0xcc, 100, 0x4c, (byte) 0xcc, 103, 5
        };
        int at = 50 - 1 + 1 + 1 + 6 + 32 + 1;
        assertArrayEquals(application, Arrays.copyOfRange(bytes, at, at + application.length));
        assertEquals(view, decode(CombatViewC2SPacket.ANIMATION_CODEC, bytes).view());
        assertEquals(123 + 15, bytes.length);
        var unsorted =
                AnimationFixtures.with(
                        row,
                        "applications",
                        List.of(
                                new AnimationStartReport.Application(5, apply),
                                new AnimationStartReport.Application(5, apply)));
        assertThrows(IllegalArgumentException.class, () -> receipt(List.of(unsorted)));
        var future = AnimationFixtures.with(row, "appliedEventSequence", 4L);
        assertThrows(IllegalArgumentException.class, () -> receipt(List.of(future)));
        var zero =
                AnimationFixtures.with(
                        row,
                        "applications",
                        List.of(
                                new AnimationStartReport.Application(
                                        0, AnimationFixtures.with(apply, "applyEventPrefix", 0L))));
        assertThrows(IllegalArgumentException.class, () -> receipt(List.of(zero)));
    }

    @Test
    void theRegisteredWireIsTheExactCodecWithNoFormatByte() {
        var wire = com.lunares.blockfield.network.GameNetworkHandler.snapshotWire();
        var state = sample(AnimationState.class);
        var exact =
                PlayerSnapshotS2CPacket.animation(
                        0,
                        0,
                        0,
                        2,
                        6,
                        10,
                        500,
                        new AnimationRecipe.Batch(
                                0,
                                0,
                                List.of(
                                        new AnimationRecipe.SnapshotRecord(
                                                state.identity(), 0, state.placement(), state)),
                                List.of(),
                                List.of()));
        assertArrayEquals(
                encode(PlayerSnapshotS2CPacket.ANIMATION_CODEC, exact), encode(wire, exact));
        assertEquals(exact.animation(), decode(wire, encode(wire, exact)).animation());
        assertThrows(IllegalArgumentException.class, () -> decode(wire, new byte[0]));
        byte[] body = encode(wire, exact);
        assertThrows(
                IllegalArgumentException.class,
                () -> decode(wire, Arrays.copyOf(body, body.length + 1)));
        var view = com.lunares.blockfield.network.GameNetworkHandler.viewWire();
        var ticket = new CombatViewC2SPacket(receipt(List.of()));
        assertArrayEquals(
                encode(CombatViewC2SPacket.ANIMATION_CODEC, ticket), encode(view, ticket));
        assertEquals(ticket.view(), decode(view, encode(view, ticket)).view());
    }

    @Test
    void probeAndEchoHaveFixedLayoutsAndRejectTrailingBytes() {
        var probe = new AnimationProbeS2CPacket(300, -5, 7, Long.MIN_VALUE);
        byte[] bytes = encode(AnimationProbeS2CPacket.CODEC, probe);
        assertArrayEquals(
                new byte[] {
                    (byte) 0xac,
                    2,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1,
                    -5,
                    7,
                    (byte) 0x80,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                },
                bytes);
        assertEquals(probe, decode(AnimationProbeS2CPacket.CODEC, bytes));
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> decode(AnimationProbeS2CPacket.CODEC, trailing));
        for (int size = 0; size < bytes.length; size++) {
            byte[] truncated = Arrays.copyOf(bytes, size);
            assertThrows(
                    RuntimeException.class, () -> decode(AnimationProbeS2CPacket.CODEC, truncated));
        }
        var echo = new AnimationProbeC2SPacket(300);
        assertArrayEquals(new byte[] {(byte) 0xac, 2}, encode(AnimationProbeC2SPacket.CODEC, echo));
        assertEquals(echo, decode(AnimationProbeC2SPacket.CODEC, new byte[] {(byte) 0xac, 2}));
        assertThrows(
                IllegalArgumentException.class,
                () -> decode(AnimationProbeC2SPacket.CODEC, new byte[] {(byte) 0xac, 2, 0}));
        assertThrows(IllegalArgumentException.class, () -> new AnimationProbeC2SPacket(-1));
    }

    @Test
    void allConditionalProofsRoundTripAndUnknownTagsFail() {
        var starts =
                List.of(
                        observer(1, 1, LayerTag.PA_94, PhaseDomain.A, 100, 0),
                        new AnimationStartReport.Start(
                                2,
                                2,
                                LayerTag.PA_41,
                                StartKind.SEED_CONTINUE,
                                AnchorClass.CANONICAL_TICK,
                                PhaseDomain.A,
                                99,
                                12,
                                0,
                                new AnimationStartReport.CanonicalTick(
                                        99, NativeStage.POST_NATIVE_TICK)),
                        new AnimationStartReport.Start(
                                3,
                                3,
                                LayerTag.PARCOOL_ACTIVE,
                                StartKind.REPLACE,
                                AnchorClass.PROJECTED_SUCCESSOR,
                                PhaseDomain.A,
                                100,
                                0,
                                2,
                                new AnimationStartReport.ProjectedSuccessor(
                                        100, NativeStage.NATIVE_TICK, 5, 1)));
        var view = receipt(List.of(victim(1, starts)));
        assertEquals(
                view,
                decode(
                                CombatViewC2SPacket.ANIMATION_CODEC,
                                encode(
                                        CombatViewC2SPacket.ANIMATION_CODEC,
                                        new CombatViewC2SPacket(view)))
                        .view());
        byte[] encoded =
                AnimationSchema.encodeBounded(
                        b -> AnimationStartReport.writeStart(b, starts.getFirst()));
        encoded[2] = (byte) 255;
        var invalid = buffer(encoded);
        try {
            assertThrows(
                    IllegalArgumentException.class, () -> AnimationStartReport.readStart(invalid));
        } finally {
            invalid.release();
        }
    }

    @Test
    void exactCodecNeverAcceptsLegacyOrPartial() {
        // The legacy codecs are gone: a receipt or batch without animation cannot be constructed.
        assertThrows(NullPointerException.class, () -> new CombatView(1, 1, 103, 98, 0, null));
        assertThrows(
                NullPointerException.class,
                () -> PlayerSnapshotS2CPacket.animation(1, 0, 103, 2, 6, 10, 500, null));
        var state = sample(AnimationState.class);
        var record =
                new AnimationRecipe.SnapshotRecord(state.identity(), 0, state.placement(), state);
        var packet =
                PlayerSnapshotS2CPacket.animation(
                        0,
                        0,
                        0,
                        2,
                        6,
                        10,
                        500,
                        new AnimationRecipe.Batch(0, 0, List.of(record), List.of(), List.of()));
        var decoded =
                decode(
                        PlayerSnapshotS2CPacket.ANIMATION_CODEC,
                        encode(PlayerSnapshotS2CPacket.ANIMATION_CODEC, packet));
        assertEquals(packet.animation(), decoded.animation());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationRecipe.SnapshotRecord(
                                state.identity(), 0, state.placement(), null));
    }

    @Test
    void everyTruncationTrailingBytesWrongVersionAndSortedSetViolationsFail() {
        byte[] valid =
                encode(
                        CombatViewC2SPacket.ANIMATION_CODEC,
                        new CombatViewC2SPacket(
                                receipt(
                                        List.of(
                                                victim(
                                                        1,
                                                        List.of(
                                                                observer(
                                                                        1,
                                                                        1,
                                                                        LayerTag.PA_94,
                                                                        PhaseDomain.A,
                                                                        100,
                                                                        0)))))));
        for (int size = 0; size < valid.length; size++) {
            byte[] truncated = Arrays.copyOf(valid, size);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> decode(CombatViewC2SPacket.ANIMATION_CODEC, truncated));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        decode(
                                CombatViewC2SPacket.ANIMATION_CODEC,
                                Arrays.copyOf(valid, valid.length + 1)));
        byte[] wrong = valid.clone();
        wrong[0] = 2;
        assertThrows(
                IllegalArgumentException.class,
                () -> decode(CombatViewC2SPacket.ANIMATION_CODEC, wrong));
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt(List.of(victim(2, List.of()), victim(1, List.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt(List.of(victim(1, List.of()), victim(1, List.of()))));
        var start = observer(1, 1, LayerTag.PA_94, PhaseDomain.A, 100, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt(List.of(victim(1, List.of(start, start)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationStartReport(Collections.nCopies(4097, victim(1, List.of()))));
    }

    @Test
    void eventIdentitiesRejectNegativeValuesZeroGenesisAndFuturePrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationEvent.EventId(-1, 1, NativeStage.INTER_TICK_ACCEPT));
        var genesis =
                with(
                        sample(AnimationEvent.class),
                        "id",
                        new AnimationEvent.EventId(103, 0, NativeStage.INTER_TICK_ACCEPT));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationEventS2CPacket(List.of(genesis)));
        var base = observer(50, 1, LayerTag.PA_94, PhaseDomain.A, 100, 0);
        var future =
                with(
                        base,
                        "proof",
                        new AnimationStartReport.ObserverApply(
                                8,
                                2,
                                3,
                                new Time(103, 0),
                                new Time(98, 19660),
                                new Time(100, 19660),
                                103,
                                101));
        assertThrows(
                IllegalArgumentException.class, () -> receipt(List.of(victim(1, List.of(future)))));
    }

    @Test
    void nativeEventAndAppliedPacketsPreserveLogicalDelivery() {
        var event =
                with(
                        sample(AnimationEvent.class),
                        "id",
                        new AnimationEvent.EventId(103, 1, NativeStage.INTER_TICK_ACCEPT));
        var second =
                with(
                        with(
                                event,
                                "id",
                                new AnimationEvent.EventId(103, 2, NativeStage.PRE_NATIVE_TICK)),
                        "predecessorEventSequence",
                        1L);
        var packet = new AnimationEventS2CPacket(List.of(event, second));
        assertEquals(
                packet,
                decode(
                        AnimationEventS2CPacket.CODEC,
                        encode(AnimationEventS2CPacket.CODEC, packet)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationEventS2CPacket(List.of(second, event)));
        var report =
                new AnimationAppliedC2SPacket(
                        receipt(
                                List.of(
                                        victim(
                                                1,
                                                List.of(
                                                        observer(
                                                                1,
                                                                1,
                                                                LayerTag.PA_94,
                                                                PhaseDomain.A,
                                                                100,
                                                                0))))));
        assertEquals(
                report,
                decode(
                        AnimationAppliedC2SPacket.CODEC,
                        encode(AnimationAppliedC2SPacket.CODEC, report)));
        var canonical =
                new AnimationStartReport.Start(
                        1,
                        1,
                        LayerTag.PA_94,
                        StartKind.NEW,
                        AnchorClass.CANONICAL_TICK,
                        PhaseDomain.A,
                        100,
                        0,
                        0,
                        new AnimationStartReport.CanonicalTick(100, NativeStage.NATIVE_TICK));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationAppliedC2SPacket(
                                receipt(List.of(victim(1, List.of(canonical))))));
    }

    @Test
    void measured31VictimCompactedAndUncompactedMixedReportsFitWithoutTruncation() {
        var compacted =
                IntStream.range(0, 7)
                        .mapToObj(
                                i ->
                                        new AnimationStartReport.Start(
                                                i + 1,
                                                i + 1,
                                                LayerTag.values()[i],
                                                StartKind.SEED_CONTINUE,
                                                AnchorClass.CANONICAL_TICK,
                                                PhaseDomain.A,
                                                100,
                                                100,
                                                0,
                                                new AnimationStartReport.CanonicalTick(
                                                        100, NativeStage.END_COMMIT)))
                        .toList();
        List<AnimationStartReport.Start> mixed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int node = i + 1;
            if (i % 3 == 0)
                mixed.add(observer(node, node, LayerTag.values()[i], PhaseDomain.A, 100, 0));
            else if (i % 3 == 1)
                mixed.add(
                        new AnimationStartReport.Start(
                                node,
                                node,
                                LayerTag.values()[i],
                                StartKind.NEW,
                                AnchorClass.CANONICAL_TICK,
                                PhaseDomain.A,
                                100,
                                0,
                                0,
                                new AnimationStartReport.CanonicalTick(
                                        100, NativeStage.NATIVE_TICK)));
            else
                mixed.add(
                        new AnimationStartReport.Start(
                                node,
                                node,
                                LayerTag.values()[i],
                                StartKind.REPLACE,
                                AnchorClass.PROJECTED_SUCCESSOR,
                                PhaseDomain.A,
                                100,
                                0,
                                node - 1,
                                new AnimationStartReport.ProjectedSuccessor(
                                        100, NativeStage.NATIVE_TICK, 1, 1)));
        }
        var compactedView =
                receipt(IntStream.rangeClosed(1, 31).mapToObj(i -> victim(i, compacted)).toList());
        var mixedView =
                receipt(IntStream.rangeClosed(1, 31).mapToObj(i -> victim(i, mixed)).toList());
        int compactedBytes =
                encode(CombatViewC2SPacket.ANIMATION_CODEC, new CombatViewC2SPacket(compactedView))
                        .length;
        int mixedBytes =
                encode(CombatViewC2SPacket.ANIMATION_CODEC, new CombatViewC2SPacket(mixedView))
                        .length;
        // Schema manifest 4 adds one application-count byte per row (31 rows); protocol 9 one
        // report presence byte.
        assertEquals(4700, compactedBytes);
        assertEquals(7397, mixedBytes);
        System.out.println(
                "ADR4 ticket bytes: compacted31x7="
                        + compactedBytes
                        + ", mixed31x10="
                        + mixedBytes);
        var capacity =
                receipt(IntStream.rangeClosed(1, 4096).mapToObj(i -> victim(i, mixed)).toList());
        var output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            CombatViewC2SPacket.ANIMATION_CODEC.encode(
                                    output, new CombatViewC2SPacket(capacity)));
            assertEquals(0, output.writerIndex());
        } finally {
            output.release();
        }
    }

    @Test
    void losslessWorldEnvelopeAndNativeVelocityWalkOrder() {
        double[] values = {
            0, -0.0, 16384.0001, -16384.0001, 65536.0001, -65536.0001, 29999984.0001, -29999984.0001
        };
        var initial = sample(AnimationState.Placement.class);
        for (double value : values) {
            var origin = new AnimationState.Vec3(value, value + .0003, value - .0003);
            var placement =
                    with(
                            with(initial, "origin", origin),
                            "previousOrigin",
                            new AnimationState.Vec3(value - 1, value - .5, value + .5));
            var decoded = roundTrip(placement);
            assertEquals(
                    Double.doubleToRawLongBits(value),
                    Double.doubleToRawLongBits(decoded.origin().x()));
            for (int alpha : new int[] {0, 1, 32768, 65535, 65536}) {
                float p = alpha / 65536f;
                var actual = SnapshotWireMath.origin(decoded, p);
                assertEquals(
                        Double.doubleToRawLongBits(value - 1 + (double) p * (value - (value - 1))),
                        Double.doubleToRawLongBits(actual.x()));
            }
        }
        var velocity =
                with(
                        with(
                                initial,
                                "deltaMovementOnPreviousTick",
                                new AnimationState.Vec3(-.8, .35, .12)),
                        "deltaMovement",
                        new AnimationState.Vec3(.7, -.3, .5));
        assertEquals(
                new AnimationState.Vec3(
                        -.8 + .5 * (.7 - (-.8)), .35 + .5 * (-.3 - .35), .12 + .5 * (.5 - .12)),
                SnapshotWireMath.velocity(velocity, .5f));
        var walk = new AnimationState.Walk(5, .2f, .7f);
        assertEquals(5 - .7f * (1 - .3f), SnapshotWireMath.walkPosition(walk, .3f));
        assertEquals(.2f + .3f * (.7f - .2f), SnapshotWireMath.walkSpeed(walk, .3f));
        assertEquals(180f, SnapshotWireMath.rotLerp(.5f, 179, -179));
    }
}
