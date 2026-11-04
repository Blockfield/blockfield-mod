package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A player (entity id) starts or stops healing from the thrown medkit `kit` (entity id): the player
 * sees their own channel confirmed or cut off, the others draw the pose.
 */
public record MedkitChannelS2CPacket(int player, int kit, boolean active)
        implements CustomPacketPayload {
    public static final Type<MedkitChannelS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "medkit_channel_sync"));
    public static final StreamCodec<FriendlyByteBuf, MedkitChannelS2CPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeVarInt(packet.player);
                        buf.writeVarInt(packet.kit);
                        buf.writeBoolean(packet.active);
                    },
                    buf ->
                            new MedkitChannelS2CPacket(
                                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
