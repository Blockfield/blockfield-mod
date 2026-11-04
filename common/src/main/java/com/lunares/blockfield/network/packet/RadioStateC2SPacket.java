package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class RadioStateC2SPacket implements CustomPacketPayload {
    public static final Type<RadioStateC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "radio_state"));
    public static final StreamCodec<FriendlyByteBuf, RadioStateC2SPacket> CODEC =
            CustomPacketPayload.codec(RadioStateC2SPacket::toBytes, RadioStateC2SPacket::new);

    private final boolean isHeld;

    public RadioStateC2SPacket(boolean isHeld) {
        this.isHeld = isHeld;
    }

    public RadioStateC2SPacket(FriendlyByteBuf buf) {
        this.isHeld = buf.readBoolean();
    }

    public boolean isHeld() {
        return this.isHeld;
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBoolean(this.isHeld);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
