package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class OpenClassMenuS2CPacket implements CustomPacketPayload {
    public static final Type<OpenClassMenuS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "open_class_menu"));
    public static final StreamCodec<FriendlyByteBuf, OpenClassMenuS2CPacket> CODEC =
            CustomPacketPayload.codec(OpenClassMenuS2CPacket::toBytes, OpenClassMenuS2CPacket::new);

    public final String classesJson;
    public final boolean isUpdate;

    public OpenClassMenuS2CPacket(String classesJson) {
        this(classesJson, false);
    }

    public OpenClassMenuS2CPacket(String classesJson, boolean isUpdate) {
        this.classesJson = classesJson;
        this.isUpdate = isUpdate;
    }

    public OpenClassMenuS2CPacket(FriendlyByteBuf buf) {
        this.classesJson = buf.readUtf(262144);
        this.isUpdate = buf.readBoolean();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(this.classesJson, 262144);
        buf.writeBoolean(this.isUpdate);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
