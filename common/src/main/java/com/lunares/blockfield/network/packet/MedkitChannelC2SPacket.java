package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The interact key is held on a thrown medkit (`kit` = its entity id) or let go ({@link #STOP}).
 * The server checks everything itself (MedkitChannels); the client only says what it holds.
 */
public record MedkitChannelC2SPacket(int kit) implements CustomPacketPayload {
    public static final int STOP = -1;
    public static final Type<MedkitChannelC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "medkit_channel"));
    public static final StreamCodec<FriendlyByteBuf, MedkitChannelC2SPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> buf.writeVarInt(packet.kit),
                    buf -> new MedkitChannelC2SPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
