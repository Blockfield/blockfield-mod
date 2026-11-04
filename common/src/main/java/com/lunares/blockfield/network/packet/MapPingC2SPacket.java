package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Метка с карты планшета: клиент присылает точку, высоту и допуск проверяет сервер. */
public record MapPingC2SPacket(double x, double z) implements CustomPacketPayload {
    public static final Type<MapPingC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "map_ping"));
    public static final StreamCodec<FriendlyByteBuf, MapPingC2SPacket> CODEC =
            CustomPacketPayload.codec(MapPingC2SPacket::write, MapPingC2SPacket::new);

    public MapPingC2SPacket(FriendlyByteBuf buf) {
        this(buf.readDouble(), buf.readDouble());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(z);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
