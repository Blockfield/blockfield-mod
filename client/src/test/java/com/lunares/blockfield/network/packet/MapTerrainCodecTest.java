package com.lunares.blockfield.network.packet;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MapTerrainCodecTest {
    private static FriendlyByteBuf header() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeResourceLocation(ResourceLocation.fromNamespaceAndPath("blockfield", "arena_1"));
        buf.writeUtf("arena", 128);
        buf.writeDouble(-100);
        buf.writeDouble(100);
        buf.writeDouble(-100);
        buf.writeDouble(100);
        buf.writeVarInt(128);
        buf.writeVarInt(128);
        buf.writeLong(2);
        buf.writeLong(1);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeVarIntArray(new int[] {7});
        return buf;
    }

    @Test
    void rejectsNegativePaletteCountWithoutAllocating() {
        var buf = header();
        buf.writeVarInt(-1);
        // Before the fix this allocated `new int[-1]` and died with NegativeArraySizeException.
        // N-1 compat: a malformed payload decodes to the ignored INVALID packet, never throws.
        assertFalse(MapTerrainS2CPacket.CODEC.decode(buf).valid());
    }

    @Test
    void roundTripSmallPacket() {
        byte[] pixels = new byte[128 * 128 * 2];
        byte[] light = new byte[128 * 128];
        var packet =
                new MapTerrainS2CPacket(
                        ResourceLocation.fromNamespaceAndPath("blockfield", "arena_1"),
                        "arena",
                        -100,
                        100,
                        -100,
                        100,
                        128,
                        128,
                        2,
                        1,
                        1,
                        0,
                        new int[] {7},
                        new int[] {0x112233},
                        pixels,
                        light);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        MapTerrainS2CPacket.CODEC.encode(buf, packet);
        var decoded = MapTerrainS2CPacket.CODEC.decode(buf);
        assertArrayEquals(new int[] {7}, decoded.states());
        assertArrayEquals(new int[] {0x112233}, decoded.colors());
    }
}
