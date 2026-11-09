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

    private static FriendlyByteBuf encodedBytes(
            StreamCodec<FriendlyByteBuf, ?> codec, Object packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        @SuppressWarnings({"unchecked", "rawtypes"})
        var raw = (StreamCodec<FriendlyByteBuf, Object>) codec;
        raw.encode(buf, packet);
        return buf;
    }

    private static FriendlyByteBuf firstBytes(
            StreamCodec<FriendlyByteBuf, ?> codec, Object packet, int keep) {
        var buf = encodedBytes(codec, packet);
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
        var buf = firstBytes(ScoreDeltaPacket.CODEC, packet, 10);
        assertFalse(ScoreDeltaPacket.CODEC.decode(buf).valid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void truncatedTicketIsInvalid() {
        var buf = firstBytes(TicketUpdatePacket.CODEC, new TicketUpdatePacket(3, 4), 3);
        assertFalse(TicketUpdatePacket.CODEC.decode(buf).valid());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void garbageIsInvalid() {
        var scoreBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[] {0x01, 0x02}));
        assertFalse(ScoreDeltaPacket.CODEC.decode(scoreBuf).valid());
        assertEquals(0, scoreBuf.readableBytes());
        var ticketBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[] {0x01, 0x02}));
        assertFalse(TicketUpdatePacket.CODEC.decode(ticketBuf).valid());
        assertEquals(0, ticketBuf.readableBytes());
    }

    @Test
    void scoreDeltaTrailingFieldStaysValid() {
        var buf =
                encodedBytes(
                        ScoreDeltaPacket.CODEC,
                        new ScoreDeltaPacket(
                                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                                7,
                                3,
                                1845.5,
                                42,
                                "sniper",
                                4));
        buf.writeInt(99); // field added by a newer server
        var decoded = ScoreDeltaPacket.CODEC.decode(buf);
        assertTrue(decoded.valid());
        assertEquals(7, decoded.kills);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void ticketTrailingFieldStaysValid() {
        var buf = encodedBytes(TicketUpdatePacket.CODEC, new TicketUpdatePacket(120, 87));
        buf.writeInt(5); // field added by a newer server
        var decoded = TicketUpdatePacket.CODEC.decode(buf);
        assertTrue(decoded.valid());
        assertEquals(120, decoded.redTickets);
        assertEquals(87, decoded.blueTickets);
        assertEquals(0, buf.readableBytes());
    }
}
