package com.lunares.blockfield.network.packet;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Кто держит рацию: клиент по этому отличает голос по рации от голоса «рядом» в голосовом HUD. */
public record RadioStateS2CPacket(UUID player, boolean held) implements CustomPacketPayload {
    public static final Type<RadioStateS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "radio_state_sync"));
    public static final StreamCodec<FriendlyByteBuf, RadioStateS2CPacket> CODEC =
            CustomPacketPayload.codec(RadioStateS2CPacket::write, RadioStateS2CPacket::new);

    public RadioStateS2CPacket(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readBoolean());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(player);
        buf.writeBoolean(held);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
