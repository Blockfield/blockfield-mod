package com.lunares.blockfield.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Empty capability marker: a client that parses v2 plans registers a receiver for this channel (and
 * ignores the payload, which is never sent). The server gates v2 generation on {@code
 * ServerPlayNetworking.canSend(player, TYPE)} so old clients keep v1 plans.
 */
public record WorkshopAiV2S2CPacket() implements CustomPacketPayload {
    public static final Type<WorkshopAiV2S2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("blockfield", "workshop_ai_v2"));
    public static final StreamCodec<FriendlyByteBuf, WorkshopAiV2S2CPacket> CODEC =
            CustomPacketPayload.codec(WorkshopAiV2S2CPacket::write, WorkshopAiV2S2CPacket::new);

    private WorkshopAiV2S2CPacket(FriendlyByteBuf buf) {
        this();
    }

    private void write(FriendlyByteBuf buf) {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
