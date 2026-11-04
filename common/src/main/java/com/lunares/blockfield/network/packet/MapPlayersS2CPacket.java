package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Position-only updates do not replace the slower match HUD/menu state. */
public record MapPlayersS2CPacket(String json) implements CustomPacketPayload {
    private static final int MAX_LENGTH = 65536;
    public static final Type<MapPlayersS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "map_players"));
    public static final StreamCodec<FriendlyByteBuf, MapPlayersS2CPacket> CODEC =
            CustomPacketPayload.codec(
                    (packet, buf) -> buf.writeUtf(packet.json(), MAX_LENGTH),
                    buf -> new MapPlayersS2CPacket(buf.readUtf(MAX_LENGTH)));

    public MapPlayersS2CPacket {
        if (json.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Map positions too large");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
