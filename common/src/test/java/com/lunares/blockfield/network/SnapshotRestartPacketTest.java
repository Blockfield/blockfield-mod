package com.lunares.blockfield.network;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.SnapshotRestartC2SPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class SnapshotRestartPacketTest {
    /** The shipped r10 bytes: renumbering or reordering the enum must not change the wire. */
    @Test
    void reasonIdsArePinnedWireBytes() {
        var reasons = SnapshotRestartC2SPacket.Reason.class;
        assertEquals(0, Enum.valueOf(reasons, "STREAM_ORDER").id());
        assertEquals(1, Enum.valueOf(reasons, "SETTINGS").id());
        assertEquals(2, Enum.valueOf(reasons, "PAIRING_FULL").id());
        assertEquals(3, Enum.valueOf(reasons, "DELTA_BASELINE").id());
        assertEquals(4, Enum.valueOf(reasons, "CAPACITY").id());
        assertEquals(5, Enum.valueOf(reasons, "ARITHMETIC").id());
        assertEquals(6, reasons.getEnumConstants().length);
    }

    @Test
    void canonicalEpochAndFixedReasonWithExactExhaustion() {
        for (var reason : SnapshotRestartC2SPacket.Reason.values()) {
            var bytes = new FriendlyByteBuf(Unpooled.buffer());
            try {
                var packet = new SnapshotRestartC2SPacket(128, reason);
                SnapshotRestartC2SPacket.CODEC.encode(bytes, packet);
                assertEquals(3, bytes.readableBytes());
                assertEquals(0x80, bytes.getUnsignedByte(0));
                assertEquals(1, bytes.getUnsignedByte(1));
                assertEquals(reason.id(), bytes.getUnsignedByte(2));
                assertEquals(packet, SnapshotRestartC2SPacket.CODEC.decode(bytes));
            } finally {
                bytes.release();
            }
        }
        for (byte[] bad :
                new byte[][] {
                    {},
                    {1},
                    {1, 6},
                    {1, 0, 0},
                    {(byte) 0x80, 0, 0},
                    {
                        (byte) 0xff,
                        (byte) 0xff,
                        (byte) 0xff,
                        (byte) 0xff,
                        (byte) 0xff,
                        (byte) 0xff,
                        (byte) 0xff,
                        (byte) 0xff,
                        (byte) 0xff,
                        1,
                        0
                    }
                }) {
            var bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(bad));
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> SnapshotRestartC2SPacket.CODEC.decode(bytes));
            } finally {
                bytes.release();
            }
        }
    }
}
