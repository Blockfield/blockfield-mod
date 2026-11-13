package com.lunares.blockfield.network;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.network.packet.ParCoolIntentC2SPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ParCoolIntentPacketTest {
    @Test
    void everyKeyMaskRoundTripsAndForeignBitsAreInvalid() {
        for (int mask = 0; mask < 256; mask++) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            ParCoolIntentC2SPacket.CODEC.encode(buffer, new ParCoolIntentC2SPacket(mask));
            assertEquals(1, buffer.readableBytes());
            var decoded = ParCoolIntentC2SPacket.CODEC.decode(buffer);
            assertEquals(mask, decoded.mask());
            assertEquals(mask < 16, decoded.valid());
            buffer.release();
        }
    }
}
