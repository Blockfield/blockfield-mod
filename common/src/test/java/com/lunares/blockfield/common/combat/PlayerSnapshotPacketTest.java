package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

/**
 * Exact snapshot S2C codec. Byte expectations are hand-derived from the wire layout, never the
 * encoder's output; truncation/trailing/version behaviour is asserted at every size.
 */
class PlayerSnapshotPacketTest {
    private static final int FULL_RESET =
            AnimationRecipe.SnapshotRecord.FLAG_FULL | AnimationRecipe.SnapshotRecord.FLAG_RESET;

    private static byte[] encode(PlayerSnapshotS2CPacket packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PlayerSnapshotS2CPacket.ANIMATION_CODEC.encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static PlayerSnapshotS2CPacket decode(byte[] bytes) {
        return decode(PlayerSnapshotS2CPacket.ANIMATION_CODEC, bytes);
    }

    private static <T> T decode(StreamCodec<FriendlyByteBuf, T> codec, byte[] bytes) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static AnimationRecipe.SnapshotRecord record(
            long stream, int entity, long tick, double x, int flags) {
        var identity =
                new AnimationState.Identity(
                        3, stream, 1, entity, 0, 5, 9, 0, 0, 0, 0, DIGEST, DIGEST);
        var checkpoint =
                with(
                        with(
                                with(sample(AnimationState.class), "identity", identity),
                                "serverTick",
                                tick),
                        "placement",
                        with(
                                sample(AnimationState.Placement.class),
                                "origin",
                                new AnimationState.Vec3(x, 64, 0)));
        return new AnimationRecipe.SnapshotRecord(
                identity, flags, checkpoint.placement(), checkpoint);
    }

    private static PlayerSnapshotS2CPacket batch(
            long stream, long previous, long tick, AnimationRecipe.SnapshotRecord... records) {
        return PlayerSnapshotS2CPacket.animation(
                stream,
                previous,
                tick,
                2,
                6,
                10,
                500,
                new AnimationRecipe.Batch(3, 1, List.of(records), List.of(), List.of()));
    }

    @Test
    void exactBatchRoundTrip() {
        var packet =
                batch(
                        3,
                        0,
                        1048576,
                        record(3, 7, 1048576, 10.5, FULL_RESET),
                        record(3, 9, 1048576, -3.25, 0));
        PlayerSnapshotS2CPacket decoded = decode(encode(packet));
        assertEquals(3, decoded.observerStreamEpoch());
        assertEquals(0, decoded.previousTickPlusOne());
        assertEquals(1048576, decoded.serverTick());
        assertEquals(2, decoded.interpMin());
        assertEquals(6, decoded.interpMax());
        assertEquals(10, decoded.dilationPercent());
        assertEquals(500, decoded.holdToSnapMs());
        assertEquals(2, decoded.animation().records().size());
        assertEquals(
                List.of(7, 9),
                decoded.animation().records().stream().map(r -> r.identity().entityId()).toList());
        var full = decoded.animation().records().get(0);
        assertEquals(10.5, full.checkpoint().placement().origin().x());
        assertEquals(FULL_RESET, full.flags());
        assertEquals(-3.25, decoded.animation().records().get(1).placement().origin().x());
    }

    @Test
    void absoluteHeaderTicksAndBounds() {
        long bigTick = (1L << 40) + 5;
        // Empty batches cannot carry a stream identity; the header round-trips on its own.
        PlayerSnapshotS2CPacket decoded = decode(encode(batch(Long.MAX_VALUE, 0, bigTick)));
        assertEquals(Long.MAX_VALUE, decoded.observerStreamEpoch());
        assertEquals(bigTick, decoded.serverTick());
        // Header bounds: negative ticks, settings outside u8, hold outside u16.
        // (Semantic ranges like interpMin 1..4 are enforced by the buffer, not the codec.)
        var empty = new AnimationRecipe.Batch(3, 1, List.of(), List.of(), List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> PlayerSnapshotS2CPacket.animation(-1, 0, 100, 2, 6, 10, 500, empty));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlayerSnapshotS2CPacket.animation(3, 0, -1, 2, 6, 10, 500, empty));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlayerSnapshotS2CPacket.animation(3, 0, 100, 256, 6, 10, 500, empty));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlayerSnapshotS2CPacket.animation(3, 0, 100, 2, 6, 10, 65536, empty));
        assertThrows(
                NullPointerException.class,
                () -> PlayerSnapshotS2CPacket.animation(3, 0, 100, 2, 6, 10, 500, null));
        // A record stamped for another stream or tick cannot ride this header.
        assertThrows(
                IllegalArgumentException.class,
                () -> batch(4, 0, 1048576, record(3, 7, 1048576, 10.5, FULL_RESET)));
        assertThrows(
                IllegalArgumentException.class,
                () -> batch(3, 0, 1048576, record(3, 7, 1048575, 10.5, FULL_RESET)));
    }

    @Test
    void recordAndBatchBoundsRejected() {
        var good = record(3, 7, 100, 1.0, FULL_RESET);
        // Unknown kind.
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationState.Identity(3, 3, 1, 7, 5, 5, 9, 0, 0, 0, 0, DIGEST, DIGEST));
        // Reserved flag bits.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationRecipe.SnapshotRecord(
                                good.identity(), 0x10, good.placement(), good.checkpoint()));
        for (int flags :
                new int[] {
                    AnimationRecipe.SnapshotRecord.FLAG_SNAP,
                    AnimationRecipe.SnapshotRecord.FLAG_SEAT,
                    AnimationRecipe.SnapshotRecord.FLAG_RESET,
                    AnimationRecipe.SnapshotRecord.FLAG_SNAP
                            | AnimationRecipe.SnapshotRecord.FLAG_SEAT
                            | AnimationRecipe.SnapshotRecord.FLAG_RESET
                }) {
            int snap = flags;
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new AnimationRecipe.SnapshotRecord(
                                    good.identity(), snap, good.placement(), good.checkpoint()));
        }
        // Kind 0 (player) requires the complete checkpoint.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationRecipe.SnapshotRecord(
                                good.identity(), 0, good.placement(), null));
        // Unordered or duplicate ids.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationRecipe.Batch(
                                3,
                                1,
                                List.of(
                                        record(3, 9, 100, 1.0, FULL_RESET),
                                        record(3, 7, 100, 1.0, FULL_RESET)),
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationRecipe.Batch(
                                3,
                                1,
                                List.of(
                                        record(3, 7, 100, 1.0, FULL_RESET),
                                        record(3, 7, 100, 2.0, FULL_RESET)),
                                List.of(),
                                List.of()));
        // More than the 4096 failure bound.
        var tooMany = new ArrayList<AnimationRecipe.SnapshotRecord>();
        for (int id = 0; id <= AnimationSchema.MAX_VICTIMS; id++)
            tooMany.add(record(3, id, 100, 1.0, FULL_RESET));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnimationRecipe.Batch(3, 1, tooMany, List.of(), List.of()));
    }

    @Test
    void everyTruncationTrailingByteAndWrongVersionRejected() {
        byte[] valid = encode(batch(3, 0, 100, record(3, 7, 100, 10.5, FULL_RESET)));
        for (int size = 0; size < valid.length; size++) {
            byte[] truncated = Arrays.copyOf(valid, size);
            assertThrows(IllegalArgumentException.class, () -> decode(truncated));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> decode(Arrays.copyOf(valid, valid.length + 1)));
        byte[] wrong = valid.clone();
        wrong[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> decode(wrong));
    }

    @Test
    void oversizePayloadIsRejectedBeforeDecoding() {
        // No valid batch can reach the cap, so an oversize buffer is hostile garbage or
        // corruption: reject it up front, never decode it.
        byte[] bytes = encode(batch(3, 0, 100, record(3, 7, 100, 10.5, FULL_RESET)));
        byte[] padded = Arrays.copyOf(bytes, PlayerSnapshotS2CPacket.MAX_PAYLOAD_BYTES + 1);
        var error = assertThrows(IllegalArgumentException.class, () -> decode(padded));
        assertTrue(error.getMessage().contains("capacity"));
    }
}
