package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class OpenMenuS2CPacket implements CustomPacketPayload {
    public static final Type<OpenMenuS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "open_menu"));
    public static final StreamCodec<FriendlyByteBuf, OpenMenuS2CPacket> CODEC =
            CustomPacketPayload.codec(OpenMenuS2CPacket::toBytes, OpenMenuS2CPacket::new);

    public final String json;

    public OpenMenuS2CPacket(String json) {
        this.json = json;
    }

    public OpenMenuS2CPacket(FriendlyByteBuf buf) {
        this.json = buf.readUtf(262144);
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(this.json, 262144);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
