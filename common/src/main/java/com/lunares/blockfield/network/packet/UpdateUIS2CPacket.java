package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class UpdateUIS2CPacket implements CustomPacketPayload {
    public static final Type<UpdateUIS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "update_ui"));
    public static final StreamCodec<FriendlyByteBuf, UpdateUIS2CPacket> CODEC =
            CustomPacketPayload.codec(UpdateUIS2CPacket::toBytes, UpdateUIS2CPacket::new);

    public final String uiDataJson;

    public UpdateUIS2CPacket(String uiDataJson) {
        this.uiDataJson = uiDataJson;
    }

    public UpdateUIS2CPacket(FriendlyByteBuf buf) {
        this.uiDataJson = buf.readUtf(262144);
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(this.uiDataJson, 262144);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
