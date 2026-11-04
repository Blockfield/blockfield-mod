package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The sender's own ParCool lean intent (forward/back/left/right movement keys) sampled at its
 * client tick, as bf7 SkyDive and creative-flight passive animation read them. Only animation of
 * this player on other screens consumes it; movement validation stays native.
 */
public record ParCoolIntentC2SPacket(int mask) implements CustomPacketPayload {
    public static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8;
    public static final Type<ParCoolIntentC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "parcool_intent"));
    public static final StreamCodec<FriendlyByteBuf, ParCoolIntentC2SPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> buf.writeByte(packet.mask),
                    buf -> new ParCoolIntentC2SPacket(buf.readUnsignedByte()));

    public boolean valid() {
        return (mask & ~15) == 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
