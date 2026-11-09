package com.lunares.blockfield.network.packet;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class ScoreTicketCodecTest {
    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, packet);
        return codec.decode(buf);
    }

    private static FriendlyByteBuf firstBytes(
            StreamCodec<FriendlyByteBuf, ?> codec, Object packet, int keep) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        @SuppressWarnings({"unchecked", "rawtypes"})
        var raw = (StreamCodec<FriendlyByteBuf, Object>) codec;
        raw.encode(buf, packet);
        byte[] full = new byte[buf.readableBytes()];
        buf.readBytes(full);
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(full, 0, keep));
    }

    @Test
    void scoreDeltaRoundTrip() {
        var packet =
                new ScoreDeltaPacket(
                        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                        7,
                        3,
                        1845.5,
                        42,
                        "sniper",
                        4);
        var decoded = roundTrip(ScoreDeltaPacket.CODEC, packet);
        assertTrue(decoded.valid());
        assertEquals(packet.playerUUID, decoded.playerUUID);
        assertEquals(7, decoded.kills);
        assertEquals(3, decoded.deaths);
        assertEquals(1845.5, decoded.score);
        assertEquals(42, decoded.pointTime);
        assertEquals("sniper", decoded.classId);
        assertEquals(4, decoded.streak);
    }

    @Test
    void ticketRoundTrip() {
        var decoded = roundTrip(TicketUpdatePacket.CODEC, new TicketUpdatePacket(120, 87));
        assertTrue(decoded.valid());
        assertEquals(120, decoded.redTickets);
        assertEquals(87, decoded.blueTickets);
    }

    @Test
    void truncatedScoreIsInvalid() {
        var packet = new ScoreDeltaPacket(UUID.randomUUID(), 1, 0, 100.0, 5, "assault", 0);
        assertFalse(
                ScoreDeltaPacket.CODEC
                        .decode(firstBytes(ScoreDeltaPacket.CODEC, packet, 10))
                        .valid());
    }

    @Test
    void truncatedTicketIsInvalid() {
        assertFalse(
                TicketUpdatePacket.CODEC
                        .decode(
                                firstBytes(
                                        TicketUpdatePacket.CODEC, new TicketUpdatePacket(3, 4), 3))
                        .valid());
    }

    @Test
    void garbageIsInvalid() {
        assertFalse(
                ScoreDeltaPacket.CODEC
                        .decode(
                                new FriendlyByteBuf(
                                        Unpooled.wrappedBuffer(new byte[] {0x01, 0x02})))
                        .valid());
        assertFalse(
                TicketUpdatePacket.CODEC
                        .decode(
                                new FriendlyByteBuf(
                                        Unpooled.wrappedBuffer(new byte[] {0x01, 0x02})))
                        .valid());
    }
}
