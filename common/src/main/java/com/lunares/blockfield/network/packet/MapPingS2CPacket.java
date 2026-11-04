package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Метка команды: позиция (высоту подобрал сервер), имя автора и сколько секунд её показывать. */
public record MapPingS2CPacket(String author, double x, double y, double z, int seconds)
        implements CustomPacketPayload {
    public static final Type<MapPingS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "map_ping_sync"));
    public static final StreamCodec<FriendlyByteBuf, MapPingS2CPacket> CODEC =
            CustomPacketPayload.codec(MapPingS2CPacket::write, MapPingS2CPacket::new);

    public MapPingS2CPacket(FriendlyByteBuf buf) {
        this(
                buf.readUtf(64),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(author, 64);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(seconds);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
