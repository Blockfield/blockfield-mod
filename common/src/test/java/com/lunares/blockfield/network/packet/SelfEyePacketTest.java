package com.lunares.blockfield.network.packet;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** Self-eye wire: exact round trip and fail-closed validation. */
class SelfEyePacketTest {
    static SelfEyeS2CPacket roundTrip(SelfEyeS2CPacket packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SelfEyeS2CPacket.CODEC.encode(buf, packet);
            return SelfEyeS2CPacket.CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    @Test
    void roundTrip() {
        var packet = new SelfEyeS2CPacket(901L, 0.25f, -1.31f, -0.5f);
        assertEquals(packet, roundTrip(packet));
    }

    @Test
    void validationRejects() {
        assertThrows(IllegalArgumentException.class, () -> new SelfEyeS2CPacket(-1, 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> new SelfEyeS2CPacket(1, Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SelfEyeS2CPacket(1, 0, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> new SelfEyeS2CPacket(1, 0, 0, -4.5f));
    }
}
