package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** U9'': shared snapshot wire math. Expectations are hand-derived, never the codec's output. */
class SnapshotWireMathTest {
    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void renderTimeAddsAlphaFraction() {
        // Wire R = tick 1021 + alpha 0.3. 0.3 * 65536 = 19660.8, so the wire carries alpha 19660.
        assertEquals(1021.3, SnapshotWireMath.renderTime(1021, 19660), 1e-4);
        assertEquals(1017.4, SnapshotWireMath.renderTime(1017, 26214), 1e-4);
    }

    @Test
    void uvarlongSizesAndRoundTrip() {
        assertEquals(1, SnapshotWireMath.sizeOfUvarLong(0));
        assertEquals(1, SnapshotWireMath.sizeOfUvarLong(127));
        assertEquals(2, SnapshotWireMath.sizeOfUvarLong(128));
        assertEquals(9, SnapshotWireMath.sizeOfUvarLong(Long.MAX_VALUE));
        for (long value : new long[] {0, 1, 127, 128, 300, 1020, (1L << 40) + 5, Long.MAX_VALUE}) {
            FriendlyByteBuf buf = buffer();
            SnapshotWireMath.writeUvarLong(buf, value);
            assertEquals(SnapshotWireMath.sizeOfUvarLong(value), buf.readableBytes());
            assertEquals(value, SnapshotWireMath.readUvarLong(buf));
        }
        assertThrows(
                IllegalArgumentException.class, () -> SnapshotWireMath.writeUvarLong(buffer(), -1));
    }

    @Test
    void uvarlongRejectsOverlongOverflowAndTruncated() {
        // Overlong zero: two bytes where one is canonical.
        FriendlyByteBuf overlong = buffer();
        overlong.writeBytes(new byte[] {(byte) 0x80, 0x00});
        assertThrows(IllegalArgumentException.class, () -> SnapshotWireMath.readUvarLong(overlong));
        // Ten-byte negative-style encoding never fits the 9-byte bound.
        FriendlyByteBuf ten = buffer();
        ten.writeBytes(
                new byte[] {
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    0x00
                });
        assertThrows(IllegalArgumentException.class, () -> SnapshotWireMath.readUvarLong(ten));
        // Dangling continuation bit.
        FriendlyByteBuf cut = buffer();
        cut.writeBytes(new byte[] {(byte) 0x80});
        assertThrows(IllegalArgumentException.class, () -> SnapshotWireMath.readUvarLong(cut));
    }

    @Test
    void uvarintSizesRoundTripAndRejection() {
        assertEquals(1, SnapshotWireMath.sizeOfUvarInt(0));
        assertEquals(2, SnapshotWireMath.sizeOfUvarInt(128));
        assertEquals(5, SnapshotWireMath.sizeOfUvarInt(Integer.MAX_VALUE));
        for (int value : new int[] {0, 127, 128, 16384, 4096, 4097, Integer.MAX_VALUE}) {
            FriendlyByteBuf buf = buffer();
            SnapshotWireMath.writeUvarInt(buf, value);
            assertEquals(SnapshotWireMath.sizeOfUvarInt(value), buf.readableBytes());
            assertEquals(value, SnapshotWireMath.readUvarInt(buf));
        }
        FriendlyByteBuf overlong = buffer();
        overlong.writeBytes(new byte[] {(byte) 0x80, 0x00});
        assertThrows(IllegalArgumentException.class, () -> SnapshotWireMath.readUvarInt(overlong));
        FriendlyByteBuf six = buffer();
        six.writeBytes(
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00});
        assertThrows(IllegalArgumentException.class, () -> SnapshotWireMath.readUvarInt(six));
    }
}
